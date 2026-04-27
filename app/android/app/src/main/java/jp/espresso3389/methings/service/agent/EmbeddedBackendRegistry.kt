package jp.espresso3389.methings.service.agent

import android.content.Context
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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

data class EmbeddedToolFailure(
    val name: String,
    val reason: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("reason", reason)
    }
}

data class EmbeddedTurnDiagnostics(
    val turnId: Long,
    val lastPhase: String,
    val responseSource: String,
    val finalToolCallCount: Int,
    val finalMessageCount: Int,
    val selectedTools: List<String>,
    val failedTools: List<String>,
    val toolFailures: List<EmbeddedToolFailure>,
    val repairUsed: Boolean,
    val repairAttemptCount: Int,
    val fallbackUsed: Boolean,
    val lastSummary: String,
    val updatedAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("turn_id", turnId)
        put("last_phase", lastPhase)
        put("response_source", responseSource)
        put("final_tool_call_count", finalToolCallCount)
        put("final_message_count", finalMessageCount)
        put("selected_tools", JSONArray(selectedTools))
        put("failed_tools", JSONArray(failedTools))
        put("tool_failures", JSONArray(toolFailures.map { it.toJson() }))
        put("repair_used", repairUsed)
        put("repair_attempt_count", repairAttemptCount)
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

internal data class EmbeddedTurnDiagnosticsState(
    val turnId: Long,
    var responseSource: String = "pending",
    var finalToolCallCount: Int = 0,
    var finalMessageCount: Int = 0,
    val selectedTools: LinkedHashSet<String> = linkedSetOf(),
    val toolFailures: LinkedHashMap<String, String> = linkedMapOf(),
    var repairUsed: Boolean = false,
    var repairAttemptCount: Int = 0,
    var fallbackUsed: Boolean = false,
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
                AiCorePreviewEmbeddedBackend(),
                LiteRtBundleEmbeddedBackend(appContext, modelManager),
            )
        }
        require(backends.isNotEmpty()) { "embedded_backends_required" }
    }

    fun statusFor(modelId: String, preferredBackendId: String = ""): EmbeddedBackendStatus? {
        val spec = EmbeddedModelCatalog.find(modelId) ?: return null
        return selectBackendStatus(spec, preferredBackendId).status
    }

    fun statusesFor(modelId: String): List<EmbeddedBackendStatus>? {
        val spec = EmbeddedModelCatalog.find(modelId) ?: return null
        return backendStatuses(spec).map { it.status }
    }

    fun generateText(modelId: String, prompt: String, preferredBackendId: String = ""): String {
        val spec = EmbeddedModelCatalog.find(modelId)
            ?: throw IllegalArgumentException("unknown_embedded_model")
        return selectBackendStatus(spec, preferredBackendId).backend.generateText(spec, prompt)
    }

    fun generateTurn(
        modelId: String,
        request: EmbeddedGenerationRequest,
        preferredBackendId: String = "",
    ): EmbeddedGenerationResult {
        val spec = EmbeddedModelCatalog.find(modelId)
            ?: throw IllegalArgumentException("unknown_embedded_model")
        return selectBackendStatus(spec, preferredBackendId).backend.generateTurn(spec, request)
    }

    fun warm(modelId: String, preferredBackendId: String = ""): EmbeddedBackendStatus {
        val spec = EmbeddedModelCatalog.find(modelId)
            ?: throw IllegalArgumentException("unknown_embedded_model")
        return selectBackendStatus(spec, preferredBackendId).backend.warm(spec)
    }

    fun unload(modelId: String): Boolean {
        val spec = EmbeddedModelCatalog.find(modelId)
            ?: throw IllegalArgumentException("unknown_embedded_model")
        return backends.any { it.unload(spec) }
    }

    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            backends.forEach { it.unloadAll() }
        }
    }

    private data class BackendStatus(
        val backend: EmbeddedBackend,
        val status: EmbeddedBackendStatus,
    )

    private fun selectBackendStatus(spec: EmbeddedModelSpec, preferredBackendId: String = ""): BackendStatus {
        val statuses = backendStatuses(spec)
        val preferred = preferredBackendId.trim()
        if (preferred.isNotEmpty()) {
            statuses.firstOrNull { it.status.backendId == preferred }?.let { return it }
        }
        return statuses.firstOrNull { it.status.runnable }
            ?: statuses.firstOrNull { it.status.installed }
            ?: statuses.first()
    }

    private fun backendStatuses(spec: EmbeddedModelSpec): List<BackendStatus> {
        return backends.map { backend ->
            BackendStatus(
                backend = backend,
                status = runCatching { backend.status(spec) }.getOrElse { ex ->
                    EmbeddedBackendStatus(
                        model = spec,
                        backendId = backend.backendId,
                        installed = false,
                        runnable = false,
                        loaded = false,
                        warm = false,
                        detail = "Embedded backend status failed: ${ex.message ?: ex.javaClass.simpleName}",
                        primaryModelPath = "",
                        candidatePaths = emptyList(),
                        lastError = ex.message ?: ex.javaClass.simpleName,
                        lastLoadedAtMs = 0L,
                        lastUsedAtMs = 0L,
                    )
                },
            )
        }
    }
}

private abstract class PromptStructuredEmbeddedBackend : EmbeddedBackend {
    protected val tag = "EmbeddedBackend"
    protected val diagnostics = ConcurrentHashMap<String, EmbeddedTurnDiagnostics>()
    private val turnCounter = AtomicLong(0L)

    protected open fun generateTextWithMedia(
        spec: EmbeddedModelSpec,
        prompt: String,
        media: List<ExtractedMedia>,
    ): String {
        return generateText(spec, prompt)
    }

    final override fun generateTurn(
        spec: EmbeddedModelSpec,
        request: EmbeddedGenerationRequest,
    ): EmbeddedGenerationResult {
        val turnDiagnostics = EmbeddedTurnDiagnosticsState(turnId = turnCounter.incrementAndGet())
        val toolSpecs = EmbeddedTurnProtocol.extractToolSpecs(request.tools)
        val planPrompt = EmbeddedTurnProtocol.renderPlanPrompt(
            systemPrompt = request.systemPrompt,
            input = request.input,
            tools = request.tools,
            requireTool = request.requireTool,
        )
        val rawPlan = generateTextWithMedia(
            spec = spec,
            prompt = planPrompt,
            media = EmbeddedTurnProtocol.extractRequestMedia(request.input),
        ).trim()
        val plan = EmbeddedTurnProtocol.parsePlanResponse(rawPlan, toolSpecs)
        updateDiagnostics(
            spec = spec,
            state = turnDiagnostics,
            phase = "plan",
            responseSource = "pending",
            finalToolCallCount = 0,
            finalMessageCount = plan.messageTexts.size,
            selectedTools = EmbeddedTurnProtocol.callNames(plan.calls),
            failedTools = emptyList(),
            toolFailures = emptyList(),
            repairUsed = false,
            repairAttemptCount = 0,
            fallbackUsed = false,
            summary = "selected=${plan.calls.length()} text=${plan.messageTexts.size}",
        )
        Log.i(tag, buildString {
            append("embedded_turn phase=plan")
            append(" backend=")
            append(backendId)
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
        val parsed = if (plan.calls.length() > 0) {
            val argumentPasses = mutableListOf<Pair<String, EmbeddedGenerationResult>>()
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
                    val toolFailures = listOf(EmbeddedToolFailure(toolName, "no_output"))
                    updateDiagnostics(
                        spec = spec,
                        state = turnDiagnostics,
                        phase = "arguments",
                        responseSource = "pending",
                        finalToolCallCount = 0,
                        finalMessageCount = 0,
                        selectedTools = listOf(toolName),
                        failedTools = listOf(toolName),
                        toolFailures = toolFailures,
                        repairUsed = false,
                        repairAttemptCount = 0,
                        fallbackUsed = false,
                        summary = "tool=$toolName no_output",
                    )
                    Log.w(tag, "embedded_turn phase=arguments backend=$backendId model=${spec.id} tool=$toolName failure=no_output")
                    continue
                }
                val parsedArgs = EmbeddedTurnProtocol.parseResponse(rawArgs, toolSpecs)
                val toolFailures = if (parsedArgs.calls.length() == 0) {
                    listOf(EmbeddedToolFailure(toolName, "invalid_arguments"))
                } else {
                    emptyList()
                }
                updateDiagnostics(
                    spec = spec,
                    state = turnDiagnostics,
                    phase = "arguments",
                    responseSource = "pending",
                    finalToolCallCount = 0,
                    finalMessageCount = parsedArgs.messageTexts.size,
                    selectedTools = listOf(toolName),
                    failedTools = toolFailures.map { it.name },
                    toolFailures = toolFailures,
                    repairUsed = false,
                    repairAttemptCount = 0,
                    fallbackUsed = false,
                    summary = "tool=$toolName validCalls=${parsedArgs.calls.length()} text=${parsedArgs.messageTexts.size}",
                )
                if (toolFailures.isEmpty()) {
                    Log.i(tag, "embedded_turn phase=arguments backend=$backendId model=${spec.id} tool=$toolName validCalls=${parsedArgs.calls.length()} textCount=${parsedArgs.messageTexts.size}")
                } else {
                    Log.w(tag, "embedded_turn phase=arguments backend=$backendId model=${spec.id} tool=$toolName failure=invalid_arguments")
                }
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
            state = turnDiagnostics,
            phase = "merged",
            responseSource = "original",
            finalToolCallCount = parsed.calls.length(),
            finalMessageCount = parsed.messageTexts.size,
            selectedTools = EmbeddedTurnProtocol.callNames(parsed.calls),
            failedTools = emptyList(),
            toolFailures = emptyList(),
            repairUsed = false,
            repairAttemptCount = 0,
            fallbackUsed = false,
            summary = "calls=${parsed.calls.length()} text=${parsed.messageTexts.size}",
        )
        Log.i(tag, "embedded_turn phase=merged backend=$backendId model=${spec.id} finalCalls=${parsed.calls.length()} toolNames=${EmbeddedTurnProtocol.callNamesSummary(parsed.calls)} textCount=${parsed.messageTexts.size}")
        if (EmbeddedTurnProtocol.needsRepair(parsed, request.requireTool, toolSpecs)) {
            updateDiagnostics(
                spec = spec,
                state = turnDiagnostics,
                phase = "repair_needed",
                responseSource = "original",
                finalToolCallCount = parsed.calls.length(),
                finalMessageCount = parsed.messageTexts.size,
                selectedTools = EmbeddedTurnProtocol.callNames(parsed.calls),
                failedTools = emptyList(),
                toolFailures = emptyList(),
                repairUsed = true,
                repairAttemptCount = 0,
                fallbackUsed = false,
                summary = "calls=${parsed.calls.length()} text=${parsed.messageTexts.size}",
            )
            Log.w(tag, "embedded_turn phase=repair_needed backend=$backendId model=${spec.id} requireTool=${request.requireTool} calls=${parsed.calls.length()} textCount=${parsed.messageTexts.size}")
            val repairedRaw = runCatching {
                generateText(spec, EmbeddedTurnProtocol.renderRepairPrompt(parsed.rawText, toolSpecs, request.requireTool)).trim()
            }.getOrDefault("")
            if (repairedRaw.isNotBlank()) {
                val repaired = EmbeddedTurnProtocol.parseResponse(repairedRaw, toolSpecs)
                updateDiagnostics(
                    spec = spec,
                    state = turnDiagnostics,
                    phase = "repair_result",
                    responseSource = "repaired",
                    finalToolCallCount = repaired.calls.length(),
                    finalMessageCount = repaired.messageTexts.size,
                    selectedTools = EmbeddedTurnProtocol.callNames(repaired.calls),
                    failedTools = emptyList(),
                    toolFailures = emptyList(),
                    repairUsed = true,
                    repairAttemptCount = 1,
                    fallbackUsed = false,
                    summary = "calls=${repaired.calls.length()} text=${repaired.messageTexts.size}",
                )
                Log.i(tag, "embedded_turn phase=repair_result backend=$backendId model=${spec.id} calls=${repaired.calls.length()} toolNames=${EmbeddedTurnProtocol.callNamesSummary(repaired.calls)} textCount=${repaired.messageTexts.size}")
                if (!EmbeddedTurnProtocol.needsRepair(repaired, request.requireTool, toolSpecs)) {
                    return repaired.copy(rawText = repairedRaw)
                }
            }
            if (request.requireTool) {
                updateDiagnostics(
                    spec = spec,
                    state = turnDiagnostics,
                    phase = "fallback",
                    responseSource = "fallback",
                    finalToolCallCount = 0,
                    finalMessageCount = 1,
                    selectedTools = toolSpecs.map { it.name },
                    failedTools = toolSpecs.map { it.name },
                    toolFailures = toolSpecs.map { EmbeddedToolFailure(it.name, "required_tool_fallback") },
                    repairUsed = true,
                    repairAttemptCount = 0,
                    fallbackUsed = true,
                    summary = "required-tool fallback",
                )
                Log.w(tag, "embedded_turn phase=fallback backend=$backendId model=${spec.id} selectedTools=${toolSpecs.joinToString(",") { it.name }}")
                return EmbeddedTurnProtocol.buildRequiredToolFallback(
                    originalText = parsed.rawText,
                    repairedText = repairedRaw,
                    toolSpecs = toolSpecs,
                )
            }
        }
        return parsed
    }

    protected fun diagnosticsFor(spec: EmbeddedModelSpec): EmbeddedTurnDiagnostics? {
        return diagnostics[spec.id.trim().lowercase()]
    }

    private fun updateDiagnostics(
        spec: EmbeddedModelSpec,
        state: EmbeddedTurnDiagnosticsState,
        phase: String,
        responseSource: String,
        finalToolCallCount: Int,
        finalMessageCount: Int,
        selectedTools: List<String>,
        failedTools: List<String>,
        toolFailures: List<EmbeddedToolFailure>,
        repairUsed: Boolean,
        repairAttemptCount: Int,
        fallbackUsed: Boolean,
        summary: String,
    ) {
        EmbeddedTurnProtocol.mergeDiagnosticsState(
            state = state,
            responseSource = responseSource,
            finalToolCallCount = finalToolCallCount,
            finalMessageCount = finalMessageCount,
            selectedTools = selectedTools,
            failedTools = failedTools,
            toolFailures = toolFailures,
            repairUsed = repairUsed,
            repairAttemptCount = repairAttemptCount,
            fallbackUsed = fallbackUsed,
        )
        diagnostics[spec.id.trim().lowercase()] = EmbeddedTurnDiagnostics(
            turnId = state.turnId,
            lastPhase = phase,
            responseSource = state.responseSource,
            finalToolCallCount = state.finalToolCallCount,
            finalMessageCount = state.finalMessageCount,
            selectedTools = state.selectedTools.toList(),
            failedTools = state.toolFailures.keys.toList(),
            toolFailures = state.toolFailures.entries.map { EmbeddedToolFailure(name = it.key, reason = it.value) },
            repairUsed = state.repairUsed,
            repairAttemptCount = state.repairAttemptCount,
            fallbackUsed = state.fallbackUsed,
            lastSummary = summary,
            updatedAtMs = System.currentTimeMillis(),
        )
    }
}

private class AiCorePreviewEmbeddedBackend : PromptStructuredEmbeddedBackend() {
    override val backendId: String = "aicore_preview"

    private data class ClientState(
        val client: GenerativeModelFutures,
        val createdAtMs: Long,
        val lastUsedAtMs: AtomicLong = AtomicLong(createdAtMs),
        @Volatile var warmed: Boolean = false,
        @Volatile var lastError: String = "",
    )

    private val clients = ConcurrentHashMap<String, ClientState>()

    override fun status(spec: EmbeddedModelSpec): EmbeddedBackendStatus {
        val runtimeAvailable = isRuntimeAvailable()
        val cacheKey = spec.id.trim().lowercase()
        val cached = clients[cacheKey]
        val checkedStatus = if (runtimeAvailable) {
            runCatching { checkStatus(clientFor(spec).client) }
                .onFailure { ex -> clientFor(spec).lastError = ex.message ?: ex.javaClass.simpleName }
                .getOrNull()
        } else {
            null
        }
        val statusLabel = checkedStatus?.let { featureStatusLabel(it) } ?: "unavailable"
        val runnable = checkedStatus == FeatureStatus.AVAILABLE
        val detail = if (!runtimeAvailable) {
            "ML Kit Prompt API runtime is not linked in this build."
        } else if (runnable) {
            "AICore Developer Preview model is available on this device."
        } else {
            "AICore Developer Preview model status: $statusLabel. Enroll and download it in the Android AICore app, or use the LiteRT bundle fallback."
        }
        return EmbeddedBackendStatus(
            model = spec,
            backendId = backendId,
            installed = checkedStatus == FeatureStatus.AVAILABLE,
            runnable = runnable,
            loaded = cached != null,
            warm = cached?.warmed == true && runnable,
            detail = detail,
            primaryModelPath = "aicore://preview/fast",
            candidatePaths = listOf("aicore://preview/fast", "aicore://preview/full"),
            lastError = cached?.lastError.orEmpty(),
            lastLoadedAtMs = cached?.createdAtMs ?: 0L,
            lastUsedAtMs = cached?.lastUsedAtMs?.get() ?: 0L,
            lastTurnDiagnostics = diagnosticsFor(spec),
        )
    }

    override fun generateText(spec: EmbeddedModelSpec, prompt: String): String {
        val state = clientFor(spec)
        val status = checkStatus(state.client)
        if (status != FeatureStatus.AVAILABLE) {
            val message = "aicore_preview_not_available:${featureStatusLabel(status)}"
            state.lastError = message
            throw IllegalStateException(message)
        }
        state.lastUsedAtMs.set(System.currentTimeMillis())
        return try {
            val response = state.client.generateContent(prompt).get(120, TimeUnit.SECONDS)
            state.lastError = ""
            responseText(response)
        } catch (ex: Exception) {
            state.lastError = ex.message ?: ex.javaClass.simpleName
            throw ex
        }
    }

    override fun generateTextWithMedia(
        spec: EmbeddedModelSpec,
        prompt: String,
        media: List<ExtractedMedia>,
    ): String {
        val image = media.firstOrNull { it.mediaType == "image" } ?: return generateText(spec, prompt)
        val state = clientFor(spec)
        val status = checkStatus(state.client)
        if (status != FeatureStatus.AVAILABLE) {
            val message = "aicore_preview_not_available:${featureStatusLabel(status)}"
            state.lastError = message
            throw IllegalStateException(message)
        }
        state.lastUsedAtMs.set(System.currentTimeMillis())
        return try {
            val bytes = Base64.getDecoder().decode(image.base64)
            val request = GenerateContentRequest.Builder(
                ImagePart(bytes),
                TextPart(prompt),
            ).build()
            val response = state.client.generateContent(request).get(120, TimeUnit.SECONDS)
            state.lastError = ""
            responseText(response)
        } catch (ex: Exception) {
            state.lastError = ex.message ?: ex.javaClass.simpleName
            throw ex
        }
    }

    override fun warm(spec: EmbeddedModelSpec): EmbeddedBackendStatus {
        val state = clientFor(spec)
        val status = checkStatus(state.client)
        if (status != FeatureStatus.AVAILABLE) {
            val message = "aicore_preview_not_available:${featureStatusLabel(status)}"
            state.lastError = message
            throw IllegalStateException(message)
        }
        if (!state.warmed) {
            runCatching {
                state.client.warmup().get(120, TimeUnit.SECONDS)
                state.lastUsedAtMs.set(System.currentTimeMillis())
                state.warmed = true
                state.lastError = ""
            }.onFailure { ex ->
                state.lastError = ex.message ?: ex.javaClass.simpleName
                throw ex
            }
        }
        return status(spec)
    }

    override fun unload(spec: EmbeddedModelSpec): Boolean {
        val existing = clients.remove(spec.id.trim().lowercase()) ?: return false
        runCatching { existing.client.getGenerativeModel().close() }
        return true
    }

    override fun unloadAll() {
        val keys = clients.keys().toList()
        for (key in keys) {
            val spec = EmbeddedModelCatalog.find(key) ?: continue
            unload(spec)
        }
    }

    private fun clientFor(spec: EmbeddedModelSpec): ClientState {
        val key = spec.id.trim().lowercase()
        clients[key]?.let { return it }
        synchronized(this) {
            clients[key]?.let { return it }
            val modelConfig = ModelConfig.Builder().apply {
                releaseStage = ModelReleaseStage.PREVIEW
                preference = ModelPreference.FAST
            }.build()
            val generationConfig = GenerationConfig.Builder().apply {
                this.modelConfig = modelConfig
            }.build()
            val generativeModel = Generation.getClient(generationConfig)
            val created = ClientState(
                client = GenerativeModelFutures.from(generativeModel),
                createdAtMs = System.currentTimeMillis(),
            )
            clients[key] = created
            return created
        }
    }

    private fun checkStatus(client: GenerativeModelFutures): Int {
        return client.checkStatus().get(15, TimeUnit.SECONDS)
    }

    private fun responseText(response: GenerateContentResponse): String {
        return response.candidates
            .mapNotNull { it.text?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
    }

    private fun isRuntimeAvailable(): Boolean {
        return runCatching {
            Class.forName("com.google.mlkit.genai.prompt.Generation")
            true
        }.getOrDefault(false)
    }

    private fun featureStatusLabel(status: Int): String {
        return when (status) {
            FeatureStatus.AVAILABLE -> "available"
            FeatureStatus.DOWNLOADABLE -> "downloadable"
            FeatureStatus.DOWNLOADING -> "downloading"
            FeatureStatus.UNAVAILABLE -> "unavailable"
            else -> "unknown($status)"
        }
    }
}

private class LiteRtBundleEmbeddedBackend(
    private val context: Context,
    private val modelManager: EmbeddedModelManager,
) : PromptStructuredEmbeddedBackend() {
    override val backendId: String = "litert_lm"
    private data class LoadedInference(
        val modelPath: String,
        val engine: Engine,
        val loadedAtMs: Long,
        val lastUsedAtMs: AtomicLong = AtomicLong(loadedAtMs),
        @Volatile var warmed: Boolean = false,
        @Volatile var lastError: String = "",
    )

    private val loaded = ConcurrentHashMap<String, LoadedInference>()

    override fun status(spec: EmbeddedModelSpec): EmbeddedBackendStatus {
        val resolved = modelManager.resolve(spec.id)
        val installed = resolved.primaryFile != null
        val invalidReason = resolved.primaryFile?.let {
            EmbeddedModelFileValidator.invalidReason(it)
                ?: EmbeddedModelCompatibilityValidator.incompatibleReason(spec, it)
        }.orEmpty()
        val runtimeAvailable = isRuntimeAvailable()
        val cacheKey = spec.id.trim().lowercase()
        val cached = loaded[cacheKey]
        val detail = if (installed && invalidReason.isNotEmpty()) {
            "Model bundle looks invalid: $invalidReason"
        } else if (installed) {
            if (runtimeAvailable) {
                if (cached != null) {
                    "Model bundle found. LiteRT-LM runtime is available and cached in memory."
                } else {
                    "Model bundle found. LiteRT-LM runtime is available."
                }
            } else {
                "Model bundle found, but LiteRT-LM runtime is not linked in this build."
            }
        } else {
            "Model bundle not found. Place a .litertlm, .task, .tflite, or model.bin under the embedded model directory."
        }
        return EmbeddedBackendStatus(
            model = spec,
            backendId = backendId,
            installed = installed,
            runnable = installed && invalidReason.isEmpty() && runtimeAvailable,
            loaded = cached != null,
            warm = cached?.warmed == true,
            detail = detail,
            primaryModelPath = resolved.primaryFile?.absolutePath ?: resolved.defaultPrimaryPath.absolutePath,
            candidatePaths = resolved.candidateFiles.map { it.absolutePath },
            lastError = cached?.lastError ?: invalidReason,
            lastLoadedAtMs = cached?.loadedAtMs ?: 0L,
            lastUsedAtMs = cached?.lastUsedAtMs?.get() ?: 0L,
            lastTurnDiagnostics = diagnosticsFor(spec),
        )
    }

    override fun generateText(spec: EmbeddedModelSpec, prompt: String): String {
        val instance = ensureLoaded(spec)
        synchronized(instance) {
            instance.lastUsedAtMs.set(System.currentTimeMillis())
            return try {
                invokeGenerateResponse(instance.engine, prompt).trim()
            } catch (ex: Exception) {
                instance.lastError = ex.message ?: ex.javaClass.simpleName
                runCatching { unload(spec) }
                throw ex
            }
        }
    }

    override fun generateTextWithMedia(
        spec: EmbeddedModelSpec,
        prompt: String,
        media: List<ExtractedMedia>,
    ): String {
        if (media.isEmpty()) return generateText(spec, prompt)
        val instance = ensureLoaded(spec)
        synchronized(instance) {
            instance.lastUsedAtMs.set(System.currentTimeMillis())
            return try {
                val contents = mutableListOf<Content>()
                for (item in media) {
                    val bytes = Base64.getDecoder().decode(item.base64)
                    when (item.mediaType) {
                        "image" -> contents += Content.ImageFile(
                            EmbeddedMediaNormalizer.imageFileForLiteRt(context, bytes, item.mimeType).absolutePath
                        )
                        "audio" -> contents += Content.AudioBytes(bytes)
                    }
                }
                contents += Content.Text(prompt)
                invokeGenerateResponse(instance.engine, Contents.of(contents)).trim()
            } catch (ex: Exception) {
                instance.lastError = ex.message ?: ex.javaClass.simpleName
                runCatching { unload(spec) }
                throw ex
            }
        }
    }

    override fun warm(spec: EmbeddedModelSpec): EmbeddedBackendStatus {
        val instance = ensureLoaded(spec)
        synchronized(instance) {
            if (!instance.warmed) {
                runCatching {
                    instance.lastUsedAtMs.set(System.currentTimeMillis())
                    invokeGenerateResponse(instance.engine, "Reply with OK.")
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
        runCatching { existing.engine.close() }
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
            Class.forName("com.google.ai.edge.litertlm.Engine")
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
            EmbeddedModelFileValidator.invalidReason(modelFile)?.let { reason ->
                throw IllegalStateException("embedded_model_invalid:$reason")
            }
            val engine = createEngine(modelFile)
            val now = System.currentTimeMillis()
            val created = LoadedInference(
                modelPath = modelFile.absolutePath,
                engine = engine,
                loadedAtMs = now,
            )
            loaded[key] = created
            Log.i(tag, "Loaded embedded model ${spec.id} from ${modelFile.absolutePath}")
            return created
        }
    }

    private fun createEngine(modelFile: File): Engine {
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        return Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                audioBackend = Backend.CPU(),
                maxNumImages = 4,
                cacheDir = context.cacheDir.absolutePath,
            )
        ).also { it.initialize() }
    }

    private fun invokeGenerateResponse(engine: Engine, prompt: String): String {
        engine.createConversation(ConversationConfig()).use { conversation ->
            return conversation.sendMessage(prompt).toString()
        }
    }

    private fun invokeGenerateResponse(engine: Engine, contents: Contents): String {
        engine.createConversation(ConversationConfig()).use { conversation ->
            return conversation.sendMessage(contents).toString()
        }
    }

}

internal object EmbeddedMediaNormalizer {
    private const val MAX_IMAGE_DIMENSION = 1024
    private const val JPEG_QUALITY = 85

    fun imageFileForLiteRt(context: Context, bytes: ByteArray, mimeType: String): File {
        val dir = File(context.cacheDir, "litert_media").apply { mkdirs() }
        cleanupOldFiles(dir)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap == null) {
            val ext = when {
                isPng(bytes) -> ".png"
                isJpeg(bytes) -> ".jpg"
                else -> ".img"
            }
            return File.createTempFile("image_", ext, dir).also { it.writeBytes(bytes) }
        }

        return try {
            val normalized = resizeIfNeeded(bitmap, MAX_IMAGE_DIMENSION)
            try {
                val out = ByteArrayOutputStream()
                if (!normalized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    throw IllegalArgumentException("Failed to encode image as JPEG for LiteRT-LM. mime_type=$mimeType bytes=${bytes.size}")
                }
                File.createTempFile("image_", ".jpg", dir).also { it.writeBytes(out.toByteArray()) }
            } finally {
                if (normalized !== bitmap) normalized.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun resizeIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val currentMax = maxOf(bitmap.width, bitmap.height)
        if (currentMax <= maxDim) return bitmap
        val scale = maxDim.toFloat() / currentMax.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun cleanupOldFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(6)
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        }
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
}

internal object EmbeddedTurnProtocol {
    private const val MAX_MODEL_OUTPUT_CHARS = 16_000
    private const val MAX_PLAN_EARLIER_MESSAGES = 4
    private const val MAX_PLAN_RECENT_MESSAGES = 6
    private const val MAX_PLAN_EARLIER_CHARS = 720
    private const val MAX_PLAN_RECENT_MESSAGE_CHARS = 480
    private const val MAX_SYSTEM_PROMPT_CHARS = 2_400
    private const val MAX_TOOL_DESCRIPTION_CHARS = 72
    private const val MAX_TOOLS_IN_PLAN = 12
    private const val MAX_PLAN_TEXT_ECHO_CHARS = 640
    private const val MAX_REPAIR_TEXT_ECHO_CHARS = 1_200

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

    fun extractRequestMedia(input: JSONArray): List<ExtractedMedia> {
        val out = mutableListOf<ExtractedMedia>()
        for (i in 0 until input.length()) {
            val msg = input.optJSONObject(i) ?: continue
            val content = msg.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                val type = part.optString("_media_type", "").trim()
                val data = part.optString("data", "").trim()
                val mime = part.optString("mime_type", "").trim()
                val validMediaType = (type == "image" && mime.startsWith("image/")) ||
                    (type == "audio" && mime.startsWith("audio/"))
                if (validMediaType && data.isNotEmpty()) {
                    out += ExtractedMedia(
                        base64 = data,
                        mimeType = mime,
                        mediaType = type,
                    )
                }
            }
        }
        return out
    }

    fun withoutNativeMediaAnalysisTools(tools: JSONArray, input: JSONArray): JSONArray {
        val nativeMediaTypes = extractRequestMedia(input).map { it.mediaType }.toSet()
        if (nativeMediaTypes.isEmpty()) return tools
        val blockedNames = buildSet {
            if ("image" in nativeMediaTypes) add("analyze_image")
            if ("audio" in nativeMediaTypes) add("analyze_audio")
        }
        if (blockedNames.isEmpty()) return tools

        val filtered = JSONArray()
        for (i in 0 until tools.length()) {
            val wrapper = tools.optJSONObject(i) ?: continue
            val fn = wrapper.optJSONObject("function") ?: wrapper
            val name = fn.optString("name", "").trim()
            if (name !in blockedNames) {
                filtered.put(wrapper)
            }
        }
        return filtered
    }

    fun mergeDiagnosticsState(
        state: EmbeddedTurnDiagnosticsState,
        responseSource: String,
        finalToolCallCount: Int,
        finalMessageCount: Int,
        selectedTools: List<String>,
        failedTools: List<String>,
        toolFailures: List<EmbeddedToolFailure>,
        repairUsed: Boolean,
        repairAttemptCount: Int,
        fallbackUsed: Boolean,
    ) {
        if (responseSource.isNotBlank() && responseSource != "pending") {
            state.responseSource = responseSource
        }
        if (finalToolCallCount >= 0) {
            state.finalToolCallCount = finalToolCallCount
        }
        if (finalMessageCount >= 0) {
            state.finalMessageCount = finalMessageCount
        }
        selectedTools
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { state.selectedTools += it }
        val normalizedFailures = toolFailures
            .filter { it.name.isNotBlank() && it.reason.isNotBlank() }
            .distinctBy { "${it.name}:${it.reason}" }
        normalizedFailures.forEach { state.toolFailures[it.name] = it.reason }
        failedTools
            .map { it.trim() }
            .filter { it.isNotEmpty() && !state.toolFailures.containsKey(it) }
            .forEach { state.toolFailures[it] = "unknown" }
        state.repairUsed = state.repairUsed || repairUsed
        state.repairAttemptCount += repairAttemptCount
        state.fallbackUsed = state.fallbackUsed || fallbackUsed
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
            append("\n\nTools:\n")
            append(toolGuide)
            append("\n\nReturn exactly one JSON object: {\"assistant_message\":\"...\",\"tool_calls\":[{\"name\":\"tool_name\"}]}.\n")
            append("Rules: no markdown fences; at this phase each tool_calls item contains only name; use [] when no tool is needed.\n")
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
            append("Convert the selected tool below into exactly one JSON object.\n")
            append("Return only JSON: {\"assistant_message\":\"...\",\"tool_calls\":[{\"name\":\"")
            append(toolName)
            append("\",\"arguments\":{...}}]}.\n")
            append("Only include this selected tool:\n")
            append(selectedSpecLines)
            append("\n\nPlanner output excerpt:\n")
            append(normalizeGeneratedOutput(rawPlanText, maxChars = MAX_PLAN_TEXT_ECHO_CHARS))
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
            toolSpecs
                .take(MAX_TOOLS_IN_PLAN)
                .joinToString("\n") { spec -> compactToolLine(spec, includeOptionalArgs = false) }
        }
        val toolRule = if (toolSpecs.isEmpty()) {
            "Tool calls are not allowed in this repair task."
        } else if (requireTool) {
            "At least one valid tool call is required unless the text clearly explains a hard blocker."
        } else {
            "Use tool_calls only if the response is actually invoking a listed tool."
        }
        return buildString {
            append("Rewrite the model output below into exactly one JSON object.\n")
            append("Return only JSON with keys assistant_message and tool_calls. Each tool call needs name and arguments.\n")
            append("Allowed tools:\n")
            append(toolLines)
            append("\n")
            append(toolRule)
            append("\n\nOriginal output excerpt:\n")
            append(normalizeGeneratedOutput(rawText, maxChars = MAX_REPAIR_TEXT_ECHO_CHARS))
        }
    }

    fun parseResponse(rawText: String, toolSpecs: List<EmbeddedToolSpec> = emptyList()): EmbeddedGenerationResult {
        val normalizedRawText = normalizeGeneratedOutput(rawText)
        val parsed = extractJsonObject(normalizedRawText)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (parsed == null) {
            return EmbeddedGenerationResult(
                messageTexts = listOf(normalizedRawText).filter { it.isNotBlank() },
                calls = JSONArray(),
                rawText = normalizedRawText,
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
            rawText = normalizedRawText,
        )
    }

    fun parsePlanResponse(rawText: String, toolSpecs: List<EmbeddedToolSpec>): EmbeddedGenerationResult {
        val normalizedRawText = normalizeGeneratedOutput(rawText)
        val parsed = extractJsonObject(normalizedRawText)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (parsed == null) {
            return EmbeddedGenerationResult(
                messageTexts = listOf(normalizedRawText).filter { it.isNotBlank() },
                calls = JSONArray(),
                rawText = normalizedRawText,
            )
        }
        val message = parsed.optString("assistant_message", "").trim()
        val calls = JSONArray()
        val toolSpecByName = toolSpecs.associateBy { it.name }
        val seenToolNames = linkedSetOf<String>()
        val rawCalls = parsed.optJSONArray("tool_calls") ?: JSONArray()
        for (i in 0 until rawCalls.length()) {
            val call = rawCalls.optJSONObject(i) ?: continue
            val name = call.optString("name", "").trim()
            if (name.isEmpty()) continue
            if (!toolSpecByName.containsKey(name)) continue
            if (!seenToolNames.add(name)) continue
            calls.put(JSONObject().apply {
                put("name", name)
                put("arguments", JSONObject())
                put("call_id", "embedded_plan_${i}_${name}")
            })
        }
        return EmbeddedGenerationResult(
            messageTexts = listOf(message).filter { it.isNotBlank() },
            calls = calls,
            rawText = normalizedRawText,
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
        val seenPlannedNames = linkedSetOf<String>()
        for (i in 0 until plan.calls.length()) {
            val call = plan.calls.optJSONObject(i) ?: continue
            val name = call.optString("name", "").trim()
            if (name.isEmpty()) continue
            if (!seenPlannedNames.add(name)) continue
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
        if (toolSpecs.isNotEmpty() && result.calls.length() == 0 && isLowSignalResult(result.messageTexts)) {
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
        val lines = extractToolSpecs(tools)
            .take(MAX_TOOLS_IN_PLAN)
            .map { compactToolLine(it, includeOptionalArgs = false) }
        return if (lines.isEmpty()) "- none" else lines.joinToString("\n")
    }

    private fun renderConversation(systemPrompt: String, input: JSONArray): String {
        val messages = mutableListOf<Pair<String, String>>()
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
            if (text.isNotEmpty()) messages += role to text
        }
        val recentStart = (messages.size - MAX_PLAN_RECENT_MESSAGES).coerceAtLeast(0)
        val earlierMessages = messages.subList(0, recentStart)
        val recentMessages = messages.subList(recentStart, messages.size)
        val sb = StringBuilder()
        sb.append("System:\n")
        sb.append(compactText(systemPrompt, MAX_SYSTEM_PROMPT_CHARS))
        sb.append("\n\nConversation:\n")
        if (earlierMessages.isNotEmpty()) {
            sb.append("Earlier summary:\n")
            earlierMessages
                .takeLast(MAX_PLAN_EARLIER_MESSAGES)
                .forEach { (role, text) ->
                    sb.append("- ")
                    sb.append(role)
                    sb.append(": ")
                    sb.append(compactText(text, MAX_PLAN_EARLIER_CHARS / MAX_PLAN_EARLIER_MESSAGES))
                    sb.append("\n")
                }
            val omittedCount = (earlierMessages.size - MAX_PLAN_EARLIER_MESSAGES).coerceAtLeast(0)
            if (omittedCount > 0) {
                sb.append("- ")
                sb.append(omittedCount)
                sb.append(" earlier message(s) omitted.\n")
            }
            sb.append("\n")
        }
        recentMessages.forEach { (role, text) ->
            sb.append(role.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            })
            sb.append(": ")
            sb.append(compactText(text, MAX_PLAN_RECENT_MESSAGE_CHARS))
            sb.append("\n\n")
        }
        sb.append("Assistant:")
        return sb.toString().trim()
    }

    private fun compactToolLine(spec: EmbeddedToolSpec, includeOptionalArgs: Boolean): String {
        val requiredArgs = spec.requiredArgumentNames.sorted()
        val optionalArgs = if (includeOptionalArgs) {
            spec.allowedArgumentNames
                .filterNot { spec.requiredArgumentNames.contains(it) }
                .sorted()
        } else {
            emptyList()
        }
        val argsSummary = buildString {
            if (requiredArgs.isNotEmpty()) {
                append("required=")
                append(requiredArgs.joinToString(","))
            }
            if (optionalArgs.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append("optional=")
                append(optionalArgs.joinToString(","))
            }
        }.ifBlank { "no_args" }
        val purpose = compactText(spec.description.ifBlank { spec.name }, MAX_TOOL_DESCRIPTION_CHARS)
        return "- ${spec.name} [$argsSummary] $purpose"
    }

    private fun compactText(text: String, maxChars: Int): String {
        val normalized = text
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (normalized.length <= maxChars) return normalized
        val ellipsis = " ...[snip]... "
        val head = ((maxChars - ellipsis.length) * 3) / 4
        val tail = (maxChars - ellipsis.length - head).coerceAtLeast(0)
        return buildString {
            append(normalized.take(head.coerceAtLeast(0)))
            append(ellipsis)
            if (tail > 0) append(normalized.takeLast(tail))
        }.trim()
    }

    fun normalizeGeneratedOutput(rawText: String, maxChars: Int = MAX_MODEL_OUTPUT_CHARS): String {
        val cleaned = rawText
            .replace("\u0000", "")
            .trim()
        if (cleaned.isEmpty()) return ""
        if (cleaned.length <= maxChars) return cleaned
        val jsonCandidate = extractJsonObject(cleaned)
        if (jsonCandidate != null && jsonCandidate.length <= maxChars) return jsonCandidate
        val head = maxChars / 2
        val tail = maxChars - head
        return buildString {
            append(cleaned.take(head))
            append("\n...[truncated]...\n")
            append(cleaned.takeLast(tail))
        }.trim()
    }

    private fun extractJsonObject(rawText: String): String? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            runCatching { JSONObject(trimmed) }.getOrNull()?.let { parsed ->
                if (looksLikeStructuredAssistantPayload(parsed)) return trimmed
            }
        }
        val candidates = mutableListOf<String>()
        val fenceMatches = Regex("```(?:json)?\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
            .findAll(trimmed)
            .map { it.groupValues[1] }
            .toList()
        candidates.addAll(fenceMatches)
        var depth = 0
        var inString = false
        var escaped = false
        var start = -1
        for (i in trimmed.indices) {
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
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth == 0) continue
                    depth--
                    if (depth == 0 && start >= 0) {
                        candidates += trimmed.substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
        val ranked = candidates.mapNotNull { candidate ->
            val parsed = runCatching { JSONObject(candidate) }.getOrNull() ?: return@mapNotNull null
            val keyScore = structuredPayloadScore(parsed)
            Triple(candidate, parsed, keyScore)
        }
        val bestStructured = ranked
            .filter { it.third > 0 }
            .maxWithOrNull(compareBy<Triple<String, JSONObject, Int>>({ it.third }, { it.first.length }))
        if (bestStructured != null) return bestStructured.first
        val bestAny = ranked.maxByOrNull { it.first.length }
        if (bestAny != null) return bestAny.first
        return null
    }

    private fun looksLikeStructuredAssistantPayload(json: JSONObject): Boolean {
        return structuredPayloadScore(json) > 0
    }

    private fun structuredPayloadScore(json: JSONObject): Int {
        var score = 0
        if (json.has("assistant_message")) score += 2
        if (json.has("tool_calls")) score += 3
        return score
    }

    private fun isLowSignalResult(messageTexts: List<String>): Boolean {
        if (messageTexts.isEmpty()) return false
        return messageTexts.all { text ->
            val normalized = text
                .lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            normalized in setOf(
                "ok",
                "okay",
                "sure",
                "done",
                "planning",
                "working on it",
                "trying tools",
                "let me do that",
                "i will do that",
            )
        }
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

internal object EmbeddedModelFileValidator {
    fun invalidReason(file: File): String? {
        if (!file.isFile) return null
        if (file.length() <= 0L) return "file is empty"
        val prefix = runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(4096)
                val read = input.read(buffer)
                if (read <= 0) return@use ""
                String(buffer, 0, read, Charsets.UTF_8)
            }
        }.getOrDefault("")
        val normalized = prefix
            .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            .lowercase()
        if (
            normalized.startsWith("<!doctype html") ||
            normalized.startsWith("<html") ||
            normalized.startsWith("<head") ||
            normalized.contains("<title>hugging face") ||
            normalized.contains("<script")
        ) {
            return "downloaded content looks like HTML, not a LiteRT model"
        }
        return null
    }
}

internal object EmbeddedModelCompatibilityValidator {
    fun incompatibleReason(spec: EmbeddedModelSpec, file: File): String? = null
}
