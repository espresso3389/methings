package jp.espresso3389.methings.service.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class ExtractedMedia(
    val base64: String,
    val mimeType: String,
    val mediaType: String,  // "image" or "audio"
)

private data class ToolRoundRecord(
    val name: String,
    val status: String,
    val args: JSONObject,
    val resultSnippet: String,
)

private data class MediaFallbackContext(
    val mediaData: ExtractedMedia,
    val prompt: String,
)

private data class ArtifactRef(
    val kind: String,
    val relPath: String,
    val name: String,
)

private data class ArtifactGroup(
    val messageIndex: Int,
    val createdAt: Long,
    val artifacts: List<ArtifactRef>,
)

private data class ReferentPattern(
    val kind: String,
    val artifactBias: String,
    val phrases: Set<String>,
)

private data class ReferentLocalePack(
    val locale: String,
    val generated: Boolean,
    val expiresAt: Long,
    val qualityScore: Double,
    val deicticSingular: Set<String>,
    val deicticPlural: Set<String>,
    val artifactTerms: Map<String, Set<String>>,
    val patterns: List<ReferentPattern>,
)

private data class TurnResumeState(
    val roundIndex: Int,
    val nextCallIndex: Int,
    val pendingInput: JSONArray,
    val pendingCalls: JSONArray,
    val forcedRounds: Int,
    val roundsUsed: Int,
    val toolCallsRequested: Int,
    val toolCallsExecuted: Int,
    val toolUsageCounts: LinkedHashMap<String, Int>,
    val lastRoundFingerprint: String,
    val consecutiveStallRounds: Int,
    val stoppedForStall: Boolean,
    val lastToolName: String,
    val lastToolStatus: String,
    val lastToolError: String,
    val toolHistory: MutableList<ToolRoundRecord>,
    val toolRequiredUnsatisfied: Boolean,
    val lastResponsesOutputItems: JSONArray,
    val lastResponsesResponseId: String,
) {
    fun toJson(): JSONObject {
        val usage = JSONObject()
        for ((name, count) in toolUsageCounts) usage.put(name, count)
        val history = JSONArray()
        for (record in toolHistory) {
            history.put(JSONObject().apply {
                put("name", record.name)
                put("status", record.status)
                put("args", record.args)
                put("result_snippet", record.resultSnippet)
            })
        }
        return JSONObject().apply {
            put("round_index", roundIndex)
            put("next_call_index", nextCallIndex)
            put("pending_input", pendingInput)
            put("pending_calls", pendingCalls)
            put("forced_rounds", forcedRounds)
            put("rounds_used", roundsUsed)
            put("tool_calls_requested", toolCallsRequested)
            put("tool_calls_executed", toolCallsExecuted)
            put("tool_usage_counts", usage)
            put("last_round_fingerprint", lastRoundFingerprint)
            put("consecutive_stall_rounds", consecutiveStallRounds)
            put("stopped_for_stall", stoppedForStall)
            put("last_tool_name", lastToolName)
            put("last_tool_status", lastToolStatus)
            put("last_tool_error", lastToolError)
            put("tool_history", history)
            put("tool_required_unsatisfied", toolRequiredUnsatisfied)
            put("last_responses_output_items", lastResponsesOutputItems)
            put("last_responses_response_id", lastResponsesResponseId)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): TurnResumeState {
            val usageJson = json.optJSONObject("tool_usage_counts") ?: JSONObject()
            val usage = linkedMapOf<String, Int>()
            val usageKeys = usageJson.keys()
            while (usageKeys.hasNext()) {
                val key = usageKeys.next()
                usage[key] = usageJson.optInt(key, 0)
            }

            val historyJson = json.optJSONArray("tool_history") ?: JSONArray()
            val history = mutableListOf<ToolRoundRecord>()
            for (i in 0 until historyJson.length()) {
                val record = historyJson.optJSONObject(i) ?: continue
                history.add(ToolRoundRecord(
                    name = record.optString("name", ""),
                    status = record.optString("status", ""),
                    args = record.optJSONObject("args") ?: JSONObject(),
                    resultSnippet = record.optString("result_snippet", ""),
                ))
            }

            return TurnResumeState(
                roundIndex = json.optInt("round_index", 0),
                nextCallIndex = json.optInt("next_call_index", 0),
                pendingInput = JSONArray(json.optJSONArray("pending_input")?.toString() ?: "[]"),
                pendingCalls = JSONArray(json.optJSONArray("pending_calls")?.toString() ?: "[]"),
                forcedRounds = json.optInt("forced_rounds", 0),
                roundsUsed = json.optInt("rounds_used", 0),
                toolCallsRequested = json.optInt("tool_calls_requested", 0),
                toolCallsExecuted = json.optInt("tool_calls_executed", 0),
                toolUsageCounts = LinkedHashMap(usage),
                lastRoundFingerprint = json.optString("last_round_fingerprint", ""),
                consecutiveStallRounds = json.optInt("consecutive_stall_rounds", 0),
                stoppedForStall = json.optBoolean("stopped_for_stall", false),
                lastToolName = json.optString("last_tool_name", ""),
                lastToolStatus = json.optString("last_tool_status", ""),
                lastToolError = json.optString("last_tool_error", ""),
                toolHistory = history,
                toolRequiredUnsatisfied = json.optBoolean("tool_required_unsatisfied", false),
                lastResponsesOutputItems = JSONArray(json.optJSONArray("last_responses_output_items")?.toString() ?: "[]"),
                lastResponsesResponseId = json.optString("last_responses_response_id", ""),
            )
        }
    }
}

class AgentRuntime(
    private val userDir: File,
    private val sysDir: File,
    private val storage: AgentStorage,
    private val journalStore: JournalStore,
    private val toolExecutor: ToolExecutor,
    private val llmClient: LlmClient,
    private val configManager: AgentConfigManager,
    private val embeddedBackendRegistry: EmbeddedBackendRegistry,
    private val emitLog: (String, JSONObject) -> Unit,
) {
    private val lock = Any()
    private val queue = ArrayDeque<JSONObject>(200)
    private val messages = ArrayDeque<JSONObject>(200)
    @Volatile private var thread: Thread? = null
    @Volatile private var stopFlag = false
    @Volatile private var interruptFlag = false
    private var interruptInfo = JSONObject()
    @Volatile private var currentItem: JSONObject? = null
    @Volatile private var busy = false
    private var lastError = ""
    private var lastProcessedAt = 0L

    private val sessionNotes = ConcurrentHashMap<String, MutableMap<String, String>>()
    private val capabilityPermissions = ConcurrentHashMap<String, String>()
    private val awaitingPermissions = ConcurrentHashMap<String, JSONObject>()
    private var activeIdentity = ""
    private var activeModelName = ""
    private val eventCoalesceLastTs = ConcurrentHashMap<String, Long>()

    // Policy doc caches
    private val userRootDocCache = ConcurrentHashMap<String, Map<String, Any>>()
    private val sysDocCache = ConcurrentHashMap<String, Map<String, Any>>()

    // Event bus callback for WebView SSE
    var onEvent: ((String, JSONObject) -> Unit)? = null

    fun start(): JSONObject {
        synchronized(lock) {
            if (thread != null && thread!!.isAlive) {
                return JSONObject().put("status", "ok").put("already_running", true)
            }
            stopFlag = false
            interruptFlag = false
            thread = Thread({ runLoop() }, "AgentRuntime").apply {
                isDaemon = true
                start()
            }
        }
        emitLog("brain_started", JSONObject())
        return JSONObject().put("status", "ok")
    }

    fun stop(): JSONObject {
        synchronized(lock) {
            stopFlag = true
            interruptFlag = true
            thread?.interrupt()
            thread = null
        }
        emitLog("brain_stopped", JSONObject())
        return JSONObject().put("status", "ok")
    }

    fun isRunning(): Boolean = thread?.isAlive == true && !stopFlag

    fun status(): JSONObject {
        synchronized(lock) {
            return JSONObject().apply {
                put("running", isRunning())
                put("busy", busy)
                put("queue_size", queue.size)
                put("last_error", lastError)
                put("last_processed_at", lastProcessedAt)
                val cur = currentItem
                if (cur != null) {
                    put("current_item_id", cur.optString("id", ""))
                    put("current_session_id", sessionIdForItem(cur))
                }
            }
        }
    }

    fun enqueueChat(text: String, meta: JSONObject = JSONObject()): JSONObject {
        val now = System.currentTimeMillis()
        val item = JSONObject().apply {
            put("id", "chat_$now")
            put("kind", "chat")
            put("text", text)
            put("meta", meta)
            put("created_at", now)
        }
        synchronized(lock) {
            queue.addLast(item)
        }
        return JSONObject().put("status", "ok").put("id", item.optString("id"))
    }

    fun enqueueEvent(
        name: String,
        payload: JSONObject = JSONObject(),
        priority: String = "normal",
        interruptPolicy: String = "turn_end",
        coalesceKey: String = "",
        coalesceWindowMs: Long = 0,
    ): JSONObject {
        if (coalesceKey.isNotEmpty() && coalesceWindowMs > 0) {
            val lastTs = eventCoalesceLastTs[coalesceKey] ?: 0L
            val now = System.currentTimeMillis()
            if (now - lastTs < coalesceWindowMs) {
                return JSONObject().put("status", "coalesced")
            }
            eventCoalesceLastTs[coalesceKey] = now
        }

        val now = System.currentTimeMillis()
        val item = JSONObject().apply {
            put("id", "event_$now")
            put("kind", "event")
            put("name", name.trim().ifEmpty { "unnamed_event" })
            put("payload", payload)
            put("meta", JSONObject().put("priority", priority).put("interrupt_policy", interruptPolicy))
            put("created_at", now)
        }
        synchronized(lock) {
            if (priority in setOf("high", "critical")) {
                queue.addFirst(item)
            } else {
                queue.addLast(item)
            }
        }
        return JSONObject().put("status", "ok").put("id", item.optString("id"))
    }

    fun clearSession(sessionId: String) {
        val sid = sessionId.trim().ifEmpty { return }
        sessionNotes.remove(sid)
        synchronized(lock) {
            val filtered = queue.filter { sessionIdForItem(it) != sid }
            queue.clear()
            filtered.forEach { queue.addLast(it) }
        }
    }

    fun interrupt(itemId: String = "", sessionId: String = "", clearQueue: Boolean = false): JSONObject {
        val iid = itemId.trim()
        val sid = sessionId.trim()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            interruptFlag = true
            interruptInfo = JSONObject().apply {
                put("ts", now)
                put("item_id", iid)
                put("session_id", sid)
                put("clear_queue", clearQueue)
            }
            if (clearQueue) {
                if (sid.isNotEmpty()) {
                    val filtered = queue.filter { sessionIdForItem(it) != sid }
                    queue.clear()
                    filtered.forEach { queue.addLast(it) }
                } else {
                    queue.clear()
                }
            }
        }
        if (iid.isNotEmpty()) {
            val meta = JSONObject().apply {
                put("item_id", iid)
                put("session_id", sid.ifEmpty { sessionIdForItem(currentItem ?: JSONObject()) }.ifEmpty { "default" })
                put("actor", "system")
            }
            recordMessage("assistant", "Interrupted.", meta)
            emitLog("brain_interrupted", JSONObject().put("item_id", iid).put("session_id", meta.optString("session_id")))
        }
        return JSONObject().put("status", "ok").put("item_id", iid).put("session_id", sid).put("clear_queue", clearQueue)
    }

    fun listMessagesForSession(sessionId: String, limit: Int = 200): List<Map<String, Any?>> {
        return storage.listChatMessages(sessionId, limit)
    }

    fun listSessions(limit: Int = 50): List<Map<String, Any?>> {
        return storage.listChatSessions(limit)
    }

    fun listProviderRequestsForSession(sessionId: String, limit: Int = 100): List<Map<String, Any?>> {
        return storage.listLlmRequests(sessionId, limit)
    }

    // --- Main Processing Loop ---

    private fun runLoop() {
        while (!stopFlag) {
            var item: JSONObject? = null
            synchronized(lock) {
                if (queue.isNotEmpty()) {
                    item = queue.pollFirst()
                }
            }
            if (item == null) {
                try {
                    Thread.sleep(800)
                } catch (_: InterruptedException) {
                    if (stopFlag) return
                }
                continue
            }
            synchronized(lock) {
                busy = true
                lastError = ""
                currentItem = item
            }
            try {
                processItem(item!!)
                synchronized(lock) {
                    lastProcessedAt = System.currentTimeMillis()
                }
                emitLog("brain_item_done", JSONObject().apply {
                    put("id", item?.optString("id"))
                    put("item_id", item?.optString("id"))
                    put("session_id", sessionIdForItem(item ?: JSONObject()))
                })
            } catch (_: InterruptedException) {
                emitLog("brain_item_interrupted", JSONObject().apply {
                    put("id", item?.optString("id"))
                    put("session_id", sessionIdForItem(item ?: JSONObject()))
                })
            } catch (ex: Exception) {
                synchronized(lock) { lastError = ex.message ?: "" }
                try {
                    val sid = sessionIdForItem(item!!)
                    var msg = ex.message ?: "Unknown error"
                    if ("401" in msg && "Unauthorized" in msg) {
                        msg = "Unauthorized (401). Check your API key in Settings."
                    }
                    if (ex is java.net.SocketTimeoutException || "Read timed out" in msg) {
                        msg = "Provider request timed out. Check network connectivity, then retry."
                    }
                    recordMessage("assistant", "Error: $msg", JSONObject().apply {
                        put("item_id", item?.optString("id"))
                        put("session_id", sid)
                        put("error", "brain_item_failed")
                    })
                } catch (_: Exception) {}
                emitLog("brain_item_failed", JSONObject().apply {
                    put("id", item?.optString("id"))
                    put("error", ex.message ?: "")
                    put("session_id", sessionIdForItem(item ?: JSONObject()))
                })
            } finally {
                emitLog("brain_idle", JSONObject().apply {
                    put("item_id", item?.optString("id") ?: "")
                    put("session_id", sessionIdForItem(item ?: JSONObject()))
                })
                synchronized(lock) {
                    busy = false
                    currentItem = null
                    interruptFlag = false
                }
            }
        }
    }

    private fun processItem(item: JSONObject) {
        val kind = item.optString("kind", "")
        if (kind == "event") {
            processEvent(item)
        } else {
            processChat(item)
        }
    }

    private fun processEvent(item: JSONObject) {
        val name = item.optString("name", "")
        val payload = item.optJSONObject("payload") ?: JSONObject()

        when (name) {
            "permission.resolved" -> {
                val permId = payload.optString("permission_id", "")
                val permStatus = payload.optString("status", "")
                val paused = awaitingPermissions.remove(permId)
                if (paused != null && permStatus == "approved") {
                    val originalItem = paused.optJSONObject("item")
                    if (originalItem != null) {
                        val resumedItem = JSONObject(originalItem.toString())
                        val turnState = paused.optJSONObject("turn_state")
                        if (turnState != null) {
                            resumedItem.put("resume_state", JSONObject(turnState.toString()))
                        }
                        processWithResponsesTools(resumedItem)
                    }
                } else if (paused != null && permStatus == "denied") {
                    val sid = paused.optString("session_id", "default")
                    recordMessage("assistant", "Permission denied for ${paused.optString("tool", "unknown")}.",
                        JSONObject().put("session_id", sid).put("actor", "system"))
                }
            }
            "permission.auto_approved" -> {
                // Internal — no need to record in chat history
            }
            else -> {
                // Internal event — not recorded in chat history
                // If run_agent is requested, escalate to a chat-like processing
                if (payload.optBoolean("run_agent", false)) {
                    val prompt = payload.optString("prompt", "").ifEmpty {
                        "System event: $name. ${payload.optString("summary", "")}"
                    }
                    // Build a short display text for the chat UI (the full prompt goes to the agent)
                    val displayText = if (name == "me.me.received") {
                        val fromName = payload.optString("from_device_name", "").trim()
                            .ifBlank { payload.optString("from_device_id", "").trim() }
                        val preview = payload.optString("message_preview", "").trim()
                        val mtype = payload.optString("message_type", "").trim()
                        when (mtype) {
                            "response" -> if (preview.isNotBlank()) "[$fromName] $preview" else "Response from $fromName"
                            "file" -> "File from $fromName" + if (preview.isNotBlank()) ": $preview" else ""
                            else -> if (preview.isNotBlank()) "[$fromName] $preview" else "Request from $fromName"
                        }
                    } else {
                        ""
                    }
                    val chatItem = JSONObject().apply {
                        put("id", item.optString("id"))
                        put("kind", "chat")
                        put("text", prompt)
                        if (displayText.isNotBlank()) put("display_text", displayText)
                        put("meta", JSONObject().put("session_id", sessionIdForItem(item)).put("actor", "system"))
                        put("created_at", System.currentTimeMillis())
                        // me.me events with run_agent require actual tool calls (not text-only responses)
                        if (name == "me.me.received") put("require_tools", true)
                    }
                    processChat(chatItem)
                }
            }
        }
    }

    private fun processChat(item: JSONObject) {
        val text = item.optString("text", "")
        val sessionId = sessionIdForItem(item)

        // Record user message (use display_text for chat UI if available, full text goes to the LLM)
        if (!item.optBoolean("suppress_user_record", false)) {
            val visibleText = item.optString("display_text", "").ifBlank { text }
            recordMessage("user", visibleText, JSONObject().apply {
                put("item_id", item.optString("id"))
                put("session_id", sessionId)
                put("actor", item.optJSONObject("meta")?.optString("actor", "human") ?: "human")
            })
        }

        processWithResponsesTools(item)
    }

    private fun processWithResponsesTools(item: JSONObject) {
        checkInterrupt()
        val sessionId = sessionIdForItem(item)
        activeIdentity = sessionId.ifEmpty { "default" }

        val config = configManager.loadFull()
        val model = configManager.getModel()
        activeModelName = model
        val vendor = configManager.getVendor()
        val baseUrl = configManager.getBaseUrl()
        val apiKey = configManager.getApiKey()
        val providerUrl = configManager.resolveProviderUrl(vendor, baseUrl)
        val providerKind = llmClient.detectProviderKind(providerUrl, vendor)
        val embeddedSpec = if (providerKind == ProviderKind.EMBEDDED) EmbeddedModelCatalog.find(model) else null
        val requiresApiKey = providerKind != ProviderKind.EMBEDDED

        if (model.isEmpty() || providerUrl.isEmpty() || (requiresApiKey && apiKey.isEmpty())) {
            recordMessage("assistant",
                "Brain is not configured yet. Configure it in Settings -> Brain.",
                JSONObject().put("item_id", item.optString("id")).put("session_id", sessionId))
            return
        }

        Log.i(TAG, "Provider: kind=$providerKind, vendor=$vendor, url=$providerUrl, model=$model")
        if (providerKind == ProviderKind.EMBEDDED) {
            val backendStatus = embeddedBackendRegistry.statusFor(model)
            if (embeddedSpec != null && backendStatus?.runnable == true) {
                val dialogueLimit = config.intWithProfile("dialogue_window_user_assistant", 40, 10, 120)
                val dialogueRawLimit = config.intWithProfile("dialogue_raw_fetch_limit", 320, 40, 2000)
                val persistentMemory = getPersistentMemory()
                val journalCurrent = journalStore.getCurrent(sessionId).optString("text", "")
                val dialogue = listDialogue(sessionId, dialogueLimit, dialogueRawLimit)
                val curText = item.optString("text", "")
                val filteredDialogue = if (dialogue.isNotEmpty() &&
                    dialogue.last().optString("role") == "user" &&
                    dialogue.last().optString("text") == curText
                ) dialogue.dropLast(1) else dialogue
                val systemPrompt = buildSystemPrompt(config, emptySet()) +
                    "\n\nEmbedded execution note: Tool calling is not wired yet in this build. Respond directly in text."
                val journalBlob = buildJournalBlob(journalCurrent, sessionId, persistentMemory)
                val pendingInput = buildInitialInput(
                    kind = ProviderKind.EMBEDDED,
                    dialogue = filteredDialogue,
                    journalBlob = journalBlob,
                    curText = curText,
                    item = item,
                    supportedMedia = emptySet(),
                )
                val prompt = renderEmbeddedPrompt(systemPrompt, pendingInput)
                val reply = runCatching { embeddedBackendRegistry.generateText(model, prompt) }.getOrElse { ex ->
                    "Embedded inference failed: ${ex.message ?: ex.javaClass.simpleName}"
                }.trim()
                val finalReply = if (reply.isNotBlank()) reply else "Embedded inference returned an empty response."
                recordMessage(
                    "assistant",
                    finalReply,
                    JSONObject().put("item_id", item.optString("id")).put("session_id", sessionId),
                )
                emitLog("brain_response", JSONObject()
                    .put("item_id", item.optString("id"))
                    .put("text", finalReply.take(300)))
                return
            }
            val detail = when {
                embeddedSpec == null -> "Unknown embedded model '$model'."
                backendStatus == null -> "Embedded model '$model' is not registered."
                else -> buildString {
                    append(backendStatus.detail)
                    append(" Expected path: ")
                    append(backendStatus.primaryModelPath)
                    if (backendStatus.candidatePaths.size > 1) {
                        append(" (alternatives: ")
                        append(backendStatus.candidatePaths.drop(1).joinToString(", "))
                        append(")")
                    }
                }
            }
            val msg = "$detail See docs/embedded_gemma4.md for the recommended implementation path."
            recordMessage(
                "assistant",
                msg,
                JSONObject().put("item_id", item.optString("id")).put("session_id", sessionId),
            )
            emitLog("brain_response", JSONObject()
                .put("item_id", item.optString("id"))
                .put("text", msg.take(300)))
            return
        }
        val extraCfg = mutableMapOf<String, String>()
        if (providerKind == ProviderKind.ANTHROPIC && config.boolWithProfile("extended_thinking", false)) {
            extraCfg["anthropic-beta"] = "interleaved-thinking-2025-05-14"
        }
        val headers = llmClient.buildHeaders(providerKind, apiKey, extraCfg)

        val dialogueLimit = config.intWithProfile("dialogue_window_user_assistant", 40, 10, 120)
        val dialogueRawLimit = config.intWithProfile("dialogue_raw_fetch_limit", 320, 40, 2000)
        val maxRounds = config.intWithProfile("max_tool_rounds", 18, 1, 24)
        val maxActions = config.intWithProfile("max_actions", 10, 1, 12)
        val connectTimeoutMs = (config.providerConnectTimeoutS.coerceIn(1, 60) * 1000)
        val readTimeoutMs = (config.providerReadTimeoutS.coerceIn(5, 600) * 1000)
        val maxRetries = config.providerMaxRetries.coerceIn(0, 3)
        val toolPolicy = config.toolPolicy
        val requireTool = toolPolicy == "required" || item.optBoolean("require_tools", false)
        var toolRequiredUnsatisfied = requireTool

        // Build dialogue context
        val persistentMemory = getPersistentMemory()
        val journalCurrent = journalStore.getCurrent(sessionId).optString("text", "")
        val dialogue = listDialogue(sessionId, dialogueLimit, dialogueRawLimit)

        // Remove last message if it duplicates current user text
        val curText = item.optString("text", "")
        val filteredDialogue = if (dialogue.isNotEmpty() &&
            dialogue.last().optString("role") == "user" &&
            dialogue.last().optString("text") == curText
        ) dialogue.dropLast(1) else dialogue

        val tools = when (providerKind) {
            ProviderKind.OPENAI_RESPONSES -> ToolDefinitions.responsesTools(ToolDefinitions.deviceApiActionNames())
            ProviderKind.OPENAI_CHAT -> ToolDefinitions.chatTools(ToolDefinitions.deviceApiActionNames())
            ProviderKind.ANTHROPIC -> ToolDefinitions.anthropicTools(ToolDefinitions.deviceApiActionNames())
            ProviderKind.GOOGLE_GEMINI -> ToolDefinitions.geminiTools(ToolDefinitions.deviceApiActionNames())
            ProviderKind.EMBEDDED -> ToolDefinitions.chatTools(ToolDefinitions.deviceApiActionNames())
        }

        // Determine which media types the provider supports natively
        val supportedMediaTypes: Set<String> = when (providerKind) {
            ProviderKind.GOOGLE_GEMINI -> setOf("image", "audio")
            ProviderKind.ANTHROPIC, ProviderKind.OPENAI_RESPONSES -> setOf("image")
            ProviderKind.OPENAI_CHAT -> if (!providerUrl.contains("generativelanguage.googleapis.com")) setOf("image") else emptySet()
            ProviderKind.EMBEDDED -> emptySet()
        }

        // Expose supported media types to ToolExecutor so analyze_image/analyze_audio can guard early
        toolExecutor.supportedMediaTypes = supportedMediaTypes

        // Gemini uses a different URL format: {base}/models/{model}:streamGenerateContent?key={apiKey}&alt=sse
        val effectiveProviderUrl = if (providerKind == ProviderKind.GOOGLE_GEMINI) {
            val base = providerUrl.trimEnd('/')
            "$base/models/$model:streamGenerateContent?key=$apiKey&alt=sse"
        } else {
            providerUrl
        }

        val systemPrompt = buildSystemPrompt(config, supportedMediaTypes)
        val journalBlob = buildJournalBlob(journalCurrent, sessionId, persistentMemory)
        var mediaFallbackContext: MediaFallbackContext? = null
        var mediaFallbackAttempted = false

        var pendingInput = buildInitialInput(providerKind, filteredDialogue, journalBlob, curText, item, supportedMediaTypes)
        val resumeStateJson = item.optJSONObject("resume_state")
        val resumeState = if (resumeStateJson != null) TurnResumeState.fromJson(resumeStateJson) else null
        if (resumeState != null) {
            pendingInput = resumeState.pendingInput
        }
        // Reset Responses API per-turn state.
        lastResponsesOutputItems = resumeState?.lastResponsesOutputItems ?: JSONArray()
        lastResponsesResponseId = resumeState?.lastResponsesResponseId ?: ""
        var forcedRounds = resumeState?.forcedRounds ?: 0
        var roundsUsed = resumeState?.roundsUsed ?: 0
        var toolCallsRequested = resumeState?.toolCallsRequested ?: 0
        var toolCallsExecuted = resumeState?.toolCallsExecuted ?: 0
        val toolUsageCounts = resumeState?.toolUsageCounts ?: linkedMapOf<String, Int>()
        var lastRoundFingerprint = resumeState?.lastRoundFingerprint ?: ""
        var consecutiveStallRounds = resumeState?.consecutiveStallRounds ?: 0
        var stoppedForStall = resumeState?.stoppedForStall ?: false
        var lastToolName = resumeState?.lastToolName ?: ""
        var lastToolStatus = resumeState?.lastToolStatus ?: ""
        var lastToolError = resumeState?.lastToolError ?: ""
        val toolHistory = resumeState?.toolHistory ?: mutableListOf()
        toolRequiredUnsatisfied = resumeState?.toolRequiredUnsatisfied ?: toolRequiredUnsatisfied
        val startRoundIdx = resumeState?.roundIndex ?: 0
        var resumedCalls: JSONArray? = resumeState?.pendingCalls
        var resumedCallIndex = resumeState?.nextCallIndex ?: 0

        for (roundIdx in startRoundIdx until maxRounds) {
            checkInterrupt()
            roundsUsed = roundIdx + 1
            emitLog("brain_status", JSONObject().apply {
                put("item_id", item.optString("id"))
                put("session_id", sessionId)
                put("status", "thinking")
                put("label", "Thinking\u2026")
            })

            val usingResumedCalls = resumedCalls != null
            val calls: JSONArray
            if (usingResumedCalls) {
                calls = resumedCalls ?: JSONArray()
            } else {
                val body = buildRequestBody(providerKind, model, tools, pendingInput, systemPrompt, toolRequiredUnsatisfied)
                if (providerKind == ProviderKind.OPENAI_RESPONSES && roundIdx > 0 && lastResponsesResponseId.isNotBlank()) {
                    body.put("previous_response_id", lastResponsesResponseId)
                }

                try {
                    val inputArr = pendingInput as? JSONArray
                    if (inputArr != null) {
                        val summary = (0 until inputArr.length()).joinToString(", ") { i ->
                            val o = inputArr.optJSONObject(i)
                            val role = o?.optString("role", "?") ?: "?"
                            val text = o?.optString("text", "") ?: ""
                            val content = o?.opt("content")
                            val textLen = when {
                                text.isNotEmpty() -> text.length
                                content is String -> content.length
                                content is JSONArray -> {
                                    (0 until content.length()).sumOf { j ->
                                        content.optJSONObject(j)?.optString("text", "")?.length ?: 0
                                    }
                                }
                                else -> 0
                            }
                            "$role($textLen)"
                        }
                        Log.i(TAG, "Round $roundIdx input: [$summary] system=${systemPrompt.length}chars tools=${tools.length()}")
                    }
                } catch (_: Exception) {}
                if (providerKind == ProviderKind.GOOGLE_GEMINI) {
                    logGeminiRequestStructure(body)
                }

                var payload: JSONObject? = null
                var lastEx: Exception? = null
                for (attempt in 0..maxRetries) {
                    try {
                        recordProviderRequest(
                            sessionId = sessionId,
                            itemId = item.optString("id"),
                            providerKind = providerKind,
                            providerUrl = effectiveProviderUrl,
                            model = model,
                            phase = "round_${roundIdx}_attempt_${attempt}",
                            body = body,
                        )
                        payload = llmClient.streamingPost(
                            effectiveProviderUrl, headers, body, providerKind,
                            connectTimeoutMs, readTimeoutMs,
                            interruptCheck = { interruptFlag }
                        )
                        lastEx = null
                        break
                    } catch (ex: InterruptedException) {
                        throw ex
                    } catch (ex: Exception) {
                        lastEx = ex
                        if (body.has("tool_choice")) {
                            try {
                                val body2 = JSONObject(body.toString())
                                body2.remove("tool_choice")
                                recordProviderRequest(
                                    sessionId = sessionId,
                                    itemId = item.optString("id"),
                                    providerKind = providerKind,
                                    providerUrl = effectiveProviderUrl,
                                    model = model,
                                    phase = "round_${roundIdx}_attempt_${attempt}_fallback_no_tool_choice",
                                    body = body2,
                                )
                                payload = llmClient.streamingPost(
                                    effectiveProviderUrl, headers, body2, providerKind,
                                    connectTimeoutMs, readTimeoutMs,
                                    interruptCheck = { interruptFlag }
                                )
                                lastEx = null
                                break
                            } catch (_: Exception) {}
                        }
                        val isTransient = ex is java.net.SocketTimeoutException || ex is java.net.ConnectException
                        if (!isTransient || attempt >= maxRetries) throw ex
                        Thread.sleep((300L * (attempt + 1)).coerceAtMost(1500))
                    }
                }
                if (lastEx != null) throw lastEx
                if (payload == null) throw RuntimeException("No response from provider")

                val parseResult = parseProviderResponse(providerKind, payload)
                val messageTexts = parseResult.first
                calls = parseResult.second
                toolCallsRequested += calls.length()

                if (calls.length() == 0) {
                    if (messageTexts.isEmpty()) {
                        if (!mediaFallbackAttempted && mediaFallbackContext != null &&
                            shouldUseDirectMediaFallback(providerKind, providerUrl, model)
                        ) {
                            mediaFallbackAttempted = true
                            val fallbackText = runDirectMediaFallback(
                                providerKind = providerKind,
                                providerUrl = effectiveProviderUrl,
                                headers = headers,
                                model = model,
                                sessionId = sessionId,
                                itemId = item.optString("id"),
                                phase = "round_${roundIdx}_direct_media_fallback",
                                context = mediaFallbackContext,
                                connectTimeoutMs = connectTimeoutMs,
                                readTimeoutMs = readTimeoutMs,
                            )
                            if (fallbackText != null) {
                                val toolSummary = buildToolHistorySummary(toolHistory)
                                val recorded = if (toolSummary.isNotEmpty()) "$fallbackText\n$toolSummary" else fallbackText
                                recordMessage("assistant", recorded, JSONObject().apply {
                                    put("item_id", item.optString("id"))
                                    put("session_id", sessionId)
                                })
                                emitLog("brain_response", JSONObject()
                                    .put("item_id", item.optString("id"))
                                    .put("text", fallbackText.take(300)))
                                return
                            }
                        }
                        pendingInput = appendUserNudge(providerKind, pendingInput,
                            "You returned an empty response (no text, no tool calls). You MUST either respond with text or call tools.")
                        continue
                    }
                    if (toolRequiredUnsatisfied && forcedRounds < 2) {
                        forcedRounds++
                        pendingInput = appendUserNudge(providerKind, pendingInput,
                            "Tool policy is REQUIRED for this request. " +
                            "You MUST call one or more tools to perform the action(s). " +
                            "If you cannot call tools (e.g. missing permission or info), " +
                            "explain the blocker to the user. Never claim an action is complete " +
                            "without tool confirmation.")
                        continue
                    }
                    if (forcedRounds < 3) {
                        val isContinuationMsg = curText.length <= 20 && curText.lowercase(Locale.US).let { t ->
                            t.contains("続け") || t.contains("どうぞ") || t.contains("go ahead") ||
                            t.contains("continue") || t.contains("proceed") || t.contains("やって") ||
                            t.contains("お願い")
                        }
                        val isIdleNoTools = toolCallsExecuted == 0 && (curText.length > 20 || isContinuationMsg)
                        val asksUserToAct = messageTexts.any { t ->
                            t.contains("続け") || t.contains("どうぞ") || t.contains("お試し") ||
                            t.contains("次のターン") || t.contains("次のメッセージ") ||
                            t.contains("proceed") || t.contains("continue") || t.contains("next turn") ||
                            t.contains("next message") || t.contains("should I") ||
                            t.contains("shall I") || t.contains("try again")
                        }
                        val isMidTaskPause = toolCallsExecuted > 0 && roundIdx < maxRounds - 2 && asksUserToAct
                        if (isIdleNoTools || isMidTaskPause) {
                            forcedRounds++
                            if (isIdleNoTools) {
                                toolRequiredUnsatisfied = true
                            }
                            Log.i(TAG, "Text-only nudge on round $roundIdx (toolsExec=$toolCallsExecuted, idle=$isIdleNoTools, pause=$isMidTaskPause)")
                            pendingInput = appendUserNudge(providerKind, pendingInput,
                                if (isIdleNoTools && isContinuationMsg)
                                    "The user is asking you to continue the previous task. " +
                                    "Your text response was NOT shown — you MUST call tools to resume work. " +
                                    "You have full tool access in this turn. Call the next tool NOW."
                                else if (isIdleNoTools)
                                    "You responded with text only — this text was NOT shown to the user. " +
                                    "You MUST call tools to perform the action. " +
                                    "If you are blocked, explain the blocker."
                                else
                                    "Do NOT ask the user to 'continue' or '続けて'. " +
                                    "Do NOT ask for 'the next turn' or another message when no new information is needed. " +
                                    "If there is more work to do, call the next tool NOW. " +
                                    "If you are blocked (missing permission, error), explain the blocker honestly.")
                            continue
                        }
                    }
                    val toolSummary = buildToolHistorySummary(toolHistory)
                    Log.i(TAG, "Final text (round=$roundIdx, toolsExec=$toolCallsExecuted, forcedRounds=$forcedRounds): ${messageTexts.joinToString(" | ").take(300)}")
                    for ((idx, text) in messageTexts.withIndex()) {
                        val recorded = if (idx == messageTexts.lastIndex && toolSummary.isNotEmpty()) {
                            "$text\n$toolSummary"
                        } else text
                        recordMessage("assistant", recorded, JSONObject().apply {
                            put("item_id", item.optString("id"))
                            put("session_id", sessionId)
                        })
                        emitLog("brain_response", JSONObject().put("item_id", item.optString("id")).put("text", text.take(300)))
                    }
                    return
                }

                toolRequiredUnsatisfied = false

                if (providerKind == ProviderKind.OPENAI_RESPONSES) {
                    pendingInput = JSONArray()
                    if (lastResponsesResponseId.isBlank()) {
                        for (ci in 0 until lastResponsesOutputItems.length()) {
                            pendingInput.put(lastResponsesOutputItems.get(ci))
                        }
                    }
                }

                if (providerKind == ProviderKind.OPENAI_CHAT) {
                    val assistantMsg = JSONObject().put("role", "assistant")
                    if (messageTexts.isNotEmpty()) assistantMsg.put("content", messageTexts.joinToString("\n"))
                    val tcArr = JSONArray()
                    for (ci in 0 until calls.length()) {
                        val c = calls.getJSONObject(ci)
                        tcArr.put(JSONObject().apply {
                            put("id", c.optString("call_id", c.optString("id", "")))
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", c.optString("name", ""))
                                put("arguments", (c.opt("arguments") as? JSONObject)?.toString() ?: "{}")
                            })
                        })
                    }
                    assistantMsg.put("tool_calls", tcArr)
                    pendingInput.put(assistantMsg)
                }

                if (providerKind == ProviderKind.ANTHROPIC) {
                    val rawContent = payload.optJSONArray("content")
                    if (rawContent != null) {
                        pendingInput.put(JSONObject().put("role", "assistant").put("content", rawContent))
                    } else {
                        val contentArr = JSONArray()
                        for (t in messageTexts) {
                            contentArr.put(JSONObject().put("type", "text").put("text", t))
                        }
                        for (ci in 0 until calls.length()) {
                            val c = calls.getJSONObject(ci)
                            contentArr.put(JSONObject().apply {
                                put("type", "tool_use")
                                put("id", c.optString("call_id", c.optString("id", "")))
                                put("name", c.optString("name", ""))
                                put("input", c.opt("arguments") ?: JSONObject())
                            })
                        }
                        pendingInput.put(JSONObject().put("role", "assistant").put("content", contentArr))
                    }
                }

                if (providerKind == ProviderKind.GOOGLE_GEMINI) {
                    val parts = JSONArray()
                    for (t in messageTexts) {
                        parts.put(JSONObject().put("text", t))
                    }
                    for (ci in 0 until calls.length()) {
                        val c = calls.getJSONObject(ci)
                        parts.put(JSONObject().apply {
                            put("functionCall", JSONObject().apply {
                                put("name", c.optString("name", ""))
                                put("args", c.opt("arguments") ?: JSONObject())
                            })
                            val sig = c.optString("thoughtSignature", "")
                            if (sig.isNotEmpty()) put("thoughtSignature", sig)
                        })
                    }
                    pendingInput.put(JSONObject().put("role", "model").put("parts", parts))
                }
            }

            val callStartIndex = if (usingResumedCalls) resumedCallIndex else 0
            for (i in callStartIndex until calls.length().coerceAtMost(maxActions)) {
                checkInterrupt()
                val call = calls.getJSONObject(i)
                val name = call.optString("name", "")
                toolCallsExecuted++
                if (name.isNotBlank()) {
                    toolUsageCounts[name] = (toolUsageCounts[name] ?: 0) + 1
                    lastToolName = name
                }
                val callId = call.optString("call_id", call.optString("id", ""))
                val rawArgs = call.opt("arguments") ?: call.opt("input")
                val args = try {
                    when (rawArgs) {
                        is String -> JSONObject(rawArgs)
                        is JSONObject -> rawArgs
                        else -> JSONObject()
                    }
                } catch (_: Exception) { JSONObject() }

                emitLog("brain_status", JSONObject().apply {
                    put("item_id", item.optString("id"))
                    put("session_id", sessionId)
                    put("status", "tool")
                    put("tool", name)
                    put("label", friendlyToolLabel(name, args))
                })

                val result = toolExecutor.executeFunctionTool(name, args, curText)
                lastToolStatus = result.optString("status", "").ifBlank { "ok" }
                lastToolError = result.optString("error", "")
                Log.i(TAG, "Tool[$roundIdx/$i] $name → status=$lastToolStatus" +
                    if (lastToolError.isNotBlank()) " error=$lastToolError" else "" +
                    " args=${args.toString().take(200)}" +
                    " result=${result.toString().take(300)}")

                toolHistory.add(ToolRoundRecord(
                    name = name,
                    status = lastToolStatus,
                    args = args,
                    resultSnippet = buildToolResultSnippet(name, args, result),
                ))

                val resultStatus = result.optString("status", "")
                if (resultStatus in setOf("permission_required", "permission_expired")) {
                    val req = result.optJSONObject("request") ?: JSONObject()
                    val pid = req.optString("id", "").trim()
                    if (pid.isNotEmpty()) {
                        val turnState = TurnResumeState(
                            roundIndex = roundIdx,
                            nextCallIndex = i,
                            pendingInput = JSONArray(pendingInput.toString()),
                            pendingCalls = JSONArray(calls.toString()),
                            forcedRounds = forcedRounds,
                            roundsUsed = roundsUsed,
                            toolCallsRequested = toolCallsRequested,
                            toolCallsExecuted = toolCallsExecuted - 1,
                            toolUsageCounts = LinkedHashMap(toolUsageCounts).apply {
                                if (name.isNotBlank()) {
                                    val updated = (this[name] ?: 1) - 1
                                    if (updated <= 0) remove(name) else put(name, updated)
                                }
                            },
                            lastRoundFingerprint = lastRoundFingerprint,
                            consecutiveStallRounds = consecutiveStallRounds,
                            stoppedForStall = stoppedForStall,
                            lastToolName = if (lastToolName == name) "" else lastToolName,
                            lastToolStatus = "",
                            lastToolError = "",
                            toolHistory = toolHistory.dropLast(1).mapTo(mutableListOf()) {
                                ToolRoundRecord(it.name, it.status, JSONObject(it.args.toString()), it.resultSnippet)
                            },
                            toolRequiredUnsatisfied = toolRequiredUnsatisfied,
                            lastResponsesOutputItems = JSONArray(lastResponsesOutputItems.toString()),
                            lastResponsesResponseId = lastResponsesResponseId,
                        )
                        awaitingPermissions[pid] = JSONObject().apply {
                            put("permission_id", pid)
                            put("status", "pending")
                            put("tool", name)
                            put("session_id", sessionId)
                            put("item", item)
                            put("turn_state", turnState.toJson())
                            put("created_at", System.currentTimeMillis())
                        }
                    }
                    emitLog("brain_response", JSONObject().put("item_id", item.optString("id")).put("text", "permission_required"))
                    return
                }

                if (resultStatus == "error") {
                    val err = result.optString("error", "")
                    if (err in setOf("command_not_allowed", "path_not_allowed", "invalid_path")) {
                        recordMessage("assistant",
                            "Tool '$name' failed with $err. This is blocked by local policy/sandbox.",
                            JSONObject().put("item_id", item.optString("id")).put("session_id", sessionId))
                        return
                    }
                }

                val mediaData = extractMediaFromToolResult(result, supportedMediaTypes)
                if (mediaData != null) {
                    Log.i(TAG, "Tool '$name' produced ${mediaData.mediaType}: ${mediaData.mimeType}, ${mediaData.base64.length} chars b64, sending as multimodal")
                } else if (supportedMediaTypes.isNotEmpty()) {
                    Log.d(TAG, "Tool '$name' result has no extractable media (supported=$supportedMediaTypes)")
                }

                val truncated = ToolExecutor.truncateToolOutput(result,
                    config.intWithProfile("max_tool_output_chars", 12000, 2000, 100000),
                    config.intWithProfile("max_tool_output_list_items", 80, 10, 500))

                val textResult = if (mediaData != null) {
                    val stripped = ToolExecutor.stripMediaData(truncated)
                    stripped.put("_media_hint",
                        "A ${mediaData.mediaType} is attached to this tool result. " +
                        "You can see/hear it directly — describe or analyze it now. " +
                        "Do NOT say you cannot analyze it or call cloud_request.")
                    stripped
                } else truncated

                appendToolResult(providerKind, pendingInput, callId, textResult, name, mediaData)
                if (mediaData != null) {
                    mediaFallbackContext = MediaFallbackContext(
                        mediaData = mediaData,
                        prompt = result.optString("prompt", "").ifBlank {
                            "Analyze the attached ${mediaData.mediaType} directly and answer the user."
                        },
                    )
                    mediaFallbackAttempted = false
                }
            }

            resumedCalls = null
            resumedCallIndex = 0

            val roundCallsFingerprint = buildString {
                val limit = calls.length().coerceAtMost(maxActions)
                for (i in 0 until limit) {
                    val c = calls.optJSONObject(i) ?: continue
                    val n = c.optString("name", "")
                    append(n)
                    if (n == "device_api") {
                        val rawArgs = c.opt("arguments") ?: c.opt("input")
                        val argsObj = try {
                            when (rawArgs) {
                                is String -> JSONObject(rawArgs)
                                is JSONObject -> rawArgs
                                else -> null
                            }
                        } catch (_: Exception) { null }
                        val action = argsObj?.optString("action", "") ?: ""
                        if (action.isNotEmpty()) append("/$action")
                        val payload = argsObj?.optJSONObject("payload")
                        if (payload != null) append("#${payload.toString().hashCode()}")
                    }
                    append(';')
                }
                append("last=")
                append(lastToolName)
                append(':')
                append(lastToolStatus)
                if (lastToolError.isNotBlank()) {
                    append(':')
                    append(lastToolError.take(80))
                }
            }
            if (roundCallsFingerprint.isNotBlank() && roundCallsFingerprint == lastRoundFingerprint) {
                consecutiveStallRounds += 1
            } else {
                consecutiveStallRounds = 0
            }
            lastRoundFingerprint = roundCallsFingerprint
            if (consecutiveStallRounds >= 2) {
                stoppedForStall = true
                break
            }

            if (roundIdx >= maxRounds - 3) {
                pendingInput = appendUserNudge(providerKind, pendingInput,
                    "Tool outputs have been provided. If the user's request is fully satisfied, respond with the final answer and STOP. " +
                    "If there are still outstanding actions needed, call additional tools now.")
            }
        }

        // Exhausted max rounds or stall — force finalization
        try {
            val stallInfo = if (stoppedForStall) {
                val toolNames = toolHistory.map { it.name }.distinct().joinToString(", ")
                val lastStatus = toolHistory.lastOrNull()?.status ?: "unknown"
                "\n- STALL DETECTED: You called the same tool(s) repeatedly ($toolNames) with status=$lastStatus." +
                "\n- Report honestly what was accomplished and what was NOT completed." +
                "\n- Do NOT claim actions were completed unless tool output confirms it."
            } else ""
            val finalPrompt = (systemPrompt + "\n\nFINALIZATION:\n- Do NOT call any tools.\n- Produce the best possible final answer now." +
                "\n- Summarize what was accomplished and what remains." +
                "\n- Do NOT ask the user to say '続けて' or 'continue'. If the task is incomplete, just say what was done and what is left." +
                stallInfo).trim()
            val finalBody = buildRequestBody(providerKind, model, JSONArray(), pendingInput, finalPrompt, false)
            if (providerKind == ProviderKind.OPENAI_RESPONSES && lastResponsesResponseId.isNotBlank()) {
                finalBody.put("previous_response_id", lastResponsesResponseId)
            }
            finalBody.put("tool_choice", "none")

            val finalPayload = try {
                recordProviderRequest(
                    sessionId = sessionId,
                    itemId = item.optString("id"),
                    providerKind = providerKind,
                    providerUrl = effectiveProviderUrl,
                    model = model,
                    phase = "finalization",
                    body = finalBody,
                )
                llmClient.streamingPost(effectiveProviderUrl, headers, finalBody, providerKind, connectTimeoutMs, readTimeoutMs,
                    interruptCheck = { interruptFlag })
            } catch (_: Exception) {
                val body2 = JSONObject(finalBody.toString())
                body2.remove("tool_choice")
                recordProviderRequest(
                    sessionId = sessionId,
                    itemId = item.optString("id"),
                    providerKind = providerKind,
                    providerUrl = effectiveProviderUrl,
                    model = model,
                    phase = "finalization_fallback_no_tool_choice",
                    body = body2,
                )
                llmClient.streamingPost(effectiveProviderUrl, headers, body2, providerKind, connectTimeoutMs, readTimeoutMs,
                    interruptCheck = { interruptFlag })
            }

            val finalResult = parseProviderResponse(providerKind, finalPayload)
            val finalToolSummary = buildToolHistorySummary(toolHistory)
            for ((idx, text) in finalResult.first.withIndex()) {
                val recorded = if (idx == finalResult.first.lastIndex && finalToolSummary.isNotEmpty()) {
                    "$text\n$finalToolSummary"
                } else text
                recordMessage("assistant", recorded, JSONObject().apply {
                    put("item_id", item.optString("id"))
                    put("session_id", sessionId)
                })
                emitLog("brain_response", JSONObject().put("item_id", item.optString("id")).put("text", text.take(300)))
            }
            if (finalResult.first.isNotEmpty()) return
        } catch (ex: Exception) {
            Log.w(TAG, "Finalization failed", ex)
        }

        val toolsUsedSummary = if (toolUsageCounts.isEmpty()) {
            "none"
        } else {
            toolUsageCounts.entries
                .sortedByDescending { it.value }
                .take(6)
                .joinToString(", ") { "${it.key} x${it.value}" }
        }
        val lastToolSummary = if (lastToolName.isBlank()) {
            "none"
        } else {
            val errPart = if (lastToolError.isNotBlank()) " (error=$lastToolError)" else ""
            "$lastToolName -> $lastToolStatus$errPart"
        }
        val fallbackToolSummary = buildToolHistorySummary(toolHistory)
        recordMessage("assistant",
            (if (stoppedForStall) "Stopped due to repetitive tool-call loop.\n" else "Reached maximum tool rounds ($maxRounds).\n") +
                "Progress summary:\n" +
                "- Rounds used: $roundsUsed/$maxRounds\n" +
                "- Tool calls requested by model: $toolCallsRequested\n" +
                "- Tool calls executed: $toolCallsExecuted\n" +
                "- Tools used: $toolsUsedSummary\n" +
                "- Last tool result: $lastToolSummary" +
                if (fallbackToolSummary.isNotEmpty()) "\n$fallbackToolSummary" else "",
            JSONObject().put("item_id", item.optString("id")).put("session_id", sessionId))
    }

    // --- Provider-Specific Request/Response Building ---

    private fun buildRequestBody(
        kind: ProviderKind, model: String, tools: JSONArray,
        input: Any, systemPrompt: String, requireTool: Boolean
    ): JSONObject {
        return when (kind) {
            ProviderKind.OPENAI_RESPONSES -> JSONObject().apply {
                put("model", model)
                put("tools", tools)
                put("input", input)
                put("instructions", systemPrompt)
                put("truncation", "auto")
                if (requireTool) put("tool_choice", "required")
            }
            ProviderKind.OPENAI_CHAT -> JSONObject().apply {
                put("model", model)
                put("tools", tools)
                // Prepend system message, then the dialogue messages
                val messages = JSONArray()
                messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
                val inputArr = input as? JSONArray ?: JSONArray()
                for (i in 0 until inputArr.length()) {
                    messages.put(inputArr.get(i))
                }
                put("messages", messages)
                if (requireTool) put("tool_choice", "required")
            }
            ProviderKind.ANTHROPIC -> JSONObject().apply {
                val cfg = configManager.loadFull()
                val thinkingEnabled = cfg.boolWithProfile("extended_thinking", false)
                val thinkingBudget = cfg.intWithProfile("thinking_budget_tokens", 8000, 1000, 32000)
                put("model", model)
                put("max_tokens", if (thinkingEnabled) maxOf(16000, thinkingBudget + 4096) else 8192)
                put("system", systemPrompt)
                put("tools", tools)
                put("messages", input)
                if (requireTool) put("tool_choice", JSONObject().put("type", "any"))
                if (thinkingEnabled) {
                    put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", thinkingBudget))
                }
            }
            ProviderKind.GOOGLE_GEMINI -> JSONObject().apply {
                put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                put("contents", input)
                put("tools", tools)
                if (requireTool) {
                    put("tool_config", JSONObject().put("function_calling_config",
                        JSONObject().put("mode", "ANY")))
                }
            }
            ProviderKind.EMBEDDED -> throw UnsupportedOperationException("Embedded providers do not build remote request bodies")
        }
    }

    private fun parseProviderResponse(kind: ProviderKind, payload: JSONObject): Pair<List<String>, JSONArray> {
        return when (kind) {
            ProviderKind.OPENAI_RESPONSES -> parseOpenAiResponsesResponse(payload)
            ProviderKind.OPENAI_CHAT -> parseOpenAiChatResponse(payload)
            ProviderKind.ANTHROPIC -> parseAnthropicResponse(payload)
            ProviderKind.GOOGLE_GEMINI -> parseGeminiResponse(payload)
            ProviderKind.EMBEDDED -> throw UnsupportedOperationException("Embedded providers do not parse remote payloads")
        }
    }

    /** All non-message output items from the last Responses API call (reasoning + function_call).
     *  Must be echoed back in the input when sending function_call_output items. */
    private var lastResponsesOutputItems = JSONArray()
    private var lastResponsesResponseId = ""

    private fun parseOpenAiResponsesResponse(payload: JSONObject): Pair<List<String>, JSONArray> {
        lastResponsesResponseId = payload.optString("id", "")
        val outputItems = payload.optJSONArray("output") ?: JSONArray()
        val messageTexts = mutableListOf<String>()
        val calls = JSONArray()
        val echoItems = JSONArray()

        for (i in 0 until outputItems.length()) {
            val out = outputItems.optJSONObject(i) ?: continue
            when (out.optString("type")) {
                "message" -> {
                    val contentArr = out.optJSONArray("content") ?: continue
                    val parts = mutableListOf<String>()
                    for (j in 0 until contentArr.length()) {
                        val part = contentArr.optJSONObject(j) ?: continue
                        val pType = part.optString("type")
                        if (pType == "output_text" || pType == "text") {
                            val t = part.optString("text", "").trim()
                            if (t.isNotEmpty()) parts.add(t)
                        }
                    }
                    if (parts.isNotEmpty()) messageTexts.add(parts.joinToString("\n"))
                }
                // Some Responses-compatible models emit top-level text items instead of
                // wrapping text inside a "message" output item.
                "output_text", "text" -> {
                    val t = out.optString("text", "").trim()
                    if (t.isNotEmpty()) messageTexts.add(t)
                    echoItems.put(out)
                }
                "function_call" -> {
                    calls.put(out)
                    echoItems.put(out)
                }
                else -> {
                    // Capture reasoning and other items needed for echo-back
                    echoItems.put(out)
                }
            }
        }
        lastResponsesOutputItems = echoItems
        return Pair(messageTexts, calls)
    }

    private fun parseOpenAiChatResponse(payload: JSONObject): Pair<List<String>, JSONArray> {
        val messageTexts = mutableListOf<String>()
        val calls = JSONArray()

        val choices = payload.optJSONArray("choices") ?: JSONArray()
        for (ci in 0 until choices.length()) {
            val choice = choices.optJSONObject(ci) ?: continue
            val msg = choice.optJSONObject("message") ?: continue
            val content = if (msg.isNull("content")) "" else msg.optString("content", "").trim()
            if (content.isNotEmpty()) messageTexts.add(content)
            val toolCalls = msg.optJSONArray("tool_calls")
            if (toolCalls != null) {
                for (ti in 0 until toolCalls.length()) {
                    val tc = toolCalls.optJSONObject(ti) ?: continue
                    val fn = tc.optJSONObject("function") ?: continue
                    val argsStr = fn.optString("arguments", "{}")
                    val argsJson = try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }
                    calls.put(JSONObject().apply {
                        put("name", fn.optString("name", ""))
                        put("call_id", tc.optString("id", ""))
                        put("arguments", argsJson)
                    })
                }
            }
        }
        return Pair(messageTexts, calls)
    }

    private fun parseAnthropicResponse(payload: JSONObject): Pair<List<String>, JSONArray> {
        val contentArr = payload.optJSONArray("content") ?: JSONArray()
        val messageTexts = mutableListOf<String>()
        val calls = JSONArray()

        for (i in 0 until contentArr.length()) {
            val block = contentArr.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> {
                    val t = block.optString("text", "").trim()
                    if (t.isNotEmpty()) messageTexts.add(t)
                }
                "tool_use" -> {
                    calls.put(JSONObject().apply {
                        put("name", block.optString("name", ""))
                        put("call_id", block.optString("id", ""))
                        put("arguments", block.optJSONObject("input") ?: JSONObject())
                    })
                }
            }
        }
        return Pair(messageTexts, calls)
    }

    private fun parseGeminiResponse(payload: JSONObject): Pair<List<String>, JSONArray> {
        val contentArr = payload.optJSONArray("content") ?: JSONArray()
        val messageTexts = mutableListOf<String>()
        val calls = JSONArray()

        for (i in 0 until contentArr.length()) {
            val block = contentArr.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> {
                    val t = block.optString("text", "").trim()
                    if (t.isNotEmpty()) messageTexts.add(t)
                }
                "function_call" -> {
                    calls.put(JSONObject().apply {
                        put("name", block.optString("name", ""))
                        put("call_id", block.optString("call_id", ""))
                        put("arguments", block.optJSONObject("arguments") ?: JSONObject())
                        val sig = block.optString("thoughtSignature", "")
                        if (sig.isNotEmpty()) put("thoughtSignature", sig)
                    })
                }
            }
        }
        return Pair(messageTexts, calls)
    }

    // --- Media / Multimodal Helpers ---

    /** Extract media from tool result.
     *  1. Check for explicit _media marker (from analyze_image/analyze_audio).
     *  2. Auto-detect from rel_path/path fields (image and audio extensions).
     *  Only returns media whose type is in [supportedTypes]. */
    private fun extractMediaFromToolResult(result: JSONObject, supportedTypes: Set<String>): ExtractedMedia? {
        if (supportedTypes.isEmpty()) return null
        return try {
            // 1. Check for explicit _media marker (from analyze_image/analyze_audio)
            val media = result.optJSONObject("_media")
            if (media != null) {
                val type = media.optString("type", "")
                val b64 = media.optString("base64", "")
                val mime = media.optString("mime_type", "")
                if (type in supportedTypes && b64.isNotEmpty() && mime.isNotEmpty()) {
                    Log.i(TAG, "extractMedia: found _media marker type=$type, mime=$mime")
                    return ExtractedMedia(b64, mime, type)
                } else if (type.isNotEmpty() && type !in supportedTypes) {
                    Log.i(TAG, "extractMedia: _media type=$type not supported by current provider (supported=$supportedTypes)")
                }
            }

            // 2. Auto-detect from rel_path/path fields
            val pathsToCheck = mutableListOf<String>()
            val directPath = result.optString("rel_path", result.optString("path", ""))
            if (directPath.isNotEmpty()) pathsToCheck.add(directPath)
            val nested = result.optJSONObject("result")
            if (nested != null) {
                val nestedPath = nested.optString("rel_path", nested.optString("path", ""))
                if (nestedPath.isNotEmpty()) pathsToCheck.add(nestedPath)
            }

            for (path in pathsToCheck) {
                val normalizedPath = when {
                    else -> path
                }
                if (normalizedPath.isBlank()) continue
                // Check images
                if ("image" in supportedTypes && MediaEncoder.isImagePath(normalizedPath)) {
                    val file = File(userDir, normalizedPath)
                    Log.d(TAG, "extractMedia: found image path='$normalizedPath', exists=${file.exists()}")
                    if (file.exists()) {
                        val maxDim = if (toolExecutor.imageResizeEnabled) toolExecutor.imageMaxDimPx else Int.MAX_VALUE
                        val encoded = MediaEncoder.encodeImage(file, maxDim, toolExecutor.imageJpegQuality)
                        if (encoded != null) {
                            Log.i(TAG, "extractMedia: encoded image from '$normalizedPath' (${encoded.base64.length} chars base64)")
                            return ExtractedMedia(encoded.base64, encoded.mimeType, "image")
                        }
                    }
                }
                // Check audio
                if ("audio" in supportedTypes && MediaEncoder.isAudioPath(normalizedPath)) {
                    val file = File(userDir, normalizedPath)
                    Log.d(TAG, "extractMedia: found audio path='$normalizedPath', exists=${file.exists()}")
                    if (file.exists()) {
                        val encoded = MediaEncoder.encodeAudio(file)
                        if (encoded != null) {
                            Log.i(TAG, "extractMedia: encoded audio from '$normalizedPath' (${encoded.base64.length} chars base64)")
                            return ExtractedMedia(encoded.base64, encoded.mimeType, "audio")
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "extractMedia: failed", e)
            null
        }
    }

    /** Extract media rel_paths from user text (lines like "rel_path: photos/img.jpg"). */
    private fun extractUserMediaPaths(text: String): Pair<String, List<String>> {
        val paths = mutableListOf<String>()
        val cleanedLines = mutableListOf<String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("rel_path:")) {
                val p = trimmed.substringAfter("rel_path:").trim()
                val normalizedPath = p
                if (normalizedPath.isNotEmpty() && MediaEncoder.isMediaPath(normalizedPath)) {
                    paths.add(normalizedPath)
                    continue  // Remove this line from text sent to LLM
                }
            }
            cleanedLines.add(line)
        }
        return Pair(cleanedLines.joinToString("\n"), paths)
    }

    private fun inferArtifactKind(path: String): String {
        return when {
            MediaEncoder.isImagePath(path) -> "image"
            MediaEncoder.isAudioPath(path) -> "audio"
            path.endsWith(".pdf", ignoreCase = true) ||
                path.endsWith(".doc", ignoreCase = true) ||
                path.endsWith(".docx", ignoreCase = true) ||
                path.endsWith(".txt", ignoreCase = true) ||
                path.endsWith(".md", ignoreCase = true) -> "document"
            path.endsWith(".zip", ignoreCase = true) ||
                path.endsWith(".tar", ignoreCase = true) ||
                path.endsWith(".gz", ignoreCase = true) -> "archive"
            path.endsWith(".mp4", ignoreCase = true) ||
                path.endsWith(".mov", ignoreCase = true) ||
                path.endsWith(".webm", ignoreCase = true) -> "video"
            path.endsWith(".py", ignoreCase = true) ||
                path.endsWith(".js", ignoreCase = true) ||
                path.endsWith(".kt", ignoreCase = true) ||
                path.endsWith(".java", ignoreCase = true) -> "code"
            else -> "file"
        }
    }

    private fun normalizeReferentText(text: String): String {
        val lowered = text.lowercase(Locale.ROOT)
        val out = StringBuilder(lowered.length)
        for (ch in lowered) {
            if (ch.isLetterOrDigit() || Character.getType(ch) == Character.OTHER_LETTER.toInt()) out.append(ch)
            else out.append(' ')
        }
        return out.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun normalizeTokenSet(values: Collection<String>): Set<String> {
        return values.map { normalizeReferentText(it) }.filter { it.isNotBlank() }.toSet()
    }

    private fun detectReferentLocale(text: String): String {
        for (ch in text) {
            val block = Character.UnicodeBlock.of(ch)
            if (block == Character.UnicodeBlock.HIRAGANA ||
                block == Character.UnicodeBlock.KATAKANA ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            ) return "ja"
        }
        return "en"
    }

    private fun builtInReferentLocalePack(locale: String): ReferentLocalePack {
        return when (locale.lowercase(Locale.ROOT)) {
            "ja" -> ReferentLocalePack(
                locale = "ja",
                generated = false,
                expiresAt = 0L,
                qualityScore = 1.0,
                deicticSingular = normalizeTokenSet(listOf("これ", "この", "その", "あの", "この写真", "この画像", "このファイル", "それ", "あれ")),
                deicticPlural = normalizeTokenSet(listOf("これら", "それら", "あれら", "これらのファイル", "そのファイルたち")),
                artifactTerms = mapOf(
                    "image" to normalizeTokenSet(listOf("写真", "画像", "スクリーンショット", "キャプチャ")),
                    "document" to normalizeTokenSet(listOf("ファイル", "文書", "ドキュメント", "pdf")),
                    "audio" to normalizeTokenSet(listOf("音声", "録音")),
                    "video" to normalizeTokenSet(listOf("動画", "ビデオ")),
                    "archive" to normalizeTokenSet(listOf("zip", "アーカイブ")),
                    "code" to normalizeTokenSet(listOf("コード", "スクリプト")),
                ),
                patterns = listOf(
                    ReferentPattern("singular", "image", normalizeTokenSet(listOf("この写真", "この画像", "その写真", "このスクリーンショット"))),
                    ReferentPattern("plural", "document", normalizeTokenSet(listOf("これらのファイル", "そのファイルたち"))),
                ),
            )
            else -> ReferentLocalePack(
                locale = "en",
                generated = false,
                expiresAt = 0L,
                qualityScore = 1.0,
                deicticSingular = normalizeTokenSet(listOf("this", "that", "it", "this one", "that one")),
                deicticPlural = normalizeTokenSet(listOf("these", "those", "them")),
                artifactTerms = mapOf(
                    "image" to normalizeTokenSet(listOf("image", "photo", "picture", "screenshot")),
                    "document" to normalizeTokenSet(listOf("document", "file", "pdf")),
                    "audio" to normalizeTokenSet(listOf("audio", "recording", "voice note")),
                    "video" to normalizeTokenSet(listOf("video", "clip")),
                    "archive" to normalizeTokenSet(listOf("zip", "archive")),
                    "code" to normalizeTokenSet(listOf("code", "script", "source file")),
                ),
                patterns = listOf(
                    ReferentPattern("singular", "image", normalizeTokenSet(listOf("this photo", "this image", "that picture", "this screenshot"))),
                    ReferentPattern("plural", "document", normalizeTokenSet(listOf("these files", "those documents"))),
                ),
            )
        }
    }

    private fun parseReferentLocalePack(file: File, locale: String): ReferentLocalePack? {
        return try {
            if (!file.exists()) return null
            val obj = JSONObject(file.readText())
            if (obj.optInt("schema_version", 0) != 1) return null
            val resolvedLocale = obj.optString("locale", locale).ifBlank { locale }
            val generated = obj.optBoolean("generated", false)
            val expiresAt = obj.optLong("expires_at", 0L)
            val qualityScore = obj.optDouble("quality_score", if (generated) 0.75 else 1.0)
            val tokens = obj.optJSONObject("tokens") ?: JSONObject()
            val singularArr = tokens.optJSONArray("deictic_singular") ?: JSONArray()
            val pluralArr = tokens.optJSONArray("deictic_plural") ?: JSONArray()
            val artifactTermsObj = obj.optJSONObject("artifact_terms") ?: JSONObject()
            val artifactTerms = linkedMapOf<String, Set<String>>()
            val artifactKeys = artifactTermsObj.keys()
            while (artifactKeys.hasNext()) {
                val key = artifactKeys.next()
                val arr = artifactTermsObj.optJSONArray(key) ?: continue
                artifactTerms[key] = normalizeTokenSet((0 until arr.length()).mapNotNull { idx -> arr.optString(idx, null) })
            }
            val patternsArr = obj.optJSONArray("patterns") ?: JSONArray()
            val patterns = mutableListOf<ReferentPattern>()
            for (i in 0 until patternsArr.length()) {
                val entry = patternsArr.optJSONObject(i) ?: continue
                val kind = entry.optString("kind", "")
                if (kind != "singular" && kind != "plural") continue
                val phrasesArr = entry.optJSONArray("phrases") ?: continue
                patterns.add(
                    ReferentPattern(
                        kind = kind,
                        artifactBias = entry.optString("artifact_bias", ""),
                        phrases = normalizeTokenSet((0 until phrasesArr.length()).mapNotNull { idx -> phrasesArr.optString(idx, null) }),
                    )
                )
            }
            ReferentLocalePack(
                locale = resolvedLocale,
                generated = generated,
                expiresAt = expiresAt,
                qualityScore = qualityScore,
                deicticSingular = normalizeTokenSet((0 until singularArr.length()).mapNotNull { idx -> singularArr.optString(idx, null) }),
                deicticPlural = normalizeTokenSet((0 until pluralArr.length()).mapNotNull { idx -> pluralArr.optString(idx, null) }),
                artifactTerms = artifactTerms,
                patterns = patterns,
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseReferentLocalePack: failed for ${file.absolutePath}", e)
            null
        }
    }

    private fun loadReferentLocalePack(text: String): ReferentLocalePack {
        val locale = detectReferentLocale(text)
        val userPackFile = File(userDir, "lib/referents/locales/$locale.json")
        val systemPackFile = File(sysDir, "lib/referents/locales/$locale.json")
        val pack = parseReferentLocalePack(userPackFile, locale)
            ?: parseReferentLocalePack(systemPackFile, locale)
            ?: builtInReferentLocalePack(locale)
        val now = System.currentTimeMillis()
        if (pack.generated && pack.expiresAt > 0L && pack.expiresAt <= now) {
            Log.i(TAG, "loadReferentLocalePack: using expired generated pack locale=${pack.locale} expiresAt=${pack.expiresAt}")
        }
        return pack
    }

    private fun extractHistoricalArtifactGroups(dialogue: List<JSONObject>): List<ArtifactGroup> {
        val out = mutableListOf<ArtifactGroup>()
        for ((index, msg) in dialogue.withIndex()) {
            if (msg.optString("role") != "user") continue
            val (_, paths) = extractUserMediaPaths(msg.optString("text", ""))
            if (paths.isEmpty()) continue
            out.add(
                ArtifactGroup(
                    messageIndex = index,
                    createdAt = msg.optLong("created_at", 0L),
                    artifacts = paths.map { path ->
                        ArtifactRef(
                            kind = inferArtifactKind(path),
                            relPath = path,
                            name = File(path).name,
                        )
                    }
                )
            )
        }
        return out.takeLast(12)
    }

    private fun resolveHistoricalArtifactReferences(currentText: String, dialogue: List<JSONObject>): List<ArtifactRef> {
        val normalized = normalizeReferentText(currentText)
        if (normalized.isBlank()) return emptyList()
        val pack = loadReferentLocalePack(currentText)
        if (pack.qualityScore < REFERENT_PACK_QUALITY_THRESHOLD) return emptyList()
        val groups = extractHistoricalArtifactGroups(dialogue)
        if (groups.isEmpty()) return emptyList()

        var artifactBias = ""
        for ((kind, terms) in pack.artifactTerms) {
            if (terms.any { token -> normalized.contains(token) }) {
                artifactBias = kind
                break
            }
        }
        var wantsSingular = pack.deicticSingular.any { token -> normalized == token || normalized.contains(token) }
        var wantsPlural = pack.deicticPlural.any { token -> normalized == token || normalized.contains(token) }
        for (pattern in pack.patterns) {
            if (pattern.phrases.any { phrase -> normalized.contains(phrase) }) {
                if (pattern.kind == "plural") wantsPlural = true else wantsSingular = true
                if (artifactBias.isBlank() && pattern.artifactBias.isNotBlank()) artifactBias = pattern.artifactBias
            }
        }
        if (!wantsSingular && !wantsPlural && artifactBias.isBlank()) return emptyList()

        data class Candidate(val group: ArtifactGroup, val score: Int)
        val candidates = groups.mapIndexedNotNull { idx, group ->
            var score = 0
            if (artifactBias.isNotBlank()) {
                if (group.artifacts.any { it.kind == artifactBias }) score += 30 else score -= 20
            }
            if (wantsPlural) score += if (group.artifacts.size > 1) 20 else -10
            if (wantsSingular) score += if (group.artifacts.size == 1) 20 else 5
            if (group.artifacts.any { artifact -> normalized.contains(normalizeReferentText(artifact.name)) }) score += 50
            score += (idx + 1).coerceAtMost(5)
            if (score <= 0) null else Candidate(group, score)
        }.sortedByDescending { it.score }

        if (candidates.isEmpty()) return emptyList()
        val best = candidates[0]
        val second = candidates.getOrNull(1)
        val confidence = (best.score / 100.0).coerceIn(0.0, 1.0)
        if (confidence < REFERENT_RESOLUTION_CONFIDENCE_THRESHOLD) return emptyList()
        if (second != null && (best.score - second.score) < REFERENT_AMBIGUITY_GAP_THRESHOLD) return emptyList()

        val resolved = if (artifactBias.isBlank()) {
            best.group.artifacts
        } else {
            best.group.artifacts.filter { it.kind == artifactBias }
        }
        if (resolved.isEmpty()) return emptyList()
        Log.i(TAG, "resolveHistoricalArtifactReferences: locale=${pack.locale} resolved=${resolved.map { it.relPath }} confidence=$confidence")
        return resolved
    }

    /** Strip media rel_path lines from replayed dialogue so old attachments don't pollute later turns. */
    private fun sanitizeHistoricalDialogueText(text: String): String = extractUserMediaPaths(text).first

    /** Build multimodal content array with text + media blocks for the current user message. */
    private fun buildMultimodalUserContent(
        kind: ProviderKind, text: String, mediaParts: List<ExtractedMedia>
    ): Any {
        if (mediaParts.isEmpty()) {
            // No media — return plain text for providers that support it
            return when (kind) {
                ProviderKind.OPENAI_RESPONSES -> {
                    JSONArray().put(JSONObject().put("type", "input_text").put("text", text))
                }
                ProviderKind.GOOGLE_GEMINI -> {
                    JSONArray().put(JSONObject().put("text", text))
                }
                ProviderKind.EMBEDDED -> text
                else -> text  // OPENAI_CHAT and ANTHROPIC accept plain strings
            }
        }
        // Has media — build multimodal content
        return when (kind) {
            ProviderKind.OPENAI_RESPONSES -> {
                val arr = JSONArray()
                arr.put(JSONObject().put("type", "input_text").put("text", text))
                for (m in mediaParts) {
                    if (m.mediaType == "image") {
                        arr.put(JSONObject().apply {
                            put("type", "input_image")
                            put("image_url", "data:${m.mimeType};base64,${m.base64}")
                        })
                    }
                    // OpenAI Responses doesn't support inline audio — skip
                }
                arr
            }
            ProviderKind.OPENAI_CHAT -> {
                val arr = JSONArray()
                arr.put(JSONObject().put("type", "text").put("text", text))
                for (m in mediaParts) {
                    if (m.mediaType == "image") {
                        arr.put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().put("url", "data:${m.mimeType};base64,${m.base64}"))
                        })
                    }
                    // OpenAI Chat doesn't support inline audio — skip
                }
                arr
            }
            ProviderKind.ANTHROPIC -> {
                val arr = JSONArray()
                for (m in mediaParts) {
                    if (m.mediaType == "image") {
                        arr.put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", m.mimeType)
                                put("data", m.base64)
                            })
                        })
                    }
                    // Anthropic doesn't support inline audio — skip
                }
                arr.put(JSONObject().put("type", "text").put("text", text))
                arr
            }
            ProviderKind.GOOGLE_GEMINI -> {
                val arr = JSONArray()
                arr.put(JSONObject().put("text", text))
                for (m in mediaParts) {
                    // Gemini supports both image and audio via inline_data
                    arr.put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", m.mimeType)
                            put("data", m.base64)
                        })
                    })
                }
                arr
            }
            ProviderKind.EMBEDDED -> text
        }
    }

    private fun buildInitialInput(
        kind: ProviderKind, dialogue: List<JSONObject>,
        journalBlob: String, curText: String, item: JSONObject,
        supportedMedia: Set<String> = emptySet()
    ): JSONArray {
        val input = JSONArray()

        // Extract media from current user message (only if provider supports it)
        val (cleanedText, currentTurnMediaPaths) = extractUserMediaPaths(curText)
        val resolvedHistoricalArtifacts = if (currentTurnMediaPaths.isNotEmpty()) {
            emptyList()
        } else {
            resolveHistoricalArtifactReferences(cleanedText, dialogue)
        }
        val mediaPaths = if (currentTurnMediaPaths.isNotEmpty()) {
            currentTurnMediaPaths
        } else {
            resolvedHistoricalArtifacts
                .map { it.relPath }
                .filter { path -> MediaEncoder.isImagePath(path) || MediaEncoder.isAudioPath(path) }
        }
        val userMedia = mutableListOf<ExtractedMedia>()
        if (supportedMedia.isNotEmpty() && mediaPaths.isNotEmpty()) {
            Log.i(TAG, "buildInitialInput: found ${mediaPaths.size} user media path(s): $mediaPaths")
            for (p in mediaPaths) {
                val file = File(userDir, p)
                if (MediaEncoder.isImagePath(p) && "image" in supportedMedia) {
                    val maxDim = if (toolExecutor.imageResizeEnabled) toolExecutor.imageMaxDimPx else Int.MAX_VALUE
                    val encoded = MediaEncoder.encodeImage(file, maxDim, toolExecutor.imageJpegQuality)
                    if (encoded != null) {
                        userMedia.add(ExtractedMedia(encoded.base64, encoded.mimeType, "image"))
                        Log.i(TAG, "buildInitialInput: encoded user image '$p' (${encoded.base64.length} chars b64)")
                    } else {
                        Log.w(TAG, "buildInitialInput: failed to encode user image '$p' (file exists=${file.exists()})")
                    }
                } else if (MediaEncoder.isAudioPath(p) && "audio" in supportedMedia) {
                    val encoded = MediaEncoder.encodeAudio(file)
                    if (encoded != null) {
                        userMedia.add(ExtractedMedia(encoded.base64, encoded.mimeType, "audio"))
                        Log.i(TAG, "buildInitialInput: encoded user audio '$p' (${encoded.base64.length} chars b64)")
                    } else {
                        Log.w(TAG, "buildInitialInput: failed to encode user audio '$p' (file exists=${file.exists()})")
                    }
                }
            }
        }
        val historicalReferenceLines = if (currentTurnMediaPaths.isEmpty()) {
            resolvedHistoricalArtifacts
                .map { "rel_path: ${it.relPath}" }
                .distinct()
        } else {
            emptyList()
        }
        val finalCleanedText = buildString {
            append(cleanedText)
            if (historicalReferenceLines.isNotEmpty()) {
                if (isNotBlank()) append("\n")
                append(historicalReferenceLines.joinToString("\n"))
            }
        }.trim()
        val finalUserText = "$journalBlob\n\n$finalCleanedText"

        when (kind) {
            ProviderKind.OPENAI_RESPONSES -> {
                for (msg in dialogue) {
                    val role = msg.optString("role")
                    val text = sanitizeHistoricalDialogueText(msg.optString("text", ""))
                    if (role in setOf("user", "assistant") && text.isNotBlank()) {
                        val blockType = if (role == "assistant") "output_text" else "input_text"
                        input.put(JSONObject().put("role", role)
                            .put("content", JSONArray().put(JSONObject().put("type", blockType).put("text", text))))
                    }
                }
                // Journal blob
                input.put(JSONObject().put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", journalBlob))))
                // Current user message (with media if present)
                val content = buildMultimodalUserContent(kind, finalCleanedText, userMedia)
                input.put(JSONObject().put("role", "user").put("content", content))
            }
            ProviderKind.OPENAI_CHAT -> {
                for (msg in dialogue) {
                    val role = msg.optString("role")
                    val text = sanitizeHistoricalDialogueText(msg.optString("text", ""))
                    if (role in setOf("user", "assistant") && text.isNotBlank()) {
                        input.put(JSONObject().put("role", role).put("content", text))
                    }
                }
                val content = buildMultimodalUserContent(kind, finalUserText, userMedia)
                input.put(JSONObject().put("role", "user").put("content", content))
            }
            ProviderKind.ANTHROPIC -> {
                for (msg in dialogue) {
                    val role = msg.optString("role")
                    val text = sanitizeHistoricalDialogueText(msg.optString("text", ""))
                    if (role in setOf("user", "assistant") && text.isNotBlank()) {
                        input.put(JSONObject().put("role", role).put("content", text))
                    }
                }
                val content = buildMultimodalUserContent(kind, finalUserText, userMedia)
                input.put(JSONObject().put("role", "user").put("content", content))
            }
            ProviderKind.GOOGLE_GEMINI -> {
                for (msg in dialogue) {
                    val role = msg.optString("role")
                    val text = sanitizeHistoricalDialogueText(msg.optString("text", ""))
                    if (role in setOf("user", "assistant") && text.isNotBlank()) {
                        val geminiRole = if (role == "assistant") "model" else "user"
                        input.put(JSONObject().put("role", geminiRole)
                            .put("parts", JSONArray().put(JSONObject().put("text", text))))
                    }
                }
                // Current user message with journal + media
                val parts = buildMultimodalUserContent(kind, finalUserText, userMedia)
                input.put(JSONObject().put("role", "user").put("parts", parts))
            }
            ProviderKind.EMBEDDED -> {
                for (msg in dialogue) {
                    val role = msg.optString("role")
                    val text = sanitizeHistoricalDialogueText(msg.optString("text", ""))
                    if (role in setOf("user", "assistant") && text.isNotBlank()) {
                        input.put(JSONObject().put("role", role).put("content", text))
                    }
                }
                input.put(JSONObject().put("role", "user").put("content", finalUserText))
            }
        }
        return input
    }

    private fun appendToolResult(
        kind: ProviderKind, input: JSONArray, callId: String,
        result: JSONObject, toolName: String = "",
        mediaData: ExtractedMedia? = null
    ) {
        when (kind) {
            ProviderKind.OPENAI_RESPONSES -> {
                input.put(JSONObject().apply {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", result.toString())
                })
                // Responses API: send image as a separate user input_image block (audio not supported)
                if (mediaData != null && mediaData.mediaType == "image") {
                    input.put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "input_image")
                                put("image_url", "data:${mediaData.mimeType};base64,${mediaData.base64}")
                            })
                        })
                    })
                }
            }
            ProviderKind.OPENAI_CHAT -> {
                input.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", callId)
                    put("content", result.toString())
                })
                if (mediaData != null && mediaData.mediaType == "image") {
                    // OpenAI-compatible chat providers commonly accept images on user messages,
                    // but not on tool-role messages. Forward the image as a follow-up user turn.
                    input.put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().put("type", "text").put("text",
                                "The previous tool result includes an attached image. Analyze it directly."))
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().put("url", "data:${mediaData.mimeType};base64,${mediaData.base64}"))
                            })
                        })
                    })
                }
            }
            ProviderKind.ANTHROPIC -> {
                val contentBlocks = JSONArray()
                contentBlocks.put(JSONObject().apply {
                    put("type", "text")
                    put("text", result.toString())
                })
                // Anthropic supports images but not audio
                if (mediaData != null && mediaData.mediaType == "image") {
                    contentBlocks.put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64")
                            put("media_type", mediaData.mimeType)
                            put("data", mediaData.base64)
                        })
                    })
                }
                val block = JSONObject().apply {
                    put("type", "tool_result")
                    put("tool_use_id", callId)
                    put("content", contentBlocks)
                }
                // Anthropic requires alternating user/assistant — merge tool_result
                // blocks into a single user message
                val lastIdx = input.length() - 1
                if (lastIdx >= 0) {
                    val last = input.optJSONObject(lastIdx)
                    if (last != null && last.optString("role") == "user") {
                        last.optJSONArray("content")?.put(block)
                        return
                    }
                }
                input.put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().put(block))
                })
            }
            ProviderKind.GOOGLE_GEMINI -> {
                // Gemini: functionResponse part + optional inline_data (supports both image and audio)
                val parts = JSONArray()
                parts.put(JSONObject().apply {
                    put("functionResponse", JSONObject().apply {
                        put("name", toolName)
                        put("response", result)
                    })
                })
                if (mediaData != null) {
                    parts.put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", mediaData.mimeType)
                            put("data", mediaData.base64)
                        })
                    })
                }
                // Gemini: merge into last user message if present, otherwise create new
                val lastIdx = input.length() - 1
                if (lastIdx >= 0) {
                    val last = input.optJSONObject(lastIdx)
                    if (last != null && last.optString("role") == "user") {
                        val existingParts = last.optJSONArray("parts")
                        if (existingParts != null) {
                            for (pi in 0 until parts.length()) {
                                existingParts.put(parts.get(pi))
                            }
                            return
                        }
                    }
                }
                input.put(JSONObject().put("role", "user").put("parts", parts))
            }
            ProviderKind.EMBEDDED -> {
                input.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", callId)
                    put("content", result.toString())
                })
            }
        }
    }

    private fun appendUserNudge(kind: ProviderKind, input: JSONArray, nudge: String): JSONArray {
        when (kind) {
            ProviderKind.OPENAI_RESPONSES -> {
                input.put(JSONObject().put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", nudge))))
            }
            ProviderKind.OPENAI_CHAT -> {
                input.put(JSONObject().put("role", "user").put("content", nudge))
            }
            ProviderKind.ANTHROPIC -> {
                // Merge nudge into last user message (which contains tool_result blocks)
                // to maintain alternating user/assistant message ordering
                val lastIdx = input.length() - 1
                if (lastIdx >= 0) {
                    val last = input.optJSONObject(lastIdx)
                    if (last != null && last.optString("role") == "user") {
                        val content = last.optJSONArray("content")
                        if (content != null) {
                            content.put(JSONObject().put("type", "text").put("text", nudge))
                            return input
                        }
                    }
                }
                input.put(JSONObject().put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", nudge))))
            }
            ProviderKind.GOOGLE_GEMINI -> {
                // Merge nudge into last user message to maintain user/model alternation
                val lastIdx = input.length() - 1
                if (lastIdx >= 0) {
                    val last = input.optJSONObject(lastIdx)
                    if (last != null && last.optString("role") == "user") {
                        val parts = last.optJSONArray("parts")
                        if (parts != null) {
                            parts.put(JSONObject().put("text", nudge))
                            return input
                        }
                    }
                }
                input.put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", nudge))))
            }
            ProviderKind.EMBEDDED -> {
                input.put(JSONObject().put("role", "user").put("content", nudge))
            }
        }
        return input
    }

    private fun renderEmbeddedPrompt(systemPrompt: String, input: JSONArray): String {
        val sb = StringBuilder()
        sb.append("System:\n")
        sb.append(systemPrompt.trim())
        sb.append("\n\nConversation:\n")
        for (i in 0 until input.length()) {
            val msg = input.optJSONObject(i) ?: continue
            val role = msg.optString("role", "user").ifBlank { "user" }
            val content = msg.opt("content")
            val text = when (content) {
                is String -> content
                is JSONArray -> {
                    buildString {
                        for (j in 0 until content.length()) {
                            val part = content.optJSONObject(j) ?: continue
                            val t = part.optString("text", "").trim()
                            if (t.isNotEmpty()) {
                                if (isNotEmpty()) append("\n")
                                append(t)
                            }
                        }
                    }
                }
                else -> msg.optString("text", "")
            }.trim()
            if (text.isEmpty()) continue
            sb.append(role.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() })
            sb.append(": ")
            sb.append(text)
            sb.append("\n\n")
        }
        sb.append("Assistant:")
        return sb.toString().trim()
    }

    private fun shouldUseDirectMediaFallback(kind: ProviderKind, providerUrl: String, model: String): Boolean {
        if (kind != ProviderKind.OPENAI_CHAT) return false
        val url = providerUrl.lowercase(Locale.US)
        val modelName = model.lowercase(Locale.US)
        return url.contains("openrouter.ai") && modelName.contains("qwen")
    }

    private fun runDirectMediaFallback(
        providerKind: ProviderKind,
        providerUrl: String,
        headers: Map<String, String>,
        model: String,
        sessionId: String,
        itemId: String,
        phase: String,
        context: MediaFallbackContext,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): String? {
        if (providerKind != ProviderKind.OPENAI_CHAT || context.mediaData.mediaType != "image") return null

        val body = JSONObject().apply {
            put("model", model)
            put("tool_choice", "none")
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content",
                    "You are a multimodal assistant. Analyze the attached image directly and answer concisely in the user's language. Do not call tools."))
                put(JSONObject().put("role", "user").put("content", JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", context.prompt))
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().put("url",
                            "data:${context.mediaData.mimeType};base64,${context.mediaData.base64}"))
                    })
                }))
            })
        }

        return try {
            recordProviderRequest(
                sessionId = sessionId,
                itemId = itemId,
                providerKind = providerKind,
                providerUrl = providerUrl,
                model = model,
                phase = phase,
                body = body,
            )
            val payload = llmClient.streamingPost(
                providerUrl, headers, body, providerKind,
                connectTimeoutMs, readTimeoutMs,
                interruptCheck = { interruptFlag }
            )
            parseProviderResponse(providerKind, payload).first.firstOrNull { it.isNotBlank() }?.trim()
        } catch (e: Exception) {
            Log.w(TAG, "Direct media fallback failed", e)
            null
        }
    }

    // --- Context Helpers ---

    private fun buildSystemPrompt(config: AgentConfig, supportedMediaTypes: Set<String> = emptySet()): String {
        val base = config.systemPrompt.ifEmpty { AgentConfig.DEFAULT_SYSTEM_PROMPT }
        val policyBlob = userRootPolicyBlob()
        val prompt = if (policyBlob.isNotEmpty()) "$base\n\n$policyBlob" else base

        // Append provider media capability info so the agent knows what's available
        val mediaInfo = if (supportedMediaTypes.isNotEmpty()) {
            val types = supportedMediaTypes.sorted().joinToString(", ")
            val suffix = if ("audio" !in supportedMediaTypes) " Audio analysis requires a model/runtime with audio support." else ""
            "\nCurrent provider supports: $types.$suffix"
        } else {
            "\nCurrent provider does not support multimodal media input."
        }

        // Language instruction MUST come last, after policy docs, so it's not drowned out.
        return "$prompt$mediaInfo\n\nIMPORTANT: Always respond in the same language the user writes in."
    }

    private fun buildJournalBlob(journalCurrent: String, sessionId: String, persistentMemory: String): String {
        val notes = sessionNotes[sessionId] ?: emptyMap()
        return buildString {
            append("Journal (per-session, keep short for context efficiency):\n")
            append(journalCurrent.trim().ifEmpty { "(empty)" })
            append("\n\nSession notes (ephemeral, no permissions required):\n")
            append(JSONObject(notes as Map<*, *>).toString())
            append("\n\nPersistent memory (may be empty; writing may require permission):\n")
            append(persistentMemory.trim().ifEmpty { "(empty)" })
        }
    }

    private fun listDialogue(sessionId: String, limit: Int, rawFetchLimit: Int): List<JSONObject> {
        val raw = storage.listChatMessages(sessionId, rawFetchLimit)
        val filtered = raw.filter { msg ->
            val role = msg["role"] as? String ?: ""
            val text = msg["text"] as? String ?: ""
            role in setOf("user", "assistant") && text.isNotBlank()
        }
        val trimmed = if (filtered.size > limit) filtered.takeLast(limit) else filtered
        return trimmed.map { msg ->
            val metaStr = msg["meta"] as? String
            val meta = try {
                if (!metaStr.isNullOrBlank()) JSONObject(metaStr) else JSONObject()
            } catch (_: Exception) { JSONObject() }
            JSONObject().apply {
                put("role", msg["role"] as? String ?: "")
                put("text", msg["text"] as? String ?: "")
                put("meta", meta)
            }
        }
    }

    private fun getPersistentMemory(): String {
        val memFile = File(userDir, "MEMORY.md")
        return try {
            if (memFile.exists()) memFile.readText(Charsets.UTF_8) else ""
        } catch (_: Exception) { "" }
    }

    private fun userRootPolicyBlob(): String {
        val parts = mutableListOf<String>()

        // System docs (read-only, always current with app version)
        for (name in listOf("AGENTS.md", "TOOLS.md")) {
            val content = readDocFile(File(sysDir, "docs/$name"))
            if (content.isNotEmpty()) {
                parts.add("## System $name\n${content.trimEnd()}\n")
            }
        }

        // User docs (agent-controlled, empty by default)
        for (name in listOf("AGENTS.md", "TOOLS.md")) {
            val content = readDocFile(File(userDir, name))
            if (content.isNotEmpty()) {
                val sha = sha256Short(content)
                parts.add("## User $name (sha256=$sha)\n$content\n")
            }
        }
        val notices = readDocFile(File(userDir, "AGENT_NOTICES.md"))
        if (notices.isNotEmpty()) {
            parts.add("## AGENT_NOTICES.md\n$notices\n")
        }

        return parts.joinToString("\n").trim()
    }

    private fun readDocFile(file: File): String {
        return try {
            if (file.exists() && file.isFile) file.readText(Charsets.UTF_8).trim() else ""
        } catch (_: Exception) { "" }
    }

    private fun sha256Short(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }

    // --- Message Recording ---

    /**
     * Sanitize assistant text before recording: strip echoed system instructions
     * and internal thinking blocks that models sometimes leak.
     */
    private fun sanitizeAssistantText(raw: String): String {
        // Strip tool activity summaries (cross-turn memory, not for UI display)
        var text = raw.replace(Regex("\n---\n\\[Tool Activity:[\\s\\S]*?---$"), "").trim()
        // Strip <thought>...</thought> blocks (internal reasoning)
        text = text.replace(Regex("<thought>[\\s\\S]*?</thought>"), "").trim()
        // Strip leading lines that are echoed system-prompt instructions.
        // Pattern: lines starting with imperative directives (NEVER, Do NOT, Do not,
        // Always, You MUST, You must, Only, Prefer, Keep responses, Use ...).
        val instructionLine = Regex(
            "^\\s*(?:" +
                "(?:NEVER|ALWAYS|IMPORTANT)\\b" +
                "|(?:Do (?:NOT|not))\\b" +
                "|(?:You (?:MUST|must|ARE|are|MAY|should))\\b" +
                "|(?:Only |Prefer |Keep responses|Use the available|Use device_api)" +
                "|(?:If (?:a |the )?(?:tool|capability|request|user))" +
                "|(?:Permission policy|Tool policy)" +
                ").*",
            RegexOption.MULTILINE
        )
        // Strip matching lines from the beginning of the text.
        // Stop once we hit a non-matching, non-blank line.
        val lines = text.lines().toMutableList()
        while (lines.isNotEmpty()) {
            val line = lines.first().trim()
            if (line.isEmpty() || instructionLine.matches(line)) {
                lines.removeFirst()
            } else {
                break
            }
        }
        return lines.joinToString("\n").trim()
    }

    private fun recordMessage(role: String, text: String, meta: JSONObject = JSONObject()) {
        val displayText = if (role == "assistant") sanitizeAssistantText(text) else text
        if (role == "assistant" && displayText.isBlank()) return // nothing useful to show
        if (!meta.has("actor") || meta.optString("actor", "").isBlank()) {
            meta.put("actor", when (role) {
                "user" -> "human"
                "assistant" -> "agent"
                "tool" -> "tool"
                else -> "system"
            })
        }
        val entry = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("role", role)
            put("text", displayText)
            put("meta", meta)
        }
        synchronized(lock) {
            messages.addLast(entry)
            if (messages.size > 200) messages.pollFirst()
        }
        try {
            val sid = meta.optString("session_id", "default").ifEmpty { "default" }
            storage.addChatMessage(sid, role, displayText, meta.toString())
        } catch (_: Exception) {}

        // Publish to event bus
        onEvent?.invoke("brain_message", entry)
    }

    // --- Helpers ---

    private fun sessionIdForItem(item: JSONObject): String {
        val meta = item.optJSONObject("meta")
        return meta?.optString("session_id", "")?.trim()?.ifEmpty { "default" } ?: "default"
    }

    private fun checkInterrupt() {
        if (interruptFlag) throw InterruptedException("Agent interrupted")
    }

    private fun friendlyToolLabel(name: String, args: JSONObject): String {
        return when (name) {
            "device_api" -> "Device: ${args.optString("action", name)}"
            "read_file" -> "Reading ${args.optString("path", "file")}"
            "write_file" -> "Writing ${args.optString("path", "file")}"
            "list_dir" -> "Listing ${args.optString("path", ".")}"
            "run_python" -> "Running python"
            "run_pip" -> "Running pip"
            "run_curl" -> "Running curl"
            "web_search" -> "Searching: ${args.optString("query", "").take(40)}"
            "cloud_request" -> "Cloud request"
            "memory_get" -> "Reading memory"
            "memory_set" -> "Writing memory"
            "sleep" -> "Sleeping"
            "analyze_image" -> "Analyzing image ${args.optString("path", "").substringAfterLast('/')}"
            "analyze_audio" -> "Analyzing audio ${args.optString("path", "").substringAfterLast('/')}"
            else -> name
        }
    }

    /** Log Gemini request structure for debugging (without dumping full base64 data). */
    private fun recordProviderRequest(
        sessionId: String,
        itemId: String,
        providerKind: ProviderKind,
        providerUrl: String,
        model: String,
        phase: String,
        body: JSONObject,
    ) {
        try {
            storage.addLlmRequest(
                sessionId = sessionId,
                itemId = itemId,
                providerKind = providerKind.name,
                providerUrl = providerUrl,
                model = model,
                phase = phase,
                requestBody = body.toString(2),
            )
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to record provider request", ex)
        }
    }

    private fun logGeminiRequestStructure(body: JSONObject) {
        try {
            val contents = body.optJSONArray("contents") ?: return
            val sb = StringBuilder("Gemini request: ${contents.length()} content(s)\n")
            for (i in 0 until contents.length()) {
                val content = contents.optJSONObject(i) ?: continue
                val role = content.optString("role", "?")
                val parts = content.optJSONArray("parts")
                val partTypes = mutableListOf<String>()
                if (parts != null) {
                    for (pi in 0 until parts.length()) {
                        val part = parts.optJSONObject(pi) ?: continue
                        when {
                            part.has("text") -> partTypes.add("text(${part.optString("text", "").take(30)}...)")
                            part.has("inline_data") -> {
                                val id = part.optJSONObject("inline_data")
                                partTypes.add("inline_data(${id?.optString("mime_type", "?")})")
                            }
                            part.has("functionCall") -> partTypes.add("functionCall(${part.optJSONObject("functionCall")?.optString("name", "?")})")
                            part.has("functionResponse") -> partTypes.add("functionResponse(${part.optJSONObject("functionResponse")?.optString("name", "?")})")
                            else -> partTypes.add("unknown")
                        }
                    }
                }
                sb.append("  [$i] role=$role parts=[${partTypes.joinToString(", ")}]\n")
            }
            Log.d(TAG, sb.toString().trimEnd())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log Gemini request structure", e)
        }
    }

    private fun buildToolResultSnippet(name: String, args: JSONObject, result: JSONObject): String {
        val status = result.optString("status", "")
        return when (name) {
            "read_file" -> {
                val path = args.optString("path", "")
                val size = result.optInt("size", 0)
                "$path ($size bytes)"
            }
            "write_file" -> {
                val path = args.optString("path", "")
                val size = result.optInt("size", 0)
                "wrote $path ($size bytes)"
            }
            "list_dir" -> {
                val path = args.optString("path", ".")
                val items = result.optJSONArray("items")
                val count = items?.length() ?: 0
                "$path ($count entries)"
            }
            "run_js" -> {
                val res = result.optString("result", "").take(80)
                "result=$res"
            }
            "run_shell", "run_python" -> {
                val code = result.optInt("exit_code", -1)
                "exit=$code"
            }
            "device_api" -> {
                val action = args.optString("action", "")
                val httpStatus = result.optInt("http_status", 0)
                if (httpStatus > 0) "$action http=$httpStatus" else "$action $status"
            }
            "web_search" -> {
                val query = args.optString("query", "").take(40)
                "query=\"$query\""
            }
            "delete_path" -> {
                val path = args.optString("path", "")
                "deleted $path"
            }
            "move_path" -> {
                val src = args.optString("src", "")
                val dst = args.optString("dst", "")
                "$src -> $dst"
            }
            else -> {
                val error = result.optString("error", "")
                if (error.isNotEmpty()) "error=$error" else status
            }
        }
    }

    private fun buildToolHistorySummary(history: List<ToolRoundRecord>): String {
        if (history.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n---\n[Tool Activity: ${history.size} call(s)]\n")

        // Tool usage counts
        val counts = linkedMapOf<String, Int>()
        for (r in history) counts[r.name] = (counts[r.name] ?: 0) + 1
        sb.append(counts.entries.joinToString(", ") { "${it.key} x${it.value}" })
        sb.append("\nOutcomes:\n")

        // Group outcomes by tool name
        val grouped = linkedMapOf<String, MutableList<ToolRoundRecord>>()
        for (r in history) grouped.getOrPut(r.name) { mutableListOf() }.add(r)

        for ((toolName, records) in grouped) {
            val snippets = records.map { it.resultSnippet }.filter { it.isNotBlank() }
            if (snippets.isNotEmpty()) {
                sb.append("  $toolName: ${snippets.joinToString("; ").take(200)}\n")
            } else {
                sb.append("  $toolName: ${records.first().status}\n")
            }
        }
        sb.append("---")

        // Cap at ~2000 chars
        val result = sb.toString()
        return if (result.length > 2000) result.take(1980) + "\n---" else result
    }

    companion object {
        private const val TAG = "AgentRuntime"
        private const val REFERENT_PACK_QUALITY_THRESHOLD = 0.55
        private const val REFERENT_RESOLUTION_CONFIDENCE_THRESHOLD = 0.35
        private const val REFERENT_AMBIGUITY_GAP_THRESHOLD = 8
    }
}
