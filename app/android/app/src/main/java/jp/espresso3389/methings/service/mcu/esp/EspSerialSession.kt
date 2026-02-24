package jp.espresso3389.methings.service.mcu.esp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.ByteArrayOutputStream
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal class EspSlipCodec {
    private val pending = ArrayDeque<Int>()
    private val frame = ArrayList<Byte>()
    private var inFrame = false

    fun feed(data: ByteArray, length: Int) {
        val n = length.coerceIn(0, data.size)
        for (i in 0 until n) pending.addLast(data[i].toInt() and 0xFF)
    }

    fun nextFrame(): ByteArray? {
        while (pending.isNotEmpty()) {
            val b = pending.removeFirst()
            if (b == 0xC0) {
                if (!inFrame) {
                    inFrame = true
                    frame.clear()
                    continue
                }
                val out = frame.toByteArray()
                frame.clear()
                inFrame = true
                if (out.isNotEmpty()) return out
                continue
            }
            if (!inFrame) continue
            if (b == 0xDB) {
                if (pending.isEmpty()) {
                    // Need one more byte; keep escape marker for next feed.
                    pending.addFirst(b)
                    return null
                }
                val esc = pending.removeFirst()
                when (esc) {
                    0xDC -> frame.add(0xC0.toByte())
                    0xDD -> frame.add(0xDB.toByte())
                    else -> return null
                }
            } else {
                frame.add(b.toByte())
            }
        }
        return null
    }
}

internal data class EspFlashResult(
    val blocksWritten: Int,
    val blockSize: Int,
)

internal class EspSyncException(
    message: String,
    val attempts: JSONArray,
) : IllegalStateException(message)

internal class EspFlashStageException(
    message: String,
    val stage: String,
    val blockIndex: Int?,
    val blocksWritten: Int,
) : IllegalStateException(message)

internal class EspFlashException(
    message: String,
    val detailPayload: JSONObject,
) : IllegalStateException(message)

internal class EspSerialSession(
    private val usbManager: UsbManager,
    private val dev: UsbDevice,
    private val conn: UsbDeviceConnection,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
    private val timeoutMs: Int,
    private val bridgeHint: String?,
    private val interfaceId: Int,
) {
    private val codec = EspSlipCodec()
    private val readBuf = ByteArray(4096)
    private val isFtdi = bridgeHint == "ftdi"
    private var serialPort: UsbSerialPort? = null
    private var serialConn: UsbDeviceConnection? = null

    private val cmdSync = 0x08
    private val cmdReadReg = 0x0A
    private val cmdSpiAttach = 0x0D
    private val cmdFlashBegin = 0x02
    private val cmdFlashData = 0x03
    private val cmdFlashEnd = 0x04
    private val checksumMagic = 0xEF
    private val flashBlockSize = 0x400

    init {
        serialPort = openUsbSerialPort()
    }

    fun usesUsbSerial(): Boolean = serialPort != null

    fun close() {
        runCatching { serialPort?.close() }
        runCatching { serialConn?.close() }
        serialPort = null
        serialConn = null
    }

    fun configureSerial() {
        val port = serialPort
        if (port != null) {
            port.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { port.setDTR(false) }
            runCatching { port.setRTS(false) }
            return
        }
        when (bridgeHint) {
            "ftdi" -> {
                ftdiResetAll()
                // Make sure host TX is not gated by modem flow control defaults.
                ftdiSetFlowControlNone()
                ftdiSetLatencyTimer(1)
                ftdiSetBaudRate(115200)
                ftdiSetData8N1()
                // Default idle state
                ftdiSetModemDtrRts(dtr = false, rts = false)
            }
        }
    }

    fun flushInput() {
        val port = serialPort
        if (port != null) {
            repeat(8) {
                val n = try {
                    port.read(readBuf, 30)
                } catch (_: Exception) {
                    -1
                }
                if (n <= 0) return
            }
            return
        }
        repeat(3) {
            val n = conn.bulkTransfer(inEp, readBuf, readBuf.size, 30)
            if (n <= 0) return
        }
    }

    fun sniffRaw(durationMs: Int): ByteArray {
        val deadline = System.currentTimeMillis() + durationMs.coerceAtLeast(1)
        val out = ByteArrayOutputStream()
        while (System.currentTimeMillis() < deadline) {
            val chunk = readBulkChunk(100)
            if (chunk.isNotEmpty()) {
                out.write(chunk)
            }
        }
        return out.toByteArray()
    }

    fun enterBootloaderIfSupported(bridgeHint: String?, interfaceId: Int) {
        when (bridgeHint) {
            "cp210x" -> {
                // Matches esptool ClassicReset semantics for DTR/RTS.
                applyModemLines(dtr = false, rts = true, interfaceId = interfaceId)
                Thread.sleep(100)
                applyModemLines(dtr = true, rts = false, interfaceId = interfaceId)
                Thread.sleep(50)
                applyModemLines(dtr = false, rts = false, interfaceId = interfaceId)
                Thread.sleep(40)
            }
            "ftdi" -> {
                // FTDI classic reset sequence (DTR=IO0, RTS=EN style wiring).
                applyModemLines(dtr = false, rts = true, interfaceId = interfaceId)   // IO0 high, EN low
                Thread.sleep(100)
                applyModemLines(dtr = true, rts = false, interfaceId = interfaceId)   // IO0 low, EN high
                Thread.sleep(50)
                applyModemLines(dtr = false, rts = false, interfaceId = interfaceId)  // IO0 high
                Thread.sleep(40)
            }
        }
    }

    fun enterBootloaderInvertedIfSupported(bridgeHint: String?, interfaceId: Int) {
        when (bridgeHint) {
            "cp210x", "ftdi" -> {
                // Some boards wire EN/IO0 opposite to common DTR/RTS mapping.
                applyModemLines(dtr = true, rts = false, interfaceId = interfaceId)
                Thread.sleep(100)
                applyModemLines(dtr = false, rts = true, interfaceId = interfaceId)
                Thread.sleep(50)
                applyModemLines(dtr = false, rts = false, interfaceId = interfaceId)
                Thread.sleep(40)
            }
        }
    }

    private fun enterBootloaderPulseEnIfSupported(bridgeHint: String?, interfaceId: Int) {
        when (bridgeHint) {
            "cp210x", "ftdi" -> {
                // Keep IO0 asserted during EN pulse; then release to run ROM loader.
                applyModemLines(dtr = true, rts = true, interfaceId = interfaceId)
                Thread.sleep(90)
                applyModemLines(dtr = true, rts = false, interfaceId = interfaceId)
                Thread.sleep(120)
                applyModemLines(dtr = false, rts = false, interfaceId = interfaceId)
                Thread.sleep(60)
            }
        }
    }

    fun rebootToRunIfSupported(bridgeHint: String?, interfaceId: Int) {
        when (bridgeHint) {
            "cp210x", "ftdi" -> {
                // Pulse EN low with IO0 released, then return to run mode.
                applyModemLines(dtr = false, rts = true, interfaceId = interfaceId)
                Thread.sleep(90)
                applyModemLines(dtr = false, rts = false, interfaceId = interfaceId)
                Thread.sleep(60)
            }
            else -> {
                setModemLines(dtr = false, rts = false)
                Thread.sleep(40)
            }
        }
    }

    fun settleAfterBootloaderReset() {
        // Allow ROM banner to finish and drain stale bytes before issuing sync.
        Thread.sleep(180)
        flushInput()
        Thread.sleep(50)
    }

    fun setModemLines(dtr: Boolean?, rts: Boolean?) {
        val port = serialPort
        if (port != null) {
            if (dtr != null) runCatching { port.setDTR(dtr) }.getOrElse { throw IllegalStateException("serial_set_dtr_failed") }
            if (rts != null) runCatching { port.setRTS(rts) }.getOrElse { throw IllegalStateException("serial_set_rts_failed") }
            if (dtr == null && rts == null) throw IllegalStateException("line_state_required")
            return
        }
        when (bridgeHint) {
            "cp210x" -> {
                if (dtr == null && rts == null) throw IllegalStateException("cp210x_line_state_required")
                cp210xSetModemLines(dtr = dtr, rts = rts, interfaceId = interfaceId, timeoutMs = 1000)
            }
            "ftdi" -> {
                if (dtr == null || rts == null) throw IllegalStateException("ftdi_requires_dtr_and_rts")
                ftdiSetModemDtrRts(dtr = dtr, rts = rts)
            }
            else -> throw IllegalStateException("unsupported_serial_bridge")
        }
    }

    fun sendSyncProbe() {
        val payload = ByteArray(36)
        payload[0] = 0x07
        payload[1] = 0x07
        payload[2] = 0x12
        payload[3] = 0x20
        for (i in 4 until payload.size) payload[i] = 0x55
        val header = byteArrayOf(
            0x00,
            (cmdSync and 0xFF).toByte(),
            (payload.size and 0xFF).toByte(),
            ((payload.size ushr 8) and 0xFF).toByte(),
            0x00, 0x00, 0x00, 0x00,
        )
        writeSlip(header + payload, timeoutMs.coerceAtLeast(200))
    }

    fun collectSlipFrames(durationMs: Int, maxFrames: Int): JSONArray {
        val out = JSONArray()
        val deadline = System.currentTimeMillis() + durationMs.coerceAtLeast(1)
        while (out.length() < maxFrames && System.currentTimeMillis() < deadline) {
            val remain = (deadline - System.currentTimeMillis()).coerceAtLeast(1L).toInt()
            val frame = readSlipFrame(minOf(80, remain)) ?: continue
            val preview = frame.copyOfRange(0, minOf(64, frame.size))
            out.put(
                JSONObject()
                    .put("len", frame.size)
                    .put("preview_b64", android.util.Base64.encodeToString(preview, android.util.Base64.NO_WRAP))
                    .put("preview_ascii", asciiPreview(preview))
            )
        }
        return out
    }

    private fun asciiPreview(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v in 32..126 || v == 10 || v == 13 || v == 9) sb.append(v.toChar()) else sb.append('.')
        }
        return sb.toString()
    }

    fun sync() {
        val payload = ByteArray(36)
        payload[0] = 0x07
        payload[1] = 0x07
        payload[2] = 0x12
        payload[3] = 0x20
        for (i in 4 until payload.size) payload[i] = 0x55
        val header = byteArrayOf(
            0x00,
            (cmdSync and 0xFF).toByte(),
            (payload.size and 0xFF).toByte(),
            ((payload.size ushr 8) and 0xFF).toByte(),
            0x00, 0x00, 0x00, 0x00,
        )

        val candidateBauds = if (isFtdi) {
            intArrayOf(115200, 74880, 57600, 38400, 230400)
        } else {
            intArrayOf(115200)
        }

        for (baud in candidateBauds) {
            if (serialPort != null) {
                runCatching { serialPort?.setParameters(baud, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) }
            } else if (isFtdi) {
                runCatching { ftdiSetBaudRate(baud) }
            }
            flushInput()
            // Match esptool-style sync retry behavior: many short attempts over a longer window.
            repeat(48) {
                writeSlip(header + payload, timeoutMs.coerceAtLeast(200))
                val deadline = System.currentTimeMillis() + 140L
                while (System.currentTimeMillis() < deadline) {
                    val remain = (deadline - System.currentTimeMillis()).coerceAtLeast(1L).toInt()
                    try {
                        val resp = readResponse(expectedOp = null, timeoutOverrideMs = minOf(70, remain))
                        if (resp.op == cmdSync) return
                    } catch (_: Exception) {
                    }
                }
                Thread.sleep(35)
            }
        }
        throw IllegalStateException("esp_response_timeout")
    }

    fun syncWithAutoResetProfiles(bridgeHint: String?, interfaceId: Int, debug: Boolean = false): JSONArray {
        val attempts = JSONArray()
        val profiles = ArrayList<Pair<String, () -> Unit>>()
        profiles.add("enter_bootloader" to {
            enterBootloaderIfSupported(bridgeHint, interfaceId)
            settleAfterBootloaderReset()
        })
        profiles.add("enter_bootloader_inverted" to {
            enterBootloaderInvertedIfSupported(bridgeHint, interfaceId)
            settleAfterBootloaderReset()
        })
        profiles.add("enter_bootloader_pulse_en" to {
            enterBootloaderPulseEnIfSupported(bridgeHint, interfaceId)
            settleAfterBootloaderReset()
        })
        // Final attempt: if reset sequencing had no effect, try direct sync anyway.
        profiles.add("flush_only" to { flushInput() })

        var lastError: Exception? = null
        for ((name, profile) in profiles) {
            val start = System.currentTimeMillis()
            try {
                profile()
                sync()
                if (debug) {
                    attempts.put(
                        JSONObject()
                            .put("profile", name)
                            .put("ok", true)
                            .put("elapsed_ms", (System.currentTimeMillis() - start).coerceAtLeast(0L))
                    )
                }
                return attempts
            } catch (ex: Exception) {
                lastError = ex
                if (debug) {
                    attempts.put(
                        JSONObject()
                            .put("profile", name)
                            .put("ok", false)
                            .put("error", ex.message ?: "unknown_error")
                            .put("elapsed_ms", (System.currentTimeMillis() - start).coerceAtLeast(0L))
                    )
                }
            }
        }
        if (debug) throw EspSyncException(lastError?.message ?: "esp_response_timeout", attempts)
        throw lastError ?: IllegalStateException("esp_response_timeout")
    }

    fun readChipDetectMagic(): Int {
        // ESP32 ROM chip-detect magic register used by esptool family.
        return readReg(0x40001000)
    }

    fun readReg(address: Int): Int {
        val payload = le32(address)
        return commandChecked(cmdReadReg, payload, checksum = 0, timeoutOverrideMs = maxOf(timeoutMs, 4000))
    }

    fun attachEsp32SpiFlash(): JSONObject {
        // ESP32 ROM requires explicit SPI attach in no-stub flow.
        val efuseBlk0Rdata5 = readReg(0x3FF5A014.toInt())
        val efuseBlk0Rdata3 = readReg(0x3FF5A00C.toInt())
        val clk = efuseBlk0Rdata5 and 0x1F
        val q = (efuseBlk0Rdata5 ushr 5) and 0x1F
        val d = (efuseBlk0Rdata5 ushr 10) and 0x1F
        val cs = (efuseBlk0Rdata5 ushr 15) and 0x1F
        val hd = (efuseBlk0Rdata3 ushr 4) and 0x1F
        val attachArg = (hd shl 24) or (cs shl 18) or (d shl 12) or (q shl 6) or clk
        val payload = le32(attachArg) + byteArrayOf(0x00, 0x00, 0x00, 0x00)
        commandChecked(cmdSpiAttach, payload, checksum = 0, timeoutOverrideMs = maxOf(timeoutMs, 4000))
        return JSONObject()
            .put("clk", clk)
            .put("q", q)
            .put("d", d)
            .put("hd", hd)
            .put("cs", cs)
            .put("attach_arg", String.format(Locale.US, "0x%08x", attachArg))
    }

    fun flashImage(image: ByteArray, offset: Int, reboot: Boolean): EspFlashResult {
        val total = image.size
        val blocks = (total + flashBlockSize - 1) / flashBlockSize
        val eraseSize = total
        val begin = le32(eraseSize) + le32(blocks) + le32(flashBlockSize) + le32(offset)
        try {
            commandChecked(cmdFlashBegin, begin, checksum = 0, timeoutOverrideMs = maxOf(timeoutMs, 10_000))
        } catch (ex: Exception) {
            throw EspFlashStageException(ex.message ?: "flash_begin_failed", "flash_begin", null, 0)
        }

        var seq = 0
        var pos = 0
        while (pos < total) {
            val end = minOf(total, pos + flashBlockSize)
            val chunk = image.copyOfRange(pos, end)
            val block = if (chunk.size == flashBlockSize) chunk else chunk + ByteArray(flashBlockSize - chunk.size) { 0xFF.toByte() }
            val hdr = le32(block.size) + le32(seq) + le32(0) + le32(0)
            val payload = hdr + block
            val chk = checksum(block)
            try {
                commandChecked(cmdFlashData, payload, checksum = chk, timeoutOverrideMs = maxOf(timeoutMs, 8000))
            } catch (ex: Exception) {
                throw EspFlashStageException(ex.message ?: "flash_data_failed", "flash_data", seq, seq)
            }
            seq += 1
            pos = end
        }

        // Follow esptool no-stub ROM behavior: don't require FLASH_END reply.
        // Some ROM flows return error on FLASH_END even after successful writes.
        if (reboot) {
            runCatching { setModemLines(dtr = false, rts = false) }
        }
        return EspFlashResult(blocksWritten = blocks, blockSize = flashBlockSize)
    }

    private fun commandChecked(op: Int, data: ByteArray, checksum: Int, timeoutOverrideMs: Int): Int {
        val response = command(op, data, checksum, timeoutOverrideMs)
        val status = response.payload
        if (status.size < 2) throw IllegalStateException("short_status")
        if ((status[0].toInt() and 0xFF) != 0) {
            val reason = status[1].toInt() and 0xFF
            throw IllegalStateException("esp_error_status_${reason}")
        }
        return response.value
    }

    private data class ResponseFrame(
        val op: Int,
        val value: Int,
        val payload: ByteArray,
    )

    private fun command(op: Int, data: ByteArray, checksum: Int, timeoutOverrideMs: Int): ResponseFrame {
        val header = byteArrayOf(
            0x00,
            (op and 0xFF).toByte(),
            (data.size and 0xFF).toByte(),
            ((data.size ushr 8) and 0xFF).toByte(),
            (checksum and 0xFF).toByte(),
            ((checksum ushr 8) and 0xFF).toByte(),
            ((checksum ushr 16) and 0xFF).toByte(),
            ((checksum ushr 24) and 0xFF).toByte(),
        )
        writeSlip(header + data, timeoutOverrideMs)
        return readResponse(op, timeoutOverrideMs)
    }

    private fun readResponse(expectedOp: Int?, timeoutOverrideMs: Int): ResponseFrame {
        val deadline = System.currentTimeMillis() + timeoutOverrideMs.toLong()
        while (System.currentTimeMillis() < deadline) {
            val frame = readSlipFrame((deadline - System.currentTimeMillis()).coerceAtLeast(1L).toInt())
                ?: continue
            val off = locateEspResponseHeader(frame, expectedOp) ?: continue
            val opRet = frame[off + 1].toInt() and 0xFF
            val payloadLen = le16ToInt(frame, off + 2)
            val value = le32ToInt(frame, off + 4)
            val payloadStart = off + 8
            val payloadEnd = minOf(payloadStart + payloadLen, frame.size)
            val payload = frame.copyOfRange(payloadStart, payloadEnd)
            return ResponseFrame(op = opRet, value = value, payload = payload)
        }
        throw IllegalStateException("esp_response_timeout")
    }

    private fun locateEspResponseHeader(frame: ByteArray, expectedOp: Int?): Int? {
        if (frame.size < 8) return null
        val last = frame.size - 8
        for (i in 0..last) {
            val dir = frame[i].toInt() and 0xFF
            if (dir != 0x01) continue
            val op = frame[i + 1].toInt() and 0xFF
            if (expectedOp != null && op != expectedOp) continue
            val len = le16ToInt(frame, i + 2)
            val end = i + 8 + len
            if (len < 0) continue
            if (end <= frame.size) return i
        }
        return null
    }

    private fun readSlipFrame(timeout: Int): ByteArray? {
        codec.nextFrame()?.let { return it }
        val chunk = readBulkChunk(timeout)
        if (chunk.isEmpty()) return null
        codec.feed(chunk, chunk.size)
        return codec.nextFrame()
    }

    private fun readBulkChunk(timeout: Int): ByteArray {
        val port = serialPort
        if (port != null) {
            val n = try {
                port.read(readBuf, timeout.coerceIn(1, 60_000))
            } catch (_: Exception) {
                -1
            }
            if (n <= 0) return ByteArray(0)
            return readBuf.copyOfRange(0, n.coerceIn(0, readBuf.size))
        }
        val n = conn.bulkTransfer(inEp, readBuf, readBuf.size, timeout.coerceIn(1, 60_000))
        if (n <= 0) return ByteArray(0)
        return if (isFtdi) {
            ftdiStripStatusBytes(readBuf, n)
        } else {
            readBuf.copyOfRange(0, n.coerceIn(0, readBuf.size))
        }
    }

    private fun writeSlip(payload: ByteArray, timeoutOverrideMs: Int) {
        val framed = slipEncode(payload)
        var off = 0
        while (off < framed.size) {
            val chunk = minOf(16384, framed.size - off)
            val part = framed.copyOfRange(off, off + chunk)
            val n = if (serialPort != null) {
                try {
                    serialPort!!.write(part, timeoutOverrideMs.coerceIn(1, 60_000))
                    part.size
                } catch (_: Exception) {
                    -1
                }
            } else {
                conn.bulkTransfer(outEp, part, part.size, timeoutOverrideMs.coerceIn(1, 60_000))
            }
            if (n <= 0) throw IllegalStateException("esp_write_failed")
            off += n
        }
    }

    private fun slipEncode(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 8)
        out.write(0xC0)
        for (b in payload) {
            when (b.toInt() and 0xFF) {
                0xC0 -> {
                    out.write(0xDB)
                    out.write(0xDC)
                }
                0xDB -> {
                    out.write(0xDB)
                    out.write(0xDD)
                }
                else -> out.write(b.toInt() and 0xFF)
            }
        }
        out.write(0xC0)
        return out.toByteArray()
    }

    private fun checksum(data: ByteArray): Int {
        var s = checksumMagic
        for (b in data) s = s xor (b.toInt() and 0xFF)
        return s
    }

    private fun openUsbSerialPort(): UsbSerialPort? {
        return try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(dev) ?: return null
            val port = driver.ports.firstOrNull() ?: return null
            val dedicatedConn = usbManager.openDevice(dev) ?: return null
            port.open(dedicatedConn)
            serialConn = dedicatedConn
            port
        } catch (_: Exception) {
            runCatching { serialConn?.close() }
            serialConn = null
            null
        }
    }

    private fun applyModemLines(dtr: Boolean, rts: Boolean, interfaceId: Int) {
        val port = serialPort
        if (port != null) {
            runCatching { port.setDTR(dtr) }.getOrElse { throw IllegalStateException("serial_set_dtr_failed") }
            runCatching { port.setRTS(rts) }.getOrElse { throw IllegalStateException("serial_set_rts_failed") }
            return
        }
        when (bridgeHint) {
            "cp210x" -> cp210xSetModemLines(dtr = dtr, rts = rts, interfaceId = interfaceId, timeoutMs = 1000)
            "ftdi" -> ftdiSetModemDtrRts(dtr = dtr, rts = rts)
            else -> throw IllegalStateException("unsupported_serial_bridge")
        }
    }

    private fun cp210xSetModemLines(dtr: Boolean?, rts: Boolean?, interfaceId: Int, timeoutMs: Int) {
        var value = 0
        if (dtr != null) {
            value = value or 0x0100
            if (dtr) value = value or 0x0001
        }
        if (rts != null) {
            value = value or 0x0200
            if (rts) value = value or 0x0002
        }
        val rc = conn.controlTransfer(
            0x41, // Host -> Interface | Vendor
            0x07, // CP210X_SET_MHS
            value,
            interfaceId,
            null,
            0,
            timeoutMs.coerceIn(1, 60_000)
        )
        if (rc < 0) throw IllegalStateException("cp210x_set_mhs_failed")
    }

    private fun ftdiStripStatusBytes(src: ByteArray, totalBytesRead: Int): ByteArray {
        val packetSize = inEp.maxPacketSize.coerceAtLeast(2)
        val out = ByteArrayOutputStream(totalBytesRead)
        var srcPos = 0
        val total = totalBytesRead.coerceIn(0, src.size)
        while (srcPos < total) {
            val end = minOf(srcPos + packetSize, total)
            val payloadStart = minOf(srcPos + 2, end)
            if (payloadStart < end) {
                out.write(src, payloadStart, end - payloadStart)
            }
            srcPos += packetSize
        }
        return out.toByteArray()
    }

    private fun ftdiControl(request: Int, value: Int, index: Int, timeoutMs: Int = 1000): Int {
        return conn.controlTransfer(
            0x40, // host->device, vendor
            request,
            value,
            index,
            null,
            0,
            timeoutMs.coerceIn(1, 60_000)
        )
    }

    private fun ftdiResetAll() {
        val rc = ftdiControl(request = 0, value = 0, index = interfaceId + 1)
        if (rc < 0) throw IllegalStateException("ftdi_reset_failed")
    }

    private fun ftdiSetModemDtrRts(dtr: Boolean, rts: Boolean) {
        val dtrBits = if (dtr) 0x0101 else 0x0100
        val rtsBits = if (rts) 0x0202 else 0x0200
        val value = dtrBits or rtsBits
        val rc = ftdiControl(request = 1, value = value, index = interfaceId + 1)
        if (rc < 0) throw IllegalStateException("ftdi_modem_ctrl_failed")
    }

    private fun ftdiSetData8N1() {
        // 8 data bits, no parity, 1 stop bit.
        val rc = ftdiControl(request = 4, value = 8, index = interfaceId + 1)
        if (rc < 0) throw IllegalStateException("ftdi_set_data_failed")
    }

    private fun ftdiSetFlowControlNone() {
        val rc = ftdiControl(request = 2, value = 0, index = interfaceId + 1)
        if (rc < 0) throw IllegalStateException("ftdi_set_flow_none_failed")
    }

    private fun ftdiSetLatencyTimer(ms: Int) {
        val clamped = ms.coerceIn(1, 255)
        val rc = ftdiControl(request = 9, value = clamped, index = interfaceId + 1)
        if (rc < 0) throw IllegalStateException("ftdi_set_latency_failed")
    }

    private fun ftdiSetBaudRate(baudRate: Int) {
        if (baudRate <= 0 || baudRate > 3_500_000) throw IllegalStateException("ftdi_invalid_baud")
        val (value, index) = ftdiBaudValueIndex(baudRate, interfaceId)
        val rc = ftdiControl(request = 3, value = value, index = index)
        if (rc < 0) throw IllegalStateException("ftdi_set_baud_failed")
    }

    private fun ftdiBaudValueIndex(baudRate: Int, portNumber: Int): Pair<Int, Int> {
        val (divisorInit, subdivisor, _) = if (baudRate >= 2_500_000) {
            Triple(0, 0, 3_000_000)
        } else if (baudRate >= 1_750_000) {
            Triple(1, 0, 2_000_000)
        } else {
            var divisor = ((24_000_000L shl 1) / baudRate.toLong()).toInt()
            divisor = (divisor + 1) shr 1
            val sub = divisor and 0x07
            divisor = divisor shr 3
            Triple(divisor, sub, 0)
        }

        var value = divisorInit
        var index = 0
        when (subdivisor) {
            0 -> {}
            4 -> value = value or 0x4000
            2 -> value = value or 0x8000
            1 -> value = value or 0xC000
            3 -> { value = value or 0x0000; index = index or 1 }
            5 -> { value = value or 0x4000; index = index or 1 }
            6 -> { value = value or 0x8000; index = index or 1 }
            7 -> { value = value or 0xC000; index = index or 1 }
        }
        // FTDI H-series / multi-port style index packing:
        index = (index shl 8) or (portNumber + 1)
        return Pair(value, index)
    }

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    private fun le32ToInt(buf: ByteArray, off: Int): Int {
        if (off + 3 >= buf.size) return 0
        return (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun le16ToInt(buf: ByteArray, off: Int): Int {
        if (off + 1 >= buf.size) return 0
        return (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8)
    }
}
