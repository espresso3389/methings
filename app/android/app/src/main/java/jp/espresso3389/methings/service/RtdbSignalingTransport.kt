package jp.espresso3389.methings.service

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class RtdbSignalingTransport(
    private val databaseUrl: String,
    private val rootPath: String,
    private val deviceId: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: (code: Int, reason: String) -> Unit = { _, _ -> },
    private val onError: (Exception) -> Unit = {},
    private val logger: (String, Throwable?) -> Unit = { _, _ -> }
) : SignalingTransport {
    private val active = AtomicBoolean(false)
    private var database: FirebaseDatabase? = null
    private var inboxRef: DatabaseReference? = null
    private var presenceRef: DatabaseReference? = null
    private var inboxListener: ChildEventListener? = null
    @Volatile private var connectedAtMs: Long = 0L

    @Volatile
    override var isConnected: Boolean = false
        private set

    override fun connect() {
        active.set(true)
        runCatching {
            connectedAtMs = System.currentTimeMillis()
            val db = FirebaseDatabase.getInstance(databaseUrl)
            database = db
            val root = db.reference.child(rootPath.trim('/'))
            val inbox = root.child("inbox").child(deviceId)
            val presence = root.child("presence").child(deviceId)
            inboxRef = inbox
            presenceRef = presence
            logger("RTDB signaling: connect start root=$rootPath device=$deviceId connectedAt=$connectedAtMs", null)

            presence.onDisconnect().removeValue()
            presence.setValue(
                mapOf(
                    "online" to true,
                    "device_id" to deviceId,
                    "updated_at" to System.currentTimeMillis()
                )
            )
            clearInboxBacklog(inbox) {
                if (!active.get()) return@clearInboxBacklog
                logger("RTDB signaling: backlog cleared, attaching listener device=$deviceId", null)
                val listener = object : ChildEventListener {
                    override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                        val raw = snapshot.value as? Map<*, *> ?: return
                        val createdAt = (raw["created_at"] as? Number)?.toLong() ?: 0L
                        if (createdAt in 1 until connectedAtMs) {
                            logger("RTDB signaling: dropping stale message key=${snapshot.key} created_at=$createdAt", null)
                            snapshot.ref.removeValue()
                            return
                        }
                        val json = JSONObject()
                        for ((k, v) in raw) {
                            if (k != null) json.put(k.toString(), toJsonValue(v))
                        }
                        logger(
                            "RTDB signaling: received type=${json.optString("type", "")} from=${json.optString("from", "")} key=${snapshot.key}",
                            null
                        )
                        runCatching { onMessage(json) }.onFailure {
                            logger("RTDB signaling: onMessage callback failed", it)
                        }
                        snapshot.ref.removeValue()
                    }

                    override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = Unit

                    override fun onChildRemoved(snapshot: DataSnapshot) = Unit

                    override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit

                    override fun onCancelled(error: DatabaseError) {
                        isConnected = false
                        val ex = error.toException()
                        logger("RTDB signaling: listener cancelled ${error.message}", ex)
                        runCatching { onError(ex) }
                        runCatching { onDisconnected(error.code, error.message) }
                    }
                }
                inboxListener = listener
                inbox.addChildEventListener(listener)
                isConnected = true
                logger("RTDB signaling: connected root=$rootPath device=$deviceId", null)
                runCatching { onConnected() }
            }
        }.onFailure {
            isConnected = false
            logger("RTDB signaling: connect failed", it)
            if (it is Exception) {
                runCatching { onError(it) }
            } else {
                runCatching { onError(RuntimeException(it)) }
            }
        }
    }

    override fun disconnect() {
        active.set(false)
        connectedAtMs = 0L
        runCatching {
            inboxListener?.let { listener ->
                inboxRef?.removeEventListener(listener)
            }
        }
        inboxListener = null
        runCatching { presenceRef?.removeValue() }
        inboxRef = null
        presenceRef = null
        database = null
        if (isConnected) {
            runCatching { onDisconnected(1000, "client_disconnect") }
        }
        isConnected = false
    }

    override fun send(msg: JSONObject): Boolean {
        if (!active.get()) return false
        val to = msg.optString("to", "").trim()
        if (to.isBlank()) return false
        val inbox = inboxRef ?: return false
        val targetRef = inbox.parent?.child(to)?.push() ?: return false
        val outbound = JSONObject(msg.toString())
            .put("from", deviceId)
            .put("created_at", System.currentTimeMillis())
        return runCatching {
            logger(
                "RTDB signaling: enqueue type=${outbound.optString("type", "")} to=$to key=${targetRef.key}",
                null
            )
            targetRef.setValue(outbound.toMap()).addOnFailureListener {
                logger("RTDB signaling: send failed to=$to key=${targetRef.key}", it)
                if (it is Exception) runCatching { onError(it) }
            }
            true
        }.getOrElse {
            logger("RTDB signaling: send failed to=$to", it)
            if (it is Exception) runCatching { onError(it) }
            false
        }
    }

    override fun reconnect() {
        disconnect()
        if (active.get()) connect()
    }

    override fun shutdown() {
        disconnect()
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = opt(key)
            out[key] = when (value) {
                is JSONObject -> value.toMap()
                else -> value
            }
        }
        return out
    }

    private fun toJsonValue(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> {
                val obj = JSONObject()
                for ((k, v) in value) {
                    if (k != null) obj.put(k.toString(), toJsonValue(v))
                }
                obj
            }
            is List<*> -> {
                val arr = JSONArray()
                for (item in value) arr.put(toJsonValue(item))
                arr
            }
            else -> value
        }
    }

    private fun clearInboxBacklog(inbox: DatabaseReference, done: () -> Unit) {
        logger("RTDB signaling: fetching backlog device=$deviceId", null)
        inbox.get()
            .addOnSuccessListener { snapshot ->
                val children = snapshot.children.toList()
                logger("RTDB signaling: backlog count=${children.size} device=$deviceId", null)
                if (children.isEmpty()) {
                    done()
                    return@addOnSuccessListener
                }
                val remaining = AtomicInteger(children.size)
                for (child in children) {
                    child.ref.removeValue().addOnCompleteListener { task ->
                        if (!task.isSuccessful) {
                            logger("RTDB signaling: failed to clear inbox message key=${child.key}", task.exception)
                        }
                        if (remaining.decrementAndGet() == 0) done()
                    }
                }
            }
            .addOnFailureListener { ex ->
                logger("RTDB signaling: failed to fetch inbox backlog", ex)
                done()
            }
    }
}
