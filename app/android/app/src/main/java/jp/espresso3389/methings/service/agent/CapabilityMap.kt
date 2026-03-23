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
    // Keys may be multi-segment action prefixes; the longest matching prefix wins.
    private val ACTION_PREFIX_MAP = mapOf(
        "tts"                         to Triple("device.tts",       "tts",              "Text-to-speech"),
        "camera"                      to Triple("device.camera",    "camera",           "Camera"),
        "ble"                         to Triple("device.ble",       "ble",              "Bluetooth LE"),
        "media.audio"                 to Triple("device.media",     "media",            "Media playback"),
        "media.stream"                to Triple("device.media",     "media_stream",     "Media stream"),
        "audio"                       to Triple("device.mic",       "recording",        "Audio recording"),
        "stt"                         to Triple("device.mic",       "stt",              "Speech recognition"),
        "location"                    to Triple("device.gps",       "location",         "Location"),
        "network"                     to Triple("device.network",   "network",          "Network"),
        "wifi"                        to Triple("device.network",   "network",          "Wi-Fi"),
        "mobile"                      to Triple("device.network",   "network",          "Mobile network"),
        "sensor"                      to Triple("device.sensors",   "sensors",          "Sensors"),
        "sensors"                     to Triple("device.sensors",   "sensors",          "Sensors"),
        "usb"                         to Triple("device.usb",       "usb",              "USB access"),
        "serial"                      to Triple("device.usb",       "usb",              "USB serial"),
        "mcu"                         to Triple("device.usb",       "usb",              "MCU programming"),
        "uvc"                         to Triple("device.usb",       "usb",              "USB video"),
        "video"                       to Triple("device.camera",    "recording",        "Video recording"),
        "screenrec"                   to Triple("device.screen",    "screen_recording", "Screen recording"),
        "ssh"                         to Triple("device.ssh",       "ssh",              "SSH"),
        "sshd"                        to Triple("device.ssh",       "ssh",              "SSH server"),
        "shell"                       to Triple("device.shell",     "shell",            "Shell execution"),
        "vision"                      to Triple("device.camera",    "camera",           "Vision processing"),
        "intent"                      to Triple("device.intent",    "intent",           "Send intent"),
        "screen"                      to Triple("device.screen",    "screen",           "Screen control"),
        "scheduler"                   to Triple("device.scheduler", "scheduler",        "Scheduler"),
        "work"                        to Triple("device.work",      "workmanager",      "WorkManager"),
        "me.me"                       to Triple("device.me_me",     "me_me",            "me.me"),
        "me.sync"                     to Triple("device.me_sync",   "me_sync",          "me.sync"),
        "brain.memory"                to Triple("device.brain",     "memory",           "Brain memory"),
        "android.permissions.request" to Triple("device.android",   "permissions",      "Android permissions"),
        "app.update.install"          to Triple("device.app",       "update_install",   "App update install"),
    )

    private fun capabilityForAction(action: String): Triple<String, String, String>? {
        return ACTION_PREFIX_MAP.entries
            .filter { action == it.key || action.startsWith(it.key + ".") }
            .maxByOrNull { it.key.length }
            ?.value
    }

    /**
     * Build the capability map from configured action prefixes.
     */
    fun toJson(): JSONObject {
        val entries = JSONArray()
        for ((prefix, cap) in ACTION_PREFIX_MAP.toSortedMap()) {
            entries.put(JSONObject().apply {
                put("prefix", prefix)
                put("tool", cap.first)
                put("capability", cap.second)
                put("label", cap.third)
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
            val cap = capabilityForAction(action) ?: continue
            result.add(cap)
        }
        return result
    }
}
