package jp.espresso3389.methings.service.agent

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EmbeddedProviderRegressionTest {

    @Test
    fun resolveProviderUrlUsesEmbeddedScheme() {
        val manager = AgentConfigManager(mock())
        assertEquals("", manager.resolveProviderUrl("embedded", ""))
        assertEquals("embedded://local", manager.resolveProviderUrl("embedded", "embedded://custom"))
    }

    @Test
    fun detectProviderKindRecognizesEmbeddedVendorAndUrl() {
        val client = LlmClient()
        assertEquals(ProviderKind.EMBEDDED, client.detectProviderKind("embedded://local", "embedded"))
        assertEquals(ProviderKind.EMBEDDED, client.detectProviderKind("embedded://other", "custom"))
    }

    @Test
    fun gemma4CatalogEntryIsTextOnlyToolCallingModel() {
        val spec = EmbeddedModelCatalog.find("gemma4-e2b-it")
        assertNotNull(spec)
        assertTrue(spec!!.supportsToolCalling)
        assertFalse(spec.supportsImageInput)
        assertFalse(spec.supportsAudioInput)
        assertEquals("litert_lm", spec.preferredBackend)
    }

    @Test
    fun embeddedJsonResponseParsesToolCalls() {
        val result = EmbeddedTurnProtocol.parseResponse(
            """{"assistant_message":"Working on it","tool_calls":[{"name":"write_file","arguments":{"path":"note.txt","content":"hello"}}]}""",
        )

        assertEquals(listOf("Working on it"), result.messageTexts)
        assertEquals(1, result.calls.length())
        assertEquals("write_file", result.calls.getJSONObject(0).getString("name"))
        assertEquals("note.txt", result.calls.getJSONObject(0).getJSONObject("arguments").getString("path"))
    }

    @Test
    fun embeddedPlainTextFallbackDoesNotInventToolCalls() {
        val result = EmbeddedTurnProtocol.parseResponse("Plain text fallback")

        assertEquals(listOf("Plain text fallback"), result.messageTexts)
        assertEquals(0, result.calls.length())
    }

    @Test
    fun embeddedModelFileValidatorRejectsHtmlPayload() {
        val file = kotlin.io.path.createTempFile(suffix = ".litertlm").toFile()
        file.writeText("<!DOCTYPE html><html><head><title>Hugging Face</title></head><body>not a model</body></html>")

        val reason = EmbeddedModelFileValidator.invalidReason(file)

        assertEquals("downloaded content looks like HTML, not a LiteRT model", reason)
    }

    @Test
    fun embeddedCompatibilityValidatorRejectsCurrentGemma4LitertLmBundle() {
        val spec = EmbeddedModelCatalog.find("gemma4-e2b-it")!!
        val file = kotlin.io.path.createTempFile(suffix = ".litertlm").toFile()
        file.writeText("not html but still unsupported in this build")

        val reason = EmbeddedModelCompatibilityValidator.incompatibleReason(spec, file)

        assertTrue(reason!!.contains("cannot load the current Gemma4 LiteRT-LM bundle yet"))
    }

    @Test
    fun embeddedPlanParserKeepsOnlyKnownToolNames() {
        val toolSpecs = listOf(
            EmbeddedToolSpec(
                name = "write_file",
                description = "Write a file",
                allowedArgumentNames = setOf("path", "content"),
                requiredArgumentNames = setOf("path", "content"),
                enumStringValues = emptyMap(),
                allowAdditionalProperties = false,
            )
        )
        val plan = EmbeddedTurnProtocol.parsePlanResponse(
            """{"assistant_message":"planning","tool_calls":[{"name":"write_file"},{"name":"fake_tool"}]}""",
            toolSpecs,
        )

        assertEquals(listOf("planning"), plan.messageTexts)
        assertEquals(1, plan.calls.length())
        assertEquals("write_file", plan.calls.getJSONObject(0).getString("name"))
    }

    @Test
    fun embeddedParserPrefersStructuredJsonOverEarlierNoiseObject() {
        val toolSpecs = listOf(
            EmbeddedToolSpec(
                name = "write_file",
                description = "Write a file",
                allowedArgumentNames = setOf("path", "content"),
                requiredArgumentNames = setOf("path", "content"),
                enumStringValues = emptyMap(),
                allowAdditionalProperties = false,
            )
        )
        val raw = """
            First draft metadata: {"note":"ignore me"}

            ```json
            {"assistant_message":"Working on it","tool_calls":[{"name":"write_file","arguments":{"path":"note.txt","content":"hello"}}]}
            ```
        """.trimIndent()

        val result = EmbeddedTurnProtocol.parseResponse(raw, toolSpecs)

        assertEquals(listOf("Working on it"), result.messageTexts)
        assertEquals(1, result.calls.length())
        assertEquals("write_file", result.calls.getJSONObject(0).getString("name"))
    }

    @Test
    fun embeddedNormalizeGeneratedOutputKeepsTrailingJsonWhenOverlong() {
        val raw = "x".repeat(20_000) + """{"assistant_message":"ok","tool_calls":[]}"""

        val normalized = EmbeddedTurnProtocol.normalizeGeneratedOutput(raw, maxChars = 512)

        assertTrue(normalized.startsWith("{"))
        assertTrue(normalized.contains("assistant_message"))
        assertTrue(normalized.length <= 512)
    }

    @Test
    fun embeddedPlanParserDeduplicatesRepeatedToolNames() {
        val toolSpecs = listOf(
            EmbeddedToolSpec(
                name = "write_file",
                description = "Write a file",
                allowedArgumentNames = setOf("path", "content"),
                requiredArgumentNames = setOf("path", "content"),
                enumStringValues = emptyMap(),
                allowAdditionalProperties = false,
            ),
            EmbeddedToolSpec(
                name = "mkdir",
                description = "Create a directory",
                allowedArgumentNames = setOf("path"),
                requiredArgumentNames = setOf("path"),
                enumStringValues = emptyMap(),
                allowAdditionalProperties = false,
            ),
        )
        val plan = EmbeddedTurnProtocol.parsePlanResponse(
            """{"assistant_message":"planning","tool_calls":[{"name":"write_file"},{"name":"write_file"},{"name":"mkdir"},{"name":"mkdir"}]}""",
            toolSpecs,
        )

        assertEquals(2, plan.calls.length())
        assertEquals("write_file", plan.calls.getJSONObject(0).getString("name"))
        assertEquals("mkdir", plan.calls.getJSONObject(1).getString("name"))
    }

    @Test
    fun embeddedMergePlanAndArgumentsUsesPlannedOrder() {
        val plan = EmbeddedGenerationResult(
            messageTexts = listOf("planning"),
            calls = org.json.JSONArray()
                .put(org.json.JSONObject().put("name", "write_file").put("arguments", org.json.JSONObject()).put("call_id", "c1"))
                .put(org.json.JSONObject().put("name", "mkdir").put("arguments", org.json.JSONObject()).put("call_id", "c2")),
            rawText = "plan",
        )
        val args = EmbeddedGenerationResult(
            messageTexts = emptyList(),
            calls = org.json.JSONArray()
                .put(org.json.JSONObject().put("name", "mkdir").put("arguments", org.json.JSONObject().put("path", "dir")))
                .put(org.json.JSONObject().put("name", "write_file").put("arguments", org.json.JSONObject().put("path", "a.txt").put("content", "hi"))),
            rawText = "args",
        )

        val merged = EmbeddedTurnProtocol.mergePlanAndArguments(plan, listOf(args))

        assertEquals(2, merged.calls.length())
        assertEquals("write_file", merged.calls.getJSONObject(0).getString("name"))
        assertEquals("a.txt", merged.calls.getJSONObject(0).getJSONObject("arguments").getString("path"))
        assertEquals("mkdir", merged.calls.getJSONObject(1).getString("name"))
        assertEquals("dir", merged.calls.getJSONObject(1).getJSONObject("arguments").getString("path"))
    }

    @Test
    fun embeddedMergePlanAndArgumentsDropsDuplicatePlannedTools() {
        val plan = EmbeddedGenerationResult(
            messageTexts = listOf("planning"),
            calls = org.json.JSONArray()
                .put(org.json.JSONObject().put("name", "write_file").put("arguments", org.json.JSONObject()).put("call_id", "c1"))
                .put(org.json.JSONObject().put("name", "write_file").put("arguments", org.json.JSONObject()).put("call_id", "c2"))
                .put(org.json.JSONObject().put("name", "mkdir").put("arguments", org.json.JSONObject()).put("call_id", "c3")),
            rawText = "plan",
        )
        val args = EmbeddedGenerationResult(
            messageTexts = emptyList(),
            calls = org.json.JSONArray()
                .put(org.json.JSONObject().put("name", "write_file").put("arguments", org.json.JSONObject().put("path", "a.txt").put("content", "hi")))
                .put(org.json.JSONObject().put("name", "mkdir").put("arguments", org.json.JSONObject().put("path", "dir"))),
            rawText = "args",
        )

        val merged = EmbeddedTurnProtocol.mergePlanAndArguments(plan, listOf(args))

        assertEquals(2, merged.calls.length())
        assertEquals("write_file", merged.calls.getJSONObject(0).getString("name"))
        assertEquals("mkdir", merged.calls.getJSONObject(1).getString("name"))
    }

    @Test
    fun embeddedSingleArgumentPromptScopesToOneTool() {
        val toolSpecs = listOf(
            EmbeddedToolSpec(
                name = "write_file",
                description = "Write a file",
                allowedArgumentNames = setOf("path", "content"),
                requiredArgumentNames = setOf("path", "content"),
                enumStringValues = emptyMap(),
                allowAdditionalProperties = false,
            ),
            EmbeddedToolSpec(
                name = "mkdir",
                description = "Create a directory",
                allowedArgumentNames = setOf("path", "parents"),
                requiredArgumentNames = setOf("path"),
                enumStringValues = emptyMap(),
                allowAdditionalProperties = false,
            ),
        )

        val prompt = EmbeddedTurnProtocol.renderSingleArgumentPrompt(
            rawPlanText = """{"assistant_message":"planning","tool_calls":[{"name":"write_file"},{"name":"mkdir"}]}""",
            toolSpecs = toolSpecs,
            toolName = "mkdir",
        )

        assertTrue(prompt.contains("Only include this selected tool"))
        assertTrue(prompt.contains("- mkdir:"))
        assertFalse(prompt.contains("- write_file:"))
    }

    @Test
    fun embeddedParserDropsUnknownToolCalls() {
        val toolSpecs = listOf(
            EmbeddedToolSpec(
                name = "write_file",
                description = "Write a file",
                allowedArgumentNames = setOf("path", "content"),
                requiredArgumentNames = setOf("path", "content"),
                enumStringValues = emptyMap(),
                allowAdditionalProperties = false,
            )
        )
        val result = EmbeddedTurnProtocol.parseResponse(
            """{"assistant_message":"Trying tools","tool_calls":[{"name":"totally_fake_tool","arguments":{"x":1}},{"name":"write_file","arguments":{"path":"note.txt","content":"ok"}}]}""",
            toolSpecs,
        )

        assertEquals(listOf("Trying tools"), result.messageTexts)
        assertEquals(1, result.calls.length())
        assertEquals("write_file", result.calls.getJSONObject(0).getString("name"))
    }

    @Test
    fun embeddedRepairIsRequestedWhenRequiredToolsDisappearAfterValidation() {
        val toolSpecs = listOf(
            EmbeddedToolSpec(
                name = "write_file",
                description = "Write a file",
                allowedArgumentNames = setOf("path", "content"),
                requiredArgumentNames = setOf("path", "content"),
                enumStringValues = emptyMap(),
                allowAdditionalProperties = false,
            )
        )
        val result = EmbeddedTurnProtocol.parseResponse(
            """{"assistant_message":"Done","tool_calls":[{"name":"totally_fake_tool","arguments":{"x":1}}]}""",
            toolSpecs,
        )

        assertTrue(EmbeddedTurnProtocol.needsRepair(result, requireTool = true, toolSpecs = toolSpecs))
    }

    @Test
    fun embeddedRepairIsRequestedForLowSignalNoToolReplyWhenToolsExist() {
        val toolSpecs = listOf(
            EmbeddedToolSpec(
                name = "write_file",
                description = "Write a file",
                allowedArgumentNames = setOf("path", "content"),
                requiredArgumentNames = setOf("path", "content"),
                enumStringValues = emptyMap(),
                allowAdditionalProperties = false,
            )
        )
        val result = EmbeddedGenerationResult(
            messageTexts = listOf("Working on it"),
            calls = org.json.JSONArray(),
            rawText = "Working on it",
        )

        assertTrue(EmbeddedTurnProtocol.needsRepair(result, requireTool = false, toolSpecs = toolSpecs))
    }

    @Test
    fun embeddedParserDropsUndeclaredArguments() {
        val toolSpecs = listOf(
            EmbeddedToolSpec(
                name = "write_file",
                description = "Write a file",
                allowedArgumentNames = setOf("path", "content"),
                requiredArgumentNames = setOf("path", "content"),
                enumStringValues = emptyMap(),
                allowAdditionalProperties = false,
            )
        )
        val result = EmbeddedTurnProtocol.parseResponse(
            """{"assistant_message":"ok","tool_calls":[{"name":"write_file","arguments":{"path":"note.txt","content":"hello","evil":"drop-me"}}]}""",
            toolSpecs,
        )

        val args = result.calls.getJSONObject(0).getJSONObject("arguments")
        assertTrue(args.has("path"))
        assertTrue(args.has("content"))
        assertFalse(args.has("evil"))
    }

    @Test
    fun embeddedParserDropsCallsMissingRequiredArguments() {
        val toolSpecs = listOf(
            EmbeddedToolSpec(
                name = "write_file",
                description = "Write a file",
                allowedArgumentNames = setOf("path", "content"),
                requiredArgumentNames = setOf("path", "content"),
                enumStringValues = emptyMap(),
                allowAdditionalProperties = false,
            )
        )
        val result = EmbeddedTurnProtocol.parseResponse(
            """{"assistant_message":"ok","tool_calls":[{"name":"write_file","arguments":{"path":"note.txt"}}]}""",
            toolSpecs,
        )

        assertEquals(0, result.calls.length())
    }

    @Test
    fun embeddedParserEnforcesEnumStringArguments() {
        val toolSpecs = listOf(
            EmbeddedToolSpec(
                name = "shell_session",
                description = "Manage shell sessions",
                allowedArgumentNames = setOf("action", "session_id"),
                requiredArgumentNames = setOf("action"),
                enumStringValues = mapOf("action" to setOf("start", "read", "kill")),
                allowAdditionalProperties = false,
            )
        )
        val invalid = EmbeddedTurnProtocol.parseResponse(
            """{"assistant_message":"ok","tool_calls":[{"name":"shell_session","arguments":{"action":"HACK","session_id":"s1"}}]}""",
            toolSpecs,
        )
        val valid = EmbeddedTurnProtocol.parseResponse(
            """{"assistant_message":"ok","tool_calls":[{"name":"shell_session","arguments":{"action":"read","session_id":"s1"}}]}""",
            toolSpecs,
        )

        assertEquals(0, invalid.calls.length())
        assertEquals(1, valid.calls.length())
        assertEquals("read", valid.calls.getJSONObject(0).getJSONObject("arguments").getString("action"))
    }

    @Test
    fun embeddedRequiredToolFallbackReturnsExplicitBlockerMessage() {
        val toolSpecs = listOf(
            EmbeddedToolSpec(
                name = "write_file",
                description = "Write a file",
                allowedArgumentNames = setOf("path", "content"),
                requiredArgumentNames = setOf("path", "content"),
                enumStringValues = emptyMap(),
                allowAdditionalProperties = false,
            )
        )
        val fallback = EmbeddedTurnProtocol.buildRequiredToolFallback(
            originalText = "not valid json",
            repairedText = "",
            toolSpecs = toolSpecs,
        )

        assertEquals(0, fallback.calls.length())
        assertTrue(fallback.messageTexts.single().contains("could not produce a valid tool call"))
        assertTrue(fallback.messageTexts.single().contains("write_file"))
    }

    @Test
    fun embeddedStatusJsonIncludesLifecycleFields() {
        val spec = EmbeddedModelCatalog.find("gemma4-e2b-it")!!
        val json = EmbeddedBackendStatus(
            model = spec,
            backendId = "litert_bundle",
            installed = true,
            runnable = true,
            loaded = true,
            warm = true,
            detail = "ready",
            primaryModelPath = "/tmp/model.litertlm",
            candidatePaths = listOf("/tmp/model.litertlm"),
            lastError = "",
            lastLoadedAtMs = 123L,
            lastUsedAtMs = 456L,
            lastTurnDiagnostics = EmbeddedTurnDiagnostics(
                turnId = 12L,
                lastPhase = "merged",
                responseSource = "repaired",
                finalToolCallCount = 1,
                finalMessageCount = 0,
                selectedTools = listOf("write_file"),
                failedTools = listOf("mkdir"),
                toolFailures = listOf(EmbeddedToolFailure("mkdir", "invalid_arguments")),
                repairUsed = true,
                repairAttemptCount = 1,
                fallbackUsed = false,
                lastSummary = "calls=1 text=0",
                updatedAtMs = 789L,
            ),
        ).toJson()

        assertTrue(json.getBoolean("loaded"))
        assertTrue(json.getBoolean("warm"))
        assertEquals(123L, json.getLong("last_loaded_at_ms"))
        assertEquals(456L, json.getLong("last_used_at_ms"))
        val diagnostics = json.getJSONObject("last_turn_diagnostics")
        assertEquals(12L, diagnostics.getLong("turn_id"))
        assertEquals("merged", diagnostics.getString("last_phase"))
        assertEquals("repaired", diagnostics.getString("response_source"))
        assertEquals(1, diagnostics.getInt("final_tool_call_count"))
        assertEquals(0, diagnostics.getInt("final_message_count"))
        assertEquals("mkdir", diagnostics.getJSONArray("failed_tools").getString(0))
        val failures = diagnostics.getJSONArray("tool_failures")
        assertEquals("mkdir", failures.getJSONObject(0).getString("name"))
        assertEquals("invalid_arguments", failures.getJSONObject(0).getString("reason"))
        assertEquals(true, diagnostics.getBoolean("repair_used"))
        assertEquals(1, diagnostics.getInt("repair_attempt_count"))
        assertEquals(false, diagnostics.getBoolean("fallback_used"))
        assertEquals(789L, diagnostics.getLong("updated_at_ms"))
    }

    @Test
    fun embeddedStatusMarksInvalidInstalledModelAsNotRunnable() {
        val app = RuntimeEnvironment.getApplication()
        val modelDir = File(app.filesDir, "user/models/embedded/gemma4-e2b-it")
        modelDir.mkdirs()
        val modelFile = File(modelDir, "model.litertlm")
        modelFile.writeText("<html><body>not a model</body></html>")
        try {
            val registry = EmbeddedBackendRegistry(context = app)

            val status = registry.statusFor("gemma4-e2b-it")!!

            assertTrue(status.installed)
            assertFalse(status.runnable)
            assertTrue(status.detail.contains("looks invalid"))
            assertTrue(status.lastError.contains("HTML"))
        } finally {
            modelFile.delete()
            modelDir.delete()
        }
    }

    @Test
    fun embeddedStatusMarksKnownIncompatibleGemma4BundleAsNotRunnable() {
        val app = RuntimeEnvironment.getApplication()
        val modelDir = File(app.filesDir, "user/models/embedded/gemma4-e2b-it")
        modelDir.mkdirs()
        val modelFile = File(modelDir, "model.litertlm")
        modelFile.writeText("placeholder model bytes")
        try {
            val registry = EmbeddedBackendRegistry(context = app)

            val status = registry.statusFor("gemma4-e2b-it")!!

            assertTrue(status.installed)
            assertFalse(status.runnable)
            assertTrue(status.detail.contains("looks invalid"))
            assertTrue(status.lastError.contains("cannot load the current Gemma4 LiteRT-LM bundle yet"))
        } finally {
            modelFile.delete()
            modelDir.delete()
        }
    }

    @Test
    fun mergeDiagnosticsStateAccumulatesFailuresAcrossPhases() {
        val state = EmbeddedTurnDiagnosticsState(turnId = 33L)

        EmbeddedTurnProtocol.mergeDiagnosticsState(
            state = state,
            responseSource = "pending",
            finalToolCallCount = 0,
            finalMessageCount = 0,
            selectedTools = listOf("write_file"),
            failedTools = emptyList(),
            toolFailures = emptyList(),
            repairUsed = false,
            repairAttemptCount = 0,
            fallbackUsed = false,
        )
        EmbeddedTurnProtocol.mergeDiagnosticsState(
            state = state,
            responseSource = "pending",
            finalToolCallCount = 0,
            finalMessageCount = 0,
            selectedTools = listOf("mkdir"),
            failedTools = listOf("mkdir"),
            toolFailures = listOf(EmbeddedToolFailure("mkdir", "invalid_arguments")),
            repairUsed = false,
            repairAttemptCount = 0,
            fallbackUsed = false,
        )
        EmbeddedTurnProtocol.mergeDiagnosticsState(
            state = state,
            responseSource = "repaired",
            finalToolCallCount = 1,
            finalMessageCount = 2,
            selectedTools = listOf("write_file"),
            failedTools = emptyList(),
            toolFailures = emptyList(),
            repairUsed = true,
            repairAttemptCount = 1,
            fallbackUsed = false,
        )

        assertEquals(listOf("write_file", "mkdir"), state.selectedTools.toList())
        assertEquals("repaired", state.responseSource)
        assertEquals(1, state.finalToolCallCount)
        assertEquals(2, state.finalMessageCount)
        assertEquals(mapOf("mkdir" to "invalid_arguments"), state.toolFailures)
        assertTrue(state.repairUsed)
        assertEquals(1, state.repairAttemptCount)
        assertFalse(state.fallbackUsed)
    }

    @Test
    fun registryWarmAndUnloadDelegateToBackend() {
        val fake = RecordingEmbeddedBackend()
        val registry = EmbeddedBackendRegistry(
            context = RuntimeEnvironment.getApplication(),
            backendOverride = listOf(fake),
        )

        val warmStatus = registry.warm("gemma4-e2b-it")
        val unloaded = registry.unload("gemma4-e2b-it")

        assertEquals(listOf("warm:gemma4-e2b-it", "unload:gemma4-e2b-it"), fake.events)
        assertTrue(warmStatus.loaded)
        assertTrue(warmStatus.warm)
        assertTrue(unloaded)
    }

    @Test
    fun registryTrimMemoryUnloadsAllBackends() {
        val fake = RecordingEmbeddedBackend()
        val registry = EmbeddedBackendRegistry(
            context = RuntimeEnvironment.getApplication(),
            backendOverride = listOf(fake),
        )

        registry.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        assertEquals(listOf("unload_all"), fake.events)
    }

    private class RecordingEmbeddedBackend : EmbeddedBackend {
        val events = mutableListOf<String>()
        override val backendId: String = "fake"

        override fun status(spec: EmbeddedModelSpec): EmbeddedBackendStatus {
            return EmbeddedBackendStatus(
                model = spec,
                backendId = backendId,
                installed = true,
                runnable = true,
                loaded = true,
                warm = true,
                detail = "fake",
                primaryModelPath = "/tmp/fake",
                candidatePaths = listOf("/tmp/fake"),
                lastError = "",
                lastLoadedAtMs = 1L,
                lastUsedAtMs = 2L,
                lastTurnDiagnostics = EmbeddedTurnDiagnostics(
                    turnId = 1L,
                    lastPhase = "plan",
                    responseSource = "pending",
                    finalToolCallCount = 0,
                    finalMessageCount = 0,
                    selectedTools = listOf("write_file"),
                    failedTools = emptyList(),
                    toolFailures = emptyList(),
                    repairUsed = false,
                    repairAttemptCount = 0,
                    fallbackUsed = false,
                    lastSummary = "selected=1 text=0",
                    updatedAtMs = 3L,
                ),
            )
        }

        override fun generateText(spec: EmbeddedModelSpec, prompt: String): String = "ok"

        override fun generateTurn(spec: EmbeddedModelSpec, request: EmbeddedGenerationRequest): EmbeddedGenerationResult {
            return EmbeddedGenerationResult(emptyList(), org.json.JSONArray(), "ok")
        }

        override fun warm(spec: EmbeddedModelSpec): EmbeddedBackendStatus {
            events += "warm:${spec.id}"
            return status(spec)
        }

        override fun unload(spec: EmbeddedModelSpec): Boolean {
            events += "unload:${spec.id}"
            return true
        }

        override fun unloadAll() {
            events += "unload_all"
        }
    }
}
