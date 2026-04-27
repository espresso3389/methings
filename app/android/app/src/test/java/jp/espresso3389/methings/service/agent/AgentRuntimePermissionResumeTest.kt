package jp.espresso3389.methings.service.agent

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Method
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgentRuntimePermissionResumeTest {

    @Test
    fun permissionApprovalResumesPendingToolSequenceWithoutExtraLlmRound() {
        val executedTools = mutableListOf<String>()
        val harness = createHarness(
            llmPayloads = listOf(initialToolCallPayload(), finalTextPayload("done")),
        ) { toolName, args ->
            executedTools += "$toolName:${args.toString()}"
            when (executedTools.size) {
                1 -> JSONObject()
                    .put("status", "permission_required")
                    .put("request", JSONObject().put("id", "perm-1"))
                else -> JSONObject().put("status", "ok")
            }
        }
        val runtime = harness.runtime

        invokePrivate(runtime, "processChat", JSONObject().apply {
            put("id", "chat_1")
            put("kind", "chat")
            put("text", "test request")
            put("meta", JSONObject().put("session_id", "s1").put("actor", "human"))
        })

        val awaitingPermissions = awaitingPermissions(runtime)
        assertTrue(awaitingPermissions.containsKey("perm-1"))
        verify(harness.llmClient, times(1)).streamingPost(any(), any(), any(), any(), any(), any(), any())

        invokePrivate(runtime, "processEvent", JSONObject().apply {
            put("id", "event_1")
            put("kind", "event")
            put("name", "permission.resolved")
            put("payload", JSONObject().put("permission_id", "perm-1").put("status", "approved"))
            put("meta", JSONObject().put("session_id", "s1"))
        })

        assertEquals(
            listOf(
                """write_file:{"path":"a.txt","content":"hello"}""",
                """write_file:{"path":"a.txt","content":"hello"}""",
                """mkdir:{"path":"dir","parents":true}""",
            ),
            executedTools,
        )
        verify(harness.llmClient, times(2)).streamingPost(any(), any(), any(), any(), any(), any(), any())
        verify(harness.storage).addChatMessage(eq("s1"), eq("assistant"), eq("done"), any())
    }

    @Test
    fun permissionDeniedRecordsBlockerWithoutResumingTurn() {
        val executedTools = mutableListOf<String>()
        val harness = createHarness(
            llmPayloads = listOf(initialToolCallPayload()),
        ) { toolName, args ->
            executedTools += "$toolName:${args.toString()}"
            JSONObject()
                .put("status", "permission_required")
                .put("request", JSONObject().put("id", "perm-denied"))
        }

        invokePrivate(harness.runtime, "processChat", JSONObject().apply {
            put("id", "chat_1")
            put("kind", "chat")
            put("text", "test request")
            put("meta", JSONObject().put("session_id", "s1").put("actor", "human"))
        })
        assertTrue(awaitingPermissions(harness.runtime).containsKey("perm-denied"))

        invokePrivate(harness.runtime, "processEvent", JSONObject().apply {
            put("id", "event_1")
            put("kind", "event")
            put("name", "permission.resolved")
            put("payload", JSONObject().put("permission_id", "perm-denied").put("status", "denied"))
            put("meta", JSONObject().put("session_id", "s1"))
        })

        assertEquals(1, executedTools.size)
        assertTrue(awaitingPermissions(harness.runtime).isEmpty())
        verify(harness.llmClient, times(1)).streamingPost(any(), any(), any(), any(), any(), any(), any())
        verify(harness.storage, never()).addChatMessage(eq("s1"), eq("assistant"), eq("done"), any())
        verify(harness.storage).addChatMessage(
            eq("s1"),
            eq("assistant"),
            eq("Permission denied for write_file."),
            any()
        )
    }

    @Test
    fun permissionApprovalOnSecondToolDoesNotReexecuteCompletedFirstTool() {
        val executedTools = mutableListOf<String>()
        val harness = createHarness(
            llmPayloads = listOf(initialToolCallPayload(), finalTextPayload("done")),
        ) { toolName, args ->
            executedTools += "$toolName:${args.toString()}"
            when (executedTools.size) {
                1 -> JSONObject().put("status", "ok")
                2 -> JSONObject()
                    .put("status", "permission_required")
                    .put("request", JSONObject().put("id", "perm-2"))
                else -> JSONObject().put("status", "ok")
            }
        }

        invokePrivate(harness.runtime, "processChat", JSONObject().apply {
            put("id", "chat_1")
            put("kind", "chat")
            put("text", "test request")
            put("meta", JSONObject().put("session_id", "s1").put("actor", "human"))
        })

        invokePrivate(harness.runtime, "processEvent", JSONObject().apply {
            put("id", "event_2")
            put("kind", "event")
            put("name", "permission.resolved")
            put("payload", JSONObject().put("permission_id", "perm-2").put("status", "approved"))
            put("meta", JSONObject().put("session_id", "s1"))
        })

        assertEquals(
            listOf(
                """write_file:{"path":"a.txt","content":"hello"}""",
                """mkdir:{"path":"dir","parents":true}""",
                """mkdir:{"path":"dir","parents":true}""",
            ),
            executedTools,
        )
        verify(harness.llmClient, times(2)).streamingPost(any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun openAiChatToolResultWithImageAddsFollowUpUserMessage() {
        val harness = createHarness(
            llmPayloads = listOf(finalTextPayload("done")),
        ) { _, _ ->
            JSONObject().put("status", "ok")
        }
        val input = org.json.JSONArray()

        invokePrivateAppendToolResult(
            runtime = harness.runtime,
            kind = ProviderKind.OPENAI_CHAT,
            input = input,
            callId = "call_img",
            result = JSONObject().put("status", "ok").put("_media_hint", "image attached"),
            toolName = "analyze_image",
            mediaData = ExtractedMedia("QUJD", "image/png", "image"),
        )

        assertEquals(2, input.length())

        val toolMessage = input.getJSONObject(0)
        assertEquals("tool", toolMessage.getString("role"))
        assertEquals("call_img", toolMessage.getString("tool_call_id"))
        assertTrue(toolMessage.getString("content").contains("_media_hint"))

        val userMessage = input.getJSONObject(1)
        assertEquals("user", userMessage.getString("role"))
        val content = userMessage.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/png;base64,QUJD",
            content.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun openAiChatBuildInitialInputStripsHistoricalMediaRelPaths() {
        val harness = createHarness(
            llmPayloads = listOf(finalTextPayload("done")),
        ) { _, _ ->
            JSONObject().put("status", "ok")
        }
        val imageDir = File(harness.userDir, "captures/chat").apply { mkdirs() }
        val imageFile = File(imageDir, "current.png")
        imageFile.writeBytes(Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a7l8AAAAASUVORK5CYII="
        ))

        val dialogue = listOf(
            JSONObject().put("role", "user").put("text", "old request\nrel_path: captures/chat/old.png"),
            JSONObject().put("role", "assistant").put("text", "previous answer"),
        )

        val input = invokePrivateBuildInitialInput(
            runtime = harness.runtime,
            kind = ProviderKind.OPENAI_CHAT,
            dialogue = dialogue,
            journalBlob = "",
            curText = "new request\nrel_path: captures/chat/current.png",
            item = JSONObject().put("id", "chat_test"),
            supportedMedia = setOf("image"),
        )

        assertEquals(3, input.length())
        assertEquals("old request", input.getJSONObject(0).getString("content"))
        assertEquals("previous answer", input.getJSONObject(1).getString("content"))

        val current = input.getJSONObject(2)
        assertEquals("user", current.getString("role"))
        val content = current.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("new request", content.getJSONObject(0).getString("text").trim())
        assertEquals("image_url", content.getJSONObject(1).getString("type"))

        val serialized = input.toString()
        assertTrue(!serialized.contains("captures/chat/old.png"))
        assertTrue(!serialized.contains("captures/chat/current.png"))
    }

    @Test
    fun embeddedBuildInitialInputPreservesUnsupportedCurrentMediaRelPath() {
        val harness = createHarness(
            llmPayloads = listOf(finalTextPayload("done")),
        ) { _, _ ->
            JSONObject().put("status", "ok")
        }

        val input = invokePrivateBuildInitialInput(
            runtime = harness.runtime,
            kind = ProviderKind.EMBEDDED,
            dialogue = emptyList(),
            journalBlob = "Journal (per-session, keep short for context efficiency):\n(empty)",
            curText = "rel_path: uploads/chat/photo.jpg",
            item = JSONObject().put("id", "chat_img"),
            supportedMedia = emptySet(),
        )

        assertEquals(1, input.length())
        val current = input.getJSONObject(0)
        assertEquals("user", current.getString("role"))
        val content = current.getString("content")
        assertTrue(content.contains("rel_path: uploads/chat/photo.jpg"))
        assertTrue(content.contains("current provider cannot receive them as native multimodal input"))
        assertTrue(content.contains("do not treat this as a journal request"))
    }

    @Test
    fun openRouterQwenEmptyMediaFollowUpFallsBackToDirectImageRequest() {
        val harness = createHarness(
            llmPayloads = listOf(
                imageAnalyzeToolCallPayload(),
                emptyAssistantPayload(),
                finalTextPayload("画像にはテストパターンがあります。"),
            ),
            model = "qwen/qwen3.5-397b-a17b",
            providerUrl = "https://openrouter.ai/api/v1/chat/completions",
        ) { _, _ ->
            JSONObject()
                .put("status", "ok")
                .put("prompt", "この画像の内容を詳しく説明してください。")
                .put("_media", JSONObject()
                    .put("type", "image")
                    .put("mime_type", "image/png")
                    .put("base64", "QUJD"))
        }

        invokePrivate(harness.runtime, "processChat", JSONObject().apply {
            put("id", "chat_img")
            put("kind", "chat")
            put("text", "rel_path: captures/chat/current.png")
            put("meta", JSONObject().put("session_id", "s1").put("actor", "human"))
        })

        verify(harness.llmClient, times(3)).streamingPost(any(), any(), any(), any(), any(), any(), any())
        verify(harness.storage).addChatMessage(
            eq("s1"),
            eq("assistant"),
            eq("画像にはテストパターンがあります。"),
            any()
        )
    }

    private fun initialToolCallPayload(): JSONObject = JSONObject(
        """
        {
          "choices": [
            {
              "message": {
                "role": "assistant",
                "content": null,
                "tool_calls": [
                  {
                    "id": "call_1",
                    "type": "function",
                    "function": {
                      "name": "write_file",
                      "arguments": "{\"path\":\"a.txt\",\"content\":\"hello\"}"
                    }
                  },
                  {
                    "id": "call_2",
                    "type": "function",
                    "function": {
                      "name": "mkdir",
                      "arguments": "{\"path\":\"dir\",\"parents\":true}"
                    }
                  }
                ]
              },
              "finish_reason": "tool_calls"
            }
          ]
        }
        """.trimIndent()
    )

    private fun imageAnalyzeToolCallPayload(): JSONObject = JSONObject(
        """
        {
          "choices": [
            {
              "message": {
                "role": "assistant",
                "content": null,
                "tool_calls": [
                  {
                    "id": "call_img",
                    "type": "function",
                    "function": {
                      "name": "analyze_image",
                      "arguments": "{\"path\":\"captures/chat/current.png\",\"prompt\":\"この画像の内容を詳しく説明してください。\"}"
                    }
                  }
                ]
              },
              "finish_reason": "tool_calls"
            }
          ]
        }
        """.trimIndent()
    )

    private fun finalTextPayload(text: String): JSONObject = JSONObject(
        """
        {
          "choices": [
            {
              "message": {
                "role": "assistant",
                "content": "$text"
              },
              "finish_reason": "stop"
            }
          ]
        }
        """.trimIndent()
    )

    private fun emptyAssistantPayload(): JSONObject = JSONObject(
        """
        {
          "choices": [
            {
              "message": {
                "role": "assistant",
                "content": null
              },
              "finish_reason": "stop"
            }
          ]
        }
        """.trimIndent()
    )

    private fun invokePrivate(target: Any, name: String, arg: JSONObject) {
        val method: Method = target.javaClass.getDeclaredMethod(name, JSONObject::class.java)
        method.isAccessible = true
        method.invoke(target, arg)
    }

    private fun invokePrivateAppendToolResult(
        runtime: AgentRuntime,
        kind: ProviderKind,
        input: org.json.JSONArray,
        callId: String,
        result: JSONObject,
        toolName: String,
        mediaData: ExtractedMedia?,
    ) {
        val method = runtime.javaClass.getDeclaredMethod(
            "appendToolResult",
            ProviderKind::class.java,
            org.json.JSONArray::class.java,
            String::class.java,
            JSONObject::class.java,
            String::class.java,
            ExtractedMedia::class.java,
        )
        method.isAccessible = true
        method.invoke(runtime, kind, input, callId, result, toolName, mediaData)
    }

    private fun invokePrivateBuildInitialInput(
        runtime: AgentRuntime,
        kind: ProviderKind,
        dialogue: List<JSONObject>,
        journalBlob: String,
        curText: String,
        item: JSONObject,
        supportedMedia: Set<String>,
    ): org.json.JSONArray {
        val method = runtime.javaClass.getDeclaredMethod(
            "buildInitialInput",
            ProviderKind::class.java,
            List::class.java,
            String::class.java,
            String::class.java,
            JSONObject::class.java,
            Set::class.java,
        )
        method.isAccessible = true
        return method.invoke(runtime, kind, dialogue, journalBlob, curText, item, supportedMedia) as org.json.JSONArray
    }

    private fun createHarness(
        llmPayloads: List<JSONObject>,
        model: String = "gpt-test",
        providerUrl: String = "https://example.test/v1/chat/completions",
        toolHandler: (String, JSONObject) -> JSONObject,
    ): Harness {
        val root = Files.createTempDirectory("agent-runtime-test").toFile()
        val userDir = File(root, "user").apply { mkdirs() }
        val sysDir = File(root, "sys").apply { mkdirs() }

        val storage = mock<AgentStorage>()
        whenever(storage.listChatMessages(any(), any())).thenReturn(emptyList())

        val journalStore = mock<JournalStore>()
        whenever(journalStore.getCurrent(any())).thenReturn(JSONObject()
            .put("status", "ok")
            .put("text", ""))

        val toolExecutor = mock<ToolExecutor>()
        whenever(toolExecutor.imageResizeEnabled).thenReturn(false)
        whenever(toolExecutor.imageJpegQuality).thenReturn(90)
        whenever(toolExecutor.imageMaxDimPx).thenReturn(2048)
        doAnswer { invocation ->
            toolHandler(
                invocation.getArgument(0),
                invocation.getArgument(1),
            )
        }.whenever(toolExecutor).executeFunctionTool(any(), any(), any())

        val llmClient = mock<LlmClient>()
        whenever(llmClient.detectProviderKind(any(), any())).thenReturn(ProviderKind.OPENAI_CHAT)
        whenever(llmClient.buildHeaders(any(), any(), any())).thenReturn(emptyMap())
        require(llmPayloads.isNotEmpty()) { "llmPayloads must not be empty" }
        whenever(llmClient.streamingPost(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(llmPayloads.first(), *llmPayloads.drop(1).toTypedArray())

        val configManager = mock<AgentConfigManager>()
        whenever(configManager.loadFull()).thenReturn(AgentConfig(
            enabled = true,
            autoStart = true,
            vendor = "openai",
            providerUrl = providerUrl,
            model = model,
            toolPolicy = "auto",
            systemPrompt = "test prompt",
            maxToolRounds = 4,
            maxActions = 4,
        ))
        whenever(configManager.getModel()).thenReturn(model)
        whenever(configManager.getVendor()).thenReturn("openai")
        whenever(configManager.getBaseUrl()).thenReturn(providerUrl.substringBefore("/chat/completions"))
        whenever(configManager.getApiKey()).thenReturn("test-key")
        whenever(configManager.resolveProviderUrl(any(), any())).thenReturn(providerUrl)
        val embeddedBackendRegistry = mock<EmbeddedBackendRegistry>()

        val runtime = AgentRuntime(
            userDir = userDir,
            sysDir = sysDir,
            storage = storage,
            journalStore = journalStore,
            toolExecutor = toolExecutor,
            llmClient = llmClient,
            configManager = configManager,
            embeddedBackendRegistry = embeddedBackendRegistry,
            emitLog = { _, _ -> },
        )
        return Harness(runtime, storage, llmClient, userDir)
    }

    @Suppress("UNCHECKED_CAST")
    private fun awaitingPermissions(runtime: AgentRuntime): ConcurrentHashMap<String, JSONObject> {
        val field = runtime.javaClass.getDeclaredField("awaitingPermissions")
        field.isAccessible = true
        return field.get(runtime) as ConcurrentHashMap<String, JSONObject>
    }

    private data class Harness(
        val runtime: AgentRuntime,
        val storage: AgentStorage,
        val llmClient: LlmClient,
        val userDir: File,
    )
}
