package jp.espresso3389.methings.service.core

import android.util.Log
import jp.espresso3389.methings.service.agent.ToolDefinitions

/**
 * Central action dispatcher for the Core API layer.
 *
 * Routes `action` strings to the appropriate [UsbCoreService], [SerialCoreService],
 * or [McuCoreService] method. Actions not handled by core services are forwarded
 * to [fallback] (which may use HTTP loopback for Phase 1).
 */
class CoreApiDispatcher(
    val usb: UsbCoreService,
    val serial: SerialCoreService,
    val mcu: McuCoreService,
    val permission: PermissionCoreService,
    /**
     * Fallback handler for actions not yet extracted into core services.
     * Takes (action, params, ctx) and returns a result map.
     * During Phase 1 this is an HTTP loopback or direct LocalHttpServer call.
     */
    private val fallback: ((action: String, params: Map<String, Any?>, ctx: ApiContext) -> Map<String, Any?>)? = null,
) {
    private companion object {
        const val TAG = "CoreApiDispatcher"
    }

    /**
     * Dispatch an action to the appropriate core service.
     *
     * @param action dot-separated action name (e.g. "usb.open", "serial.read", "mcu.flash")
     * @param params action parameters as a Map
     * @param ctx caller identity and source
     * @return result Map (always contains "status" or "error")
     */
    fun dispatch(action: String, params: Map<String, Any?>, ctx: ApiContext): Map<String, Any?> {
        return try {
            dispatchInternal(action, params, ctx)
        } catch (ex: Exception) {
            Log.e(TAG, "dispatch($action) failed", ex)
            CoreApiUtils.error("dispatch_failed", 500, mapOf("detail" to (ex.message ?: "")))
        }
    }

    private fun dispatchInternal(action: String, params: Map<String, Any?>, ctx: ApiContext): Map<String, Any?> {
        return when (action) {
            // ---- USB ----
            "usb.list" -> usb.list(ctx, params)
            "usb.status" -> usb.status(ctx, params)
            "usb.open" -> usb.open(ctx, params)
            "usb.close" -> usb.close(ctx, params)
            "usb.control_transfer" -> usb.controlTransfer(ctx, params)
            "usb.raw_descriptors" -> usb.rawDescriptors(ctx, params)
            "usb.claim_interface" -> usb.claimInterface(ctx, params)
            "usb.release_interface" -> usb.releaseInterface(ctx, params)
            "usb.bulk_transfer" -> usb.bulkTransfer(ctx, params)
            "usb.iso_transfer" -> usb.isoTransfer(ctx, params)

            // ---- Serial ----
            "serial.ws.contract" -> serial.wsContract(ctx, params)
            "serial.status" -> serial.status(ctx, params)
            "serial.open" -> serial.open(ctx, params)
            "serial.list_ports" -> serial.listPorts(ctx, params)
            "serial.close" -> serial.close(ctx, params)
            "serial.read" -> serial.read(ctx, params)
            "serial.write" -> serial.write(ctx, params)
            "serial.lines" -> serial.lines(ctx, params)
            "serial.exchange" -> serial.exchange(ctx, params)

            // ---- MCU ----
            "mcu.models" -> mcu.models(ctx, params)
            "mcu.probe" -> mcu.probe(ctx, params)
            "mcu.flash" -> mcu.flash(ctx, params)
            "mcu.flash.plan" -> mcu.flashPlan(ctx, params)
            "mcu.diag.serial" -> mcu.diagSerial(ctx, params)
            "mcu.serial_lines" -> mcu.serialLines(ctx, params)
            "mcu.reset" -> mcu.reset(ctx, params)
            "mcu.serial_monitor" -> mcu.serialMonitor(ctx, params)
            "mcu.micropython.exec" -> mcu.micropythonExec(ctx, params)
            "mcu.micropython.write_file" -> mcu.micropythonWriteFile(ctx, params)
            "mcu.micropython.soft_reset" -> mcu.micropythonSoftReset(ctx, params)

            // ---- Fallback (actions not yet extracted) ----
            else -> {
                fallback?.invoke(action, params, ctx)
                    ?: CoreApiUtils.error("unknown_action", 400, mapOf("action" to action))
            }
        }
    }

    /** Check if an action is handled directly by a core service (not fallback). */
    fun isDirectAction(action: String): Boolean {
        return action.startsWith("usb.") || action.startsWith("serial.") || action.startsWith("mcu.")
    }

    /** Capability string for a given action (used by ToolDefinitions). */
    fun capabilityFor(action: String): String? {
        return when {
            action.startsWith("usb.") -> "usb"
            action.startsWith("serial.") -> "usb" // serial uses USB capability
            action.startsWith("mcu.") -> "usb"    // MCU uses USB capability
            else -> {
                val spec = ToolDefinitions.ACTIONS[action]
                if (spec?.requiresPermission == true) {
                    // Derive capability from action prefix
                    action.substringBefore('.').let { prefix ->
                        when (prefix) {
                            "camera" -> "camera"
                            "ble" -> "ble"
                            "tts", "stt" -> "audio"
                            "audio", "media" -> "audio"
                            "video", "screenrec" -> "video"
                            "location" -> "location"
                            "sensor", "sensors" -> "sensors"
                            "sshd" -> "sshd"
                            "me" -> "me"
                            else -> prefix
                        }
                    }
                } else null
            }
        }
    }
}
