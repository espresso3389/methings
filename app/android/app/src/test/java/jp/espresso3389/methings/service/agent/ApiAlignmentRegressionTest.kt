package jp.espresso3389.methings.service.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ApiAlignmentRegressionTest {

    @Test
    fun capabilityMapCoversAllPermissionedActions() {
        val missing = ToolDefinitions.ACTIONS.keys
            .filter { ToolDefinitions.ACTIONS[it]?.requiresPermission == true }
            .filter { action ->
                CapabilityMap.capabilitiesForCode("""device_api("$action", {})""").isEmpty()
            }

        assertTrue("Missing CapabilityMap coverage for: $missing", missing.isEmpty())
    }

    @Test
    fun reviewedApiDocsUseCurrentActionNames() {
        val repoRoot = repoRoot()
        val expectedHeadings = mapOf(
            "user/docs/api/ble.md" to listOf(
                "## ble.scan.start",
                "## ble.scan.stop",
                "## ble.gatt.services",
                "## ble.gatt.read",
                "## ble.gatt.write",
                "## ble.gatt.notify.start",
                "## ble.gatt.notify.stop",
            ),
            "user/docs/api/screen_record.md" to listOf(
                "## screenrec.status",
                "## screenrec.start",
                "## screenrec.stop",
                "## screenrec.config.get",
                "## screenrec.config.set",
            ),
            "user/docs/api/video_record.md" to listOf(
                "## video.record.status",
                "## video.record.start",
                "## video.record.stop",
                "## video.record.config.get",
                "## video.record.config.set",
                "## video.stream.start",
                "## video.stream.stop",
            ),
            "user/docs/api/media_stream.md" to listOf(
                "## media.stream.status",
                "## media.stream.audio.start",
                "## media.stream.video.start",
                "## media.stream.stop",
            ),
            "user/docs/api/mcu.md" to listOf(
                "## mcu.flash.plan",
                "## mcu.diag.serial",
                "### mcu.micropython.exec",
                "### mcu.micropython.write_file",
                "### mcu.micropython.soft_reset",
            ),
            "user/docs/api/uvc.md" to listOf(
                "## uvc.mjpeg.capture",
            ),
        )
        val legacyHeadings = mapOf(
            "user/docs/api/ble.md" to listOf(
                "## ble.scan_start",
                "## ble.scan_stop",
                "## ble.gatt_services",
                "## ble.gatt_read",
                "## ble.gatt_write",
                "## ble.gatt_notify_start",
                "## ble.gatt_notify_stop",
            ),
            "user/docs/api/screen_record.md" to listOf("## screen_record."),
            "user/docs/api/video_record.md" to listOf("## video_record.", "## video_stream."),
            "user/docs/api/media_stream.md" to listOf("## media_stream."),
            "user/docs/api/mcu.md" to listOf(
                "## mcu.flash_plan",
                "## mcu.diag_serial",
                "### mcu.micropython_exec",
                "### mcu.micropython_write_file",
                "### mcu.micropython_soft_reset",
            ),
            "user/docs/api/uvc.md" to listOf("## uvc.mjpeg_capture"),
        )

        for ((relativePath, headings) in expectedHeadings) {
            val text = readText(repoRoot.resolve(relativePath))
            for (heading in headings) {
                assertTrue("Expected '$heading' in $relativePath", text.contains(heading))
            }
        }
        for ((relativePath, headings) in legacyHeadings) {
            val text = readText(repoRoot.resolve(relativePath))
            for (heading in headings) {
                assertFalse("Legacy heading '$heading' still present in $relativePath", text.contains(heading))
            }
        }
    }

    @Test
    fun gatedWebsocketEndpointsRequirePermissionChecks() {
        val source = readText(
            repoRoot().resolve("app/android/app/src/main/java/jp/espresso3389/methings/service/LocalHttpServer.kt")
        )
        val endpoints = listOf(
            "/ws/ble/events",
            "/ws/camera/preview",
            "/ws/stt/events",
            "/ws/audio/pcm",
            "/ws/video/frames",
            "/ws/media/stream/",
        )

        for (endpoint in endpoints) {
            val block = extractWsBlock(source, endpoint)
            assertTrue("Expected ensureDevicePermissionForWs in block for $endpoint", block.contains("ensureDevicePermissionForWs("))
        }
    }

    @Test
    fun permissionRequiredRoutesAreBrokerGuarded() {
        val source = readText(
            repoRoot().resolve("app/android/app/src/main/java/jp/espresso3389/methings/service/LocalHttpServer.kt")
        )

        val routeMarkers = listOf(
            """uri == "/app/update/install" && session.method == Method.POST""",
            """uri == "/brain/memory" && session.method == Method.POST""",
            """uri == "/android/permissions/request" || uri == "/android/permissions/request/") && session.method == Method.POST""",
        )

        for (marker in routeMarkers) {
            val block = extractRouteBlock(source, marker)
            assertTrue("Expected ensureDevicePermission in route block for marker: $marker", block.contains("ensureDevicePermission("))
        }
    }

    private fun extractWsBlock(source: String, endpoint: String): String {
        val marker = if (endpoint.endsWith("/")) {
            """val mediaStreamPrefix = "$endpoint""""
        } else {
            """if (uri == "$endpoint")"""
        }
        val start = source.indexOf(marker)
        require(start >= 0) { "Marker not found: $marker" }
        val next = source.indexOf("\n        if (uri == ", start + marker.length)
            .takeIf { it >= 0 }
            ?: source.indexOf("\n        val ", start + marker.length).takeIf { it >= 0 }
            ?: source.length
        return source.substring(start, next)
    }

    private fun extractRouteBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "Marker not found: $marker" }
        val next = source.indexOf("\n            uri == ", start + marker.length)
            .takeIf { it >= 0 }
            ?: source.indexOf("\n            else ->", start + marker.length).takeIf { it >= 0 }
            ?: source.length
        return source.substring(start, next)
    }

    private fun repoRoot(): Path {
        var current: Path = Paths.get("").toAbsolutePath()
        while (true) {
            if (Files.exists(current.resolve("AGENTS.md")) && Files.isDirectory(current.resolve("user/docs/api"))) {
                return current
            }
            val parent = current.parent ?: error("Repository root not found from ${Paths.get("").toAbsolutePath()}")
            current = parent
        }
    }

    private fun readText(path: Path): String = File(path.toUri()).readText()
}
