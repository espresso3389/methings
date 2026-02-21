package jp.espresso3389.methings.service.agent

import org.json.JSONArray
import org.json.JSONObject

data class ActionSpec(val method: String, val path: String, val requiresPermission: Boolean)

object ToolDefinitions {

    fun responsesTools(deviceApiActions: List<String>): JSONArray {
        val tools = JSONArray()

        tools.put(functionTool("list_dir", "List files/directories under the user root (safe alternative to `ls`).") {
            put("path", prop("string"))
            put("show_hidden", prop("boolean"))
            put("limit", prop("integer"))
        }.withRequired("path", "show_hidden", "limit"))

        tools.put(functionTool("read_file", "Read a UTF-8 text file under the user root.") {
            put("path", prop("string"))
            put("max_bytes", prop("integer"))
        }.withRequired("path", "max_bytes"))

        tools.put(functionTool("read_binary_file", "Read a binary/media file window under the user root and return base64 bytes.") {
            put("path", prop("string"))
            put("offset_bytes", prop("integer"))
            put("size_bytes", prop("integer"))
            put("encoding", JSONObject().put("type", "string").put("enum", JSONArray().put("base64")))
        }.withRequired("path"))

        tools.put(functionTool("device_api", "Invoke allowlisted local device API action on 127.0.0.1:33389.") {
            put("action", JSONObject().put("type", "string").put("enum", JSONArray().apply { deviceApiActions.forEach { put(it) } }))
            put("payload", JSONObject().put("type", "object").put("additionalProperties", true))
            put("detail", prop("string"))
        }.withRequired("action", "payload", "detail"))

        tools.put(functionTool("memory_get", "Read persistent memory (notes) stored on the device.") {
        }.withRequired())

        tools.put(functionTool("memory_set", "Replace persistent memory (notes) stored on the device.") {
            put("content", prop("string"))
        }.withRequired("content"))

        tools.put(functionTool("journal_get_current", "Read the per-session journal CURRENT note (kept small for context efficiency).") {
            put("session_id", prop("string"))
        }.withRequired())

        tools.put(functionTool("journal_set_current", "Replace the per-session journal CURRENT note (auto-rotates if too large).") {
            put("session_id", prop("string"))
            put("text", prop("string"))
        }.withRequired("text"))

        tools.put(functionTool("journal_append", "Append a journal entry (auto-rotates entries and stores oversized payloads as a separate file).") {
            put("session_id", prop("string"))
            put("kind", prop("string"))
            put("title", prop("string"))
            put("text", prop("string"))
            put("meta", JSONObject().put("type", "object").put("additionalProperties", true))
        }.withRequired("kind", "title", "text"))

        tools.put(functionTool("journal_list", "List recent journal entries for a session.") {
            put("session_id", prop("string"))
            put("limit", prop("integer"))
        }.withRequired())

        tools.put(functionTool("run_js", "Execute JavaScript code using the built-in QuickJS engine with async/await support. Always available without Termux. Supports: `await fetch(url, options?)` for HTTP, `await connectWs(url)` for WebSocket, `await delay(ms)`, setTimeout/setInterval, `readFile`/`writeFile`/`readBinaryFile`/`writeBinaryFile` (Uint8Array), `listDir`/`mkdir`/`deleteFile`/`rmdir`, `await openFile(path, mode)` for RandomAccessFile handle (size/position/read/write/seek/truncate/close), and `device_api(action, payload)`. Top-level `await` supported. Full reference: `\$sys/docs/run_js.md`.") {
            put("code", prop("string"))
            put("timeout_ms", prop("integer"))
        }.withRequired("code"))

        tools.put(functionTool("run_python", "Run Python locally (equivalent to: python <args>) within the user directory. Requires Termux.") {
            put("args", prop("string"))
            put("cwd", prop("string"))
        }.withRequired("args", "cwd"))

        tools.put(functionTool("run_pip", "Run pip locally (equivalent to: pip <args>) within the user directory. Requires Termux.") {
            put("args", prop("string"))
            put("cwd", prop("string"))
        }.withRequired("args", "cwd"))

        tools.put(functionTool("run_shell", "Execute a shell command. Uses Termux when available (full bash + packages); falls back to native Android shell (/system/bin/sh) otherwise. For long-running or interactive commands, use shell_session instead.") {
            put("command", prop("string"))
            put("cwd", prop("string"))
            put("timeout_ms", prop("integer"))
            put("env", JSONObject().put("type", "object").put("additionalProperties", true))
        }.withRequired("command"))

        tools.put(functionTool("shell_session", "Manage persistent shell sessions. Termux provides full PTY (ANSI, resize); native mode uses pipe-based sessions (no PTY). Actions: start (create session), exec (send command and read output), write (raw stdin), read (buffered output), resize (terminal size, Termux only), kill (terminate), list (active sessions).") {
            put("action", JSONObject().put("type", "string").put("enum", JSONArray().apply {
                put("start"); put("exec"); put("write"); put("read"); put("resize"); put("kill"); put("list")
            }))
            put("session_id", prop("string"))
            put("command", prop("string"))
            put("input", prop("string"))
            put("cwd", prop("string"))
            put("rows", prop("integer"))
            put("cols", prop("integer"))
            put("timeout", prop("integer"))
            put("env", JSONObject().put("type", "object").put("additionalProperties", true))
        }.withRequired("action"))

        tools.put(functionTool("termux_fs", "Access files in the Termux filesystem (outside the app's user root). Actions: read, write, list, stat, mkdir, delete. Requires Termux.") {
            put("action", JSONObject().put("type", "string").put("enum", JSONArray().apply {
                put("read"); put("write"); put("list"); put("stat"); put("mkdir"); put("delete")
            }))
            put("path", prop("string"))
            put("content", prop("string"))
            put("encoding", prop("string"))
            put("max_bytes", prop("integer"))
            put("offset", prop("integer"))
            put("show_hidden", prop("boolean"))
            put("parents", prop("boolean"))
            put("recursive", prop("boolean"))
        }.withRequired("action", "path"))

        tools.put(functionTool("run_curl", "Make an HTTP request. Works natively without Termux. Parameters: url (required), method (GET/POST/PUT/DELETE/PATCH/HEAD, default GET), headers (JSON object), body (string), timeout_ms (default 30000).") {
            put("url", prop("string"))
            put("method", prop("string"))
            put("headers", JSONObject().put("type", "object").put("additionalProperties", true))
            put("body", prop("string"))
            put("timeout_ms", prop("integer"))
        }.withRequired("url"))

        tools.put(functionTool("web_search", "Search the web (permission-gated). Provider defaults to auto (Brave if configured, else DuckDuckGo Instant Answer).") {
            put("query", prop("string"))
            put("max_results", prop("integer"))
            put("provider", prop("string"))
        }.withRequired("query", "max_results"))

        tools.put(functionTool("cloud_request", "Make a cloud HTTP request via the Kotlin broker (supports \${vault:...} and \${file:...} placeholders, permission-gated).") {
            put("request", JSONObject().put("type", "object").put("additionalProperties", true))
        }.withRequired("request"))

        tools.put(functionTool("write_file", "Write UTF-8 text file under user root.") {
            put("path", prop("string"))
            put("content", prop("string"))
        }.withRequired("path", "content"))

        tools.put(functionTool("mkdir", "Create a directory under the user root.") {
            put("path", prop("string"))
            put("parents", prop("boolean"))
        }.withRequired("path", "parents"))

        tools.put(functionTool("move_path", "Move/rename a file or directory within the user root.") {
            put("src", prop("string"))
            put("dst", prop("string"))
            put("overwrite", prop("boolean"))
        }.withRequired("src", "dst", "overwrite"))

        tools.put(functionTool("delete_path", "Delete a file or directory under the user root.") {
            put("path", prop("string"))
            put("recursive", prop("boolean"))
        }.withRequired("path", "recursive"))

        tools.put(functionTool("sleep", "Pause execution for small delay.") {
            put("seconds", prop("number"))
        }.withRequired("seconds"))

        return tools
    }

    fun deviceApiActions(): Map<String, ActionSpec> = ACTIONS

    fun deviceApiActionNames(): List<String> {
        val names = ACTIONS.keys.toMutableSet()
        names.addAll(listOf("uvc.ptz.get_abs", "uvc.ptz.get_limits", "uvc.ptz.set_abs", "uvc.ptz.nudge"))
        return names.sorted()
    }

    // Anthropic Messages API tool format (different from OpenAI Responses API)
    /** Chat Completions format: wraps each tool in {"type":"function","function":{...}} */
    fun chatTools(deviceApiActions: List<String>): JSONArray {
        val tools = JSONArray()
        val responseTools = responsesTools(deviceApiActions)
        for (i in 0 until responseTools.length()) {
            val tool = responseTools.getJSONObject(i)
            tools.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.getString("name"))
                    put("description", tool.getString("description"))
                    put("parameters", tool.getJSONObject("parameters"))
                })
            })
        }
        return tools
    }

    /** Gemini format: wraps tools in a single object with function_declarations array */
    fun geminiTools(deviceApiActions: List<String>): JSONArray {
        val declarations = JSONArray()
        val responseTools = responsesTools(deviceApiActions)
        for (i in 0 until responseTools.length()) {
            val tool = responseTools.getJSONObject(i)
            declarations.put(JSONObject().apply {
                put("name", tool.getString("name"))
                put("description", tool.getString("description"))
                put("parameters", tool.getJSONObject("parameters"))
            })
        }
        // Gemini expects: [{"function_declarations": [...]}]
        val wrapper = JSONArray()
        wrapper.put(JSONObject().put("function_declarations", declarations))
        return wrapper
    }

    fun anthropicTools(deviceApiActions: List<String>): JSONArray {
        val tools = JSONArray()
        val responseTools = responsesTools(deviceApiActions)
        for (i in 0 until responseTools.length()) {
            val tool = responseTools.getJSONObject(i)
            tools.put(JSONObject().apply {
                put("name", tool.getString("name"))
                put("description", tool.getString("description"))
                put("input_schema", tool.getJSONObject("parameters"))
            })
        }
        return tools
    }

    private fun prop(type: String): JSONObject = JSONObject().put("type", type)

    private fun functionTool(name: String, description: String, properties: JSONObject.() -> Unit): JSONObject {
        val props = JSONObject().apply(properties)
        return JSONObject().apply {
            put("type", "function")
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("additionalProperties", false)
                put("properties", props)
            })
        }
    }

    private fun JSONObject.withRequired(vararg keys: String): JSONObject {
        val params = this.getJSONObject("parameters")
        params.put("required", JSONArray().apply { keys.forEach { put(it) } })
        return this
    }

    val ACTIONS: Map<String, ActionSpec> = mapOf(
        "termux.status" to ActionSpec("GET", "/termux/status", false),
        "termux.restart" to ActionSpec("POST", "/termux/restart", true),
        "screen.status" to ActionSpec("GET", "/screen/status", false),
        "screen.keep_on" to ActionSpec("POST", "/screen/keep_on", true),
        "sshd.status" to ActionSpec("GET", "/sshd/status", false),
        "sshd.config" to ActionSpec("POST", "/sshd/config", true),
        "ssh.exec" to ActionSpec("POST", "/ssh/exec", true),
        "ssh.scp" to ActionSpec("POST", "/ssh/scp", true),
        "ssh.ws.contract" to ActionSpec("GET", "/ssh/ws/contract", false),
        "sshd.keys.list" to ActionSpec("GET", "/sshd/keys", false),
        "sshd.keys.add" to ActionSpec("POST", "/sshd/keys/add", true),
        "sshd.keys.delete" to ActionSpec("POST", "/sshd/keys/delete", true),
        "sshd.keys.policy.get" to ActionSpec("GET", "/sshd/keys/policy", false),
        "sshd.keys.policy.set" to ActionSpec("POST", "/sshd/keys/policy", true),
        "sshd.pin.status" to ActionSpec("GET", "/sshd/pin/status", false),
        "sshd.pin.start" to ActionSpec("POST", "/sshd/pin/start", true),
        "sshd.pin.stop" to ActionSpec("POST", "/sshd/pin/stop", true),
        "sshd.noauth.status" to ActionSpec("GET", "/sshd/noauth/status", false),
        "sshd.noauth.start" to ActionSpec("POST", "/sshd/noauth/start", true),
        "sshd.noauth.stop" to ActionSpec("POST", "/sshd/noauth/stop", true),
        "camera.list" to ActionSpec("GET", "/camera/list", true),
        "camera.status" to ActionSpec("GET", "/camera/status", true),
        "camera.preview.start" to ActionSpec("POST", "/camera/preview/start", true),
        "camera.preview.stop" to ActionSpec("POST", "/camera/preview/stop", true),
        "camera.capture" to ActionSpec("POST", "/camera/capture", true),
        "ble.status" to ActionSpec("GET", "/ble/status", true),
        "ble.scan.start" to ActionSpec("POST", "/ble/scan/start", true),
        "ble.scan.stop" to ActionSpec("POST", "/ble/scan/stop", true),
        "ble.connect" to ActionSpec("POST", "/ble/connect", true),
        "ble.disconnect" to ActionSpec("POST", "/ble/disconnect", true),
        "ble.gatt.services" to ActionSpec("POST", "/ble/gatt/services", true),
        "ble.gatt.read" to ActionSpec("POST", "/ble/gatt/read", true),
        "ble.gatt.write" to ActionSpec("POST", "/ble/gatt/write", true),
        "ble.gatt.notify.start" to ActionSpec("POST", "/ble/gatt/notify/start", true),
        "ble.gatt.notify.stop" to ActionSpec("POST", "/ble/gatt/notify/stop", true),
        "tts.init" to ActionSpec("POST", "/tts/init", true),
        "tts.voices" to ActionSpec("GET", "/tts/voices", true),
        "tts.speak" to ActionSpec("POST", "/tts/speak", true),
        "tts.stop" to ActionSpec("POST", "/tts/stop", true),
        "media.audio.status" to ActionSpec("GET", "/media/audio/status", true),
        "media.audio.play" to ActionSpec("POST", "/media/audio/play", true),
        "media.audio.stop" to ActionSpec("POST", "/media/audio/stop", true),
        "audio.record.status" to ActionSpec("GET", "/audio/record/status", true),
        "audio.record.start" to ActionSpec("POST", "/audio/record/start", true),
        "audio.record.stop" to ActionSpec("POST", "/audio/record/stop", true),
        "audio.record.config.get" to ActionSpec("GET", "/audio/record/config", true),
        "audio.record.config.set" to ActionSpec("POST", "/audio/record/config", true),
        "audio.stream.start" to ActionSpec("POST", "/audio/stream/start", true),
        "audio.stream.stop" to ActionSpec("POST", "/audio/stream/stop", true),
        "video.record.status" to ActionSpec("GET", "/video/record/status", true),
        "video.record.start" to ActionSpec("POST", "/video/record/start", true),
        "video.record.stop" to ActionSpec("POST", "/video/record/stop", true),
        "video.record.config.get" to ActionSpec("GET", "/video/record/config", true),
        "video.record.config.set" to ActionSpec("POST", "/video/record/config", true),
        "video.stream.start" to ActionSpec("POST", "/video/stream/start", true),
        "video.stream.stop" to ActionSpec("POST", "/video/stream/stop", true),
        "screenrec.status" to ActionSpec("GET", "/screen/record/status", true),
        "screenrec.start" to ActionSpec("POST", "/screen/record/start", true),
        "screenrec.stop" to ActionSpec("POST", "/screen/record/stop", true),
        "screenrec.config.get" to ActionSpec("GET", "/screen/record/config", true),
        "screenrec.config.set" to ActionSpec("POST", "/screen/record/config", true),
        "media.stream.status" to ActionSpec("GET", "/media/stream/status", true),
        "media.stream.audio.start" to ActionSpec("POST", "/media/stream/audio/start", true),
        "media.stream.video.start" to ActionSpec("POST", "/media/stream/video/start", true),
        "media.stream.stop" to ActionSpec("POST", "/media/stream/stop", true),
        "stt.status" to ActionSpec("GET", "/stt/status", true),
        "stt.record" to ActionSpec("POST", "/stt/record", true),
        "location.status" to ActionSpec("GET", "/location/status", true),
        "location.get" to ActionSpec("POST", "/location/get", true),
        "network.status" to ActionSpec("GET", "/network/status", true),
        "wifi.status" to ActionSpec("GET", "/wifi/status", true),
        "mobile.status" to ActionSpec("GET", "/mobile/status", true),
        "sensors.list" to ActionSpec("GET", "/sensors/list", true),
        "sensor.list" to ActionSpec("GET", "/sensor/list", true),
        "sensors.ws.contract" to ActionSpec("GET", "/sensors/ws/contract", true),
        "sensor.stream.start" to ActionSpec("POST", "/sensor/stream/start", true),
        "sensor.stream.stop" to ActionSpec("POST", "/sensor/stream/stop", true),
        "sensor.stream.status" to ActionSpec("GET", "/sensor/stream/status", true),
        "sensor.stream.latest" to ActionSpec("GET", "/sensor/stream/latest", true),
        "sensor.stream.batch" to ActionSpec("GET", "/sensor/stream/batch", true),
        "usb.list" to ActionSpec("GET", "/usb/list", true),
        "usb.status" to ActionSpec("GET", "/usb/status", true),
        "usb.open" to ActionSpec("POST", "/usb/open", true),
        "usb.close" to ActionSpec("POST", "/usb/close", true),
        "usb.control_transfer" to ActionSpec("POST", "/usb/control_transfer", true),
        "usb.raw_descriptors" to ActionSpec("POST", "/usb/raw_descriptors", true),
        "usb.claim_interface" to ActionSpec("POST", "/usb/claim_interface", true),
        "usb.release_interface" to ActionSpec("POST", "/usb/release_interface", true),
        "usb.bulk_transfer" to ActionSpec("POST", "/usb/bulk_transfer", true),
        "usb.iso_transfer" to ActionSpec("POST", "/usb/iso_transfer", true),
        "usb.stream.start" to ActionSpec("POST", "/usb/stream/start", true),
        "usb.stream.stop" to ActionSpec("POST", "/usb/stream/stop", true),
        "usb.stream.status" to ActionSpec("GET", "/usb/stream/status", true),
        "uvc.mjpeg.capture" to ActionSpec("POST", "/uvc/mjpeg/capture", true),
        "uvc.diagnose" to ActionSpec("POST", "/uvc/diagnose", true),
        "vision.model.load" to ActionSpec("POST", "/vision/model/load", true),
        "vision.model.unload" to ActionSpec("POST", "/vision/model/unload", true),
        "vision.frame.put" to ActionSpec("POST", "/vision/frame/put", true),
        "vision.frame.get" to ActionSpec("POST", "/vision/frame/get", true),
        "vision.frame.delete" to ActionSpec("POST", "/vision/frame/delete", true),
        "vision.frame.save" to ActionSpec("POST", "/vision/frame/save", true),
        "vision.image.load" to ActionSpec("POST", "/vision/image/load", true),
        "vision.run" to ActionSpec("POST", "/vision/run", true),
        "shell.exec" to ActionSpec("POST", "/shell/exec", true),
        "brain.memory.get" to ActionSpec("GET", "/brain/memory", false),
        "brain.memory.set" to ActionSpec("POST", "/brain/memory", true),
        "viewer.open" to ActionSpec("POST", "/ui/viewer/open", false),
        "viewer.close" to ActionSpec("POST", "/ui/viewer/close", false),
        "viewer.immersive" to ActionSpec("POST", "/ui/viewer/immersive", false),
        "viewer.slideshow" to ActionSpec("POST", "/ui/viewer/slideshow", false),
        "viewer.goto" to ActionSpec("POST", "/ui/viewer/goto", false),
        "android.device" to ActionSpec("GET", "/android/device", false),
        "android.permissions" to ActionSpec("GET", "/android/permissions", false),
        "android.permissions.request" to ActionSpec("POST", "/android/permissions/request", true),
        "app.info" to ActionSpec("GET", "/app/info", false),
        "app.update.check" to ActionSpec("GET", "/app/update/check", false),
        "app.update.install" to ActionSpec("POST", "/app/update/install", true),
        "brain.config.get" to ActionSpec("GET", "/brain/config", false),
        "file_transfer.prefs.get" to ActionSpec("GET", "/file_transfer/prefs", false),
        "notifications.prefs.get" to ActionSpec("GET", "/notifications/prefs", false),
        "notifications.prefs.set" to ActionSpec("POST", "/notifications/prefs", false),
        "me.sync.status" to ActionSpec("GET", "/me/sync/status", false),
        "me.sync.progress" to ActionSpec("GET", "/me/sync/progress", false),
        "me.me.status" to ActionSpec("GET", "/me/me/status", false),
        "me.me.routes" to ActionSpec("GET", "/me/me/routes", false),
        "me.me.config.get" to ActionSpec("GET", "/me/me/config", false),
        "me.me.config.set" to ActionSpec("POST", "/me/me/config", true),
        "me.me.scan" to ActionSpec("POST", "/me/me/scan", true),
        "me.me.connect" to ActionSpec("POST", "/me/me/connect", true),
        "me.me.accept" to ActionSpec("POST", "/me/me/accept", true),
        "me.me.disconnect" to ActionSpec("POST", "/me/me/disconnect", true),
        "me.me.message.send" to ActionSpec("POST", "/me/me/message/send", true),
        "me.me.message.send_file" to ActionSpec("POST", "/me/me/message/send", true),
        "me.me.messages.pull" to ActionSpec("POST", "/me/me/messages/pull", true),
        "me.me.relay.status" to ActionSpec("GET", "/me/me/relay/status", false),
        "me.me.relay.config.get" to ActionSpec("GET", "/me/me/relay/config", false),
        "me.me.relay.config.set" to ActionSpec("POST", "/me/me/relay/config", true),
        "me.me.relay.register" to ActionSpec("POST", "/me/me/relay/register", true),
        "me.sync.local_state" to ActionSpec("GET", "/me/sync/local_state", false),
        "me.sync.prepare_export" to ActionSpec("POST", "/me/sync/prepare_export", true),
        "me.sync.import" to ActionSpec("POST", "/me/sync/import", true),
        "me.sync.wipe_all" to ActionSpec("POST", "/me/sync/wipe_all", true),
        "me.sync.v3.ticket.create" to ActionSpec("POST", "/me/sync/v3/ticket/create", true),
        "me.sync.v3.ticket.status" to ActionSpec("GET", "/me/sync/v3/ticket/status", false),
        "me.sync.v3.ticket.cancel" to ActionSpec("POST", "/me/sync/v3/ticket/cancel", true),
        "me.sync.v3.import.apply" to ActionSpec("POST", "/me/sync/v3/import/apply", true),
        "ui.settings.sections" to ActionSpec("GET", "/ui/settings/sections", false),
        "ui.settings.navigate" to ActionSpec("POST", "/ui/settings/navigate", false),
        "ui.me.sync.export.show" to ActionSpec("POST", "/ui/me/sync/export/show", false),
        "debug.logs.export" to ActionSpec("POST", "/debug/logs/export", false),
        "debug.logs.list" to ActionSpec("GET", "/debug/logs/list", false),
        "debug.logs.delete_all" to ActionSpec("POST", "/debug/logs/delete_all", false),
        "debug.logs.stream" to ActionSpec("POST", "/debug/logs/stream", false),
        "intent.send" to ActionSpec("POST", "/intent/send", true),
        "intent.share_app" to ActionSpec("POST", "/intent/share_app", true),
        "work.app_update_check.status" to ActionSpec("GET", "/work/jobs/app_update_check", false),
        "work.app_update_check.schedule" to ActionSpec("POST", "/work/jobs/app_update_check/schedule", true),
        "work.app_update_check.run_once" to ActionSpec("POST", "/work/jobs/app_update_check/run_once", true),
        "work.app_update_check.cancel" to ActionSpec("POST", "/work/jobs/app_update_check/cancel", true),
        "webview.open" to ActionSpec("POST", "/webview/open", false),
        "webview.close" to ActionSpec("POST", "/webview/close", false),
        "webview.status" to ActionSpec("GET", "/webview/status", false),
        "webview.screenshot" to ActionSpec("POST", "/webview/screenshot", false),
        "webview.js" to ActionSpec("POST", "/webview/js", false),
        "webview.tap" to ActionSpec("POST", "/webview/tap", false),
        "webview.scroll" to ActionSpec("POST", "/webview/scroll", false),
        "webview.back" to ActionSpec("POST", "/webview/back", false),
        "webview.forward" to ActionSpec("POST", "/webview/forward", false),
        "webview.split" to ActionSpec("POST", "/webview/split", false),
        "webview.console" to ActionSpec("GET", "/webview/console", false),
        "webview.console.clear" to ActionSpec("POST", "/webview/console/clear", false),
        "scheduler.status" to ActionSpec("GET", "/scheduler/status", false),
        "scheduler.list" to ActionSpec("GET", "/scheduler/schedules", false),
        "scheduler.create" to ActionSpec("POST", "/scheduler/create", true),
        "scheduler.get" to ActionSpec("POST", "/scheduler/get", false),
        "scheduler.update" to ActionSpec("POST", "/scheduler/update", true),
        "scheduler.delete" to ActionSpec("POST", "/scheduler/delete", true),
        "scheduler.trigger" to ActionSpec("POST", "/scheduler/trigger", true),
        "scheduler.log" to ActionSpec("POST", "/scheduler/log", false),
    )

    val ACTION_TIMEOUTS: Map<String, Double> = mapOf(
        "camera.capture" to 45.0,
        "camera.preview.start" to 25.0,
        "camera.preview.stop" to 25.0,
        "ssh.exec" to 300.0,
        "ssh.scp" to 600.0,
        "vision.run" to 75.0,
        "usb.open" to 60.0,
        "usb.stream.start" to 25.0,
        "usb.stream.stop" to 25.0,
        "uvc.mjpeg.capture" to 45.0,
        "screen.keep_on" to 12.0,
        "media.audio.play" to 120.0,
        "media.audio.status" to 20.0,
        "media.audio.stop" to 20.0,
        "audio.record.start" to 25.0,
        "audio.record.stop" to 25.0,
        "audio.stream.start" to 25.0,
        "video.record.start" to 25.0,
        "video.record.stop" to 25.0,
        "video.stream.start" to 25.0,
        "screenrec.start" to 45.0,
        "me.me.relay.register" to 20.0,
        "screenrec.stop" to 25.0,
        "me.sync.v3.ticket.create" to 30.0,
        "me.sync.v3.import.apply" to 300.0,
        "me.me.scan" to 45.0,
        "me.me.connect" to 40.0,
        "me.me.message.send" to 45.0,
        "debug.logs.export" to 45.0,
        "debug.logs.stream" to 140.0,
        "webview.open" to 35.0,
        "webview.screenshot" to 15.0,
        "webview.js" to 15.0,
    )

    const val DEFAULT_ACTION_TIMEOUT_S = 12.0
    const val MIN_TIMEOUT_S = 3.0
    const val MAX_TIMEOUT_S = 900.0

    fun timeoutForAction(action: String): Double {
        return (ACTION_TIMEOUTS[action] ?: DEFAULT_ACTION_TIMEOUT_S).coerceIn(MIN_TIMEOUT_S, MAX_TIMEOUT_S)
    }
}
