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

    private fun invokePrivate(target: Any, name: String, arg: JSONObject) {
        val method: Method = target.javaClass.getDeclaredMethod(name, JSONObject::class.java)
        method.isAccessible = true
        method.invoke(target, arg)
    }

    private fun createHarness(
        llmPayloads: List<JSONObject>,
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
            providerUrl = "https://example.test/v1/chat/completions",
            model = "gpt-test",
            toolPolicy = "auto",
            systemPrompt = "test prompt",
            maxToolRounds = 4,
            maxActions = 4,
        ))
        whenever(configManager.getModel()).thenReturn("gpt-test")
        whenever(configManager.getVendor()).thenReturn("openai")
        whenever(configManager.getBaseUrl()).thenReturn("https://example.test/v1")
        whenever(configManager.getApiKey()).thenReturn("test-key")
        whenever(configManager.resolveProviderUrl(any(), any())).thenReturn("https://example.test/v1/chat/completions")

        val runtime = AgentRuntime(
            userDir = userDir,
            sysDir = sysDir,
            storage = storage,
            journalStore = journalStore,
            toolExecutor = toolExecutor,
            llmClient = llmClient,
            configManager = configManager,
            emitLog = { _, _ -> },
        )
        return Harness(runtime, storage, llmClient)
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
    )
}
