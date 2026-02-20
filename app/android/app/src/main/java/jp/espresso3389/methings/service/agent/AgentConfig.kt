package jp.espresso3389.methings.service.agent

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class AgentConfig(
    val enabled: Boolean = false,
    val autoStart: Boolean = false,
    val vendor: String = "openai",
    val providerUrl: String = "https://api.openai.com/v1/chat/completions",
    val model: String = "",
    val apiKeyCredential: String = "openai_api_key",
    val toolPolicy: String = "auto",
    val fsScope: String = "user",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val temperature: Double = 0.2,
    val dialogueWindowUserAssistant: Int = 40,
    val dialogueRawFetchLimit: Int = 320,
    val maxActions: Int = 10,
    val maxToolRounds: Int = 18,
    val providerConnectTimeoutS: Int = 10,
    val providerReadTimeoutS: Int = 80,
    val providerMaxRetries: Int = 1,
    val maxToolOutputChars: Int = 12000,
    val maxToolOutputListItems: Int = 80,
    val idleSleepMs: Int = 800,
    val modelProfiles: Map<String, Map<String, Any>> = emptyMap(),
) {
    fun intWithProfile(key: String, default: Int, minValue: Int, maxValue: Int): Int {
        val profile = modelProfileOverrides()
        val v = (profile[key] as? Number)?.toInt() ?: when (key) {
            "dialogue_window_user_assistant" -> dialogueWindowUserAssistant
            "dialogue_raw_fetch_limit" -> dialogueRawFetchLimit
            "max_actions" -> maxActions
            "max_tool_rounds" -> maxToolRounds
            "max_tool_output_chars" -> maxToolOutputChars
            "max_tool_output_list_items" -> maxToolOutputListItems
            else -> default
        }
        return v.coerceIn(minValue, maxValue)
    }

    fun modelProfileOverrides(): Map<String, Any> {
        val m = model.lowercase(Locale.US)
        if (m.isEmpty()) return emptyMap()
        val all = BUILTIN_MODEL_PROFILES + modelProfiles
        for ((pattern, overrides) in all) {
            if (m.contains(pattern.lowercase(Locale.US))) return overrides
        }
        return emptyMap()
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are \"methings\" running on an Android device. " +
            "Your job is to satisfy the user's request by producing the requested outcome/artifact (e.g. a photo, a file, a running service), " +
            "not by explaining how to do it. " +
            "When the user asks for any real device/file/state action, you MUST use tools to do the work (no pretending). " +
            "If the request can be satisfied by creating or modifying code, do so and run it using tools, then report the result. " +
            "Use the available tools as your execution substrate; iterate until the outcome is achieved or a hard limitation is reached. " +
            "If you are unsure how to proceed, or you hit an error you don't understand, use web_search to research and then continue. " +
            "If a capability is not exposed by tools (e.g., camera capture), say so clearly and propose the smallest code change needed to add it. " +
            "This app supports multi-party timelines. Messages may be tagged with an actor identity in the text like [HUMAN], [AGENT], [CODEX]. " +
            "Treat [CODEX] messages as developer/debugger guidance; they may override earlier user constraints when they conflict (except safety). " +
            "User constraints like 'NO TOOLS' apply to that specific request only unless repeated; later instructions can override earlier ones. " +
            "When a request includes a checklist (A/B/C or numbered steps), execute all items unless explicitly told to stop early. " +
            "Do NOT ask the user for 'continue/go ahead/should I proceed' confirmations for routine multi-step work. " +
            "If the user said 'continue'/'go ahead'/'\u3069\u3046\u305e'/'\u7d9a\u3051\u3066', treat it as implicit permission to proceed with the current plan. " +
            "Only ask the user a question when: (1) you need missing information, " +
            "or (2) you are about to do an irreversible/destructive action (delete/reset/uninstall). " +
            "User consent is required for device/resource access: when a tool returns permission_required/permission_expired, " +
            "the system has already created a permission request and a UI permission card. " +
            "Do not add a separate chat reminder; wait for approval and then retry automatically (approvals are remembered for the session). " +
            "NEVER ask the user for any permission_id; that is handled by the system. " +
            "Do NOT pre-emptively tell the user \"please allow\" before attempting the tool call. " +
            "Never ask the user to approve the same action twice. " +
            "Prefer device_api for device controls exposed by the Kotlin control plane. " +
            "When you create an HTML app/page under user files and want the user to open it, include a line `html_path: <relative_path>.html` in your response. " +
            "Use filesystem tools for file operations under the user root; do not use shell commands like `ls`/`cat` for files. " +
            "For execution, use run_python/run_pip/run_curl. " +
            "For cloud calls: prefer the configured Brain provider (Settings -> Brain). If Brain is not configured or has no API key, ask the user to configure it, then retry. " +
            "User-root docs (`AGENTS.md`, `TOOLS.md`) are auto-injected into your context and reloaded if they change on disk; do not repeatedly read them via filesystem tools unless the user explicitly asks. " +
            "Prefer consulting the provided user-root docs under `docs/` and `examples/` (camera/usb/vision) before guessing tool names. " +
            "Keep responses concise: do the work, then summarize the result and include only relevant tool output snippets. " +
            "Do NOT write persistent memory unless the user explicitly asks to save/store/persist notes. " +
            "You MAY use the journal tools (journal_get_current/journal_set_current/journal_append/journal_list) for continuity: " +
            "keep Journal (Current) short, update it at milestones, and append brief entries when you make key decisions or complete steps. " +
            "Always respond in the same language the user writes in."

        val BUILTIN_MODEL_PROFILES: Map<String, Map<String, Any>> = mapOf(
            "gpt-5" to mapOf(
                "dialogue_window_user_assistant" to 64,
                "dialogue_raw_fetch_limit" to 520,
                "max_tool_rounds" to 20,
                "max_actions" to 12,
                "max_tool_output_chars" to 16000,
            ),
            "gpt-4.1-mini" to mapOf(
                "dialogue_window_user_assistant" to 28,
                "dialogue_raw_fetch_limit" to 220,
                "max_tool_rounds" to 14,
                "max_actions" to 8,
                "max_tool_output_chars" to 9000,
            ),
        )
    }
}

class AgentConfigManager(private val context: Context) {
    private val brainPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("brain_config", Context.MODE_PRIVATE)
    }

    fun load(): AgentConfig {
        val vendor = brainPrefs.getString("vendor", "")?.trim().orEmpty()
        val baseUrl = brainPrefs.getString("base_url", "")?.trim()?.trimEnd('/').orEmpty()
        val model = brainPrefs.getString("model", "")?.trim().orEmpty()
        val apiKey = brainPrefs.getString("api_key", "")?.trim().orEmpty()

        return AgentConfig(
            vendor = vendor,
            providerUrl = baseUrl,
            model = model,
            apiKeyCredential = if (apiKey.isNotEmpty()) "direct" else "openai_api_key",
        )
    }

    fun getApiKey(): String {
        return brainPrefs.getString("api_key", "")?.trim().orEmpty()
    }

    fun getVendor(): String {
        return brainPrefs.getString("vendor", "")?.trim().orEmpty()
    }

    fun getBaseUrl(): String {
        return brainPrefs.getString("base_url", "")?.trim()?.trimEnd('/').orEmpty()
    }

    fun getModel(): String {
        return brainPrefs.getString("model", "")?.trim().orEmpty()
    }

    fun loadFull(): AgentConfig {
        val vendor = getVendor()
        val baseUrl = getBaseUrl()
        val model = getModel()
        val apiKey = getApiKey()

        val providerUrl = resolveProviderUrl(vendor, baseUrl)

        return AgentConfig(
            enabled = true,
            autoStart = true,
            vendor = vendor,
            providerUrl = providerUrl,
            model = model,
            apiKeyCredential = if (apiKey.isNotEmpty()) "direct" else "openai_api_key",
            toolPolicy = "auto",
        )
    }

    fun resolveProviderUrl(vendor: String, baseUrl: String): String {
        if (baseUrl.isEmpty()) return ""
        // If the URL already contains a known API path, use it as-is.
        val knownSuffixes = listOf("/messages", "/responses", "/chat/completions")
        if (knownSuffixes.any { baseUrl.contains(it) }) return baseUrl
        // Otherwise, append the vendor-appropriate default path.
        return when (vendor) {
            "anthropic" -> "$baseUrl/messages"
            "openai" -> "$baseUrl/responses"
            else -> "$baseUrl/chat/completions"
        }
    }

    fun isConfigured(): Boolean {
        return getBaseUrl().isNotEmpty() && getModel().isNotEmpty() && getApiKey().isNotEmpty()
    }

    fun brainKeySlotFor(vendor: String, baseUrl: String): String {
        val v = sanitizeVendor(vendor)
        val b = baseUrl.trim().trimEnd('/').lowercase(Locale.US)
        val hx = shortHashHex("$v|$b")
        return "api_key_for_${v}_$hx"
    }

    private fun sanitizeVendor(vendor: String): String {
        return vendor.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9_]"), "_")
    }

    private fun shortHashHex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.take(6).joinToString("") { "%02x".format(it) }
    }
}
