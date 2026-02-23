package jp.espresso3389.methings.service.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

data class ExtractedMedia(
    val base64: String,
    val mimeType: String,
    val mediaType: String,  // "image" or "audio"
)

class AgentRuntime(
    private val userDir: File,
    private val sysDir: File,
    private val storage: AgentStorage,
    private val journalStore: JournalStore,
    private val toolExecutor: ToolExecutor,
    private val llmClient: LlmClient,
    private val configManager: AgentConfigManager,
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
                        processWithResponsesTools(originalItem)
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
        val visibleText = item.optString("display_text", "").ifBlank { text }
        recordMessage("user", visibleText, JSONObject().apply {
            put("item_id", item.optString("id"))
            put("session_id", sessionId)
            put("actor", item.optJSONObject("meta")?.optString("actor", "human") ?: "human")
        })

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

        if (model.isEmpty() || providerUrl.isEmpty() || apiKey.isEmpty()) {
            recordMessage("assistant",
                "Brain is not configured yet. Configure it in Settings -> Brain.",
                JSONObject().put("item_id", item.optString("id")).put("session_id", sessionId))
            return
        }

        val providerKind = llmClient.detectProviderKind(providerUrl, vendor)
        Log.i(TAG, "Provider: kind=$providerKind, vendor=$vendor, url=$providerUrl, model=$model")
        val headers = llmClient.buildHeaders(providerKind, apiKey)

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
        }

        // Determine which media types the provider supports natively
        val supportedMediaTypes: Set<String> = when (providerKind) {
            ProviderKind.GOOGLE_GEMINI -> setOf("image", "audio")
            ProviderKind.ANTHROPIC, ProviderKind.OPENAI_RESPONSES -> setOf("image")
            ProviderKind.OPENAI_CHAT -> if (!providerUrl.contains("generativelanguage.googleapis.com")) setOf("image") else emptySet()
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

        var pendingInput = buildInitialInput(providerKind, filteredDialogue, journalBlob, curText, item, supportedMediaTypes)
        var forcedRounds = 0
        var roundsUsed = 0
        var toolCallsRequested = 0
        var toolCallsExecuted = 0
        val toolUsageCounts = linkedMapOf<String, Int>()
        var lastToolName = ""
        var lastToolStatus = ""
        var lastToolError = ""

        for (roundIdx in 0 until maxRounds) {
            checkInterrupt()
            roundsUsed = roundIdx + 1
            emitLog("brain_status", JSONObject().apply {
                put("item_id", item.optString("id"))
                put("session_id", sessionId)
                put("status", "thinking")
                put("label", "Thinking\u2026")
            })

            val body = buildRequestBody(providerKind, model, tools, pendingInput, systemPrompt, toolRequiredUnsatisfied)

            // Debug: log request structure (content types, not full data) for troubleshooting
            if (providerKind == ProviderKind.GOOGLE_GEMINI) {
                logGeminiRequestStructure(body)
            }

            // Make API call with retries
            var payload: JSONObject? = null
            var lastEx: Exception? = null
            for (attempt in 0..maxRetries) {
                try {
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
                    // Retry without tool_choice if that was set
                    if (body.has("tool_choice")) {
                        try {
                            val body2 = JSONObject(body.toString())
                            body2.remove("tool_choice")
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

            // Parse response based on provider kind
            val parseResult = parseProviderResponse(providerKind, payload)
            val messageTexts = parseResult.first
            val calls = parseResult.second
            toolCallsRequested += calls.length()

            if (calls.length() == 0) {
                if (messageTexts.isEmpty()) {
                    pendingInput = appendUserNudge(providerKind, pendingInput,
                        "You returned an empty response (no text, no tool calls). You MUST either respond with text or call tools.")
                    continue
                }
                if (toolRequiredUnsatisfied && forcedRounds < 2) {
                    forcedRounds++
                    pendingInput = appendUserNudge(providerKind, pendingInput,
                        "Tool policy is REQUIRED for this request. " +
                        "You MUST call one or more tools to perform the action(s) — do NOT " +
                        "describe or narrate tool usage, actually call the tools. " +
                        "Then summarize after tool outputs are provided.")
                    continue
                }
                // Final assistant message
                for (text in messageTexts) {
                    recordMessage("assistant", text, JSONObject().apply {
                        put("item_id", item.optString("id"))
                        put("session_id", sessionId)
                    })
                    emitLog("brain_response", JSONObject().put("item_id", item.optString("id")).put("text", text.take(300)))
                }
                return
            }

            toolRequiredUnsatisfied = false

            // Record assistant text
            for (text in messageTexts) {
                recordMessage("assistant", text, JSONObject().apply {
                    put("item_id", item.optString("id"))
                    put("session_id", sessionId)
                })
                emitLog("brain_response", JSONObject().put("item_id", item.optString("id")).put("text", text.take(300)))
            }

            // Execute tool calls
            // Responses API is stateful — reset and echo back only new items.
            // Chat Completions and Anthropic are stateless — keep accumulating
            // the full conversation history so multi-round tool loops retain context.
            if (providerKind == ProviderKind.OPENAI_RESPONSES) {
                pendingInput = JSONArray()
                for (ci in 0 until lastResponsesOutputItems.length()) {
                    pendingInput.put(lastResponsesOutputItems.get(ci))
                }
            }

            // Chat Completions: include the assistant message (with tool_calls) before tool results
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

            // Anthropic: echo back assistant message with tool_use content blocks
            if (providerKind == ProviderKind.ANTHROPIC) {
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

            // Gemini: echo back model message with functionCall parts
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
                        // Gemini 3.x: echo back thoughtSignature at the part level
                        val sig = c.optString("thoughtSignature", "")
                        if (sig.isNotEmpty()) put("thoughtSignature", sig)
                    })
                }
                pendingInput.put(JSONObject().put("role", "model").put("parts", parts))
            }

            for (i in 0 until calls.length().coerceAtMost(maxActions)) {
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

                // Check for permission_required
                val resultStatus = result.optString("status", "")
                if (resultStatus in setOf("permission_required", "permission_expired")) {
                    val req = result.optJSONObject("request") ?: JSONObject()
                    val pid = req.optString("id", "").trim()
                    if (pid.isNotEmpty()) {
                        awaitingPermissions[pid] = JSONObject().apply {
                            put("permission_id", pid)
                            put("status", "pending")
                            put("tool", name)
                            put("session_id", sessionId)
                            put("item", item)
                            put("created_at", System.currentTimeMillis())
                        }
                    }
                    emitLog("brain_response", JSONObject().put("item_id", item.optString("id")).put("text", "permission_required"))
                    return
                }

                // Check for policy errors
                if (resultStatus == "error") {
                    val err = result.optString("error", "")
                    if (err in setOf("command_not_allowed", "path_not_allowed", "invalid_path")) {
                        recordMessage("assistant",
                            "Tool '$name' failed with $err. This is blocked by local policy/sandbox.",
                            JSONObject().put("item_id", item.optString("id")).put("session_id", sessionId))
                        return
                    }
                }

                // Extract media from tool result BEFORE truncation (only if provider supports the media type)
                val mediaData = extractMediaFromToolResult(result, supportedMediaTypes)
                if (mediaData != null) {
                    Log.i(TAG, "Tool '$name' produced ${mediaData.mediaType}: ${mediaData.mimeType}, ${mediaData.base64.length} chars b64, sending as multimodal")
                } else if (supportedMediaTypes.isNotEmpty()) {
                    Log.d(TAG, "Tool '$name' result has no extractable media (supported=$supportedMediaTypes)")
                }

                val truncated = ToolExecutor.truncateToolOutput(result,
                    config.intWithProfile("max_tool_output_chars", 12000, 2000, 100000),
                    config.intWithProfile("max_tool_output_list_items", 80, 10, 500))

                // Strip redundant base64 data from text when media is sent separately
                val textResult = if (mediaData != null) {
                    val stripped = ToolExecutor.stripMediaData(truncated)
                    // Inject a hint so the agent knows the media is visible right now
                    stripped.put("_media_hint",
                        "A ${mediaData.mediaType} is attached to this tool result. " +
                        "You can see/hear it directly — describe or analyze it now. " +
                        "Do NOT say you cannot analyze it or call cloud_request.")
                    stripped
                } else truncated

                appendToolResult(providerKind, pendingInput, callId, textResult, name, mediaData)
            }

            // Nudge to stop
            pendingInput = appendUserNudge(providerKind, pendingInput,
                "Tool outputs have been provided. If the user's request is fully satisfied, respond with the final answer and STOP. " +
                "If there are still outstanding actions needed, call additional tools now.")
        }

        // Exhausted max rounds — force finalization
        try {
            val finalPrompt = (systemPrompt + "\n\nFINALIZATION:\n- Do NOT call any tools.\n- Produce the best possible final answer now.").trim()
            val finalBody = buildRequestBody(providerKind, model, JSONArray(), pendingInput, finalPrompt, false)
            finalBody.put("tool_choice", "none")

            val finalPayload = try {
                llmClient.streamingPost(effectiveProviderUrl, headers, finalBody, providerKind, connectTimeoutMs, readTimeoutMs,
                    interruptCheck = { interruptFlag })
            } catch (_: Exception) {
                val body2 = JSONObject(finalBody.toString())
                body2.remove("tool_choice")
                llmClient.streamingPost(effectiveProviderUrl, headers, body2, providerKind, connectTimeoutMs, readTimeoutMs,
                    interruptCheck = { interruptFlag })
            }

            val finalResult = parseProviderResponse(providerKind, finalPayload)
            for (text in finalResult.first) {
                recordMessage("assistant", text, JSONObject().apply {
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
        recordMessage("assistant",
            "Reached maximum tool rounds ($maxRounds).\n" +
                "Progress summary:\n" +
                "- Rounds used: $roundsUsed/$maxRounds\n" +
                "- Tool calls requested by model: $toolCallsRequested\n" +
                "- Tool calls executed: $toolCallsExecuted\n" +
                "- Tools used: $toolsUsedSummary\n" +
                "- Last tool result: $lastToolSummary\n" +
                "Please continue with the next concrete sub-step.",
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
                put("model", model)
                put("max_tokens", 8192)
                put("system", systemPrompt)
                put("tools", tools)
                put("messages", input)
                if (requireTool) put("tool_choice", JSONObject().put("type", "any"))
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
        }
    }

    private fun parseProviderResponse(kind: ProviderKind, payload: JSONObject): Pair<List<String>, JSONArray> {
        return when (kind) {
            ProviderKind.OPENAI_RESPONSES -> parseOpenAiResponsesResponse(payload)
            ProviderKind.OPENAI_CHAT -> parseOpenAiChatResponse(payload)
            ProviderKind.ANTHROPIC -> parseAnthropicResponse(payload)
            ProviderKind.GOOGLE_GEMINI -> parseGeminiResponse(payload)
        }
    }

    /** All non-message output items from the last Responses API call (reasoning + function_call).
     *  Must be echoed back in the input when sending function_call_output items. */
    private var lastResponsesOutputItems = JSONArray()

    private fun parseOpenAiResponsesResponse(payload: JSONObject): Pair<List<String>, JSONArray> {
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
                        if (part.optString("type") == "output_text") {
                            val t = part.optString("text", "").trim()
                            if (t.isNotEmpty()) parts.add(t)
                        }
                    }
                    if (parts.isNotEmpty()) messageTexts.add(parts.joinToString("\n"))
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
                // Check images
                if ("image" in supportedTypes && MediaEncoder.isImagePath(path)) {
                    val file = File(userDir, path)
                    Log.d(TAG, "extractMedia: found image path='$path', exists=${file.exists()}")
                    if (file.exists()) {
                        val maxDim = if (toolExecutor.imageResizeEnabled) toolExecutor.imageMaxDimPx else Int.MAX_VALUE
                        val encoded = MediaEncoder.encodeImage(file, maxDim, toolExecutor.imageJpegQuality)
                        if (encoded != null) {
                            Log.i(TAG, "extractMedia: encoded image from '$path' (${encoded.base64.length} chars base64)")
                            return ExtractedMedia(encoded.base64, encoded.mimeType, "image")
                        }
                    }
                }
                // Check audio
                if ("audio" in supportedTypes && MediaEncoder.isAudioPath(path)) {
                    val file = File(userDir, path)
                    Log.d(TAG, "extractMedia: found audio path='$path', exists=${file.exists()}")
                    if (file.exists()) {
                        val encoded = MediaEncoder.encodeAudio(file)
                        if (encoded != null) {
                            Log.i(TAG, "extractMedia: encoded audio from '$path' (${encoded.base64.length} chars base64)")
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
                if (p.isNotEmpty() && MediaEncoder.isMediaPath(p)) {
                    paths.add(p)
                    continue  // Remove this line from text sent to LLM
                }
            }
            cleanedLines.add(line)
        }
        return Pair(cleanedLines.joinToString("\n"), paths)
    }

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
        }
    }

    private fun buildInitialInput(
        kind: ProviderKind, dialogue: List<JSONObject>,
        journalBlob: String, curText: String, item: JSONObject,
        supportedMedia: Set<String> = emptySet()
    ): JSONArray {
        val input = JSONArray()

        // Extract media from current user message (only if provider supports it)
        val (cleanedText, mediaPaths) = extractUserMediaPaths(curText)
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
        val finalUserText = "$journalBlob\n\n$cleanedText"

        when (kind) {
            ProviderKind.OPENAI_RESPONSES -> {
                for (msg in dialogue) {
                    val role = msg.optString("role")
                    val text = msg.optString("text", "")
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
                val content = buildMultimodalUserContent(kind, cleanedText, userMedia)
                input.put(JSONObject().put("role", "user").put("content", content))
            }
            ProviderKind.OPENAI_CHAT -> {
                for (msg in dialogue) {
                    val role = msg.optString("role")
                    val text = msg.optString("text", "")
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
                    val text = msg.optString("text", "")
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
                    val text = msg.optString("text", "")
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
                if (mediaData != null && mediaData.mediaType == "image") {
                    // Multimodal tool result: content is an array of text + image_url (audio not supported)
                    val contentArr = JSONArray()
                    contentArr.put(JSONObject().put("type", "text").put("text", result.toString()))
                    contentArr.put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().put("url", "data:${mediaData.mimeType};base64,${mediaData.base64}"))
                    })
                    input.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", callId)
                        put("content", contentArr)
                    })
                } else {
                    input.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", callId)
                        put("content", result.toString())
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
        }
        return input
    }

    // --- Context Helpers ---

    private fun buildSystemPrompt(config: AgentConfig, supportedMediaTypes: Set<String> = emptySet()): String {
        val base = config.systemPrompt.ifEmpty { AgentConfig.DEFAULT_SYSTEM_PROMPT }
        val policyBlob = userRootPolicyBlob()
        val prompt = if (policyBlob.isNotEmpty()) "$base\n\n$policyBlob" else base

        // Append provider media capability info so the agent knows what's available
        val mediaInfo = if (supportedMediaTypes.isNotEmpty()) {
            val types = supportedMediaTypes.sorted().joinToString(", ")
            val suffix = if ("audio" !in supportedMediaTypes) " Audio analysis requires switching to a Gemini model." else ""
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

        // System docs (read-only)
        for (name in listOf("AGENTS.md", "TOOLS.md")) {
            val content = readDocFile(File(sysDir, "docs/$name"))
            if (content.isNotEmpty()) {
                parts.add("## System $name\n${content.trimEnd()}\n")
            }
        }

        // User docs (editable)
        var hasUser = false
        for (name in listOf("AGENTS.md", "TOOLS.md")) {
            val content = readDocFile(File(userDir, name))
            if (content.isNotEmpty()) {
                if (!hasUser) {
                    parts.add("\nUser-root docs (auto-injected; reloaded if changed on disk).\n")
                    hasUser = true
                }
                val sha = sha256Short(content)
                parts.add("## User $name (sha256=$sha)\n$content\n")
            }
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
        // Strip <thought>...</thought> blocks (internal reasoning)
        var text = raw.replace(Regex("<thought>[\\s\\S]*?</thought>"), "").trim()
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
                "|(?:Permission|Tool policy)" +
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
            storage.addChatMessage(sid, role, text, meta.toString())
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

    companion object {
        private const val TAG = "AgentRuntime"
    }
}
