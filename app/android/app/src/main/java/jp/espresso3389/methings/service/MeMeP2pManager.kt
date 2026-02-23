package jp.espresso3389.methings.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MeMeP2pManager(
    private val context: Context,
    private val deviceId: String,
    private val onDataChannelMessage: (peerDeviceId: String, data: String) -> Unit,
    private val onConnectionStateChanged: (peerDeviceId: String, state: String) -> Unit = { _, _ -> },
    private val logger: (String, Throwable?) -> Unit = { _, _ -> }
) {
    data class P2pConfig(
        val enabled: Boolean = false,
        val signalingUrl: String = "",
        val iceServersJson: String = "[]",
        val autoConnect: Boolean = false,
        val signalingToken: String = ""
    )

    private data class P2pPeerState(
        val peerDeviceId: String,
        val peerConnection: PeerConnection,
        var dataChannel: DataChannel?,
        var iceState: String = "new",
        var dcState: String = "closed",
        val createdAt: Long = System.currentTimeMillis()
    )

    @Volatile private var config = P2pConfig()
    @Volatile private var peerConnectionFactory: PeerConnectionFactory? = null
    @Volatile private var signalingWs: SignalingWebSocket? = null
    @Volatile private var initialized = false
    private val peers = ConcurrentHashMap<String, P2pPeerState>()
    private val dcOpenLatches = ConcurrentHashMap<String, CountDownLatch>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MeMeP2p").apply { isDaemon = true }
    }

    fun initialize(config: P2pConfig) {
        this.config = config
        if (!config.enabled) {
            shutdown()
            return
        }
        executor.execute {
            runCatching { initInternal(config) }.onFailure {
                logger("P2P: initialize failed", it)
            }
        }
    }

    private fun initInternal(cfg: P2pConfig) {
        if (peerConnectionFactory == null) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setFieldTrials("")
                    .createInitializationOptions()
            )
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .createPeerConnectionFactory()
        }
        signalingWs?.shutdown()
        if (cfg.signalingUrl.isNotBlank() && cfg.signalingToken.isNotBlank()) {
            signalingWs = SignalingWebSocket(
                signalingUrl = cfg.signalingUrl,
                deviceId = deviceId,
                token = cfg.signalingToken,
                onMessage = { msg -> executor.execute { handleSignalingMessage(msg) } },
                onConnected = { logger("P2P: signaling connected", null) },
                onDisconnected = { code, reason -> logger("P2P: signaling disconnected code=$code reason=$reason", null) },
                onError = { ex -> logger("P2P: signaling error", ex) },
                logger = logger
            ).also { it.connect() }
        }
        initialized = true
        logger("P2P: initialized (iceServers=${cfg.iceServersJson})", null)
    }

    fun shutdown() {
        initialized = false
        for ((peerId, _) in peers) {
            disconnectPeer(peerId)
        }
        peers.clear()
        signalingWs?.shutdown()
        signalingWs = null
        runCatching {
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
        }
    }

    fun updateConfig(newConfig: P2pConfig) {
        val prev = config
        config = newConfig
        if (!newConfig.enabled) {
            shutdown()
            return
        }
        if (prev.signalingUrl != newConfig.signalingUrl ||
            prev.signalingToken != newConfig.signalingToken ||
            prev.iceServersJson != newConfig.iceServersJson ||
            !initialized
        ) {
            executor.execute {
                shutdown()
                runCatching { initInternal(newConfig) }.onFailure {
                    logger("P2P: updateConfig re-init failed", it)
                }
            }
        }
    }

    fun connectToPeer(peerDeviceId: String) {
        if (!initialized) {
            logger("P2P: not initialized, cannot connect to $peerDeviceId", null)
            return
        }
        executor.execute {
            runCatching { createOfferAndSend(peerDeviceId) }.onFailure {
                logger("P2P: connectToPeer($peerDeviceId) failed", it)
            }
        }
    }

    /**
     * Try to establish a P2P connection and wait up to [timeoutMs] for the DataChannel to open.
     * Returns true if connected within the timeout, false otherwise.
     */
    fun connectAndWait(peerDeviceId: String, timeoutMs: Long): Boolean {
        if (!initialized) return false
        if (isConnected(peerDeviceId)) return true
        val latch = CountDownLatch(1)
        dcOpenLatches[peerDeviceId] = latch
        executor.execute {
            runCatching { createOfferAndSend(peerDeviceId) }.onFailure {
                logger("P2P: connectAndWait($peerDeviceId) offer failed", it)
                latch.countDown()
            }
        }
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS) && isConnected(peerDeviceId)
        } finally {
            dcOpenLatches.remove(peerDeviceId, latch)
        }
    }

    fun disconnectPeer(peerDeviceId: String) {
        val state = peers.remove(peerDeviceId) ?: return
        runCatching { state.dataChannel?.close() }
        runCatching { state.peerConnection.close() }
        runCatching {
            signalingWs?.send(
                JSONObject().put("type", "hangup").put("to", peerDeviceId)
            )
        }
        onConnectionStateChanged(peerDeviceId, "disconnected")
        logger("P2P: disconnected peer $peerDeviceId", null)
    }

    fun isConnected(peerDeviceId: String): Boolean {
        val state = peers[peerDeviceId] ?: return false
        return state.dcState == "open"
    }

    fun sendJson(peerDeviceId: String, payload: JSONObject): Boolean {
        val state = peers[peerDeviceId] ?: return false
        val dc = state.dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return runCatching {
            val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
            val buf = DataChannel.Buffer(ByteBuffer.wrap(bytes), false)
            dc.send(buf)
        }.getOrDefault(false)
    }

    fun statusJson(): JSONObject {
        return JSONObject()
            .put("enabled", config.enabled)
            .put("initialized", initialized)
            .put("signaling_connected", signalingWs?.isConnected == true)
            .put("signaling_url", config.signalingUrl)
            .put("peer_count", peers.size)
            .put("connected_peer_count", peers.values.count { it.dcState == "open" })
    }

    fun peerStatesJson(): JSONArray {
        val arr = JSONArray()
        for ((peerId, state) in peers) {
            arr.put(
                JSONObject()
                    .put("peer_device_id", peerId)
                    .put("ice_state", state.iceState)
                    .put("dc_state", state.dcState)
                    .put("created_at", state.createdAt)
            )
        }
        return arr
    }

    // -- Signaling message handling --

    private fun handleSignalingMessage(msg: JSONObject) {
        when (msg.optString("type", "")) {
            "registered" -> {
                logger("P2P: signaling registered ok=${msg.optBoolean("ok", false)}", null)
            }
            "offer" -> {
                val from = msg.optString("from", "").trim()
                val sdp = ensureSdpTrailingNewline(msg.optString("sdp", ""))
                if (from.isNotBlank() && sdp.isNotBlank()) handleRemoteOffer(from, sdp)
            }
            "answer" -> {
                val from = msg.optString("from", "").trim()
                val sdp = ensureSdpTrailingNewline(msg.optString("sdp", ""))
                if (from.isNotBlank() && sdp.isNotBlank()) handleRemoteAnswer(from, sdp)
            }
            "candidate" -> {
                val from = msg.optString("from", "").trim()
                val cObj = msg.optJSONObject("candidate")
                if (from.isNotBlank() && cObj != null) handleRemoteCandidate(from, cObj)
            }
            "hangup" -> {
                val from = msg.optString("from", "").trim()
                if (from.isNotBlank()) {
                    logger("P2P: received hangup from $from", null)
                    disconnectPeer(from)
                }
            }
            "peer_online" -> {
                val peerId = msg.optString("device_id", "").trim()
                logger("P2P: peer_online $peerId", null)
            }
            "peer_offline" -> {
                val peerId = msg.optString("device_id", "").trim()
                logger("P2P: peer_offline $peerId", null)
            }
            "error" -> {
                logger("P2P: signaling error code=${msg.optString("code")} message=${msg.optString("message")}", null)
            }
        }
    }

    /** Ensure SDP ends with \r\n; WebRTC native parser requires trailing newline. */
    private fun ensureSdpTrailingNewline(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return s
        return if (s.endsWith("\r\n")) s else "$s\r\n"
    }

    // -- PeerConnection creation --

    private fun getOrCreatePeerConnection(peerDeviceId: String): P2pPeerState {
        peers[peerDeviceId]?.let { return it }
        val factory = peerConnectionFactory ?: throw IllegalStateException("PeerConnectionFactory not initialized")
        val iceServers = buildIceServers()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
        }
        val observer = createPeerConnectionObserver(peerDeviceId)
        val pc = factory.createPeerConnection(rtcConfig, observer)
            ?: throw IllegalStateException("Failed to create PeerConnection for $peerDeviceId")
        val state = P2pPeerState(
            peerDeviceId = peerDeviceId,
            peerConnection = pc,
            dataChannel = null
        )
        peers[peerDeviceId] = state
        return state
    }

    private fun createOfferAndSend(peerDeviceId: String) {
        // If a PeerConnection already exists in a non-stable state, don't re-offer.
        peers[peerDeviceId]?.let { existing ->
            val sigState = existing.peerConnection.signalingState()
            if (sigState != PeerConnection.SignalingState.STABLE &&
                sigState != PeerConnection.SignalingState.CLOSED
            ) {
                logger("P2P: skipping offer to $peerDeviceId (already negotiating, signalingState=$sigState)", null)
                return
            }
            if (existing.dcState == "open") {
                logger("P2P: skipping offer to $peerDeviceId (DataChannel already open)", null)
                return
            }
            // Existing PC is stable or closed but DC not open — tear down and retry fresh.
            runCatching { existing.dataChannel?.close() }
            runCatching { existing.peerConnection.close() }
            peers.remove(peerDeviceId)
        }
        val state = getOrCreatePeerConnection(peerDeviceId)
        val pc = state.peerConnection
        val dcInit = DataChannel.Init().apply {
            ordered = true
            negotiated = false
        }
        val dc = pc.createDataChannel("meme", dcInit)
        if (dc != null) {
            dc.registerObserver(createDataChannelObserver(peerDeviceId))
            state.dataChannel = dc
        }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createOffer(object : SimpleSdpObserver("createOffer($peerDeviceId)") {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                pc.setLocalDescription(SimpleSdpObserver("setLocalDesc-offer($peerDeviceId)"), sdp)
                signalingWs?.send(
                    JSONObject()
                        .put("type", "offer")
                        .put("to", peerDeviceId)
                        .put("sdp", sdp.description)
                )
                logger("P2P: sent offer to $peerDeviceId", null)
            }
        }, constraints)
    }

    private fun handleRemoteOffer(from: String, sdp: String) {
        logger("P2P: received offer from $from", null)
        val existing = peers[from]
        if (existing != null) {
            val sigState = existing.peerConnection.signalingState()
            if (sigState != PeerConnection.SignalingState.STABLE) {
                // Glare: both sides sent offers simultaneously.
                // "Polite" peer (lower device_id) yields and accepts the remote offer.
                val isPolite = deviceId < from
                if (!isPolite) {
                    logger("P2P: ignoring offer from $from (impolite peer, signalingState=$sigState)", null)
                    return
                }
                logger("P2P: glare with $from — polite peer yielding (signalingState=$sigState)", null)
                runCatching { existing.dataChannel?.close() }
                runCatching { existing.peerConnection.close() }
                peers.remove(from)
            }
        }
        val state = getOrCreatePeerConnection(from)
        val pc = state.peerConnection
        pc.setRemoteDescription(
            SimpleSdpObserver("setRemoteDesc-offer($from)"),
            SessionDescription(SessionDescription.Type.OFFER, sdp)
        )
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createAnswer(object : SimpleSdpObserver("createAnswer($from)") {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                pc.setLocalDescription(SimpleSdpObserver("setLocalDesc-answer($from)"), sdp)
                signalingWs?.send(
                    JSONObject()
                        .put("type", "answer")
                        .put("to", from)
                        .put("sdp", sdp.description)
                )
                logger("P2P: sent answer to $from", null)
            }
        }, constraints)
    }

    private fun handleRemoteAnswer(from: String, sdp: String) {
        logger("P2P: received answer from $from", null)
        val state = peers[from] ?: return
        state.peerConnection.setRemoteDescription(
            SimpleSdpObserver("setRemoteDesc-answer($from)"),
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    private fun handleRemoteCandidate(from: String, candidateObj: JSONObject) {
        val state = peers[from] ?: return
        val candidate = IceCandidate(
            candidateObj.optString("sdpMid", ""),
            candidateObj.optInt("sdpMLineIndex", 0),
            candidateObj.optString("candidate", "")
        )
        state.peerConnection.addIceCandidate(candidate)
    }

    // -- Observers --

    private fun createPeerConnectionObserver(peerDeviceId: String): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                val stateStr = newState?.name?.lowercase() ?: "unknown"
                logger("P2P: ICE state $peerDeviceId -> $stateStr", null)
                peers[peerDeviceId]?.iceState = stateStr
                onConnectionStateChanged(peerDeviceId, stateStr)
                if (newState == PeerConnection.IceConnectionState.FAILED ||
                    newState == PeerConnection.IceConnectionState.DISCONNECTED
                ) {
                    logger("P2P: ICE $stateStr for $peerDeviceId, cleaning up", null)
                }
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                signalingWs?.send(
                    JSONObject()
                        .put("type", "candidate")
                        .put("to", peerDeviceId)
                        .put("candidate", JSONObject()
                            .put("sdpMid", candidate.sdpMid)
                            .put("sdpMLineIndex", candidate.sdpMLineIndex)
                            .put("candidate", candidate.sdp)
                        )
                )
            }

            override fun onDataChannel(dc: DataChannel?) {
                if (dc == null) return
                logger("P2P: remote DataChannel opened for $peerDeviceId label=${dc.label()}", null)
                dc.registerObserver(createDataChannelObserver(peerDeviceId))
                peers[peerDeviceId]?.dataChannel = dc
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
            override fun onRenegotiationNeeded() {}
        }
    }

    private fun createDataChannelObserver(peerDeviceId: String): DataChannel.Observer {
        return object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                val dc = peers[peerDeviceId]?.dataChannel ?: return
                val stateStr = dc.state().name.lowercase()
                logger("P2P: DataChannel state $peerDeviceId -> $stateStr", null)
                peers[peerDeviceId]?.dcState = stateStr
                if (stateStr == "open") {
                    dcOpenLatches.remove(peerDeviceId)?.countDown()
                    onConnectionStateChanged(peerDeviceId, "p2p_connected")
                } else if (stateStr == "closed") {
                    onConnectionStateChanged(peerDeviceId, "p2p_disconnected")
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null) return
                val data = buffer.data
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                val text = String(bytes, StandardCharsets.UTF_8)
                runCatching { onDataChannelMessage(peerDeviceId, text) }.onFailure {
                    logger("P2P: onDataChannelMessage error for $peerDeviceId", it)
                }
            }
        }
    }

    // -- ICE server building --

    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        // Default Google STUN
        servers.add(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer()
        )
        // Parse configured ICE servers
        runCatching {
            val arr = JSONArray(config.iceServersJson)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val urls = mutableListOf<String>()
                if (obj.has("urls")) {
                    val u = obj.opt("urls")
                    if (u is JSONArray) {
                        for (j in 0 until u.length()) urls.add(u.optString(j, ""))
                    } else if (u != null) {
                        urls.add(u.toString())
                    }
                } else if (obj.has("url")) {
                    urls.add(obj.optString("url", ""))
                }
                urls.removeAll { it.isBlank() }
                if (urls.isEmpty()) continue
                val builder = PeerConnection.IceServer.builder(urls)
                val username = obj.optString("username", "").trim()
                val credential = obj.optString("credential", "").trim()
                if (username.isNotBlank()) builder.setUsername(username)
                if (credential.isNotBlank()) builder.setPassword(credential)
                servers.add(builder.createIceServer())
            }
        }.onFailure {
            logger("P2P: failed to parse iceServersJson", it)
        }
        return servers
    }

    // -- SDP observer helper --

    private open inner class SimpleSdpObserver(private val tag: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            logger("P2P: SDP $tag create failed: $error", null)
        }
        override fun onSetFailure(error: String?) {
            logger("P2P: SDP $tag set failed: $error", null)
        }
    }
}
