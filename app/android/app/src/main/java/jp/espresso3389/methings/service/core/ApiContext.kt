package jp.espresso3389.methings.service.core

/**
 * Identifies the caller of a Core API method.
 *
 * [identity] — user / install identity string (maps to permission scopes).
 * [source]   — call origin: "http", "agent", "js_runtime", "scheduler".
 */
data class ApiContext(
    val identity: String,
    val source: String,
)

/**
 * Result of a permission check performed by [PermissionCoreService].
 */
sealed class PermissionResult {
    /** Permission is granted; proceed with the action. */
    data object Approved : PermissionResult()

    /** Permission is pending (user prompt shown) or denied.
     *  The [response] map should be returned to the caller as-is. */
    data class Pending(val id: String, val response: Map<String, Any?>) : PermissionResult()
}
