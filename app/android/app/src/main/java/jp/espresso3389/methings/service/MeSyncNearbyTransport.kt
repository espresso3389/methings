package jp.espresso3389.methings.service

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MeSyncNearbyTransport(
    context: Context,
    private val serviceId: String,
    private val openOutgoingStream: (ticketId: String, transferId: String) -> InputStream?,
    private val logger: (String, Throwable?) -> Unit
) {
    private data class TicketOffer(
        val ticketId: String,
        val transferId: String,
        val sessionNonce: String,
        val expiresAt: Long
    )

    data class ReceiveResult(
        val endpointId: String,
        val bytesReceived: Long
    )

    private val appContext = context.applicationContext
    private val client: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val io = Executors.newCachedThreadPool()
    private val offers = ConcurrentHashMap<String, TicketOffer>()
    private val activeSendPayloads = ConcurrentHashMap<Long, String>()
    private val isAdvertising = AtomicBoolean(false)
    private val endpointName = "methings-" + UUID.randomUUID().toString().take(8)
    private val strategy = Strategy.P2P_POINT_TO_POINT

    private val sourcePayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val req = runCatching {
                JSONObject(String(payload.asBytes() ?: ByteArray(0), Charsets.UTF_8))
            }.getOrNull() ?: return
            if (req.optString("op", "") != "pull") return
            val ticketId = req.optString("ticket_id", "").trim()
            val nonce = req.optString("session_nonce", "").trim()
            val transferId = req.optString("transfer_id", "").trim()
            val offer = offers[ticketId]
            if (offer == null) {
                logger(
                    "me.sync nearby reject: ticket_not_found " +
                        "(endpoint=$endpointId ticket_id=$ticketId transfer_id=$transferId)",
                    null
                )
                sendReject(endpointId, "ticket_not_found")
                return
            }
            val now = System.currentTimeMillis()
            if (offer.expiresAt in 1..now) {
                logger(
                    "me.sync nearby reject: ticket_expired " +
                        "(endpoint=$endpointId ticket_id=$ticketId transfer_id=${offer.transferId} " +
                        "expires_at=${offer.expiresAt} now=$now)",
                    null
                )
                sendReject(endpointId, "ticket_expired")
                return
            }
            if (nonce.isBlank() || nonce != offer.sessionNonce) {
                logger(
                    "me.sync nearby reject: invalid_nonce " +
                        "(endpoint=$endpointId ticket_id=$ticketId transfer_id=${offer.transferId} " +
                        "nonce_len=${nonce.length} expected_len=${offer.sessionNonce.length})",
                    null
                )
                sendReject(endpointId, "invalid_nonce")
                return
            }
            if (transferId.isBlank() || transferId != offer.transferId) {
                logger(
                    "me.sync nearby reject: invalid_transfer " +
                        "(endpoint=$endpointId ticket_id=$ticketId transfer_id=$transferId " +
                        "expected_transfer_id=${offer.transferId})",
                    null
                )
                sendReject(endpointId, "invalid_transfer")
                return
            }
            val stream = runCatching { openOutgoingStream(ticketId, offer.transferId) }.getOrNull()
            if (stream == null) {
                logger(
                    "me.sync nearby reject: stream_unavailable " +
                        "(endpoint=$endpointId ticket_id=$ticketId transfer_id=${offer.transferId})",
                    null
                )
                sendReject(endpointId, "stream_unavailable")
                return
            }
            val meta = JSONObject()
                .put("op", "accept")
                .put("ticket_id", ticketId)
                .put("transfer_id", offer.transferId)
            client.sendPayload(endpointId, Payload.fromBytes(meta.toString().toByteArray(Charsets.UTF_8)))
            val streamPayload = Payload.fromStream(stream)
            activeSendPayloads[streamPayload.id] = offer.transferId
            client.sendPayload(endpointId, streamPayload)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS ||
                update.status == PayloadTransferUpdate.Status.FAILURE ||
                update.status == PayloadTransferUpdate.Status.CANCELED
            ) {
                activeSendPayloads.remove(update.payloadId)
            }
        }
    }

    private val sourceConnectionLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, sourcePayloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            // Receiver drives flow by sending a "pull" request after connection success.
        }

        override fun onDisconnected(endpointId: String) {
        }
    }

    fun publishTicket(ticketId: String, transferId: String, sessionNonce: String, expiresAt: Long) {
        offers[ticketId] = TicketOffer(ticketId, transferId, sessionNonce, expiresAt)
        ensureAdvertising()
    }

    fun unpublishTicket(ticketId: String) {
        offers.remove(ticketId)
        if (offers.isEmpty()) stopAdvertising()
    }

    fun cleanupExpired(now: Long = System.currentTimeMillis()) {
        offers.entries.removeIf { it.value.expiresAt in 1..now }
        if (offers.isEmpty()) stopAdvertising()
    }

    fun shutdown() {
        try {
            stopAdvertising()
        } catch (_: Exception) {
        }
        try {
            client.stopAllEndpoints()
        } catch (_: Exception) {
        }
        try {
            io.shutdownNow()
        } catch (_: Exception) {
        }
    }

    fun receiveToFile(
        ticketId: String,
        transferId: String,
        sessionNonce: String,
        destFile: File,
        timeoutMs: Long,
        maxBytes: Long
    ): ReceiveResult {
        val done = CountDownLatch(1)
        val streamDone = CountDownLatch(1)
        val connectedEndpoint = AtomicReference("")
        val error = AtomicReference("")
        val bytesReceived = AtomicLong(0L)
        val requested = AtomicBoolean(false)
        val discovered = AtomicBoolean(false)

        val receiverPayload = object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                when (payload.type) {
                    Payload.Type.BYTES -> {
                        val obj = runCatching {
                            JSONObject(String(payload.asBytes() ?: ByteArray(0), Charsets.UTF_8))
                        }.getOrNull() ?: return
                        val op = obj.optString("op", "").trim()
                        if (op == "reject") {
                            val reason = obj.optString("error", "rejected")
                            logger(
                                "me.sync nearby rejected by exporter: $reason " +
                                    "(endpoint=$endpointId ticket_id=$ticketId transfer_id=$transferId)",
                                null
                            )
                            error.set(reason)
                            done.countDown()
                        }
                    }
                    Payload.Type.STREAM -> {
                        val stream = payload.asStream()?.asInputStream() ?: run {
                            error.set("invalid_stream")
                            done.countDown()
                            return
                        }
                        io.execute {
                            try {
                                FileOutputStream(destFile).use { out ->
                                    copyStreamWithLimit(stream, out, maxBytes) { delta ->
                                        bytesReceived.addAndGet(delta)
                                    }
                                }
                                streamDone.countDown()
                                done.countDown()
                            } catch (ex: Exception) {
                                error.set(ex.message ?: "stream_read_failed")
                                streamDone.countDown()
                                done.countDown()
                            } finally {
                                runCatching { stream.close() }
                            }
                        }
                    }
                    else -> {
                    }
                }
            }

            override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
                if (update.status == PayloadTransferUpdate.Status.FAILURE ||
                    update.status == PayloadTransferUpdate.Status.CANCELED
                ) {
                    error.set("transfer_failed")
                    done.countDown()
                }
            }
        }

        val receiverLifecycle = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                client.acceptConnection(endpointId, receiverPayload)
            }

            override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
                if (resolution.status.isSuccess) {
                    connectedEndpoint.set(endpointId)
                    if (requested.compareAndSet(false, true)) {
                        val req = JSONObject()
                            .put("op", "pull")
                            .put("ticket_id", ticketId)
                            .put("transfer_id", transferId)
                            .put("session_nonce", sessionNonce)
                        client.sendPayload(endpointId, Payload.fromBytes(req.toString().toByteArray(Charsets.UTF_8)))
                    }
                } else {
                    error.set("connection_failed")
                    done.countDown()
                }
            }

            override fun onDisconnected(endpointId: String) {
                if (streamDone.count == 0L) return
                if (error.get().isBlank()) {
                    error.set("disconnected")
                }
                done.countDown()
            }
        }

        val discovery = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                if (!discovered.compareAndSet(false, true)) return
                client.requestConnection(
                    "methings-importer-" + UUID.randomUUID().toString().take(6),
                    endpointId,
                    receiverLifecycle
                ).addOnFailureListener {
                    error.set("request_connection_failed")
                    done.countDown()
                }
            }

            override fun onEndpointLost(endpointId: String) {
            }
        }

        client.startDiscovery(
            serviceId,
            discovery,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        ).addOnFailureListener { ex ->
            error.set("discovery_start_failed")
            logger("Nearby discovery start failed", ex)
            done.countDown()
        }

        val ok = done.await(timeoutMs.coerceAtLeast(5_000L), TimeUnit.MILLISECONDS)
        runCatching { client.stopDiscovery() }
        val endpoint = connectedEndpoint.get()
        if (endpoint.isNotBlank()) {
            runCatching { client.disconnectFromEndpoint(endpoint) }
        }
        if (!ok) {
            throw IllegalStateException("nearby_timeout")
        }
        val err = error.get().trim()
        if (err.isNotBlank()) {
            throw IllegalStateException(err)
        }
        return ReceiveResult(
            endpointId = endpoint,
            bytesReceived = bytesReceived.get().coerceAtLeast(0L)
        )
    }

    private fun ensureAdvertising() {
        if (isAdvertising.get()) return
        client.startAdvertising(
            endpointName,
            serviceId,
            sourceConnectionLifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        ).addOnSuccessListener {
            isAdvertising.set(true)
        }.addOnFailureListener { ex ->
            logger("Nearby advertising start failed", ex)
        }
    }

    private fun stopAdvertising() {
        if (!isAdvertising.get()) return
        try {
            client.stopAdvertising()
        } catch (_: Exception) {
        }
        isAdvertising.set(false)
    }

    private fun sendReject(endpointId: String, reason: String) {
        val obj = JSONObject()
            .put("op", "reject")
            .put("error", reason)
        client.sendPayload(endpointId, Payload.fromBytes(obj.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun copyStreamWithLimit(
        input: InputStream,
        output: FileOutputStream,
        maxBytes: Long,
        onBytes: (Long) -> Unit
    ) {
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n.toLong()
            if (total > maxBytes) throw IllegalStateException("package_too_large")
            output.write(buf, 0, n)
            onBytes(n.toLong())
        }
    }
}
