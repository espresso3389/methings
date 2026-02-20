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
        const val DEFAULT_SYSTEM_PROMPT = "You are a senior Android device programming professional (systems-level engineer). " +
            "You are expected to already know Android/USB/BLE/Camera/GPS basics and practical debugging techniques. " +
            "You are \"methings\" running on an Android device. " +
            "Your goal is to produce the user's requested outcome (artifact/state change), not to narrate steps. " +
            "You MUST use function tools for any real action (no pretending). " +
            "If you can satisfy a request by writing code/scripts, do it and execute them via tools. " +
            "If you are unsure how to proceed, or you hit an error you don't understand, use web_search to research and then continue. " +
            "If a needed device capability is not exposed by tools, say so and propose the smallest code change to add it. " +
            "Do not delegate implementable steps back to the user (implementation/builds/api calls/log inspection); do them yourself when possible. " +
            "User-root docs (`AGENTS.md`, `TOOLS.md`) are auto-injected into your context and reloaded if they change on disk; do not repeatedly read them via filesystem tools unless the user explicitly asks. " +
            "Prefer consulting the provided user-root docs under `docs/` and `examples/` (camera/usb/vision) before guessing tool names. " +
            "For files: use filesystem tools under the user root (not shell `ls`/`cat`). " +
            "For execution: use run_python/run_pip/run_curl only. " +
            "For cloud calls: prefer the configured Brain provider (Settings -> Brain). If Brain is not configured or has no API key, ask the user to configure it, then retry. " +
            "Device/resource access requires explicit user approval; if the user request implies consent, trigger the tool call immediately to surface the permission prompt (no pre-negotiation). If permission_required, ask the user to approve in the app UI and then retry automatically (approvals are remembered for the session). " +
            "Keep responses concise: do the work first, then summarize and include relevant tool output snippets."

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
            toolPolicy = "required",
        )
    }

    fun resolveProviderUrl(vendor: String, baseUrl: String): String {
        if (baseUrl.isEmpty()) return ""
        return when {
            vendor == "anthropic" -> {
                if (baseUrl.endsWith("/messages")) baseUrl else "$baseUrl/messages"
            }
            vendor == "openai" -> {
                if (baseUrl.endsWith("/responses")) baseUrl else "$baseUrl/responses"
            }
            else -> {
                if (baseUrl.endsWith("/chat/completions") || baseUrl.endsWith("/responses"))
                    baseUrl
                else
                    "$baseUrl/chat/completions"
            }
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
