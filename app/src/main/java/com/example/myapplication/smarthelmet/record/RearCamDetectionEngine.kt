package com.example.myapplication.smarthelmet.record

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * 후면 카메라 MJPEG 스트림을 읽어 TFLite SSD 모델로 객체 인식을 수행하는 엔진.
 * 기존 RearCamTestActivity의 파이프라인을 분리해 재사용 가능하게 구성했다.
 */
class RearCamDetectionEngine(
    private val assets: AssetManager,
    private val modelFile: String = Defaults.MODEL,
    private val labelFile: String = Defaults.LABELS,
    private val useGpu: Boolean = Defaults.USE_GPU,
) : AutoCloseable {

    private val detector = Detector(assets, modelFile, labelFile, useGpu)

    suspend fun run(
        url: String,
        onFrame: ((Bitmap) -> Unit)? = null,
        onDetections: (RearDetectionResult) -> Unit,
    ) {
        val runner = Runner(url, detector)
        runner.run(onFrame = onFrame, onDetections = onDetections)
    }

    override fun close() {
        detector.close()
    }

    object Defaults {
        const val MODEL = "mobilenet_ssd.tflite"
        const val LABELS = "labelmap.txt"
        const val INPUT = 300
        const val MEAN = 127.5f
        const val STD = 127.5f
        const val SCORE_TH_DEFAULT = 0.40f
        const val SCORE_TH_PERSON = 0.25f
        const val INFER_FPS = 6
        const val DOWNSCALE_MAX_W = 960
        const val USE_GPU = true
        const val LABEL_PERSON = "person"
        const val LABEL_BICYCLE = "bicycle"
        const val LABEL_VEHICLE = "vehicle"
    }

    private object PersonFilterCfg {
        const val FULL_MIN_H_FRAC = 0.42f
        const val FULL_MIN_AR_H_OVER_W = 1.20f
        const val UPPER_MIN_H_FRAC = 0.22f
        const val UPPER_MIN_AR_H_OVER_W = 1.00f
        const val UPPER_MAX_AR_H_OVER_W = 2.60f
        const val MIN_AREA_FRAC = 0.004f
        const val DARK_MAX_AREA_FOR_REJECT = 0.06f
        const val DARK_MIN_MEAN_LUMA = 0.22f
    }

    private object PostCfg {
        const val NMS_IOU = 0.50f
    }

    /**
     * MJPEG 스트림을 읽어 비트맵과 검출 결과를 콜백으로 전달한다.
     */
    private class Runner(private val url: String, private val detector: Detector) {
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()

        suspend fun run(
            onFrame: ((Bitmap) -> Unit)? = null,
            onDetections: (RearDetectionResult) -> Unit,
        ) {
            val req = Request.Builder().url(url).build()
            val ctx = currentCoroutineContext()
            while (ctx.isActive) {
                try {
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        val input = BufferedInputStream(resp.body!!.byteStream(), 64 * 1024)
                        readMjpeg(input, onFrame, onDetections)
                    }
                } catch (_: CancellationException) {
                    throw
                } catch (_: Throwable) {
                    if (!ctx.isActive) break
                    delay(300)
                }
            }
        }

        private suspend fun readMjpeg(
            input: InputStream,
            onFrame: ((Bitmap) -> Unit)?,
            onDetections: (RearDetectionResult) -> Unit,
        ) {
            val buf = java.io.ByteArrayOutputStream(512 * 1024)
            val decodeOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            var prev = -1
            var lastInfer = 0L
            val ctx = currentCoroutineContext()

            fun now() = System.currentTimeMillis()

            while (ctx.isActive) {
                ctx.ensureActive()
                var b: Int
                while (ctx.isActive) {
                    b = input.read()
                    if (b == -1) return
                    if (prev == 0xFF && b == 0xD8) {
                        buf.reset()
                        buf.write(0xFF)
                        buf.write(0xD8)
                        break
                    }
                    prev = b
                }

                prev = -1
                while (ctx.isActive) {
                    b = input.read()
                    if (b == -1) return
                    buf.write(b)
                    if (prev == 0xFF && b == 0xD9) break
                    prev = b
                }

                val bytes = buf.toByteArray()
                val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: continue
                val bmp = raw.downscaleIfNeeded(Defaults.DOWNSCALE_MAX_W)
                onFrame?.invoke(bmp)

                val t = now()
                val interval = 1000L / Defaults.INFER_FPS
                if (t - lastInfer < interval) continue
                lastInfer = t

                val detections = detector.detect(bmp)
                onDetections(
                    RearDetectionResult(
                        frameWidth = bmp.width,
                        frameHeight = bmp.height,
                        detections = detections,
                    ),
                )
            }
        }
    }

    private class Detector(
        private val assets: AssetManager,
        modelFile: String,
        labelFile: String,
        useGpu: Boolean,
    ) : AutoCloseable {

        private val inputSize = Defaults.INPUT
        private val intValues = IntArray(inputSize * inputSize)

        private val labels: List<String> = assets.open(labelFile).bufferedReader().useLines { seq ->
            seq.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }

        private val interpreter: Interpreter
        private var gpu: GpuDelegate? = null
        private val isQuantized: Boolean

        private val bufF32: ByteBuffer =
            ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
        private val bufU8: ByteBuffer =
            ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())

        init {
            val opts = Interpreter.Options().apply {
                setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
            }
            if (useGpu) {
                try {
                    gpu = GpuDelegate()
                    opts.addDelegate(gpu)
                } catch (_: Throwable) {
                }
            }

            val afd = assets.openFd(modelFile)
            FileInputStream(afd.fileDescriptor).use { fis ->
                val mapped = fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                interpreter = Interpreter(mapped, opts)
            }
            isQuantized = interpreter.getInputTensor(0).dataType() == DataType.UINT8
        }

        override fun close() {
            try {
                gpu?.close()
            } catch (_: Throwable) {
            }
            interpreter.close()
        }

        fun detect(bitmap: Bitmap): List<RearDetection> {
            val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            if (isQuantized) bufU8.clear() else bufF32.clear()
            scaled.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)
            var i = 0
            for (y in 0 until inputSize) {
                for (x in 0 until inputSize) {
                    val v = intValues[i++]
                    val r = (v shr 16) and 0xFF
                    val g = (v shr 8) and 0xFF
                    val b = v and 0xFF
                    if (isQuantized) {
                        bufU8.put(r.toByte())
                        bufU8.put(g.toByte())
                        bufU8.put(b.toByte())
                    } else {
                        bufF32.putFloat((r - Defaults.MEAN) / Defaults.STD)
                        bufF32.putFloat((g - Defaults.MEAN) / Defaults.STD)
                        bufF32.putFloat((b - Defaults.MEAN) / Defaults.STD)
                    }
                }
            }

            val num = 10
            val loc = Array(1) { Array(num) { FloatArray(4) } }
            val cls = Array(1) { FloatArray(num) }
            val scr = Array(1) { FloatArray(num) }
            val cnt = FloatArray(1)
            val outputs = mapOf(0 to loc, 1 to cls, 2 to scr, 3 to cnt)

            interpreter.runForMultipleInputsOutputs(
                arrayOf(if (isQuantized) bufU8 else bufF32),
                outputs,
            )

            val prelim = mutableListOf<RearDetection>()
            val n = min(num, cnt[0].toInt().coerceAtLeast(0))
            for (k in 0 until n) {
                val rawScore = scr[0][k]
                if (rawScore < Defaults.SCORE_TH_PERSON && rawScore < Defaults.SCORE_TH_DEFAULT) continue

                val rawId = cls[0][k].toInt().coerceAtLeast(0)
                val candLabels = listOfNotNull(
                    labels.getOrNull(rawId),
                    labels.getOrNull(rawId + 1),
                    labels.getOrNull(rawId - 1),
                ).map { it.trim().lowercase() }

                val canon = when {
                    "person" in candLabels -> Defaults.LABEL_PERSON
                    "bicycle" in candLabels -> Defaults.LABEL_BICYCLE
                    setOf("car", "motorcycle", "bus", "truck", "train", "vehicle", "auto", "van").any { it in candLabels } -> Defaults.LABEL_VEHICLE
                    else -> null
                } ?: continue

                val b = loc[0][k]
                val ymin = b[0].coerceIn(0f, 1f)
                val xmin = b[1].coerceIn(0f, 1f)
                val ymax = b[2].coerceIn(0f, 1f)
                val xmax = b[3].coerceIn(0f, 1f)

                val scoreTh = if (canon == Defaults.LABEL_PERSON) Defaults.SCORE_TH_PERSON else Defaults.SCORE_TH_DEFAULT
                if (rawScore < scoreTh) continue

                prelim += RearDetection(
                    id = when (canon) {
                        Defaults.LABEL_PERSON -> 1
                        Defaults.LABEL_BICYCLE -> 2
                        else -> 3
                    },
                    label = canon,
                    score = rawScore,
                    box = RectF(xmin, ymin, xmax, ymax),
                )
            }

            val personsRaw = prelim.filter { it.label == Defaults.LABEL_PERSON }
            val personsFiltered = personsRaw.filter { d ->
                val w = (d.box.right - d.box.left).coerceAtLeast(0f)
                val h = (d.box.bottom - d.box.top).coerceAtLeast(0f)
                val ar = if (w > 1e-6f) h / w else 999f
                val area = w * h
                if (area < PersonFilterCfg.MIN_AREA_FRAC) return@filter false

                val isFullBody =
                    h >= PersonFilterCfg.FULL_MIN_H_FRAC &&
                            ar >= PersonFilterCfg.FULL_MIN_AR_H_OVER_W

                val isUpperBody =
                    h >= PersonFilterCfg.UPPER_MIN_H_FRAC &&
                            ar >= PersonFilterCfg.UPPER_MIN_AR_H_OVER_W &&
                            ar <= PersonFilterCfg.UPPER_MAX_AR_H_OVER_W

                if (!(isFullBody || isUpperBody)) return@filter false

                if (area <= PersonFilterCfg.DARK_MAX_AREA_FOR_REJECT) {
                    val luma = sampleMeanLuma(bitmap, d.box)
                    if (luma < PersonFilterCfg.DARK_MIN_MEAN_LUMA) return@filter false
                }
                true
            }
            val personsToUse = if (personsFiltered.isNotEmpty()) personsFiltered else personsRaw

            val bicycles = prelim.filter { it.label == Defaults.LABEL_BICYCLE }
            val vehicles = prelim.filter { it.label == Defaults.LABEL_VEHICLE }

            val personsNms = nms(personsToUse, PostCfg.NMS_IOU)
            val bicyclesNms = nms(bicycles, PostCfg.NMS_IOU)
            val vehiclesNms = nms(vehicles, PostCfg.NMS_IOU)

            fun nearScore(d: RearDetection): Float {
                val b = d.box
                val w = (b.right - b.left).coerceAtLeast(0f)
                val h = (b.bottom - b.top).coerceAtLeast(0f)
                val area = w * h
                return area * (0.5f + 0.5f * b.bottom.coerceIn(0f, 1f))
            }
            val nearestPerson =
                personsNms.maxByOrNull { nearScore(it) }?.let { listOf(it) } ?: emptyList()

            val out = mutableListOf<RearDetection>()
            out += nearestPerson
            out += bicyclesNms
            out += vehiclesNms
            return out.sortedByDescending { it.score }
        }

        private fun sampleMeanLuma(bmp: Bitmap, box01: RectF, nx: Int = 8, ny: Int = 8): Float {
            val x1 = (box01.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val y1 = (box01.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            val x2 = (box01.right * bmp.width).toInt().coerceIn(x1 + 1, bmp.width)
            val y2 = (box01.bottom * bmp.height).toInt().coerceIn(y1 + 1, bmp.height)

            val w = x2 - x1
            val h = y2 - y1
            if (w <= 1 || h <= 1) return 1f

            var sum = 0f
            var cnt = 0
            val stepx = max(1, w / nx)
            val stepy = max(1, h / ny)
            var y = y1
            while (y < y2) {
                var x = x1
                while (x < x2) {
                    val c = bmp.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val l = (299 * r + 587 * g + 114 * b) / 255000f
                    sum += l
                    cnt++
                    x += stepx
                }
                y += stepy
            }
            return if (cnt == 0) 1f else sum / cnt
        }
    }

    data class RearDetection(
        val id: Int,
        val label: String,
        val score: Float,
        val box: RectF,
    )

    data class RearDetectionResult(
        val frameWidth: Int,
        val frameHeight: Int,
        val detections: List<RearDetection>,
    )
}

private fun iou(a: RectF, b: RectF): Float {
    val ix1 = max(a.left, b.left)
    val iy1 = max(a.top, b.top)
    val ix2 = min(a.right, b.right)
    val iy2 = min(a.bottom, b.bottom)
    val iw = max(0f, ix2 - ix1)
    val ih = max(0f, iy2 - iy1)
    val inter = iw * ih
    val union = (a.width() * a.height()) + (b.width() * b.height()) - inter
    return if (union <= 0f) 0f else inter / union
}

private fun nms(dets: List<RearCamDetectionEngine.RearDetection>, iouTh: Float): List<RearCamDetectionEngine.RearDetection> {
    if (dets.isEmpty()) return dets
    val sorted = dets.sortedByDescending { it.score }.toMutableList()
    val keep = mutableListOf<RearCamDetectionEngine.RearDetection>()
    while (sorted.isNotEmpty()) {
        val best = sorted.removeAt(0)
        keep += best
        val it = sorted.iterator()
        while (it.hasNext()) {
            val d = it.next()
            if (iou(best.box, d.box) >= iouTh) it.remove()
        }
    }
    return keep
}

private fun Bitmap.downscaleIfNeeded(maxW: Int): Bitmap {
    if (width <= maxW) return this
    val ratio = maxW.toFloat() / width
    val h = (height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, maxW, h, true)
}