package jp.espresso3389.methings.service.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

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
                recordMessage("assistant", "Permission auto-approved.",
                    JSONObject().put("actor", "system"))
            }
            else -> {
                // Generic event — record summary
                val summary = "Event: $name"
                recordMessage("tool", summary, JSONObject().put("actor", "system").put("event", name))
                // If run_agent is requested, escalate to a chat-like processing
                val meta = item.optJSONObject("meta") ?: JSONObject()
                if (meta.optBoolean("run_agent", false)) {
                    val chatItem = JSONObject().apply {
                        put("id", item.optString("id"))
                        put("kind", "chat")
                        put("text", "System event: $name. ${payload.optString("summary", "")}")
                        put("meta", JSONObject().put("session_id", sessionIdForItem(item)).put("actor", "system"))
                        put("created_at", System.currentTimeMillis())
                    }
                    processChat(chatItem)
                }
            }
        }
    }

    private fun processChat(item: JSONObject) {
        val text = item.optString("text", "")
        val sessionId = sessionIdForItem(item)

        // Record user message
        recordMessage("user", text, JSONObject().apply {
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
        val headers = llmClient.buildHeaders(providerKind, apiKey)

        val dialogueLimit = config.intWithProfile("dialogue_window_user_assistant", 40, 10, 120)
        val dialogueRawLimit = config.intWithProfile("dialogue_raw_fetch_limit", 320, 40, 2000)
        val maxRounds = config.intWithProfile("max_tool_rounds", 18, 1, 24)
        val maxActions = config.intWithProfile("max_actions", 10, 1, 12)
        val connectTimeoutMs = (config.providerConnectTimeoutS.coerceIn(1, 60) * 1000)
        val readTimeoutMs = (config.providerReadTimeoutS.coerceIn(5, 600) * 1000)
        val maxRetries = config.providerMaxRetries.coerceIn(0, 3)
        val toolPolicy = config.toolPolicy
        val requireTool = toolPolicy == "required"
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
            ProviderKind.OPENAI_COMPAT -> ToolDefinitions.responsesTools(ToolDefinitions.deviceApiActionNames())
            ProviderKind.ANTHROPIC -> ToolDefinitions.anthropicTools(ToolDefinitions.deviceApiActionNames())
        }

        val systemPrompt = buildSystemPrompt(config)
        val journalBlob = buildJournalBlob(journalCurrent, sessionId, persistentMemory)

        var pendingInput = buildInitialInput(providerKind, filteredDialogue, journalBlob, curText, item)
        var forcedRounds = 0

        for (roundIdx in 0 until maxRounds) {
            checkInterrupt()
            emitLog("brain_status", JSONObject().apply {
                put("item_id", item.optString("id"))
                put("session_id", sessionId)
                put("status", "thinking")
                put("label", "Thinking\u2026")
            })

            val body = buildRequestBody(providerKind, model, tools, pendingInput, systemPrompt, toolRequiredUnsatisfied)

            // Make API call with retries
            var payload: JSONObject? = null
            var lastEx: Exception? = null
            for (attempt in 0..maxRetries) {
                try {
                    payload = llmClient.streamingPost(
                        providerUrl, headers, body, providerKind,
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
                                providerUrl, headers, body2, providerKind,
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

            if (calls.length() == 0) {
                if (messageTexts.isEmpty()) {
                    pendingInput = appendUserNudge(providerKind, pendingInput,
                        "You returned an empty response (no text, no tool calls). You MUST either respond with text or call tools.")
                    continue
                }
                if (toolRequiredUnsatisfied && forcedRounds < 1) {
                    forcedRounds++
                    pendingInput = buildToolRequiredNudge(providerKind)
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
            pendingInput = JSONArray()
            for (i in 0 until calls.length().coerceAtMost(maxActions)) {
                checkInterrupt()
                val call = calls.getJSONObject(i)
                val name = call.optString("name", "")
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

                val truncated = ToolExecutor.truncateToolOutput(result,
                    config.intWithProfile("max_tool_output_chars", 12000, 2000, 100000),
                    config.intWithProfile("max_tool_output_list_items", 80, 10, 500))

                appendToolResult(providerKind, pendingInput, callId, truncated)
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
                llmClient.streamingPost(providerUrl, headers, finalBody, providerKind, connectTimeoutMs, readTimeoutMs,
                    interruptCheck = { interruptFlag })
            } catch (_: Exception) {
                val body2 = JSONObject(finalBody.toString())
                body2.remove("tool_choice")
                llmClient.streamingPost(providerUrl, headers, body2, providerKind, connectTimeoutMs, readTimeoutMs,
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

        recordMessage("assistant",
            "Reached maximum tool rounds ($maxRounds). Try breaking the task into smaller steps.",
            JSONObject().put("item_id", item.optString("id")).put("session_id", sessionId))
    }

    // --- Provider-Specific Request/Response Building ---

    private fun buildRequestBody(
        kind: ProviderKind, model: String, tools: JSONArray,
        input: Any, systemPrompt: String, requireTool: Boolean
    ): JSONObject {
        return when (kind) {
            ProviderKind.OPENAI_COMPAT -> JSONObject().apply {
                put("model", model)
                put("tools", tools)
                put("input", input)
                put("instructions", systemPrompt)
                put("truncation", "auto")
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
        }
    }

    private fun parseProviderResponse(kind: ProviderKind, payload: JSONObject): Pair<List<String>, JSONArray> {
        return when (kind) {
            ProviderKind.OPENAI_COMPAT -> parseOpenAiResponse(payload)
            ProviderKind.ANTHROPIC -> parseAnthropicResponse(payload)
        }
    }

    private fun parseOpenAiResponse(payload: JSONObject): Pair<List<String>, JSONArray> {
        val outputItems = payload.optJSONArray("output") ?: JSONArray()
        val messageTexts = mutableListOf<String>()
        val calls = JSONArray()

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
                "function_call" -> calls.put(out)
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

    private fun buildInitialInput(
        kind: ProviderKind, dialogue: List<JSONObject>,
        journalBlob: String, curText: String, item: JSONObject
    ): JSONArray {
        val input = JSONArray()
        when (kind) {
            ProviderKind.OPENAI_COMPAT -> {
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
                // Current user message
                input.put(JSONObject().put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", curText))))
            }
            ProviderKind.ANTHROPIC -> {
                for (msg in dialogue) {
                    val role = msg.optString("role")
                    val text = msg.optString("text", "")
                    if (role in setOf("user", "assistant") && text.isNotBlank()) {
                        input.put(JSONObject().put("role", role).put("content", text))
                    }
                }
                input.put(JSONObject().put("role", "user").put("content", "$journalBlob\n\n$curText"))
            }
        }
        return input
    }

    private fun appendToolResult(kind: ProviderKind, input: JSONArray, callId: String, result: JSONObject) {
        when (kind) {
            ProviderKind.OPENAI_COMPAT -> {
                input.put(JSONObject().apply {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", result.toString())
                })
            }
            ProviderKind.ANTHROPIC -> {
                input.put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().put(JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", callId)
                        put("content", result.toString())
                    }))
                })
            }
        }
    }

    private fun appendUserNudge(kind: ProviderKind, input: JSONArray, nudge: String): JSONArray {
        when (kind) {
            ProviderKind.OPENAI_COMPAT -> {
                input.put(JSONObject().put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", nudge))))
            }
            ProviderKind.ANTHROPIC -> {
                input.put(JSONObject().put("role", "user").put("content", nudge))
            }
        }
        return input
    }

    private fun buildToolRequiredNudge(kind: ProviderKind): JSONArray {
        val nudge = "Tool policy is REQUIRED for this request. " +
            "You MUST call one or more tools to perform the action(s), " +
            "then summarize after tool outputs are provided."
        val input = JSONArray()
        when (kind) {
            ProviderKind.OPENAI_COMPAT -> {
                input.put(JSONObject().put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", nudge))))
            }
            ProviderKind.ANTHROPIC -> {
                input.put(JSONObject().put("role", "user").put("content", nudge))
            }
        }
        return input
    }

    // --- Context Helpers ---

    private fun buildSystemPrompt(config: AgentConfig): String {
        val base = config.systemPrompt.ifEmpty { AgentConfig.DEFAULT_SYSTEM_PROMPT }
        val policyBlob = userRootPolicyBlob()
        return if (policyBlob.isNotEmpty()) "$base\n\n$policyBlob" else base
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

    private fun recordMessage(role: String, text: String, meta: JSONObject = JSONObject()) {
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
            put("text", text)
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
            "run_python" -> "Running Python"
            "run_pip" -> "Running pip"
            "run_curl" -> "Running curl"
            "web_search" -> "Searching: ${args.optString("query", "").take(40)}"
            "cloud_request" -> "Cloud request"
            "memory_get" -> "Reading memory"
            "memory_set" -> "Writing memory"
            "sleep" -> "Sleeping"
            else -> name
        }
    }

    companion object {
        private const val TAG = "AgentRuntime"
    }
}
