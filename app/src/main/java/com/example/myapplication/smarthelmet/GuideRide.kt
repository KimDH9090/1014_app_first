package com.example.myapplication.smarthelmet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.example.myapplication.R
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

class GuideRide : Fragment() {

    companion object {
        private const val STREAM_URL = "http://10.42.0.1:5000/video_feed"
    }

    private lateinit var previewView: ImageView

    // ---- 병렬 파이프라인 잡 ----
    private var netJob: Job? = null
    private var procJob: Job? = null

    // 최신 프레임만 보관하는 채널(구 프레임은 자동 폐기)
    private val frameBytesChan = Channel<ByteArray>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // ===== 처리/그리기 파라미터 =====
    private val PROCESS_W = 640
    private val ROI_TOP_RATIO = 0.35
    private val BOTTOM_HIST_START = 0.70
    private val CORRIDOR_W_RATIO = 0.06
    private val CANNY_LOW = 60.0
    private val CANNY_HIGH = 180.0
    private val STEPS = 16
    private val THICK = 4

    // ===== 추적 상태 =====
    private data class Track(var xb: Double, var xt: Double, var vb: Double = 0.0, var vt: Double = 0.0)
    private var leftT: Track? = null
    private var rightT: Track? = null

    // α-β + 게이트
    private val ALPHA = 0.35
    private val BETA  = 0.15
    private val MAX_STEP_RATIO = 0.04
    private val MAX_SLOPE_DELTA = 0.0018

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_ride_guide, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        previewView = view.findViewById(R.id.ipcamView)
        if (!OpenCVLoader.initDebug()) throw RuntimeException("OpenCV 초기화 실패")
        startStream()
    }

    private fun startStream() {
        if (netJob?.isActive == true || procJob?.isActive == true) return

        // 1) 네트워크 읽기 전용 코루틴: 최신 JPEG 바이트만 채널로 밀어넣음
        netJob = CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(STREAM_URL).openConnection() as HttpURLConnection).apply {
                    doInput = true
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                    setRequestProperty("Pragma", "no-cache")
                    setRequestProperty("Accept", "multipart/x-mixed-replace, image/jpeg")
                    setRequestProperty("Connection", "Keep-Alive")
                    connectTimeout = 4000
                    readTimeout  = 4000
                    connect()
                }
                val mjpeg = MjpegInputStream(conn!!.inputStream)

                while (isActive) {
                    val jpeg = mjpeg.readLatestFrameBytes() ?: continue
                    // 최신 프레임만 유지 (DROP_OLDEST)
                    frameBytesChan.trySend(jpeg)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }

        // 2) 처리/렌더 코루틴: 채널에서 가장 최신 프레임만 꺼내서 디코드+처리
        procJob = CoroutineScope(Dispatchers.Default).launch {
            // 디코드 비용 절감을 위한 옵션 (RGB_565)
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            while (isActive) {
                // 하나 받기
                val first = frameBytesChan.receiveCatching().getOrNull() ?: continue
                var latest = first
                // 채널에 더 쌓여있으면 모두 비우고 마지막 것만 사용 → 지연 제거
                while (true) {
                    val more = frameBytesChan.tryReceive().getOrNull() ?: break
                    latest = more
                }

                // 디코드 → 회전 → 검출
                val bmp = BitmapFactory.decodeByteArray(latest, 0, latest.size, opts) ?: continue
                val rotated = bmp.rotate180()
                val output = try { detectLanesFast(rotated) } catch (t: Throwable) { t.printStackTrace(); rotated }

                withContext(Dispatchers.Main) {
                    previewView.setImageBitmap(output)
                }
                // 다음 프레임 처리 기회
                yield()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopStream()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopStream()
    }

    private fun stopStream() {
        netJob?.cancel(); netJob = null
        procJob?.cancel(); procJob = null
        frameBytesChan.tryReceive().getOrNull() // 비우기 시도
    }

    // Bitmap → 180도 회전
    private fun Bitmap.rotate180(): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(this, src)
        val dst = Mat()
        Core.rotate(src, dst, Core.ROTATE_180)
        val out = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, out)
        src.release(); dst.release()
        return out
    }

    /**
     * 빠른 차선 검출 파이프라인 (기존 그대로)
     */
    private fun detectLanesFast(input: Bitmap): Bitmap {
        val rgbaFull = Mat()
        Utils.bitmapToMat(input, rgbaFull)
        val bgrFull = Mat()
        Imgproc.cvtColor(rgbaFull, bgrFull, Imgproc.COLOR_RGBA2BGR)

        val scale = PROCESS_W.toDouble() / bgrFull.cols().toDouble()
        val procH = max((bgrFull.rows() * scale).toInt(), 360)
        val proc = Mat()
        Imgproc.resize(bgrFull, proc, Size(PROCESS_W.toDouble(), procH.toDouble()))

        val gray = Mat()
        Imgproc.cvtColor(proc, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(gray, edges, CANNY_LOW, CANNY_HIGH)

        val h = edges.rows().toDouble()
        val w = edges.cols().toDouble()
        val roiTop = h * ROI_TOP_RATIO
        val roiMask = Mat.zeros(edges.size(), CvType.CV_8UC1)
        val roi = MatOfPoint(
            Point(0.0, h),
            Point(w,   h),
            Point(w * 0.70, roiTop),
            Point(w * 0.30, roiTop)
        )
        Imgproc.fillPoly(roiMask, listOf(roi), Scalar(255.0, 255.0, 255.0))
        val roiEdges = Mat()
        Core.bitwise_and(edges, roiMask, roiEdges)

        val histY0 = (h * BOTTOM_HIST_START).toInt().coerceAtMost((h - 1.0).toInt())
        val hist = DoubleArray(w.toInt()) { 0.0 }
        for (y in histY0 until h.toInt()) {
            var x = 0
            while (x < w.toInt()) {
                val v = roiEdges.get(y, x)
                if (v != null && v[0] > 0.0) hist[x] += 1.0
                x++
            }
        }
        val midX = (w * 0.5).toInt()
        var lPeak = 0; var lVal = -1.0
        for (x in 0 until midX) if (hist[x] > lVal) { lVal = hist[x]; lPeak = x }
        var rPeak = midX; var rVal = -1.0
        for (x in midX until w.toInt()) if (hist[x] > rVal) { rVal = hist[x]; rPeak = x }

        val corridor = (w * CORRIDOR_W_RATIO).toInt().coerceAtLeast(8)
        val corridorMask = Mat.zeros(roiEdges.size(), CvType.CV_8UC1)
        Imgproc.rectangle(corridorMask,
            Point((lPeak - corridor).toDouble(), roiTop),
            Point((lPeak + corridor).toDouble(), h),
            Scalar(255.0, 255.0, 255.0), -1)
        Imgproc.rectangle(corridorMask,
            Point((rPeak - corridor).toDouble(), roiTop),
            Point((rPeak + corridor).toDouble(), h),
            Scalar(255.0, 255.0, 255.0), -1)
        val eCorridor = Mat()
        Core.bitwise_and(roiEdges, corridorMask, eCorridor)

        val lsd = Imgproc.createLineSegmentDetector(Imgproc.LSD_REFINE_STD)
        val lines = Mat()
        lsd.detect(eCorridor, lines)

        val yTop = roiTop
        val yBot = h
        val leftBottomXs = ArrayList<Double>()
        val leftTopXs = ArrayList<Double>()
        val rightBottomXs = ArrayList<Double>()
        val rightTopXs = ArrayList<Double>()

        for (r in 0 until lines.rows()) {
            val v = lines.get(r, 0) ?: continue
            val x1 = v[0].toDouble(); val y1 = v[1].toDouble()
            val x2 = v[2].toDouble(); val y2 = v[3].toDouble()
            val dx = x2 - x1
            val dy = y2 - y1
            if (abs(dy) < abs(dx) * 1.6) continue
            val k = dx / dy
            val b = x1 - k * y1
            val xAtTop = k * yTop + b
            val xAtBot = k * yBot + b
            if (xAtBot < w * 0.5) {
                leftBottomXs.add(xAtBot); leftTopXs.add(xAtTop)
            } else {
                rightBottomXs.add(xAtBot); rightTopXs.add(xAtTop)
            }
        }

        fun median(list: List<Double>): Double {
            if (list.isEmpty()) return Double.NaN
            val s = list.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
        }

        val measL = Pair(median(leftBottomXs), median(leftTopXs))
        val measR = Pair(median(rightBottomXs), median(rightTopXs))

        fun updateTrack(track: Track?, meas: Pair<Double, Double>, imgW: Double): Track? {
            var (zb, zt) = meas
            if (zb.isNaN() || zt.isNaN()) return track
            if (track == null) return Track(zb, zt)
            var pb = track.xb + track.vb
            var pt = track.xt + track.vt
            val rb = zb - pb
            val rt = zt - pt
            val vb = track.vb + BETA * rb
            val vt = track.vt + BETA * rt
            var xb = pb + ALPHA * rb
            var xt = pt + ALPHA * rt
            val maxStep = imgW * MAX_STEP_RATIO
            val db = xb - track.xb
            if (abs(db) > maxStep) xb = track.xb + maxStep * sign(db)
            val kPrev = (track.xb - track.xt) / (yBot - yTop + 1e-6)
            var kNew = (xb - xt) / (yBot - yTop + 1e-6)
            val dk = kNew - kPrev
            if (abs(dk) > MAX_SLOPE_DELTA) {
                kNew = kPrev + MAX_SLOPE_DELTA * sign(dk)
                xt = xb - kNew * (yBot - yTop)
            }
            return Track(xb, xt, vb, vt)
        }

        leftT = updateTrack(leftT, measL, w)
        rightT = updateTrack(rightT, measR, w)

        fun kbFromTrack(t: Track?): Pair<Double, Double>? {
            if (t == null) return null
            val k = (t.xb - t.xt) / (yBot - yTop + 1e-6)
            val b = t.xb - k * yBot
            return Pair(k, b)
        }
        val leftKB = kbFromTrack(leftT)
        val rightKB = kbFromTrack(rightT)

        val overlayFull = bgrFull.clone()
        val color = Scalar(0.0, 255.0, 0.0)

        fun drawModel(kb: Pair<Double, Double>?) {
            if (kb == null) return
            val (k, b) = kb
            val ys = DoubleArray(STEPS + 1) { i ->
                val yProc = roiTop + (h - roiTop) * (i.toDouble() / STEPS.toDouble())
                (yProc / scale)
            }
            for (j in 0 until STEPS) {
                val y1p = roiTop + (h - roiTop) * (j.toDouble() / STEPS.toDouble())
                val y2p = roiTop + (h - roiTop) * ((j + 1).toDouble() / STEPS.toDouble())
                val x1p = k * y1p + b
                val x2p = k * y2p + b
                val x1f = (x1p / scale); val x2f = (x2p / scale)
                val p1 = Point(min(max(x1f, 0.0), bgrFull.cols() - 1.0), min(max(ys[j], 0.0), bgrFull.rows() - 1.0))
                val p2 = Point(min(max(x2f, 0.0), bgrFull.cols() - 1.0), min(max(ys[j + 1], 0.0), bgrFull.rows() - 1.0))
                Imgproc.line(overlayFull, p1, p2, color, THICK, Imgproc.LINE_AA)
            }
        }
        drawModel(leftKB)
        drawModel(rightKB)

        val outBmp = Bitmap.createBitmap(overlayFull.cols(), overlayFull.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(overlayFull, outBmp)

        listOf(rgbaFull, bgrFull, proc, gray, edges, roiMask, roi, roiEdges, corridorMask, eCorridor, overlayFull, lines)
            .forEach { it.release() }
        return outBmp
    }

    // ===== 드레인 모드 MJPEG 파서: 항상 최신 바이트만 추출 (디코드는 처리 코루틴에서) =====
    private class MjpegInputStream(private val input: java.io.InputStream) {
        private val DRAIN_THRESHOLD_BYTES = 16 * 1024
        private val MAX_SKIP_FRAMES = 8

        fun readLatestFrameBytes(): ByteArray? {
            try {
                var skipped = 0
                while (true) {
                    // 1) 입력 버퍼가 많이 쌓였으면 드레인(지연 원인 제거)
                    val avail = input.available()
                    if (avail > DRAIN_THRESHOLD_BYTES) {
                        input.skip((avail - DRAIN_THRESHOLD_BYTES).toLong())
                    }

                    // 2) SOI(FFD8) 찾기
                    val baos = ByteArrayOutputStream()
                    var prev = -1
                    while (true) {
                        val curr = input.read().takeIf { it >= 0 } ?: return null
                        if (prev == 0xFF && curr == 0xD8) {
                            baos.reset()
                            baos.write(0xFF); baos.write(0xD8)
                            break
                        }
                        prev = curr
                    }

                    // 3) EOI(FFD9)까지 읽기
                    prev = -1
                    while (true) {
                        val curr = input.read().takeIf { it >= 0 } ?: break
                        baos.write(curr)
                        if (prev == 0xFF && curr == 0xD9) break
                        prev = curr
                    }

                    // 4) 아직 버퍼가 과하게 쌓여 있으면 이 프레임은 버리고 다음으로
                    val afterAvail = input.available()
                    if (afterAvail > DRAIN_THRESHOLD_BYTES && skipped < MAX_SKIP_FRAMES) {
                        skipped++
                        continue
                    }

                    // 5) 최신 프레임 바이트 반환 (디코드는 밖에서)
                    return baos.toByteArray()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }
    }
}
