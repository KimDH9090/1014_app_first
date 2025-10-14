package com.example.myapplication.smarthelmet.record

import android.graphics.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.*
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

/* ----------------------- 런타임 설정 ----------------------- */
private object TestCfg {
    const val STREAM_URL = "http://10.42.0.1:5000/usb_feed?dev=2"
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

/* ----------------------- 사람 필터 설정(전신/상반신 + 어두운 소물체 컷) ----------------------- */
private object PersonFilterCfg {
    // 전신
    const val FULL_MIN_H_FRAC = 0.42f
    const val FULL_MIN_AR_H_OVER_W = 1.20f
    // 상반신
    const val UPPER_MIN_H_FRAC = 0.22f
    const val UPPER_MIN_AR_H_OVER_W = 1.00f
    const val UPPER_MAX_AR_H_OVER_W = 2.60f
    // 너무 작은 점 노이즈 컷
    const val MIN_AREA_FRAC = 0.004f
    // 어두운 작은 물체 컷(검은 신발/물체 방지)
    const val DARK_MAX_AREA_FOR_REJECT = 0.06f   // 프레임 대비 면적이 이보다 작고
    const val DARK_MIN_MEAN_LUMA = 0.22f         // 평균 밝기(Luma 0~1)가 이보다 어두우면 컷
}

/* ----------------------- NMS ----------------------- */
private object PostCfg {
    const val NMS_IOU = 0.50f
}

/* ----------------------- 색상 매핑 ----------------------- */
private object ColorCfg {
    private const val PERSON = 0xFF00C853.toInt()   // green 600
    private const val BICYCLE = 0xFF2962FF.toInt()  // blue A700
    private const val VEHICLE = 0xFFD50000.toInt()  // red A700

    fun strokeColor(label: String): Int = when (label) {
        TestCfg.LABEL_PERSON -> PERSON
        TestCfg.LABEL_BICYCLE -> BICYCLE
        TestCfg.LABEL_VEHICLE -> VEHICLE
        else -> 0xFFFFAB00.toInt()
    }
    fun fillColor(label: String, alpha: Int = 80): Int {
        val base = strokeColor(label)
        val r = (base shr 16) and 0xFF
        val g = (base shr 8) and 0xFF
        val b = base and 0xFF
        return Color.argb(alpha.coerceIn(0, 255), r, g, b)
    }
    fun textColor(label: String): Int = strokeColor(label)
}

class RearCamTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Surface(Modifier.fillMaxSize()) { RearCamTestScreen() } }
    }
}

/* ----------------------- UI ----------------------- */
@Composable
private fun RearCamTestScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var dets by remember { mutableStateOf<List<Detection>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val detector = Detector(ctx.assets, TestCfg.MODEL, TestCfg.LABELS, TestCfg.USE_GPU)
            val runner = Runner(TestCfg.STREAM_URL, detector)
            try {
                runner.run(
                    onFrame = { bmp -> frame = bmp },
                    onDetections = { ds -> dets = ds }
                )
            } finally {
                detector.close()
            }
        }
    }

    // 영상 + 오버레이를 함께 180° 회전
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer(rotationZ = 180f)
    ) {
        frame?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Rear Stream",
                modifier = Modifier.fillMaxSize()
            )
            DetectionOverlay(bmp, dets, Modifier.fillMaxSize())
        }
    }
}

/* ----------------------- 러너 ----------------------- */
private class Runner(private val url: String, private val detector: Detector) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    suspend fun run(onFrame: (Bitmap) -> Unit, onDetections: (List<Detection>) -> Unit) {
        val req = Request.Builder().url(url).build()
        while (true) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    val input = BufferedInputStream(resp.body!!.byteStream(), 64 * 1024)
                    readMjpeg(input, onFrame, onDetections)
                }
            } catch (_: Throwable) {
                delay(300)
            }
        }
    }

    private suspend fun readMjpeg(
        input: InputStream,
        onFrame: (Bitmap) -> Unit,
        onDetections: (List<Detection>) -> Unit
    ) {
        val buf = java.io.ByteArrayOutputStream(512 * 1024)
        val decodeOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var prev = -1
        var lastInfer = 0L
        fun now() = System.currentTimeMillis()

        while (true) {
            // SOI
            var b: Int
            while (true) {
                b = input.read(); if (b == -1) return
                if (prev == 0xFF && b == 0xD8) { buf.reset(); buf.write(0xFF); buf.write(0xD8); break }
                prev = b
            }
            // EOI
            prev = -1
            while (true) {
                b = input.read(); if (b == -1) return
                buf.write(b); if (prev == 0xFF && b == 0xD9) break
                prev = b
            }

            val bytes = buf.toByteArray()
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: continue
            val bmp = raw.downscaleIfNeeded(TestCfg.DOWNSCALE_MAX_W)
            onFrame(bmp)

            val t = now()
            val interval = 1000L / TestCfg.INFER_FPS
            if (t - lastInfer < interval) continue
            lastInfer = t

            val out = detector.detect(bmp)
            onDetections(out)
        }
    }
}

/* ----------------------- Detector ----------------------- */
private class Detector(
    private val assets: android.content.res.AssetManager,
    modelFile: String,
    labelFile: String,
    useGpu: Boolean
) : AutoCloseable {

    private val inputSize = TestCfg.INPUT
    private val intValues = IntArray(inputSize * inputSize)

    // 라벨 파일 로드 (줄맟춤/공백 제거)
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

    // 클래스 인덱스 오프셋 자동판단은 라벨 문자열 기반으로 안전 매핑으로 대체
    init {
        val opts = Interpreter.Options().apply {
            setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
        }
        if (useGpu) { try { gpu = GpuDelegate(); opts.addDelegate(gpu) } catch (_: Throwable) {} }

        val afd = assets.openFd(modelFile)
        FileInputStream(afd.fileDescriptor).use { fis ->
            val mapped = fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            interpreter = Interpreter(mapped, opts)
        }
        isQuantized = interpreter.getInputTensor(0).dataType() == DataType.UINT8

        android.util.Log.i(
            "Detector",
            "labels(size=${labels.size}): ${labels.take(5)}..."
        )
    }

    override fun close() {
        try { gpu?.close() } catch (_: Throwable) {}
        interpreter.close()
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        // --- 전처리 ---
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
                    bufU8.put(r.toByte()); bufU8.put(g.toByte()); bufU8.put(b.toByte())
                } else {
                    bufF32.putFloat((r - TestCfg.MEAN) / TestCfg.STD)
                    bufF32.putFloat((g - TestCfg.MEAN) / TestCfg.STD)
                    bufF32.putFloat((b - TestCfg.MEAN) / TestCfg.STD)
                }
            }
        }

        // --- SSD 출력 ---
        val num = 10
        val loc = Array(1) { Array(num) { FloatArray(4) } } // [ymin,xmin,ymax,xmax]
        val cls = Array(1) { FloatArray(num) }              // 클래스 인덱스(float)
        val scr = Array(1) { FloatArray(num) }
        val cnt = FloatArray(1)
        val outputs = mapOf(0 to loc, 1 to cls, 2 to scr, 3 to cnt)

        interpreter.runForMultipleInputsOutputs(
            arrayOf(if (isQuantized) bufU8 else bufF32),
            outputs
        )

        // --- 후보 생성: 안전한 라벨 매핑(문자열 기반) ---
        val prelim = mutableListOf<Detection>()
        val n = min(num, cnt[0].toInt().coerceAtLeast(0))
        for (k in 0 until n) {
            val rawScore = scr[0][k]
            if (rawScore < TestCfg.SCORE_TH_PERSON && rawScore < TestCfg.SCORE_TH_DEFAULT) continue

            val rawId = cls[0][k].toInt().coerceAtLeast(0)

            // id, id±1 위치를 모두 시도하여 문자열 라벨을 얻는다 (오프셋 혼동 방지)
            val candLabels = listOfNotNull(
                labels.getOrNull(rawId),
                labels.getOrNull(rawId + 1),
                labels.getOrNull(rawId - 1)
            ).map { it.trim().lowercase() }

            val canon = when {
                "person" in candLabels -> TestCfg.LABEL_PERSON
                "bicycle" in candLabels -> TestCfg.LABEL_BICYCLE
                setOf("car","motorcycle","bus","truck","train","vehicle","auto","van").any { it in candLabels } -> TestCfg.LABEL_VEHICLE
                else -> null
            } ?: continue

            val b = loc[0][k]
            val ymin = b[0].coerceIn(0f, 1f)
            val xmin = b[1].coerceIn(0f, 1f)
            val ymax = b[2].coerceIn(0f, 1f)
            val xmax = b[3].coerceIn(0f, 1f)

            // 클래스별 점수 임계 적용
            val scoreTh = if (canon == TestCfg.LABEL_PERSON) TestCfg.SCORE_TH_PERSON else TestCfg.SCORE_TH_DEFAULT
            if (rawScore < scoreTh) continue

            prelim += Detection(
                id = when (canon) {
                    TestCfg.LABEL_PERSON -> 1
                    TestCfg.LABEL_BICYCLE -> 2
                    else -> 3
                },
                label = canon,
                score = rawScore,
                box = RectF(xmin, ymin, xmax, ymax)
            )
        }

        // --- 사람 체형/밝기 필터 적용 (fallback 포함) ---
        val personsRaw = prelim.filter { it.label == TestCfg.LABEL_PERSON }
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
        // 사람이 모두 사라지면(라벨 매핑은 됐는데 필터가 과한 경우) 이번 프레임만 원시값 사용
        val personsToUse = if (personsFiltered.isNotEmpty()) personsFiltered else personsRaw

        // --- 자전거/차량은 건드리지 않음 ---
        val bicycles = prelim.filter { it.label == TestCfg.LABEL_BICYCLE }
        val vehicles = prelim.filter { it.label == TestCfg.LABEL_VEHICLE }

        // --- 클래스별 NMS ---
        val personsNms  = nms(personsToUse, PostCfg.NMS_IOU)
        val bicyclesNms = nms(bicycles,     PostCfg.NMS_IOU)
        val vehiclesNms = nms(vehicles,     PostCfg.NMS_IOU)

        // ========= [핵심] 사람은 "가장 가까운 1명"만 남김 =========
        fun nearScore(d: Detection): Float {
            val b = d.box
            val w = (b.right - b.left).coerceAtLeast(0f)
            val h = (b.bottom - b.top).coerceAtLeast(0f)
            val area = w * h
            // 클수록 + 프레임 하단일수록(거리 근사)
            return area * (0.5f + 0.5f * b.bottom.coerceIn(0f, 1f))
        }
        val nearestPerson: List<Detection> =
            personsNms.maxByOrNull { nearScore(it) }?.let { listOf(it) } ?: emptyList()
        // =====================================================

        val out = mutableListOf<Detection>()
        out += nearestPerson          // 사람: 1명만
        out += bicyclesNms            // 자전거: 모두
        out += vehiclesNms            // 차량: 모두
        return out.sortedByDescending { it.score }
    }

    // 박스 내부 평균 밝기(0~1) 샘플링(저비용 격자 샘플)
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
                // BT.601 luma
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

/* ----------------------- NMS ----------------------- */
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

private fun nms(dets: List<Detection>, iouTh: Float): List<Detection> {
    if (dets.isEmpty()) return dets
    val sorted = dets.sortedByDescending { it.score }.toMutableList()
    val keep = mutableListOf<Detection>()
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

/* ----------------------- 데이터/그리기 ----------------------- */
private data class Detection(
    val id: Int,       // 1=person, 2=bicycle, 3=vehicle
    val label: String, // "person" / "bicycle" / "vehicle"
    val score: Float,
    val box: RectF     // (xmin,ymin,xmax,ymax) in [0,1]
)

@Composable
private fun DetectionOverlay(bmp: Bitmap, dets: List<Detection>, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val vw = size.width; val vh = size.height
        val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
        val scale = min(vw / bw, vh / bh)
        val dw = bw * scale; val dh = bh * scale
        val ox = (vw - dw) / 2f; val oy = (vh - dh) / 2f

        val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
        val textP = Paint().apply { textSize = 36f; isAntiAlias = true }
        val fill = Paint().apply { style = Paint.Style.FILL }

        val canvas = drawContext.canvas.nativeCanvas
        dets.forEach { d ->
            val l = ox + d.box.left * dw
            val t = oy + d.box.top * dh
            val r = ox + d.box.right * dw
            val b = oy + d.box.bottom * dh

            val sc = ColorCfg.strokeColor(d.label)
            val fc = ColorCfg.fillColor(d.label, alpha = 80)
            val tc = ColorCfg.textColor(d.label)

            fill.color = fc
            stroke.color = sc
            textP.color = tc

            canvas.drawRect(l, t, r, b, fill)
            canvas.drawRect(l, t, r, b, stroke)
            canvas.drawText("${d.label} ${(d.score * 100).toInt()}%", l + 6f, (t - 8f).coerceAtLeast(24f), textP)
        }
    }
}

/* ----------------------- 유틸 ----------------------- */
private fun Bitmap.downscaleIfNeeded(maxW: Int): Bitmap {
    if (width <= maxW) return this
    val ratio = maxW.toFloat() / width
    val h = (height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, maxW, h, true)
}
