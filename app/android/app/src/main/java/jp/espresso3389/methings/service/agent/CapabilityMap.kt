package jp.espresso3389.methings.service.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps device_api action prefixes to permission capabilities.
 *
 * Used by the scheduler to pre-acquire permissions at schedule creation time
 * so that scheduled code can run unattended.
 *
 * MAINTENANCE: when adding a new device_api action family that requires
 * permission, add a mapping here. See docs/adding_device_apis.md.
 */
object CapabilityMap {

    // Maps action prefix → (tool, capability, label) for permission pre-check.
    // Only prefixes whose actions require permission need an entry here.
    // Prefixes not listed are auto-derived as "none" (no permission needed).
    private val PREFIX_MAP = mapOf(
        "tts"       to Triple("device.tts",      "tts",       "Text-to-speech"),
        "camera"    to Triple("device.camera",    "camera",    "Camera"),
        "ble"       to Triple("device.ble",       "ble",       "Bluetooth LE"),
        "media"     to Triple("device.media",     "media",     "Media playback"),
        "audio"     to Triple("device.mic",       "recording", "Audio recording"),
        "stt"       to Triple("device.mic",       "recording", "Speech recognition"),
        "location"  to Triple("device.location",  "location",  "Location"),
        "sensor"    to Triple("device.sensors",   "sensors",   "Sensors"),
        "sensors"   to Triple("device.sensors",   "sensors",   "Sensors"),
        "usb"       to Triple("device.usb",       "usb",       "USB access"),
        "uvc"       to Triple("device.usb",       "usb",       "USB video"),
        "video"     to Triple("device.camera",    "camera",    "Video recording"),
        "screenrec" to Triple("device.screen",    "screen",    "Screen recording"),
        "ssh"       to Triple("device.ssh",       "ssh",       "SSH"),
        "sshd"      to Triple("device.ssh",       "ssh",       "SSH server"),
        "shell"     to Triple("device.shell",     "shell",     "Shell execution"),
        "vision"    to Triple("device.camera",    "camera",    "Vision processing"),
        "intent"    to Triple("device.intent",    "intent",    "Send intent"),
        "termux"    to Triple("device.termux",    "termux",    "Termux control"),
        "screen"    to Triple("device.screen",    "screen",    "Screen control"),
    )

    /**
     * Build the full capability map from ACTIONS — every prefix is included.
     * Permission-requiring prefixes show their tool/capability/label;
     * others show "none". The agent never needs to guess.
     */
    fun toJson(): JSONObject {
        val entries = JSONArray()
        val prefixGroups = ToolDefinitions.ACTIONS.entries
            .groupBy { it.key.split(".").first() }
        for ((prefix, actions) in prefixGroups.toSortedMap()) {
            val needsPermission = actions.any { it.value.requiresPermission }
            val cap = if (needsPermission) PREFIX_MAP[prefix] else null
            entries.put(JSONObject().apply {
                put("prefix", prefix)
                put("tool", cap?.first ?: "none")
                put("capability", cap?.second ?: "none")
                put("label", cap?.third ?: "No permission required")
            })
        }
        return JSONObject().put("capabilities", entries)
    }

    private val DEVICE_API_REGEX = Regex("""device_api\s*\(\s*["']([^"']+)["']""")

    /** Extract unique (tool, capability, detail) needed by device_api calls in JS code. */
    fun capabilitiesForCode(code: String): Set<Triple<String, String, String>> {
        val result = mutableSetOf<Triple<String, String, String>>()
        for (match in DEVICE_API_REGEX.findAll(code)) {
            val action = match.groupValues[1]
            if (ToolDefinitions.ACTIONS[action]?.requiresPermission != true) continue
            val prefix = action.split(".").first()
            val cap = PREFIX_MAP[prefix] ?: continue
            result.add(cap)
        }
        return result
    }
}
