package jp.espresso3389.methings.service.core

import jp.espresso3389.methings.perm.InstallIdentity
import jp.espresso3389.methings.perm.PermissionPrefs
import jp.espresso3389.methings.perm.PermissionStore
import jp.espresso3389.methings.perm.PermissionStoreFacade

/**
 * Permission checking extracted from LocalHttpServer.ensureDevicePermission.
 *
 * Side-effect callbacks ([onPrompt], [onAutoApproved]) remain in LocalHttpServer
 * so this service stays decoupled from Intent broadcasting and agent notifications.
 */
class PermissionCoreService(
    private val permissionStore: PermissionStoreFacade,
    private val permissionPrefs: PermissionPrefs,
    private val installIdentity: InstallIdentity,
    /** Called when a user-facing permission prompt should be shown. */
    private val onPrompt: (id: String, tool: String, detail: String) -> Unit,
    /** Called when a permission is auto-approved (skip-permissions mode or me.me). */
    private val onAutoApproved: (PermissionStore.PermissionRequest) -> Unit,
) {
    /**
     * Check whether [ctx] is permitted to invoke [tool] / [capability].
     *
     * @param params mutable param map — `permission_id` will be consumed / injected.
     * @return [PermissionResult.Approved] or [PermissionResult.Pending] with a response map.
     */
    fun ensurePermission(
        ctx: ApiContext,
        params: Map<String, Any?>,
        tool: String,
        capability: String,
        detail: String,
    ): PermissionResult {
        val identity = (params["identity"]?.toString()?.trim() ?: "")
            .ifBlank { ctx.identity }
            .ifBlank { installIdentity.get() }
        var permissionId = (params["permission_id"]?.toString()?.trim()) ?: ""

        val scope = if (permissionPrefs.rememberApprovals()) "persistent" else "session"

        // 1. Check for reusable approved permission
        if (!isApproved(permissionId) && identity.isNotBlank()) {
            val reusable = permissionStore.findReusableApproved(
                tool = tool, scope = scope, identity = identity, capability = capability,
            )
            if (reusable != null) permissionId = reusable.id
        }

        // 2. Auto-approve me.me tool (device-to-device control plane)
        if (tool == "device.me_me" && !isApproved(permissionId)) {
            val approved = autoApprove(tool, detail, scope, identity, capability)
            return PermissionResult.Approved
        }

        // 3. If still not approved, either skip-permissions or prompt
        if (!isApproved(permissionId)) {
            if (permissionPrefs.dangerouslySkipPermissions()) {
                autoApprove(tool, detail, scope, identity, capability)
                return PermissionResult.Approved
            }
            // Reuse existing pending request for the same (identity, tool, capability)
            val existing = if (identity.isNotBlank()) {
                permissionStore.findRecentPending(tool = tool, identity = identity, capability = capability)
            } else null
            val req = existing ?: permissionStore.create(
                tool = tool,
                detail = detail.take(240),
                scope = scope,
                identity = identity,
                capability = capability,
            )
            onPrompt(req.id, req.tool, req.detail)
            val response = mapOf(
                "status" to "permission_required",
                "_http_status" to 403,
                "request" to mapOf(
                    "id" to req.id,
                    "status" to req.status,
                    "tool" to req.tool,
                    "detail" to req.detail,
                    "scope" to req.scope,
                    "created_at" to req.createdAt,
                    "identity" to req.identity,
                    "capability" to req.capability,
                ),
            )
            return PermissionResult.Pending(req.id, response)
        }

        return PermissionResult.Approved
    }

    /** Check if a permission ID is approved (consuming "once" scope). */
    fun isApproved(permissionId: String, consume: Boolean = true): Boolean {
        if (permissionId.isBlank()) return false
        val req = permissionStore.get(permissionId) ?: return false
        if (req.status != "approved") return false
        if (consume && req.scope == "once") {
            permissionStore.markUsed(permissionId)
        }
        return true
    }

    private fun autoApprove(
        tool: String, detail: String, scope: String, identity: String, capability: String,
    ): PermissionStore.PermissionRequest {
        val req = permissionStore.create(
            tool = tool, detail = detail.take(240), scope = scope,
            identity = identity, capability = capability,
        )
        val updated = permissionStore.updateStatus(req.id, "approved") ?: req.copy(status = "approved")
        onAutoApproved(updated)
        return updated
    }
}
