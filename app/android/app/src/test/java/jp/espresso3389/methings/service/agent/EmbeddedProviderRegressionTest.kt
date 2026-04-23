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
        ).toJson()

        assertTrue(json.getBoolean("loaded"))
        assertTrue(json.getBoolean("warm"))
        assertEquals(123L, json.getLong("last_loaded_at_ms"))
        assertEquals(456L, json.getLong("last_used_at_ms"))
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
