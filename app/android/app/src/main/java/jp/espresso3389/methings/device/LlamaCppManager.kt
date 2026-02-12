package jp.espresso3389.methings.device

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LlamaCppManager(private val context: Context) {
    private val filesRoot: File = context.filesDir
    private val userRoot: File = File(filesRoot, "user")
    private val ttsPluginFile: File = File(userRoot, ".config/llama_tts_plugins.json")
    private val defaultModelRoots: List<File> = listOf(
        File(filesRoot, "models"),
        File(filesRoot, "models/llama"),
        File(userRoot, "models"),
        File(userRoot, "models/llama"),
    )
    private val wavPlayer = WavStreamPlayer()
    private val speechTasks = ConcurrentHashMap<String, SpeechTask>()

    private data class SpeechTask(
        val id: String,
        val outputFile: File,
        val startedAt: Long,
        val minOutputDurationMs: Long = 400L,
        val stopRequested: AtomicBoolean = AtomicBoolean(false),
        @Volatile var status: String = "running",
        @Volatile var process: Process? = null,
        @Volatile var processExitCode: Int? = null,
        @Volatile var processTimedOut: Boolean = false,
        @Volatile var playback: Map<String, Any?>? = null,
        @Volatile var outputWav: Map<String, Any?>? = null,
        @Volatile var validationError: Map<String, Any?>? = null,
        @Volatile var stdout: String = "",
        @Volatile var stderr: String = "",
        @Volatile var finishedAt: Long? = null,
    )

    fun status(payload: JSONObject): Map<String, Any?> {
        val cli = resolveBinary(payload.optString("cli_path", ""), "llama-cli")
        val tts = resolveBinary(payload.optString("tts_path", ""), "llama-tts")
        val roots = readRoots(payload.optJSONArray("roots"))
        val models = listModelsInternal(roots, maxDepth = payload.optInt("max_depth", 4).coerceIn(1, 8), limit = 200)
        return mapOf(
            "status" to "ok",
            "available" to (cli != null || tts != null),
            "binaries" to mapOf(
                "llama_cli" to pathOrNull(cli),
                "llama_tts" to pathOrNull(tts),
            ),
            "model_roots" to roots.map { it.absolutePath },
            "model_count" to models.size,
            "models" to JSONArray(models.map { JSONObject(it) })
        )
    }

    fun listModels(payload: JSONObject): Map<String, Any?> {
        val roots = readRoots(payload.optJSONArray("roots"))
        val maxDepth = payload.optInt("max_depth", 4).coerceIn(1, 8)
        val limit = payload.optInt("limit", 500).coerceIn(1, 10_000)
        val models = listModelsInternal(roots, maxDepth, limit)
        return mapOf(
            "status" to "ok",
            "items" to JSONArray(models.map { JSONObject(it) }),
            "count" to models.size
        )
    }

    fun run(payload: JSONObject): Map<String, Any?> {
        val target = payload.optString("binary", "").trim()
        val binary = resolveBinary(target, "llama-cli")
            ?: return mapOf(
                "status" to "error",
                "error" to "binary_not_found",
                "detail" to "Could not find llama binary. Place binaries in files/bin or use an absolute path."
            )

        val args = jsonArrayToStringList(payload.optJSONArray("args"))
        val cwd = resolveCwd(payload.optString("cwd", ""))
        val timeoutMs = payload.optLong("timeout_ms", 180_000L).coerceIn(1_000L, 900_000L)
        val maxOutputBytes = payload.optInt("max_output_bytes", 1_000_000).coerceIn(4096, 8_000_000)
        val stdin = payload.optString("stdin", "").takeIf { it.isNotEmpty() }

        return exec(binary, args, cwd, stdin, timeoutMs, maxOutputBytes)
    }

    fun generate(payload: JSONObject): Map<String, Any?> {
        val model = resolveModelFile(payload) ?: return mapOf(
            "status" to "error",
            "error" to "model_not_found",
            "detail" to "Set model/model_path and ensure the GGUF file exists."
        )
        val prompt = payload.optString("prompt", "").trim()
        if (prompt.isEmpty()) return mapOf("status" to "error", "error" to "missing_prompt")

        val binary = resolveBinary(payload.optString("binary", ""), "llama-cli")
            ?: return mapOf("status" to "error", "error" to "binary_not_found", "detail" to "llama-cli not found")

        val args = mutableListOf("-m", model.absolutePath, "-p", prompt)
        args += listOf("-n", payload.optInt("n_predict", 128).coerceIn(1, 8192).toString())
        if (payload.has("ctx_size")) args += listOf("-c", payload.optInt("ctx_size", 4096).coerceIn(128, 131072).toString())
        if (payload.has("temperature")) args += listOf("--temp", payload.optDouble("temperature", 0.8).toString())
        if (payload.has("top_p")) args += listOf("--top-p", payload.optDouble("top_p", 0.95).toString())
        args += jsonArrayToStringList(payload.optJSONArray("args"))

        val cwd = resolveCwd(payload.optString("cwd", ""))
        val timeoutMs = payload.optLong("timeout_ms", 240_000L).coerceIn(1_000L, 900_000L)
        val maxOutputBytes = payload.optInt("max_output_bytes", 2_000_000).coerceIn(4096, 8_000_000)
        val result = exec(binary, args, cwd, null, timeoutMs, maxOutputBytes)
        val stdout = (result["stdout"] as? String) ?: ""
        return LinkedHashMap(result).apply {
            put("text", stdout.trim())
            put("model_path", model.absolutePath)
        }
    }

    fun ttsPluginsList(payload: JSONObject): Map<String, Any?> {
        val includeArgs = payload.optBoolean("include_args", true)
        val doc = loadTtsPluginDoc()
        val pluginsObj = doc.optJSONObject("plugins") ?: JSONObject()
        val ids = ArrayList<String>()
        val it = pluginsObj.keys()
        while (it.hasNext()) ids.add(it.next())
        ids.sort()
        val items = JSONArray()
        for (id in ids) {
            val p = pluginsObj.optJSONObject(id) ?: continue
            val item = JSONObject()
                .put("plugin_id", id)
                .put("description", p.optString("description", ""))
                .put("created_at", p.optLong("created_at", 0L))
                .put("updated_at", p.optLong("updated_at", 0L))
                .put("defaults", p.optJSONObject("defaults") ?: JSONObject())
            val preset = p.optJSONObject("preset")
            if (preset != null) item.put("preset", preset)
            if (includeArgs) item.put("args", p.optJSONArray("args") ?: JSONArray())
            items.put(item)
        }
        return mapOf(
            "status" to "ok",
            "count" to items.length(),
            "default_plugin_id" to doc.optString("default_plugin_id", ""),
            "items" to items
        )
    }

    fun ttsPluginsUpsert(payload: JSONObject): Map<String, Any?> {
        val pluginId = payload.optString("plugin_id", "").trim()
        if (pluginId.isEmpty()) return mapOf("status" to "error", "error" to "missing_plugin_id")
        val args = payload.optJSONArray("args")
            ?: return mapOf("status" to "error", "error" to "missing_args", "detail" to "Provide payload.args template.")
        if (args.length() == 0) return mapOf("status" to "error", "error" to "missing_args")

        val now = System.currentTimeMillis()
        val doc = loadTtsPluginDoc()
        val pluginsObj = doc.optJSONObject("plugins") ?: JSONObject()
        val existing = pluginsObj.optJSONObject(pluginId)

        val plugin = JSONObject()
            .put("plugin_id", pluginId)
            .put("args", JSONArray(jsonArrayToStringList(args)))
            .put("description", payload.optString("description", "").trim())
            .put("defaults", payload.optJSONObject("defaults") ?: JSONObject())
            .put("created_at", existing?.optLong("created_at", now) ?: now)
            .put("updated_at", now)

        val presetIn = payload.optJSONObject("preset")
        if (presetIn != null) {
            val preset = JSONObject()
            val allow = listOf("binary", "model", "model_path", "cwd", "timeout_ms", "max_output_bytes", "min_output_duration_ms")
            for (k in allow) {
                if (presetIn.has(k)) preset.put(k, presetIn.get(k))
            }
            plugin.put("preset", preset)
        } else if (existing?.optJSONObject("preset") != null) {
            plugin.put("preset", existing.optJSONObject("preset"))
        }

        pluginsObj.put(pluginId, plugin)
        doc.put("plugins", pluginsObj)
        if (payload.optBoolean("set_default", false)) doc.put("default_plugin_id", pluginId)
        saveTtsPluginDoc(doc)
        return mapOf("status" to "ok", "plugin" to plugin)
    }

    fun ttsPluginsDelete(payload: JSONObject): Map<String, Any?> {
        val pluginId = payload.optString("plugin_id", "").trim()
        if (pluginId.isEmpty()) return mapOf("status" to "error", "error" to "missing_plugin_id")
        val doc = loadTtsPluginDoc()
        val pluginsObj = doc.optJSONObject("plugins") ?: JSONObject()
        if (!pluginsObj.has(pluginId)) return mapOf("status" to "ok", "deleted" to false, "plugin_id" to pluginId)
        pluginsObj.remove(pluginId)
        doc.put("plugins", pluginsObj)
        if (doc.optString("default_plugin_id", "") == pluginId) doc.put("default_plugin_id", "")
        saveTtsPluginDoc(doc)
        return mapOf("status" to "ok", "deleted" to true, "plugin_id" to pluginId)
    }

    fun tts(payload: JSONObject): Map<String, Any?> {
        if (payload.optBoolean("speak", false)) {
            return ttsSpeak(payload)
        }
        val resolved = resolveTtsPayload(payload)
        if (resolved.error != null) return resolved.error
        val req = resolved.payload

        val binary = resolveBinary(req.optString("binary", ""), "llama-tts")
            ?: return mapOf("status" to "error", "error" to "binary_not_found", "detail" to "llama-tts not found")
        val model = resolveModelFile(req)
        val text = req.optString("text", "")
        val outputRel = req.optString("output_path", "").trim().ifBlank {
            "outputs/llama_tts_${System.currentTimeMillis()}.wav"
        }
        val outputFile = resolveUserPath(outputRel)
        outputFile.parentFile?.mkdirs()

        val templateArgs = req.optJSONArray("args")
        if (templateArgs == null || templateArgs.length() == 0) {
            return mapOf(
                "status" to "error",
                "error" to "missing_args",
                "detail" to "Provide payload.args for llama-tts command arguments."
            )
        }

        val substitutions = LinkedHashMap<String, String>()
        substitutions.putAll(resolved.templateVars)
        substitutions.putAll(mapOf(
            "model" to (model?.absolutePath ?: ""),
            "text" to text,
            "output_path" to outputFile.absolutePath
        ))
        val args = renderTemplateArgs(jsonArrayToStringList(templateArgs), substitutions)

        val cwd = resolveCwd(req.optString("cwd", ""))
        val timeoutMs = req.optLong("timeout_ms", 300_000L).coerceIn(1_000L, 900_000L)
        val maxOutputBytes = req.optInt("max_output_bytes", 2_000_000).coerceIn(4096, 8_000_000)
        val result = exec(binary, args, cwd, null, timeoutMs, maxOutputBytes)

        val out = LinkedHashMap(result)
        if ((result["status"] == "ok") && outputFile.exists()) {
            attachOutputFileInfo(out, outputFile)
            val validation = validateSpeechOutput(outputFile, req.optLong("min_output_duration_ms", 400L))
            if (validation != null) {
                out["status"] = "error"
                out["error"] = validation["error"]
                out["detail"] = validation["detail"]
                out["validation"] = JSONObject(validation)
            }
        }
        if (model != null) out["model_path"] = model.absolutePath
        resolved.pluginId?.let { out["plugin_id"] = it }
        return out
    }

    fun ttsSpeak(payload: JSONObject): Map<String, Any?> {
        val resolved = resolveTtsPayload(payload)
        if (resolved.error != null) return resolved.error
        val req = resolved.payload

        val binary = resolveBinary(req.optString("binary", ""), "llama-tts")
            ?: return mapOf("status" to "error", "error" to "binary_not_found", "detail" to "llama-tts not found")
        val model = resolveModelFile(req)
        val text = req.optString("text", "")
        val outputRel = req.optString("output_path", "").trim().ifBlank {
            "outputs/llama_tts_${System.currentTimeMillis()}.wav"
        }
        val outputFile = resolveUserPath(outputRel)
        outputFile.parentFile?.mkdirs()

        val templateArgs = req.optJSONArray("args")
            ?: return mapOf("status" to "error", "error" to "missing_args", "detail" to "Provide payload.args for llama-tts.")
        if (templateArgs.length() == 0) {
            return mapOf("status" to "error", "error" to "missing_args", "detail" to "Provide payload.args for llama-tts.")
        }
        val substitutions = LinkedHashMap<String, String>()
        substitutions.putAll(resolved.templateVars)
        substitutions.putAll(mapOf(
            "model" to (model?.absolutePath ?: ""),
            "text" to text,
            "output_path" to outputFile.absolutePath
        ))
        val args = renderTemplateArgs(jsonArrayToStringList(templateArgs), substitutions)
        val cwd = resolveCwd(req.optString("cwd", ""))
        val timeoutMs = req.optLong("timeout_ms", 300_000L).coerceIn(1_000L, 900_000L)
        val maxOutputBytes = req.optInt("max_output_bytes", 1_000_000).coerceIn(4096, 4_000_000)

        val id = "tts_" + UUID.randomUUID().toString().replace("-", "")
        val task = SpeechTask(
            id = id,
            outputFile = outputFile,
            startedAt = System.currentTimeMillis(),
            minOutputDurationMs = req.optLong("min_output_duration_ms", 400L)
        )
        speechTasks[id] = task

        Thread {
            runSpeechTask(task, binary, args, cwd, timeoutMs, maxOutputBytes)
        }.apply {
            isDaemon = true
            start()
        }

        return mapOf(
            "status" to "ok",
            "started" to true,
            "speech_id" to id,
            "output_path" to relativizeToUser(outputFile),
            "output_abs_path" to outputFile.absolutePath,
            "model_path" to model?.absolutePath,
            "plugin_id" to resolved.pluginId,
        )
    }

    fun ttsSpeakStatus(payload: JSONObject): Map<String, Any?> {
        val id = payload.optString("speech_id", "").trim()
        if (id.isEmpty()) return mapOf("status" to "error", "error" to "missing_speech_id")
        val task = speechTasks[id] ?: return mapOf("status" to "error", "error" to "speech_not_found")
        return mapOf(
            "status" to "ok",
            "speech" to speechTaskToJson(task)
        )
    }

    fun ttsSpeakStop(payload: JSONObject): Map<String, Any?> {
        val id = payload.optString("speech_id", "").trim()
        val taskOrNull = if (id.isNotEmpty()) {
            speechTasks[id]
        } else {
            speechTasks.values.firstOrNull { it.status == "running" }
        }
        val task = taskOrNull ?: return mapOf("status" to "ok", "stopped" to false)
        task.stopRequested.set(true)
        runCatching { task.process?.destroy() }
        val playbackStop = wavPlayer.stop()
        return mapOf("status" to "ok", "stopped" to true, "speech_id" to task.id, "playback" to JSONObject(playbackStop))
    }

    private fun runSpeechTask(
        task: SpeechTask,
        binary: File,
        args: List<String>,
        cwd: File?,
        timeoutMs: Long,
        maxOutputBytes: Int
    ) {
        val cmd = ArrayList<String>(1 + args.size).apply {
            add(binary.absolutePath)
            addAll(args)
        }
        val stdoutBuf = ByteArrayOutputStream()
        val stderrBuf = ByteArrayOutputStream()
        val stdoutOverflow = AtomicBoolean(false)
        val stderrOverflow = AtomicBoolean(false)
        try {
            val pb = ProcessBuilder(cmd)
            if (cwd != null) pb.directory(cwd)
            configureRuntimeEnv(pb, binary)
            val process = pb.start()
            task.process = process

            val tOut = streamPump(process.inputStream, stdoutBuf, maxOutputBytes, stdoutOverflow)
            val tErr = streamPump(process.errorStream, stderrBuf, maxOutputBytes, stderrOverflow)
            runCatching { process.outputStream.close() }

            val playback = wavPlayer.playGrowingWav(
                file = task.outputFile,
                producerAlive = { process.isAlive },
                stopRequested = task.stopRequested
            )
            task.playback = playback

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                task.processTimedOut = true
                runCatching { process.destroy() }
                runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }
                runCatching { process.destroyForcibly() }
            }
            tOut.join(1500)
            tErr.join(1500)
            task.processExitCode = if (finished) process.exitValue() else null
            task.stdout = stdoutBuf.toString(StandardCharsets.UTF_8.name())
            task.stderr = stderrBuf.toString(StandardCharsets.UTF_8.name())
            if (task.outputFile.exists()) {
                task.outputWav = parseWavSummary(task.outputFile)
            }
            val validation = if (finished && task.processExitCode == 0) {
                validateSpeechOutput(task.outputFile, task.minOutputDurationMs)
            } else {
                null
            }
            task.validationError = validation
            task.status = if (task.stopRequested.get()) {
                "stopped"
            } else if (finished && task.processExitCode == 0 && validation == null) {
                "done"
            } else {
                "error"
            }
        } catch (ex: Exception) {
            task.status = if (task.stopRequested.get()) "stopped" else "error"
            task.stderr = (task.stderr + "\n" + (ex.message ?: ex.javaClass.simpleName)).trim()
        } finally {
            task.finishedAt = System.currentTimeMillis()
            task.process = null
            if (task.stdout.isNotEmpty() && stdoutOverflow.get()) task.stdout += "\n[truncated]"
            if (task.stderr.isNotEmpty() && stderrOverflow.get()) task.stderr += "\n[truncated]"
        }
    }

    private fun speechTaskToJson(task: SpeechTask): JSONObject {
        val out = JSONObject()
            .put("speech_id", task.id)
            .put("status", task.status)
            .put("started_at", task.startedAt)
            .put("finished_at", task.finishedAt ?: JSONObject.NULL)
            .put("timed_out", task.processTimedOut)
            .put("exit_code", task.processExitCode ?: JSONObject.NULL)
            .put("output_path", relativizeToUser(task.outputFile))
            .put("output_abs_path", task.outputFile.absolutePath)
            .put("output_exists", task.outputFile.exists())
            .put("output_bytes", if (task.outputFile.exists()) task.outputFile.length() else 0L)
            .put("stdout", task.stdout)
            .put("stderr", task.stderr)
        task.outputWav?.let { out.put("output_wav", JSONObject(it)) }
        task.validationError?.let { out.put("validation", JSONObject(it)) }
        val playback = task.playback
        if (playback != null) out.put("playback", JSONObject(playback))
        return out
    }

    private fun exec(
        binary: File,
        args: List<String>,
        cwd: File?,
        stdin: String?,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): Map<String, Any?> {
        val cmd = ArrayList<String>(1 + args.size).apply {
            add(binary.absolutePath)
            addAll(args)
        }
        val startedAt = System.currentTimeMillis()
        return try {
            val pb = ProcessBuilder(cmd)
            if (cwd != null) pb.directory(cwd)
            configureRuntimeEnv(pb, binary)
            val process = pb.start()

            val stdoutBuf = ByteArrayOutputStream()
            val stderrBuf = ByteArrayOutputStream()
            val stdoutOverflow = AtomicBoolean(false)
            val stderrOverflow = AtomicBoolean(false)

            val tOut = streamPump(process.inputStream, stdoutBuf, maxOutputBytes, stdoutOverflow)
            val tErr = streamPump(process.errorStream, stderrBuf, maxOutputBytes, stderrOverflow)

            if (stdin != null) {
                process.outputStream.use { os ->
                    os.write(stdin.toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                }
            } else {
                runCatching { process.outputStream.close() }
            }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroy() }
                runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }
                runCatching { process.destroyForcibly() }
            }

            tOut.join(1500)
            tErr.join(1500)

            val elapsed = System.currentTimeMillis() - startedAt
            val stdout = stdoutBuf.toString(StandardCharsets.UTF_8.name())
            val stderr = stderrBuf.toString(StandardCharsets.UTF_8.name())
            val exitCode = if (finished) process.exitValue() else null
            val status = if (finished && exitCode == 0) "ok" else "error"
            val out = LinkedHashMap<String, Any?>()
            out["status"] = status
            out["timed_out"] = !finished
            out["exit_code"] = exitCode
            out["elapsed_ms"] = elapsed
            out["command"] = cmd
            out["stdout"] = stdout
            out["stderr"] = stderr
            out["stdout_truncated"] = stdoutOverflow.get()
            out["stderr_truncated"] = stderrOverflow.get()
            if (status == "error" && !finished) out["error"] = "timeout"
            else if (status == "error") out["error"] = "process_failed"
            out
        } catch (ex: Exception) {
            mapOf(
                "status" to "error",
                "error" to "exec_failed",
                "detail" to (ex.message ?: ex.javaClass.simpleName),
                "command" to cmd
            )
        }
    }

    private fun streamPump(
        input: InputStream,
        out: ByteArrayOutputStream,
        limitBytes: Int,
        overflow: AtomicBoolean
    ): Thread {
        return Thread {
            input.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    val remaining = limitBytes - out.size()
                    if (remaining > 0) {
                        val toWrite = if (n > remaining) remaining else n
                        out.write(buf, 0, toWrite)
                    }
                    if (out.size() >= limitBytes) overflow.set(true)
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun resolveBinary(spec: String, defaultName: String): File? {
        if (spec.isNotBlank()) {
            val direct = resolvePath(spec)
            if (isRunnable(direct)) return direct
            val byName = findByName(spec)
            if (isRunnable(byName)) return byName
        }
        return findByName(defaultName).takeIf { isRunnable(it) }
    }

    private fun configureRuntimeEnv(pb: ProcessBuilder, binary: File) {
        val env = pb.environment()
        val pathSep = File.pathSeparator
        val extra = linkedSetOf(
            binary.parentFile?.absolutePath,
            context.applicationInfo.nativeLibraryDir
        ).filterNotNull().filter { it.isNotBlank() }
        if (extra.isEmpty()) return

        val merged = ArrayList<String>()
        merged.addAll(extra)
        val existing = (env["LD_LIBRARY_PATH"] ?: "").trim()
        if (existing.isNotEmpty()) merged.add(existing)
        env["LD_LIBRARY_PATH"] = merged.joinToString(pathSep)

        // llama-tts may use XDG/HF caches; ensure they point to writable app user paths.
        val userHome = userRoot.absolutePath
        val cacheRoot = File(userRoot, ".cache")
        val hfRoot = File(cacheRoot, "hf")
        runCatching { cacheRoot.mkdirs() }
        runCatching { hfRoot.mkdirs() }
        env["HOME"] = userHome
        env["XDG_CACHE_HOME"] = cacheRoot.absolutePath
        env["HF_HOME"] = hfRoot.absolutePath
    }

    private fun findByName(name: String): File? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val alt = linkedSetOf(
            clean,
            clean.lowercase(Locale.US),
            clean.replace('_', '-'),
            clean.replace('-', '_'),
            "lib$clean.so",
            "lib${clean.replace('-', '_')}.so",
            "lib${clean.replace('_', '-')}.so",
        )
        val searchDirs = listOf(
            File(context.applicationInfo.nativeLibraryDir),
            File(filesRoot, "bin"),
            File(userRoot, "bin"),
        )
        for (dir in searchDirs) {
            for (n in alt) {
                val f = File(dir, n)
                if (f.exists() && f.isFile) return f
            }
        }
        return null
    }

    private fun isRunnable(file: File?): Boolean {
        if (file == null || !file.exists() || !file.isFile) return false
        if (file.canExecute()) return true
        runCatching { file.setExecutable(true, true) }
        return file.canExecute()
    }

    private fun resolveModelFile(payload: JSONObject): File? {
        val modelPath = payload.optString("model_path", "").trim()
        if (modelPath.isNotBlank()) {
            return resolvePath(modelPath).takeIf { it.exists() && it.isFile }
        }
        val model = payload.optString("model", "").trim()
        if (model.isBlank()) return null
        val direct = resolvePath(model)
        if (direct.exists() && direct.isFile) return direct

        val roots = readRoots(payload.optJSONArray("model_roots"))
        val lower = model.lowercase(Locale.US)
        for (item in listModelsInternal(roots, maxDepth = 6, limit = 5000)) {
            val p = (item["path"] as? String) ?: continue
            val n = (item["name"] as? String) ?: continue
            if (n.lowercase(Locale.US) == lower || p.lowercase(Locale.US).endsWith("/$lower")) {
                val f = File(p)
                if (f.exists() && f.isFile) return f
            }
        }
        return null
    }

    private fun listModelsInternal(roots: List<File>, maxDepth: Int, limit: Int): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        val queue = ArrayDeque<Pair<File, Int>>()
        for (r in roots) {
            if (r.exists() && r.isDirectory) queue.add(r to 0)
        }
        while (queue.isNotEmpty() && out.size < limit) {
            val (dir, depth) = queue.removeFirst()
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (f.isFile && f.name.lowercase(Locale.US).endsWith(".gguf")) {
                    out.add(
                        mapOf(
                            "name" to f.name,
                            "path" to f.absolutePath,
                            "size" to f.length(),
                            "mtime_ms" to f.lastModified()
                        )
                    )
                    if (out.size >= limit) break
                } else if (f.isDirectory && depth < maxDepth) {
                    queue.add(f to (depth + 1))
                }
            }
        }
        return out.sortedByDescending { (it["mtime_ms"] as? Long) ?: 0L }
    }

    private fun readRoots(arr: JSONArray?): List<File> {
        val roots = ArrayList<File>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val raw = arr.optString(i, "").trim()
                if (raw.isNotEmpty()) roots.add(resolvePath(raw))
            }
        }
        if (roots.isEmpty()) roots.addAll(defaultModelRoots)
        return roots
    }

    private fun resolvePath(raw: String): File {
        val t = raw.trim()
        if (t.isEmpty()) return userRoot
        val f = File(t)
        if (f.isAbsolute) return f
        return File(userRoot, t)
    }

    private fun resolveCwd(raw: String): File? {
        val t = raw.trim()
        if (t.isEmpty()) return userRoot
        val f = resolvePath(t)
        return if (f.exists() && f.isDirectory) f else null
    }

    private fun resolveUserPath(raw: String): File {
        val f = File(raw)
        return if (f.isAbsolute) f else File(userRoot, raw)
    }

    private fun relativizeToUser(file: File): String {
        return runCatching { userRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/') }
            .getOrDefault(file.absolutePath)
    }

    private fun jsonArrayToStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            out.add(arr.opt(i)?.toString() ?: "")
        }
        return out
    }

    private data class ResolvedTtsPayload(
        val payload: JSONObject,
        val templateVars: Map<String, String>,
        val pluginId: String? = null,
        val error: Map<String, Any?>? = null,
    )

    private fun resolveTtsPayload(payload: JSONObject): ResolvedTtsPayload {
        val pluginIdRaw = payload.optString("plugin_id", "").trim()
        val pluginId = when {
            pluginIdRaw.isNotBlank() -> pluginIdRaw
            payload.optBoolean("use_default_plugin", false) -> loadTtsPluginDoc().optString("default_plugin_id", "").trim()
            else -> ""
        }
        if (pluginId.isBlank()) {
            return ResolvedTtsPayload(
                payload = JSONObject(payload.toString()),
                templateVars = parseTemplateVars(payload.optJSONObject("vars")),
            )
        }

        val doc = loadTtsPluginDoc()
        val plugin = doc.optJSONObject("plugins")?.optJSONObject(pluginId)
            ?: return ResolvedTtsPayload(
                payload = JSONObject(),
                templateVars = emptyMap(),
                pluginId = pluginId,
                error = mapOf("status" to "error", "error" to "plugin_not_found", "plugin_id" to pluginId)
            )

        val out = JSONObject()
        val preset = plugin.optJSONObject("preset")
        if (preset != null) {
            val keys = preset.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out.put(k, preset.get(k))
            }
        }

        val inputKeys = payload.keys()
        while (inputKeys.hasNext()) {
            val k = inputKeys.next()
            out.put(k, payload.get(k))
        }

        if (!out.has("args")) out.put("args", plugin.optJSONArray("args") ?: JSONArray())

        val vars = LinkedHashMap<String, String>()
        vars.putAll(parseTemplateVars(plugin.optJSONObject("defaults")))
        vars.putAll(parseTemplateVars(payload.optJSONObject("vars")))
        return ResolvedTtsPayload(payload = out, templateVars = vars, pluginId = pluginId)
    }

    private fun parseTemplateVars(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.opt(k)?.toString() ?: ""
        }
        return out
    }

    private fun renderTemplateArgs(tokens: List<String>, substitutions: Map<String, String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        val exact = Regex("^\\{\\{([A-Za-z0-9_.:-]+)}}$")
        for (raw in tokens) {
            var token = raw
            val m = exact.matchEntire(token)
            if (m != null) {
                val key = m.groupValues[1]
                token = substitutions[key] ?: ""
            } else {
                for ((k, v) in substitutions) {
                    token = token.replace("{{${k}}}", v)
                }
            }
            if (token.isNotEmpty()) out.add(token)
        }
        return out
    }

    private fun loadTtsPluginDoc(): JSONObject {
        return runCatching {
            if (!ttsPluginFile.exists()) return@runCatching JSONObject().put("version", 1).put("plugins", JSONObject())
            val text = ttsPluginFile.readText(StandardCharsets.UTF_8)
            val parsed = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (!parsed.has("version")) parsed.put("version", 1)
            if (!parsed.has("plugins")) parsed.put("plugins", JSONObject())
            parsed
        }.getOrElse {
            JSONObject().put("version", 1).put("plugins", JSONObject())
        }
    }

    private fun saveTtsPluginDoc(doc: JSONObject) {
        runCatching {
            ttsPluginFile.parentFile?.mkdirs()
            ttsPluginFile.writeText(doc.toString(2), StandardCharsets.UTF_8)
        }
    }

    private fun attachOutputFileInfo(out: MutableMap<String, Any?>, outputFile: File) {
        out["output_path"] = relativizeToUser(outputFile)
        out["output_abs_path"] = outputFile.absolutePath
        out["output_bytes"] = outputFile.length()
        parseWavSummary(outputFile)?.let { out["output_wav"] = JSONObject(it) }
    }

    private fun validateSpeechOutput(outputFile: File, minOutputDurationMs: Long): Map<String, Any?>? {
        if (!outputFile.exists()) {
            return mapOf(
                "error" to "output_missing",
                "detail" to "TTS finished but no output WAV file was created."
            )
        }
        if (minOutputDurationMs <= 0L) return null
        val wav = parseWavSummary(outputFile) ?: return null
        val durationMs = (wav["duration_ms"] as? Long) ?: return null
        if (durationMs >= minOutputDurationMs) return null
        return mapOf(
            "error" to "output_too_short",
            "detail" to "Generated WAV is too short (${durationMs}ms < ${minOutputDurationMs}ms). Check model/vocoder args.",
            "duration_ms" to durationMs,
            "min_duration_ms" to minOutputDurationMs
        )
    }

    private fun parseWavSummary(file: File): Map<String, Any?>? {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 44L) return null
                val riff = ByteArray(4)
                raf.readFully(riff)
                if (String(riff, StandardCharsets.US_ASCII) != "RIFF") return null
                raf.skipBytes(4)
                val wave = ByteArray(4)
                raf.readFully(wave)
                if (String(wave, StandardCharsets.US_ASCII) != "WAVE") return null

                var audioFormat = 0
                var channels = 0
                var sampleRate = 0
                var bitsPerSample = 0
                var dataOffset = -1L
                var dataSize = -1L

                while (raf.filePointer + 8L <= raf.length()) {
                    val idBytes = ByteArray(4)
                    raf.readFully(idBytes)
                    val chunkId = String(idBytes, StandardCharsets.US_ASCII)
                    val chunkSize = readU32LE(raf)
                    val chunkPos = raf.filePointer
                    when (chunkId) {
                        "fmt " -> {
                            if (chunkSize >= 16 && chunkPos + chunkSize <= raf.length()) {
                                audioFormat = readU16LE(raf)
                                channels = readU16LE(raf)
                                sampleRate = readU32LE(raf).toInt()
                                raf.skipBytes(6)
                                bitsPerSample = readU16LE(raf)
                            }
                        }
                        "data" -> {
                            dataOffset = chunkPos
                            dataSize = chunkSize
                            break
                        }
                    }
                    raf.seek(chunkPos + chunkSize + (chunkSize % 2))
                }
                if (channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0 || dataOffset < 0L || dataSize < 0L) return null
                val bytesPerFrame = (channels * bitsPerSample) / 8
                if (bytesPerFrame <= 0) return null
                val frames = dataSize / bytesPerFrame
                val durationMs = (frames * 1000L) / sampleRate.toLong().coerceAtLeast(1L)
                mapOf(
                    "audio_format" to audioFormat,
                    "channels" to channels,
                    "sample_rate" to sampleRate,
                    "bits_per_sample" to bitsPerSample,
                    "data_offset" to dataOffset,
                    "data_size" to dataSize,
                    "frames" to frames,
                    "duration_ms" to durationMs
                )
            }
        }.getOrNull()
    }

    private fun readU16LE(raf: RandomAccessFile): Int {
        val b = ByteArray(2)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    }

    private fun readU32LE(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }

    private fun pathOrNull(file: File?): String? = file?.absolutePath
}
