// app/src/main/java/com/example/myapplication/smarthelmet/StreamActivity.kt
package com.example.myapplication.smarthelmet

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.databinding.ActivityStreamBinding
import com.example.myapplication.smarthelmet.MjpegReader
import com.example.myapplication.smarthelmet.ui.RearDetectionOverlayView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest

import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

// 🚨 사고 폴링 + 팝업 컨트롤러
import com.example.myapplication.smarthelmet.accident.SagoStatusPoller
import com.example.myapplication.smarthelmet.accident.AccidentAlertController
import com.example.myapplication.smarthelmet.RearCamDetectionManager
import com.example.myapplication.smarthelmet.record.RearCamDetectionEngine

class StreamActivity : AppCompatActivity() {

    private lateinit var vb: ActivityStreamBinding
    private var reader: MjpegReader? = null
    private lateinit var prefs: SharedPreferences

    private var lastUrl: String? = null
    private var lastIpOnly: String? = null

    private enum class Cam { FRONT, REAR, USB_OTHER }
    private var currentCam: Cam? = null
    private var currentUsbDev: Int? = null

    // 전면 색상 스왑 값 유지
    private var frontSwap: Int
        get() = prefs.getInt("front_swap", 0)
        set(v) { prefs.edit { putInt("front_swap", if (v != 0) 1 else 0) } }

    // 전면 차선 가이드용 처리 상태
    private data class Track(var xb: Double, var xt: Double, var vb: Double = 0.0, var vt: Double = 0.0)
    private var leftT: Track? = null
    private var rightT: Track? = null
    private var lastProcMs: Long = 0L
    private var laneEnabled: Boolean = false
    private val PROCESS_W = 640
    private val ROI_TOP_RATIO = 0.35
    private val BOTTOM_HIST_START = 0.70
    private val CORRIDOR_W_RATIO = 0.06
    private val CANNY_LOW = 60.0
    private val CANNY_HIGH = 180.0
    private val STEPS = 16
    private val THICK = 4
    private val ALPHA = 0.35
    private val BETA = 0.15
    private val MAX_STEP_RATIO = 0.04
    private val MAX_SLOPE_DELTA = 0.0018

    private var rearOverlay: RearDetectionOverlayView? = null
    private var detectionCollectJob: Job? = null
    private var lastRearDetection: RearCamDetectionEngine.RearDetectionResult? = null

    // 🚨 사고 알림 폴러
    private var sagoPoller: SagoStatusPoller? = null
    private var alertController: AccidentAlertController? = null
    private val PREF_LAST_SAGO_TS = "last_sago_ts"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(vb.root)

        rearOverlay = findViewById(R.id.rearDetectionOverlay)

        // 화면 꺼짐 방지
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("stream_prefs", MODE_PRIVATE)

        OpenCVLoader.initDebug()

        // 좌상단 홈 → 종료, 시스템 뒤로가기도 종료
        vb.toolbar.setNavigationOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })

        // 우측 메뉴(옵션)
        vb.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_options -> {
                    showOptionsSheet()
                    true
                }
                else -> false
            }
        }

        // ✅ 사고 팝업 컨트롤러(30초 후 자동 신고 안내)
        alertController = AccidentAlertController(
            lifecycleOwner = this,
            context = this,
            autoReportDelayMs = 30_000L,
            onRequirePause = { sagoPoller?.pause() },
            onAllowResume = { sagoPoller?.resume() },
            onHandled = { ts ->
                recordSagoHandled(ts)
            }
        )

        // 자동 전면 재생: Intent PI_IP → 저장된 IP → 없으면 옵션 표시
        val ip = intent.getStringExtra("PI_IP") ?: prefs.getString("pi_ip", null).orEmpty()
        if (ip.isNotBlank()) {
            startFront(ip, frontSwap)
        } else {
            showOptionsSheet(autoFocusIp = true)
        }
    }

    override fun onStart() {
        super.onStart()
        if (reader == null && !lastUrl.isNullOrBlank()) {
            startStream(lastUrl!!)
        }

        // ✅ 컨트롤러 리셋(이전 팝업 상태 초기화)
        alertController?.reset()

        // 🚨 라즈베리파이 sago 상태 폴링 시작 (포트 5001: /accident/status)
        val baseUrlSago = "http://10.42.0.1:5001"
        sagoPoller = SagoStatusPoller(lifecycleScope, baseUrlSago, intervalMs = 1000L).also { poller ->
            poller.setBaseline(loadLastSagoTs())
            poller.start(
                onNewSago = { ts ->
                    // 컨트롤러가 즉시 팝업을 띄우고 30초 후 자동 신고 안내로 전환
                    alertController?.onAccident(ts)
                }
            )
        }

        detectionCollectJob = lifecycleScope.launch {
            RearCamDetectionManager.state.collectLatest { result ->
                lastRearDetection = result
                renderRearDetections()
            }
        }
        renderRearDetections()
    }

    override fun onStop() {
        reader?.stop()
        reader = null

        // 폴러/팝업 정리
        sagoPoller?.stop(); sagoPoller = null
        alertController?.dispose()

        detectionCollectJob?.cancel(); detectionCollectJob = null
        rearOverlay?.submit(null)
        rearOverlay?.visibility = View.GONE

        super.onStop()
    }

    override fun onDestroy() {
        reader?.stop()
        alertController?.dispose()
        alertController = null
        super.onDestroy()
    }

    // --------- helpers ---------

    private fun startFront(ip: String, swap: Int) {
        val s = if (swap != 0) 1 else 0
        val url = "http://$ip:5000/front?swap=$s"
        setSubtitle("전면 ($ip) · swap=$s")
        lastIpOnly = ip
        currentCam = Cam.FRONT
        currentUsbDev = null
        PiEndpoint.saveHost(this, ip)
        RearCamDetectionManager.refreshIfRunning(this)
        prefs.edit { putString("pi_ip", ip) }
        frontSwap = s

        // 전면: OpenCV 기반 차선 가이드 활성화
        laneEnabled = true
        leftT = null
        rightT = null
        // 전면 시작 시 후면 박스 대신 차선만 보이도록 기존 박스/선 상태 초기화
        clearRearDetections()
        clearLaneOverlay()
        startStream(url)
        renderRearDetections()
    }

    private fun startRear(ip: String) {
        val url = "http://$ip:5000/rear"  // dev=2 별칭
        setSubtitle("후면 dev=2 ($ip)")
        lastIpOnly = ip
        currentCam = Cam.REAR
        currentUsbDev = 2
        PiEndpoint.saveHost(this, ip)
        RearCamDetectionManager.refreshIfRunning(this)
        prefs.edit { putString("pi_ip", ip) }

        // 후면: 차선 비활성, 박스만
        laneEnabled = false
        clearLaneOverlay()
        startStream(url)
        renderRearDetections()
    }

    private fun startUsb(ip: String, dev: Int) {
        val url = if (dev == 2) "http://$ip:5000/rear"
        else "http://$ip:5000/usb_feed?dev=$dev"
        setSubtitle(if (dev == 2) "후면 dev=2 ($ip)" else "USB dev=$dev ($ip)")
        lastIpOnly = ip
        currentCam = if (dev == 2) Cam.REAR else Cam.USB_OTHER
        currentUsbDev = dev
        PiEndpoint.saveHost(this, ip)
        RearCamDetectionManager.refreshIfRunning(this)
        prefs.edit { putString("pi_ip", ip) }

        // USB 기타: 기본 비활성
        laneEnabled = false
        clearLaneOverlay()
        startStream(url)
        renderRearDetections()
    }

    private fun startStream(url: String) {
        if (lastUrl == url && reader != null) return
        reader?.stop()
        lastUrl = url
        vb.tvStatus.text = "연결 준비: $url"

        val isFrontLane = laneEnabled && currentCam == Cam.FRONT
        val targetView = if (isFrontLane) android.widget.ImageView(this).apply {
            visibility = View.GONE
        } else vb.ivStream

        // onFrame 콜백에서 전면은 OpenCV로 차선 추적, 그 외에는 기본 렌더링 유지
        reader = MjpegReader(
            url = url,
            target = targetView,
            scope = lifecycleScope,
            fpsCap = 30,
            autoReconnect = true,
            onFrame = { bmp ->
                // 처리 FPS 제한(약 10fps)
                val now = SystemClock.elapsedRealtime()
                if (now - lastProcMs < 80) return@MjpegReader
                lastProcMs = now

                if (isFrontLane) {
                    lifecycleScope.launch(Dispatchers.Default) {
                        val processed = try {
                            val rotated = rotate180(bmp)
                            detectLanesFast(rotated)
                        } catch (t: Throwable) {
                            t.printStackTrace()
                            null
                        }
                        withContext(Dispatchers.Main) {
                            processed?.let { vb.ivStream.setImageBitmap(it) }
                        }
                    }
                } else {
                    val w = bmp.width
                    val h = bmp.height
                    rearOverlay?.post {
                        rearOverlay?.updateSourceSize(w, h)
                    }
                }
            },
            onStatus = { s ->
                vb.tvStatus.post { vb.tvStatus.text = s }
            }
        ).also { it.start() }
    }

    private fun renderRearDetections() {
        val overlay = rearOverlay ?: return
        val shouldShow = currentCam == Cam.REAR
        val result = if (shouldShow) lastRearDetection else null
        overlay.submit(result)
        overlay.visibility = when {
            !shouldShow -> View.GONE
            result == null || result.detections.isEmpty() -> View.INVISIBLE
            else -> View.VISIBLE
        }
    }

    private fun clearLaneOverlay() {
        vb.overlay.submit(null)
        vb.overlay.visibility = View.GONE
    }

    private fun clearRearDetections() {
        rearOverlay?.submit(null)
        rearOverlay?.visibility = View.GONE
    }

    private fun setSubtitle(text: String) {
        vb.toolbar.subtitle = text
    }

    private fun recordSagoHandled(ts: String) {
        saveLastSagoTs(ts)
        sagoPoller?.setBaseline(ts)
    }

    private fun loadLastSagoTs(): String? = prefs.getString(PREF_LAST_SAGO_TS, null)

    private fun saveLastSagoTs(ts: String) {
        prefs.edit { putString(PREF_LAST_SAGO_TS, ts) }
    }

    // 옵션 시트
    private fun showOptionsSheet(autoFocusIp: Boolean = false) {
        val dlg = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bs_stream_options, null)
        dlg.setContentView(view)

        val etIp   = view.findViewById<android.widget.EditText>(R.id.etIp)
        val rg     = view.findViewById<android.widget.RadioGroup>(R.id.rgSource)
        val rbFront= view.findViewById<android.widget.RadioButton>(R.id.rbFront)
        val rbUsb  = view.findViewById<android.widget.RadioButton>(R.id.rbUsb)
        val spUsb  = view.findViewById<android.widget.Spinner>(R.id.spUsbDev)
        val spSwap = view.findViewById<android.widget.Spinner>(R.id.spSwap)
        val btnOk  = view.findViewById<android.widget.Button>(R.id.btnApply)
        val btnNo  = view.findViewById<android.widget.Button>(R.id.btnCancel)

        // USB dev 목록(0,1,2) — 기본 2번
        spUsb.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, listOf(0, 1, 2))
        spUsb.setSelection(2)

        // Front 색상 스왑(0/1)
        spSwap.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, listOf(0, 1))
        spSwap.setSelection(frontSwap)

        // IP 초기값
        etIp.setText(prefs.getString("pi_ip", "") ?: "")
        if (autoFocusIp) etIp.post { etIp.requestFocus() }

        // 전면/USB 선택에 따라 스피너 활성화
        fun updateEnables() {
            spUsb.isEnabled = rbUsb.isChecked
            spSwap.isEnabled = rbFront.isChecked
        }
        rg.setOnCheckedChangeListener { _, _ -> updateEnables() }
        updateEnables()

        btnOk.setOnClickListener {
            val ip = etIp.text?.toString()?.trim().orEmpty()
            if (ip.isBlank()) { dlg.dismiss(); return@setOnClickListener }

            if (rbFront.isChecked) {
                val swap = spSwap.selectedItem as Int
                startFront(ip, swap)
            } else {
                val dev = spUsb.selectedItem as Int
                if (dev == 2) startRear(ip) else startUsb(ip, dev)
            }
            dlg.dismiss()
        }
        btnNo.setOnClickListener { dlg.dismiss() }

        dlg.show()
    }

    private fun rotate180(srcBmp: Bitmap): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(srcBmp, src)
        val dst = Mat()
        Core.rotate(src, dst, Core.ROTATE_180)
        val out = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, out)
        src.release()
        dst.release()
        return out
    }

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
            Point(w, h),
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
        var lPeak = 0
        var lVal = -1.0
        for (x in 0 until midX) if (hist[x] > lVal) {
            lVal = hist[x]
            lPeak = x
        }
        var rPeak = midX
        var rVal = -1.0
        for (x in midX until w.toInt()) if (hist[x] > rVal) {
            rVal = hist[x]
            rPeak = x
        }

        val corridor = (w * CORRIDOR_W_RATIO).toInt().coerceAtLeast(8)
        val corridorMask = Mat.zeros(roiEdges.size(), CvType.CV_8UC1)
        Imgproc.rectangle(
            corridorMask,
            Point((lPeak - corridor).toDouble(), roiTop),
            Point((lPeak + corridor).toDouble(), h),
            Scalar(255.0, 255.0, 255.0),
            -1
        )
        Imgproc.rectangle(
            corridorMask,
            Point((rPeak - corridor).toDouble(), roiTop),
            Point((rPeak + corridor).toDouble(), h),
            Scalar(255.0, 255.0, 255.0),
            -1
        )
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
            val x1 = v[0].toDouble()
            val y1 = v[1].toDouble()
            val x2 = v[2].toDouble()
            val y2 = v[3].toDouble()
            val dx = x2 - x1
            val dy = y2 - y1
            if (abs(dy) < abs(dx) * 1.6) continue
            val k = dx / dy
            val b = x1 - k * y1
            val xAtTop = k * yTop + b
            val xAtBot = k * yBot + b
            if (xAtBot < w * 0.5) {
                leftBottomXs.add(xAtBot)
                leftTopXs.add(xAtTop)
            } else {
                rightBottomXs.add(xAtBot)
                rightTopXs.add(xAtTop)
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
                val x1f = (x1p / scale)
                val x2f = (x2p / scale)
                val p1 = Point(
                    min(max(x1f, 0.0), bgrFull.cols() - 1.0),
                    min(max(ys[j], 0.0), bgrFull.rows() - 1.0)
                )
                val p2 = Point(
                    min(max(x2f, 0.0), bgrFull.cols() - 1.0),
                    min(max(ys[j + 1], 0.0), bgrFull.rows() - 1.0)
                )
                Imgproc.line(overlayFull, p1, p2, color, THICK, Imgproc.LINE_AA)
            }
        }
        drawModel(leftKB)
        drawModel(rightKB)

        val outBmp = Bitmap.createBitmap(overlayFull.cols(), overlayFull.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(overlayFull, outBmp)

        listOf(rgbaFull, bgrFull, proc, gray, edges, roiMask, roiEdges, corridorMask, eCorridor, overlayFull, lines)
            .forEach { it.release() }
        return outBmp
    }

}
