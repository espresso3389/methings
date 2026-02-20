package jp.espresso3389.methings.service.agent

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class JsResult(
    val status: String,
    val result: String,
    val consoleOutput: String,
    val error: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("status", status)
        put("result", result)
        put("console_output", consoleOutput)
        if (error.isNotEmpty()) put("error", error)
    }
}

class JsEngine {

    fun execute(code: String, timeoutMs: Long = 30_000): JsResult {
        val consoleBuffer = StringBuilder()

        val executor = Executors.newSingleThreadExecutor()
        var quickJs: QuickJs? = null

        try {
            val future = executor.submit<JsResult> {
                runBlocking {
                    val js = QuickJs.create(Dispatchers.Unconfined)
                    quickJs = js
                    try {
                        js.memoryLimit = 32 * 1024 * 1024L
                        js.maxStackSize = 512 * 1024L

                        js.define("console") {
                            function("log") { args ->
                                consoleBuffer.appendLine(args.joinToString(" ") { stringify(it) })
                            }
                            function("warn") { args ->
                                consoleBuffer.appendLine("[WARN] " + args.joinToString(" ") { stringify(it) })
                            }
                            function("error") { args ->
                                consoleBuffer.appendLine("[ERROR] " + args.joinToString(" ") { stringify(it) })
                            }
                            function("info") { args ->
                                consoleBuffer.appendLine("[INFO] " + args.joinToString(" ") { stringify(it) })
                            }
                        }

                        val rawResult = js.evaluate<Any?>(code)
                        val resultStr = stringify(rawResult)

                        JsResult(
                            status = "ok",
                            result = resultStr,
                            consoleOutput = consoleBuffer.toString().trimEnd(),
                        )
                    } catch (e: QuickJsException) {
                        JsResult(
                            status = "error",
                            result = "",
                            consoleOutput = consoleBuffer.toString().trimEnd(),
                            error = e.message ?: "QuickJS error",
                        )
                    } catch (e: Exception) {
                        JsResult(
                            status = "error",
                            result = "",
                            consoleOutput = consoleBuffer.toString().trimEnd(),
                            error = e.message ?: "execution_failed",
                        )
                    } finally {
                        js.close()
                    }
                }
            }

            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            // Force-close QuickJS to interrupt the blocked native thread
            try {
                quickJs?.close()
            } catch (_: Exception) {}
            return JsResult(
                status = "error",
                result = "",
                consoleOutput = consoleBuffer.toString().trimEnd(),
                error = "execution_timeout: exceeded ${timeoutMs}ms",
            )
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause ?: e
            return JsResult(
                status = "error",
                result = "",
                consoleOutput = consoleBuffer.toString().trimEnd(),
                error = cause.message ?: "execution_failed",
            )
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        private const val TAG = "JsEngine"

        private fun stringify(value: Any?): String {
            return when (value) {
                null -> "undefined"
                is String -> value
                is Boolean, is Number -> value.toString()
                is Map<*, *> -> JSONObject(value as Map<String, Any?>).toString()
                is List<*> -> org.json.JSONArray(value).toString()
                is Array<*> -> org.json.JSONArray(value.toList()).toString()
                else -> value.toString()
            }
        }
    }
}
