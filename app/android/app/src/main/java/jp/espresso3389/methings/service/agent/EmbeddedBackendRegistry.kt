package jp.espresso3389.methings.service.agent

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class EmbeddedBackendStatus(
    val model: EmbeddedModelSpec,
    val backendId: String,
    val installed: Boolean,
    val runnable: Boolean,
    val detail: String,
    val primaryModelPath: String,
    val candidatePaths: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("model", model.id)
        put("label", model.label)
        put("backend", backendId)
        put("installed", installed)
        put("runnable", runnable)
        put("detail", detail)
        put("primary_model_path", primaryModelPath)
        put("candidate_paths", JSONArray(candidatePaths))
        put("supports_tool_calling", model.supportsToolCalling)
        put("supports_image_input", model.supportsImageInput)
        put("supports_audio_input", model.supportsAudioInput)
    }
}

interface EmbeddedBackend {
    val backendId: String
    fun status(spec: EmbeddedModelSpec): EmbeddedBackendStatus
    fun generateText(spec: EmbeddedModelSpec, prompt: String): String
}

class EmbeddedBackendRegistry(
    context: Context,
) {
    private val modelManager = EmbeddedModelManager(context)
    private val appContext = context.applicationContext
    private val backends: List<EmbeddedBackend> = listOf(
        LiteRtBundleEmbeddedBackend(appContext, modelManager),
    )

    fun statusFor(modelId: String): EmbeddedBackendStatus? {
        val spec = EmbeddedModelCatalog.find(modelId) ?: return null
        return backends.first().status(spec)
    }

    fun generateText(modelId: String, prompt: String): String {
        val spec = EmbeddedModelCatalog.find(modelId)
            ?: throw IllegalArgumentException("unknown_embedded_model")
        return backends.first().generateText(spec, prompt)
    }
}

private class LiteRtBundleEmbeddedBackend(
    private val context: Context,
    private val modelManager: EmbeddedModelManager,
) : EmbeddedBackend {
    override val backendId: String = "litert_bundle"

    override fun status(spec: EmbeddedModelSpec): EmbeddedBackendStatus {
        val resolved = modelManager.resolve(spec.id)
        val installed = resolved.primaryFile != null
        val runtimeAvailable = isRuntimeAvailable()
        val detail = if (installed) {
            if (runtimeAvailable) {
                "Model bundle found. MediaPipe LLM Inference runtime is available."
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
            detail = detail,
            primaryModelPath = resolved.primaryFile?.absolutePath ?: resolved.defaultPrimaryPath.absolutePath,
            candidatePaths = resolved.candidateFiles.map { it.absolutePath },
        )
    }

    override fun generateText(spec: EmbeddedModelSpec, prompt: String): String {
        val resolved = modelManager.resolve(spec.id)
        val modelFile = resolved.primaryFile ?: throw IllegalStateException("embedded_model_not_installed")
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(1024)
            .build()
        val inference = LlmInference.createFromOptions(context, options)
        return try {
            inference.generateResponse(prompt).trim()
        } finally {
            runCatching {
                inference.javaClass.methods.firstOrNull { it.name == "close" && it.parameterCount == 0 }
                    ?.invoke(inference)
            }
        }
    }

    private fun isRuntimeAvailable(): Boolean {
        return runCatching {
            Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
            true
        }.getOrDefault(false)
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
