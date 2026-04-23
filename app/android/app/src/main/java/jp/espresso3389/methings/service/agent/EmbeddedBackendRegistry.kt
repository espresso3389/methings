package jp.espresso3389.methings.service.agent

import android.content.Context
import android.content.ComponentCallbacks2
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class EmbeddedGenerationRequest(
    val systemPrompt: String,
    val input: JSONArray,
    val tools: JSONArray,
    val requireTool: Boolean,
)

data class EmbeddedGenerationResult(
    val messageTexts: List<String>,
    val calls: JSONArray,
    val rawText: String,
)

data class EmbeddedTurnDiagnostics(
    val lastPhase: String,
    val selectedTools: List<String>,
    val failedTools: List<String>,
    val repairUsed: Boolean,
    val fallbackUsed: Boolean,
    val lastSummary: String,
    val updatedAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("last_phase", lastPhase)
        put("selected_tools", JSONArray(selectedTools))
        put("failed_tools", JSONArray(failedTools))
        put("repair_used", repairUsed)
        put("fallback_used", fallbackUsed)
        put("last_summary", lastSummary)
        put("updated_at_ms", updatedAtMs)
    }
}

internal data class EmbeddedToolSpec(
    val name: String,
    val description: String,
    val allowedArgumentNames: Set<String>,
    val requiredArgumentNames: Set<String>,
    val enumStringValues: Map<String, Set<String>>,
    val allowAdditionalProperties: Boolean,
)

data class EmbeddedBackendStatus(
    val model: EmbeddedModelSpec,
    val backendId: String,
    val installed: Boolean,
    val runnable: Boolean,
    val loaded: Boolean,
    val warm: Boolean,
    val detail: String,
    val primaryModelPath: String,
    val candidatePaths: List<String>,
    val lastError: String,
    val lastLoadedAtMs: Long,
    val lastUsedAtMs: Long,
    val lastTurnDiagnostics: EmbeddedTurnDiagnostics? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("model", model.id)
        put("label", model.label)
        put("backend", backendId)
        put("installed", installed)
        put("runnable", runnable)
        put("loaded", loaded)
        put("warm", warm)
        put("detail", detail)
        put("primary_model_path", primaryModelPath)
        put("candidate_paths", JSONArray(candidatePaths))
        put("last_error", lastError)
        put("last_loaded_at_ms", lastLoadedAtMs)
        put("last_used_at_ms", lastUsedAtMs)
        if (lastTurnDiagnostics != null) put("last_turn_diagnostics", lastTurnDiagnostics.toJson())
        put("supports_tool_calling", model.supportsToolCalling)
        put("supports_image_input", model.supportsImageInput)
        put("supports_audio_input", model.supportsAudioInput)
    }
}

interface EmbeddedBackend {
    val backendId: String
    fun status(spec: EmbeddedModelSpec): EmbeddedBackendStatus
    fun generateText(spec: EmbeddedModelSpec, prompt: String): String
    fun generateTurn(spec: EmbeddedModelSpec, request: EmbeddedGenerationRequest): EmbeddedGenerationResult
    fun warm(spec: EmbeddedModelSpec): EmbeddedBackendStatus
    fun unload(spec: EmbeddedModelSpec): Boolean
    fun unloadAll()
}

class EmbeddedBackendRegistry(
    context: Context,
    private val backendOverride: List<EmbeddedBackend>? = null,
) {
    private val backends: List<EmbeddedBackend>

    init {
        backends = backendOverride ?: run {
            val modelManager = EmbeddedModelManager(context)
            val appContext = context.applicationContext
            listOf(
                LiteRtBundleEmbeddedBackend(appContext, modelManager),
            )
        }
        require(backends.isNotEmpty()) { "embedded_backends_required" }
    }

    fun statusFor(modelId: String): EmbeddedBackendStatus? {
        val spec = EmbeddedModelCatalog.find(modelId) ?: return null
        return backends.first().status(spec)
    }

    fun generateText(modelId: String, prompt: String): String {
        val spec = EmbeddedModelCatalog.find(modelId)
            ?: throw IllegalArgumentException("unknown_embedded_model")
        return backends.first().generateText(spec, prompt)
    }

    fun generateTurn(
        modelId: String,
        request: EmbeddedGenerationRequest,
    ): EmbeddedGenerationResult {
        val spec = EmbeddedModelCatalog.find(modelId)
            ?: throw IllegalArgumentException("unknown_embedded_model")
        return backends.first().generateTurn(spec, request)
    }

    fun warm(modelId: String): EmbeddedBackendStatus {
        val spec = EmbeddedModelCatalog.find(modelId)
            ?: throw IllegalArgumentException("unknown_embedded_model")
        return backends.first().warm(spec)
    }

    fun unload(modelId: String): Boolean {
        val spec = EmbeddedModelCatalog.find(modelId)
            ?: throw IllegalArgumentException("unknown_embedded_model")
        return backends.first().unload(spec)
    }

    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            backends.forEach { it.unloadAll() }
        }
    }
}

private class LiteRtBundleEmbeddedBackend(
    private val context: Context,
    private val modelManager: EmbeddedModelManager,
) : EmbeddedBackend {
    override val backendId: String = "litert_bundle"
    private val tag = "EmbeddedBackend"
    private data class LoadedInference(
        val modelPath: String,
        val inference: LlmInference,
        val loadedAtMs: Long,
        val lastUsedAtMs: AtomicLong = AtomicLong(loadedAtMs),
        @Volatile var warmed: Boolean = false,
        @Volatile var lastError: String = "",
    )

    private val loaded = ConcurrentHashMap<String, LoadedInference>()
    private val diagnostics = ConcurrentHashMap<String, EmbeddedTurnDiagnostics>()

    override fun status(spec: EmbeddedModelSpec): EmbeddedBackendStatus {
        val resolved = modelManager.resolve(spec.id)
        val installed = resolved.primaryFile != null
        val runtimeAvailable = isRuntimeAvailable()
        val cacheKey = spec.id.trim().lowercase()
        val cached = loaded[cacheKey]
        val detail = if (installed) {
            if (runtimeAvailable) {
                if (cached != null) {
                    "Model bundle found. MediaPipe LLM Inference runtime is available and cached in memory."
                } else {
                    "Model bundle found. MediaPipe LLM Inference runtime is available."
                }
            } else {
                "Model bundle found, but MediaPipe LLM Inference runtime is not linked in this build."
            }
        } else {
            "Model bundle not found. Place a .litertlm, .task, .tflite, or model.bin under the embedded model directory."
        }
        return EmbeddedBackendStatus(
            model = spec,
            backendId = backendId,
            installed = installed,
            runnable = installed && runtimeAvailable,
            loaded = cached != null,
            warm = cached?.warmed == true,
            detail = detail,
            primaryModelPath = resolved.primaryFile?.absolutePath ?: resolved.defaultPrimaryPath.absolutePath,
            candidatePaths = resolved.candidateFiles.map { it.absolutePath },
            lastError = cached?.lastError ?: "",
            lastLoadedAtMs = cached?.loadedAtMs ?: 0L,
            lastUsedAtMs = cached?.lastUsedAtMs?.get() ?: 0L,
            lastTurnDiagnostics = diagnostics[cacheKey],
        )
    }

    override fun generateText(spec: EmbeddedModelSpec, prompt: String): String {
        val instance = ensureLoaded(spec)
        synchronized(instance) {
            instance.lastUsedAtMs.set(System.currentTimeMillis())
            return try {
                instance.inference.generateResponse(prompt).trim()
            } catch (ex: Exception) {
                instance.lastError = ex.message ?: ex.javaClass.simpleName
                runCatching { unload(spec) }
                throw ex
            }
        }
    }

    override fun generateTurn(
        spec: EmbeddedModelSpec,
        request: EmbeddedGenerationRequest,
    ): EmbeddedGenerationResult {
        val toolSpecs = EmbeddedTurnProtocol.extractToolSpecs(request.tools)
        val planPrompt = EmbeddedTurnProtocol.renderPlanPrompt(
            systemPrompt = request.systemPrompt,
            input = request.input,
            tools = request.tools,
            requireTool = request.requireTool,
        )
        val rawPlan = generateText(spec, planPrompt).trim()
        val plan = EmbeddedTurnProtocol.parsePlanResponse(rawPlan, toolSpecs)
        updateDiagnostics(
            spec = spec,
            phase = "plan",
            selectedTools = EmbeddedTurnProtocol.callNames(plan.calls),
            failedTools = emptyList(),
            repairUsed = false,
            fallbackUsed = false,
            summary = "selected=${plan.calls.length()} text=${plan.messageTexts.size}",
        )
        Log.i(tag, buildString {
            append("embedded_turn phase=plan")
            append(" model=")
            append(spec.id)
            append(" requireTool=")
            append(request.requireTool)
            append(" selectedTools=")
            append(plan.calls.length())
            append(" toolNames=")
            append(EmbeddedTurnProtocol.callNamesSummary(plan.calls))
            append(" textCount=")
            append(plan.messageTexts.size)
        })
        var parsed = if (plan.calls.length() > 0) {
            val argumentPasses = mutableListOf<Pair<String, EmbeddedGenerationResult>>()
            val failedTools = mutableListOf<String>()
            for (i in 0 until plan.calls.length()) {
                val selectedCall = plan.calls.optJSONObject(i) ?: continue
                val toolName = selectedCall.optString("name", "").trim()
                if (toolName.isEmpty()) continue
                val rawArgs = runCatching {
                    generateText(
                        spec,
                        EmbeddedTurnProtocol.renderSingleArgumentPrompt(
                            rawPlanText = rawPlan,
                            toolSpecs = toolSpecs,
                            toolName = toolName,
                        )
                    ).trim()
                }.getOrDefault("")
                if (rawArgs.isBlank()) {
                    failedTools += toolName
                    updateDiagnostics(
                        spec = spec,
                        phase = "arguments",
                        selectedTools = listOf(toolName),
                        failedTools = listOf(toolName),
                        repairUsed = false,
                        fallbackUsed = false,
                        summary = "tool=$toolName no_output",
                    )
                    continue
                }
                val parsedArgs = EmbeddedTurnProtocol.parseResponse(rawArgs, toolSpecs)
                val failedForTool = if (parsedArgs.calls.length() == 0) listOf(toolName) else emptyList()
                if (failedForTool.isNotEmpty()) failedTools += toolName
                updateDiagnostics(
                    spec = spec,
                    phase = "arguments",
                    selectedTools = listOf(toolName),
                    failedTools = failedForTool,
                    repairUsed = false,
                    fallbackUsed = false,
                    summary = "tool=$toolName validCalls=${parsedArgs.calls.length()} text=${parsedArgs.messageTexts.size}",
                )
                Log.i(tag, buildString {
                    append("embedded_turn phase=arguments")
                    append(" model=")
                    append(spec.id)
                    append(" tool=")
                    append(toolName)
                    append(" validCalls=")
                    append(parsedArgs.calls.length())
                    append(" textCount=")
                    append(parsedArgs.messageTexts.size)
                })
                argumentPasses += rawArgs to parsedArgs
            }
            if (argumentPasses.isNotEmpty()) {
                EmbeddedTurnProtocol.mergePlanAndArguments(
                    plan = plan,
                    argumentResults = argumentPasses.map { it.second },
                ).copy(rawText = buildString {
                    append(rawPlan)
                    for ((idx, pair) in argumentPasses.withIndex()) {
                        append("\n\n-- arguments ")
                        append(idx + 1)
                        append(" --\n")
                        append(pair.first)
                    }
                }.trim())
            } else {
                plan
            }
        } else {
            plan
        }
        updateDiagnostics(
            spec = spec,
            phase = "merged",
            selectedTools = EmbeddedTurnProtocol.callNames(parsed.calls),
            failedTools = emptyList(),
            repairUsed = false,
            fallbackUsed = false,
            summary = "calls=${parsed.calls.length()} text=${parsed.messageTexts.size}",
        )
        Log.i(tag, buildString {
            append("embedded_turn phase=merged")
            append(" model=")
            append(spec.id)
            append(" finalCalls=")
            append(parsed.calls.length())
            append(" toolNames=")
            append(EmbeddedTurnProtocol.callNamesSummary(parsed.calls))
            append(" textCount=")
            append(parsed.messageTexts.size)
        })
        if (EmbeddedTurnProtocol.needsRepair(parsed, request.requireTool, toolSpecs)) {
            updateDiagnostics(
                spec = spec,
                phase = "repair_needed",
                selectedTools = EmbeddedTurnProtocol.callNames(parsed.calls),
                failedTools = emptyList(),
                repairUsed = true,
                fallbackUsed = false,
                summary = "calls=${parsed.calls.length()} text=${parsed.messageTexts.size}",
            )
            Log.w(tag, buildString {
                append("embedded_turn phase=repair_needed")
                append(" model=")
                append(spec.id)
                append(" requireTool=")
                append(request.requireTool)
                append(" calls=")
                append(parsed.calls.length())
                append(" textCount=")
                append(parsed.messageTexts.size)
            })
            val repairedRaw = runCatching {
                generateText(spec, EmbeddedTurnProtocol.renderRepairPrompt(parsed.rawText, toolSpecs, request.requireTool)).trim()
            }.getOrDefault("")
            if (repairedRaw.isNotBlank()) {
                val repaired = EmbeddedTurnProtocol.parseResponse(repairedRaw, toolSpecs)
                updateDiagnostics(
                    spec = spec,
                    phase = "repair_result",
                    selectedTools = EmbeddedTurnProtocol.callNames(repaired.calls),
                    failedTools = emptyList(),
                    repairUsed = true,
                    fallbackUsed = false,
                    summary = "calls=${repaired.calls.length()} text=${repaired.messageTexts.size}",
                )
                Log.i(tag, buildString {
                    append("embedded_turn phase=repair_result")
                    append(" model=")
                    append(spec.id)
                    append(" calls=")
                    append(repaired.calls.length())
                    append(" toolNames=")
                    append(EmbeddedTurnProtocol.callNamesSummary(repaired.calls))
                    append(" textCount=")
                    append(repaired.messageTexts.size)
                })
                if (!EmbeddedTurnProtocol.needsRepair(repaired, request.requireTool, toolSpecs)) {
                    return repaired.copy(rawText = repairedRaw)
                }
            }
            if (request.requireTool) {
                updateDiagnostics(
                    spec = spec,
                    phase = "fallback",
                    selectedTools = toolSpecs.map { it.name },
                    failedTools = toolSpecs.map { it.name },
                    repairUsed = true,
                    fallbackUsed = true,
                    summary = "required-tool fallback",
                )
                Log.w(tag, buildString {
                    append("embedded_turn phase=fallback")
                    append(" model=")
                    append(spec.id)
                    append(" selectedTools=")
                    append(toolSpecs.joinToString(",") { it.name })
                })
                return EmbeddedTurnProtocol.buildRequiredToolFallback(
                    originalText = parsed.rawText,
                    repairedText = repairedRaw,
                    toolSpecs = toolSpecs,
                )
            }
        }
        return parsed
    }

    override fun warm(spec: EmbeddedModelSpec): EmbeddedBackendStatus {
        val instance = ensureLoaded(spec)
        synchronized(instance) {
            if (!instance.warmed) {
                runCatching {
                    instance.lastUsedAtMs.set(System.currentTimeMillis())
                    instance.inference.generateResponse("Reply with OK.")
                    instance.warmed = true
                    instance.lastError = ""
                }.onFailure { ex ->
                    instance.lastError = ex.message ?: ex.javaClass.simpleName
                    runCatching { unload(spec) }
                    throw ex
                }
            }
        }
        return status(spec)
    }

    override fun unload(spec: EmbeddedModelSpec): Boolean {
        val key = spec.id.trim().lowercase()
        val existing = loaded.remove(key) ?: return false
        runCatching {
            existing.inference.javaClass.methods.firstOrNull { it.name == "close" && it.parameterCount == 0 }
                ?.invoke(existing.inference)
        }
        return true
    }

    override fun unloadAll() {
        val keys = loaded.keys().toList()
        for (key in keys) {
            val spec = EmbeddedModelCatalog.find(key) ?: continue
            unload(spec)
        }
    }

    private fun isRuntimeAvailable(): Boolean {
        return runCatching {
            Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
            true
        }.getOrDefault(false)
    }

    private fun ensureLoaded(spec: EmbeddedModelSpec): LoadedInference {
        val key = spec.id.trim().lowercase()
        loaded[key]?.let { return it }
        synchronized(this) {
            loaded[key]?.let { return it }
            val resolved = modelManager.resolve(spec.id)
            val modelFile = resolved.primaryFile ?: throw IllegalStateException("embedded_model_not_installed")
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(1024)
                .build()
            val inference = LlmInference.createFromOptions(context, options)
            val now = System.currentTimeMillis()
            val created = LoadedInference(
                modelPath = modelFile.absolutePath,
                inference = inference,
                loadedAtMs = now,
            )
            loaded[key] = created
            Log.i(tag, "Loaded embedded model ${spec.id} from ${modelFile.absolutePath}")
            return created
        }
    }

    private fun updateDiagnostics(
        spec: EmbeddedModelSpec,
        phase: String,
        selectedTools: List<String>,
        failedTools: List<String>,
        repairUsed: Boolean,
        fallbackUsed: Boolean,
        summary: String,
    ) {
        diagnostics[spec.id.trim().lowercase()] = EmbeddedTurnDiagnostics(
            lastPhase = phase,
            selectedTools = selectedTools,
            failedTools = failedTools,
            repairUsed = repairUsed,
            fallbackUsed = fallbackUsed,
            lastSummary = summary,
            updatedAtMs = System.currentTimeMillis(),
        )
    }
}

internal object EmbeddedTurnProtocol {
    fun callNames(calls: JSONArray): List<String> {
        val names = mutableListOf<String>()
        for (i in 0 until calls.length()) {
            val call = calls.optJSONObject(i) ?: continue
            val name = call.optString("name", "").trim()
            if (name.isNotEmpty()) names += name
        }
        return names
    }

    fun callNamesSummary(calls: JSONArray): String {
        val names = callNames(calls)
        return if (names.isEmpty()) "none" else names.joinToString(",")
    }

    fun extractToolSpecs(tools: JSONArray): List<EmbeddedToolSpec> {
        val specs = mutableListOf<EmbeddedToolSpec>()
        for (i in 0 until tools.length()) {
            val wrapper = tools.optJSONObject(i) ?: continue
            val fn = wrapper.optJSONObject("function") ?: wrapper
            val name = fn.optString("name", "").trim()
            if (name.isEmpty()) continue
            val params = fn.optJSONObject("parameters")
            val properties = params?.optJSONObject("properties")
            val allowedArgumentNames = mutableSetOf<String>()
            val enumStringValues = linkedMapOf<String, Set<String>>()
            if (properties != null) {
                val keys = properties.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    allowedArgumentNames.add(key)
                    val prop = properties.optJSONObject(key)
                    val enumArr = prop?.optJSONArray("enum")
                    if (enumArr != null) {
                        val values = mutableSetOf<String>()
                        for (j in 0 until enumArr.length()) {
                            val value = enumArr.optString(j, "").trim()
                            if (value.isNotEmpty()) values.add(value)
                        }
                        if (values.isNotEmpty()) enumStringValues[key] = values
                    }
                }
            }
            val requiredArgumentNames = mutableSetOf<String>()
            val required = params?.optJSONArray("required")
            if (required != null) {
                for (j in 0 until required.length()) {
                    val key = required.optString(j, "").trim()
                    if (key.isNotEmpty()) requiredArgumentNames.add(key)
                }
            }
            specs.add(
                EmbeddedToolSpec(
                    name = name,
                    description = fn.optString("description", "").trim(),
                    allowedArgumentNames = allowedArgumentNames,
                    requiredArgumentNames = requiredArgumentNames,
                    enumStringValues = enumStringValues,
                    allowAdditionalProperties = params?.optBoolean("additionalProperties", false) ?: false,
                )
            )
        }
        return specs
    }

    fun renderPlanPrompt(
        systemPrompt: String,
        input: JSONArray,
        tools: JSONArray,
        requireTool: Boolean,
    ): String {
        val base = renderConversation(systemPrompt, input)
        val toolGuide = summarizeTools(tools)
        val toolRule = if (tools.length() == 0) {
            "Tool calls are disabled for this turn. Return only the final answer."
        } else if (requireTool) {
            "You MUST call one or more tools unless a hard blocker prevents progress."
        } else {
            "Call tools when they are needed to complete the task. Otherwise answer directly."
        }
        return buildString {
            append(base)
            append("\n\nAvailable tools:\n")
            append(toolGuide)
            append("\n\nPlanning output contract:\n")
            append("- Return exactly one JSON object.\n")
            append("- Keys: assistant_message (string), tool_calls (array).\n")
            append("- At this phase, each tool_calls item should contain only: name.\n")
            append("- Do not wrap the JSON in markdown fences.\n")
            append("- If no tool is needed, return an empty tool_calls array.\n")
            append("- ")
            append(toolRule)
        }
    }

    fun renderSingleArgumentPrompt(
        rawPlanText: String,
        toolSpecs: List<EmbeddedToolSpec>,
        toolName: String,
    ): String {
        val selectedSpecLines = toolSpecs.firstOrNull { it.name == toolName }?.let { spec ->
            val required = spec.requiredArgumentNames.sorted().joinToString(", ")
            val allowed = spec.allowedArgumentNames.sorted().joinToString(", ")
            buildString {
                append("- ")
                append(toolName)
                append(": allowed args [")
                append(allowed)
                append("]")
                if (required.isNotEmpty()) {
                    append(", required [")
                    append(required)
                    append("]")
                }
            }
        } ?: run {
            "- none"
        }
        return buildString {
            append("Convert the selected tool names below into exactly one valid JSON object.\n")
            append("Return only JSON. No markdown fences.\n")
            append("Keys: assistant_message (string), tool_calls (array).\n")
            append("Each tool_calls item must have: name (string), arguments (object).\n")
            append("Only include this selected tool:\n")
            append(selectedSpecLines)
            append("\n\nPlanner output:\n")
            append(rawPlanText)
        }
    }

    fun renderRepairPrompt(
        rawText: String,
        toolSpecs: List<EmbeddedToolSpec>,
        requireTool: Boolean,
    ): String {
        val toolLines = if (toolSpecs.isEmpty()) {
            "- none"
        } else {
            toolSpecs.joinToString("\n") { spec -> "- ${spec.name}: ${spec.description}" }
        }
        val toolRule = if (toolSpecs.isEmpty()) {
            "Tool calls are not allowed in this repair task."
        } else if (requireTool) {
            "At least one valid tool call is required unless the text clearly explains a hard blocker."
        } else {
            "Use tool_calls only if the response is actually invoking a listed tool."
        }
        return buildString {
            append("Rewrite the following model output into exactly one valid JSON object.\n")
            append("Return only JSON. No markdown fences.\n")
            append("Allowed keys: assistant_message (string), tool_calls (array).\n")
            append("Each tool_calls item must have: name (string), arguments (object).\n")
            append("Allowed tools:\n")
            append(toolLines)
            append("\n")
            append(toolRule)
            append("\n\nOriginal output:\n")
            append(rawText)
        }
    }

    fun parseResponse(rawText: String, toolSpecs: List<EmbeddedToolSpec> = emptyList()): EmbeddedGenerationResult {
        val parsed = extractJsonObject(rawText)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (parsed == null) {
            return EmbeddedGenerationResult(
                messageTexts = listOf(rawText).filter { it.isNotBlank() },
                calls = JSONArray(),
                rawText = rawText,
            )
        }
        val message = parsed.optString("assistant_message", "").trim()
        val calls = JSONArray()
        val toolSpecByName = toolSpecs.associateBy { it.name }
        val rawCalls = parsed.optJSONArray("tool_calls") ?: JSONArray()
        for (i in 0 until rawCalls.length()) {
            val call = rawCalls.optJSONObject(i) ?: continue
            val name = call.optString("name", "").trim()
            if (name.isEmpty()) continue
            val toolSpec = toolSpecByName[name]
            if (toolSpecs.isNotEmpty() && toolSpec == null) continue
            val rawArguments = call.optJSONObject("arguments") ?: JSONObject()
            val arguments = sanitizeArguments(rawArguments, toolSpec)
            if (toolSpec != null && !hasRequiredArguments(arguments, toolSpec)) continue
            calls.put(JSONObject().apply {
                put("name", name)
                put("arguments", arguments)
                put("call_id", "embedded_${i}_${name}")
            })
        }
        return EmbeddedGenerationResult(
            messageTexts = listOf(message).filter { it.isNotBlank() },
            calls = calls,
            rawText = rawText,
        )
    }

    fun parsePlanResponse(rawText: String, toolSpecs: List<EmbeddedToolSpec>): EmbeddedGenerationResult {
        val parsed = extractJsonObject(rawText)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (parsed == null) {
            return EmbeddedGenerationResult(
                messageTexts = listOf(rawText).filter { it.isNotBlank() },
                calls = JSONArray(),
                rawText = rawText,
            )
        }
        val message = parsed.optString("assistant_message", "").trim()
        val calls = JSONArray()
        val toolSpecByName = toolSpecs.associateBy { it.name }
        val rawCalls = parsed.optJSONArray("tool_calls") ?: JSONArray()
        for (i in 0 until rawCalls.length()) {
            val call = rawCalls.optJSONObject(i) ?: continue
            val name = call.optString("name", "").trim()
            if (name.isEmpty()) continue
            if (!toolSpecByName.containsKey(name)) continue
            calls.put(JSONObject().apply {
                put("name", name)
                put("arguments", JSONObject())
                put("call_id", "embedded_plan_${i}_${name}")
            })
        }
        return EmbeddedGenerationResult(
            messageTexts = listOf(message).filter { it.isNotBlank() },
            calls = calls,
            rawText = rawText,
        )
    }

    fun mergePlanAndArguments(
        plan: EmbeddedGenerationResult,
        argumentResults: List<EmbeddedGenerationResult>,
    ): EmbeddedGenerationResult {
        val argCallsByName = linkedMapOf<String, JSONObject>()
        for (argumentsResult in argumentResults) {
            for (i in 0 until argumentsResult.calls.length()) {
                val call = argumentsResult.calls.optJSONObject(i) ?: continue
                val name = call.optString("name", "").trim()
                if (name.isEmpty()) continue
                argCallsByName.putIfAbsent(name, call.optJSONObject("arguments") ?: JSONObject())
            }
        }
        val mergedCalls = JSONArray()
        for (i in 0 until plan.calls.length()) {
            val call = plan.calls.optJSONObject(i) ?: continue
            val name = call.optString("name", "").trim()
            if (name.isEmpty()) continue
            mergedCalls.put(JSONObject().apply {
                put("name", name)
                put("arguments", argCallsByName[name] ?: JSONObject())
                put("call_id", call.optString("call_id", "embedded_merge_${i}_${name}"))
            })
        }
        return EmbeddedGenerationResult(
            messageTexts = if (plan.messageTexts.isNotEmpty()) {
                plan.messageTexts
            } else {
                argumentResults.firstOrNull { it.messageTexts.isNotEmpty() }?.messageTexts ?: emptyList()
            },
            calls = mergedCalls,
            rawText = argumentResults.joinToString("\n\n") { it.rawText }.trim(),
        )
    }

    fun needsRepair(
        result: EmbeddedGenerationResult,
        requireTool: Boolean,
        toolSpecs: List<EmbeddedToolSpec>,
    ): Boolean {
        if (requireTool && toolSpecs.isNotEmpty() && result.calls.length() == 0) {
            return true
        }
        if (result.messageTexts.isEmpty() && result.calls.length() == 0) {
            return true
        }
        return false
    }

    fun buildRequiredToolFallback(
        originalText: String,
        repairedText: String,
        toolSpecs: List<EmbeddedToolSpec>,
    ): EmbeddedGenerationResult {
        val availableTools = if (toolSpecs.isEmpty()) {
            "none"
        } else {
            toolSpecs.joinToString(", ") { it.name }
        }
        val detailSource = repairedText.ifBlank { originalText }.trim()
        val detail = detailSource.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.take(240)
            .orEmpty()
        val message = buildString {
            append("Embedded backend could not produce a valid tool call for this required-tools turn.")
            append(" Available tools: ")
            append(availableTools)
            append(".")
            if (detail.isNotEmpty()) {
                append(" Last model output: ")
                append(detail)
            }
        }
        val combinedRaw = buildString {
            append(originalText)
            if (repairedText.isNotBlank()) {
                append("\n\n-- repair --\n")
                append(repairedText)
            }
        }.trim()
        return EmbeddedGenerationResult(
            messageTexts = listOf(message),
            calls = JSONArray(),
            rawText = combinedRaw,
        )
    }

    private fun sanitizeArguments(arguments: JSONObject, toolSpec: EmbeddedToolSpec?): JSONObject {
        if (toolSpec == null) return JSONObject(arguments.toString())
        if (toolSpec.allowAdditionalProperties && toolSpec.enumStringValues.isEmpty()) {
            return JSONObject(arguments.toString())
        }
        val sanitized = JSONObject()
        val keys = arguments.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!toolSpec.allowAdditionalProperties && !toolSpec.allowedArgumentNames.contains(key)) continue
            val value = arguments.opt(key)
            val enumValues = toolSpec.enumStringValues[key]
            if (enumValues != null) {
                val stringValue = (value as? String)?.trim().orEmpty()
                if (!enumValues.contains(stringValue)) continue
                sanitized.put(key, stringValue)
                continue
            }
            sanitized.put(key, value)
        }
        return sanitized
    }

    private fun hasRequiredArguments(arguments: JSONObject, toolSpec: EmbeddedToolSpec): Boolean {
        for (key in toolSpec.requiredArgumentNames) {
            if (!arguments.has(key) || arguments.isNull(key)) return false
            if ((arguments.opt(key) as? String)?.trim()?.isEmpty() == true) return false
        }
        return true
    }

    private fun summarizeTools(tools: JSONArray): String {
        if (tools.length() == 0) return "- none"
        val lines = mutableListOf<String>()
        for (i in 0 until tools.length()) {
            val wrapper = tools.optJSONObject(i) ?: continue
            val fn = wrapper.optJSONObject("function") ?: wrapper
            val name = fn.optString("name", "").trim()
            if (name.isEmpty()) continue
            val description = fn.optString("description", "").trim()
            val params = fn.optJSONObject("parameters")
            val properties = params?.optJSONObject("properties")
            val required = params?.optJSONArray("required")
            val argNames = mutableListOf<String>()
            if (properties != null) {
                val keys = properties.keys()
                while (keys.hasNext()) argNames.add(keys.next())
            }
            argNames.sort()
            val requiredSet = mutableSetOf<String>()
            if (required != null) {
                for (j in 0 until required.length()) {
                    val key = required.optString(j, "").trim()
                    if (key.isNotEmpty()) requiredSet.add(key)
                }
            }
            val argsSummary = if (argNames.isEmpty()) {
                "no arguments"
            } else {
                argNames.joinToString(", ") { key ->
                    if (requiredSet.contains(key)) "$key*" else key
                }
            }
            lines.add("- $name($argsSummary): $description")
        }
        return if (lines.isEmpty()) "- none" else lines.joinToString("\n")
    }

    private fun renderConversation(systemPrompt: String, input: JSONArray): String {
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
            sb.append(role.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            })
            sb.append(": ")
            sb.append(text)
            sb.append("\n\n")
        }
        sb.append("Assistant:")
        return sb.toString().trim()
    }

    private fun extractJsonObject(rawText: String): String? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val fenceMatch = Regex("```(?:json)?\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
            .find(trimmed)
        if (fenceMatch != null) return fenceMatch.groupValues[1]
        val first = trimmed.indexOf('{')
        if (first < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in first until trimmed.length) {
            val ch = trimmed[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return trimmed.substring(first, i + 1)
                }
            }
        }
        return null
    }
}

private class EmbeddedModelManager(
    context: Context,
) {
    private val embeddedRoot = File(context.filesDir, "user/models/embedded")

    data class ResolvedModel(
        val rootDir: File,
        val primaryFile: File?,
        val defaultPrimaryPath: File,
        val candidateFiles: List<File>,
    )

    fun resolve(modelId: String): ResolvedModel {
        val modelDir = File(embeddedRoot, modelId.trim().lowercase())
        val candidateFiles = listOf(
            File(modelDir, "model.litertlm"),
            File(modelDir, "model.task"),
            File(modelDir, "model.tflite"),
            File(modelDir, "model.bin"),
        )
        val primary = candidateFiles.firstOrNull { it.isFile }
        return ResolvedModel(
            rootDir = modelDir,
            primaryFile = primary,
            defaultPrimaryPath = candidateFiles.first(),
            candidateFiles = candidateFiles,
        )
    }
}
