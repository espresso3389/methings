package jp.espresso3389.methings.vision

import android.content.Context
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

class TfliteModelManager(private val context: Context) {
    private data class LoadedModel(
        val name: String,
        val path: String,
        val buffer: MappedByteBuffer,
        val interpreter: Interpreter,
        val delegateKind: String,
    )

    private val models = ConcurrentHashMap<String, LoadedModel>()

    fun unload(name: String): Boolean {
        val key = name.trim()
        val m = models.remove(key) ?: return false
        runCatching { m.interpreter.close() }
        return true
    }

    fun load(
        name: String,
        file: File,
        delegate: String,
        numThreads: Int,
    ): Map<String, Any> {
        val key = name.trim()
        require(key.isNotEmpty()) { "name_required" }
        require(file.exists()) { "model_not_found" }

        unload(key)

        val mapped = FileInputStream(file).use { fis ->
            val ch = fis.channel
            ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
        }

        val opts = Interpreter.Options()
        if (numThreads > 0) opts.setNumThreads(numThreads.coerceIn(1, 8))

        var delegateKind = "none"
        var nnapi: NnApiDelegate? = null
        var gpu: GpuDelegate? = null
        when (delegate.trim().lowercase()) {
            "nnapi" -> {
                nnapi = runCatching { NnApiDelegate() }.getOrNull()
                if (nnapi != null) {
                    opts.addDelegate(nnapi)
                    delegateKind = "nnapi"
                }
            }
            "gpu" -> {
                gpu = runCatching { GpuDelegate() }.getOrNull()
                if (gpu != null) {
                    opts.addDelegate(gpu)
                    delegateKind = "gpu"
                }
            }
        }

        val interpreter = Interpreter(mapped, opts)
        val loaded = LoadedModel(
            name = key,
            path = file.absolutePath,
            buffer = mapped,
            interpreter = interpreter,
            delegateKind = delegateKind,
        )
        models[key] = loaded

        val inTensor = interpreter.getInputTensor(0)
        val inputShape = inTensor.shape()
        val inputType = inTensor.dataType()

        val outInfo = ArrayList<Map<String, Any>>()
        for (i in 0 until interpreter.outputTensorCount) {
            val t = interpreter.getOutputTensor(i)
            outInfo.add(
                mapOf(
                    "index" to i,
                    "shape" to t.shape().toList(),
                    "dtype" to t.dataType().toString(),
                )
            )
        }

        Log.i("TfliteModelManager", "Loaded model=$key delegate=$delegateKind inputShape=${inputShape.contentToString()} inputType=$inputType")
        return mapOf(
            "name" to key,
            "path" to file.absolutePath,
            "delegate" to delegateKind,
            "input" to mapOf("shape" to inputShape.toList(), "dtype" to inputType.toString()),
            "outputs" to outInfo,
        )
    }

    fun runRgba(
        name: String,
        rgba: ByteArray,
        width: Int,
        height: Int,
        normalize: Boolean,
        mean: FloatArray,
        std: FloatArray,
    ): Map<String, Any> {
        val key = name.trim()
        val m = models[key] ?: throw IllegalArgumentException("model_not_loaded")
        val interpreter = m.interpreter

        val inTensor = interpreter.getInputTensor(0)
        val shape = inTensor.shape()
        val dtype = inTensor.dataType()
        if (shape.size != 4) throw IllegalArgumentException("unsupported_input_shape")
        val batch = shape[0]
        val inH = shape[1]
        val inW = shape[2]
        val inC = shape[3]
        if (batch != 1) throw IllegalArgumentException("unsupported_batch")
        if (inC != 3 && inC != 4) throw IllegalArgumentException("unsupported_channels")

        // Resize if needed (RGBA only).
        val src = if (width != inW || height != inH) {
            com.google.android.renderscript.Toolkit.resize(rgba, width, height, inW, inH, 4)
        } else {
            rgba
        }

        val input: Any = when (dtype) {
            DataType.UINT8 -> {
                val bb = ByteBuffer.allocateDirect(inW * inH * inC).order(ByteOrder.nativeOrder())
                var si = 0
                if (inC == 4) {
                    // Copy RGBA directly.
                    bb.put(src, 0, inW * inH * 4)
                } else {
                    // Drop alpha.
                    val nPix = inW * inH
                    for (i in 0 until nPix) {
                        bb.put(src[si])     // R
                        bb.put(src[si + 1]) // G
                        bb.put(src[si + 2]) // B
                        si += 4
                    }
                }
                bb.rewind()
                bb
            }
            DataType.FLOAT32 -> {
                val bb = ByteBuffer.allocateDirect(inW * inH * inC * 4).order(ByteOrder.nativeOrder())
                var si = 0
                val nPix = inW * inH
                for (i in 0 until nPix) {
                    val r = (src[si].toInt() and 0xFF) / 255.0f
                    val g = (src[si + 1].toInt() and 0xFF) / 255.0f
                    val b = (src[si + 2].toInt() and 0xFF) / 255.0f
                    si += 4
                    if (normalize) {
                        bb.putFloat((r - mean[0]) / std[0])
                        bb.putFloat((g - mean[1]) / std[1])
                        bb.putFloat((b - mean[2]) / std[2])
                    } else {
                        bb.putFloat(r)
                        bb.putFloat(g)
                        bb.putFloat(b)
                    }
                    if (inC == 4) {
                        bb.putFloat(1.0f)
                    }
                }
                bb.rewind()
                bb
            }
            else -> throw IllegalArgumentException("unsupported_input_dtype")
        }

        val outputs = HashMap<Int, Any>()
        val outMeta = ArrayList<Map<String, Any>>()
        for (i in 0 until interpreter.outputTensorCount) {
            val t = interpreter.getOutputTensor(i)
            val oShape = t.shape()
            val oType = t.dataType()
            outMeta.add(mapOf("index" to i, "shape" to oShape.toList(), "dtype" to oType.toString()))

            val out: Any = when (oType) {
                DataType.FLOAT32 -> FloatArray(oShape.fold(1) { a, b -> a * b })
                DataType.UINT8 -> ByteArray(oShape.fold(1) { a, b -> a * b })
                DataType.INT32 -> IntArray(oShape.fold(1) { a, b -> a * b })
                else -> throw IllegalArgumentException("unsupported_output_dtype")
            }
            outputs[i] = out
        }

        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        // Convert outputs to JSON-friendly lists (small tensors only).
        val outJson = ArrayList<Map<String, Any>>()
        for (i in 0 until interpreter.outputTensorCount) {
            val meta = outMeta[i]
            val out = outputs[i]!!
            val value: Any = when (out) {
                is FloatArray -> out.toList()
                is IntArray -> out.toList()
                is ByteArray -> out.map { it.toInt() and 0xFF }
                else -> out.toString()
            }
            outJson.add(meta + mapOf("value" to value))
        }

        return mapOf(
            "model" to key,
            "delegate" to m.delegateKind,
            "input" to mapOf("width" to inW, "height" to inH, "channels" to inC, "dtype" to dtype.toString()),
            "outputs" to outJson,
        )
    }
}

