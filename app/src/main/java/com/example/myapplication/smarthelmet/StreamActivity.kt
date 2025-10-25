// app/src/main/java/com/example/myapplication/smarthelmet/StreamActivity.kt
package com.example.myapplication.smarthelmet

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest

// 🚨 사고 폴링 + 팝업 컨트롤러
import com.example.myapplication.smarthelmet.accident.SagoStatusPoller
import com.example.myapplication.smarthelmet.accident.AccidentAlertController
import com.example.myapplication.smarthelmet.RearCamDetectionManager
import com.example.myapplication.smarthelmet.processing.LaneProcessorLite
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

    private var lastProcMs: Long = 0L
    private var laneEnabled: Boolean = false

    private var rearOverlay: RearDetectionOverlayView? = null
    private var detectionCollectJob: Job? = null
    private var lastRearDetection: RearCamDetectionEngine.RearDetectionResult? = null

    private var laneProcessingJob: Job? = null
    private val laneProcessor = LaneProcessorLite()

    // 🚨 사고 알림 폴러
    private var sagoPoller: SagoStatusPoller? = null
    private var alertController: AccidentAlertController? = null
    private val SAGO_COOLDOWN_MS = 3_000L
    private val PREF_LAST_SAGO_TS = "last_sago_ts"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(vb.root)

        rearOverlay = findViewById(R.id.rearDetectionOverlay)

        // 화면 꺼짐 방지
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("stream_prefs", MODE_PRIVATE)

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

        // 최초 진입 시에도 객체 인식 엔진이 바로 실행되도록 보장한다.
        RearCamDetectionManager.start(this)

        // ✅ 컨트롤러 리셋(이전 팝업 상태 초기화)
        alertController?.reset()

        // 🚨 라즈베리파이 sago 상태 폴링 시작 (포트 5001: /accident/status)
        val baseUrlSago = "http://10.42.0.1:5001"
        sagoPoller = SagoStatusPoller(
            scope = lifecycleScope,
            baseUrl = baseUrlSago,
            intervalMs = 1000L,
            minIntervalMs = SAGO_COOLDOWN_MS
        ).also { poller ->
            poller.setBaseline(loadLastSagoTs())
            poller.start(
                onNewSago = { ts ->
                    // 컨트롤러가 즉시 팝업을 띄우고 30초 후 자동 신고 안내로 전환
                    alertController?.onAccident(ts)
                },
                onError = { e -> e.printStackTrace() },
                onSuppressed = { ts ->
                    // 3초 쿨다운 동안 들어온 신호는 기록만 남기고 팝업을 띄우지 않는다.
                    recordSagoHandled(ts)
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
        clearLaneOverlay()

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

        // 전면 차선 가이드 활성화
        laneEnabled = true
        // 전면 시작 시 후면 박스 대신 차선만 보이도록 기존 박스/선 상태 초기화
        clearRearDetections()
        clearLaneOverlay()
        startStream(url)
        renderRearDetections()
    }

    private fun startRear(ip: String) {
        val url = "http://$ip:5000/usb_feed?dev=0"
        setSubtitle("후면 dev=0 ($ip)")
        lastIpOnly = ip
        currentCam = Cam.REAR
        currentUsbDev = 0
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
        val url = "http://$ip:5000/usb_feed?dev=$dev"
        val isRear = dev == 0
        setSubtitle(if (isRear) "후면 dev=0 ($ip)" else "USB dev=$dev ($ip)")
        lastIpOnly = ip
        currentCam = if (isRear) Cam.REAR else Cam.USB_OTHER
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
        lastProcMs = 0L

        // onFrame 콜백에서 전면은 경량 차선 인식 오버레이를 갱신하고, 모든 스트림은 180도 회전하여 표시한다.
        reader = MjpegReader(
            url = url,
            target = android.widget.ImageView(this).apply { visibility = View.GONE },
            scope = lifecycleScope,
            fpsCap = 30,
            autoReconnect = true,
            onFrame = { bmp ->
                val rotated = rotateBitmap180(bmp)
                bmp.recycle()

                vb.ivStream.post { vb.ivStream.setImageBitmap(rotated) }

                val shouldProcessLane = laneEnabled && currentCam == Cam.FRONT
                if (shouldProcessLane) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProcMs < 120) return@MjpegReader
                    lastProcMs = now

                    val sample = rotated.copy(Bitmap.Config.ARGB_8888, false)
                    laneProcessingJob?.cancel()
                    laneProcessingJob = lifecycleScope.launch(Dispatchers.Default) {
                        try {
                            val overlay = laneProcessor.processFrame(sample)
                            withContext(Dispatchers.Main) {
                                if (currentCam == Cam.FRONT && laneEnabled) {
                                    if (overlay != null) {
                                        vb.overlay.visibility = View.VISIBLE
                                        vb.overlay.submit(overlay)
                                    } else {
                                        vb.overlay.submit(null)
                                        vb.overlay.visibility = View.INVISIBLE
                                    }
                                }
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            t.printStackTrace()
                        } finally {
                            sample.recycle()
                            if (laneProcessingJob === this@launch) {
                                laneProcessingJob = null
                            }
                        }
                    }
                } else {
                    vb.overlay.post {
                        vb.overlay.submit(null)
                        vb.overlay.visibility = View.GONE
                    }
                    val w = rotated.width
                    val h = rotated.height
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
        val result = if (shouldShow) lastRearDetection?.let { rotateDetections180(it) } else null
        overlay.submit(result)
        overlay.visibility = when {
            !shouldShow -> View.GONE
            result == null || result.detections.isEmpty() -> View.INVISIBLE
            else -> View.VISIBLE
        }
    }

    private fun clearLaneOverlay() {
        laneProcessingJob?.cancel()
        laneProcessingJob = null
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

        // USB dev 목록(0,1,2) — 기본 0번
        spUsb.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, listOf(0, 1, 2))
        spUsb.setSelection(0)

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
                if (dev == 0) startRear(ip) else startUsb(ip, dev)
            }
            dlg.dismiss()
        }
        btnNo.setOnClickListener { dlg.dismiss() }

        dlg.show()
    }

    private fun rotateBitmap180(src: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(180f, src.width / 2f, src.height / 2f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun rotateDetections180(result: RearCamDetectionEngine.RearDetectionResult): RearCamDetectionEngine.RearDetectionResult {
        val rotated = result.detections.map { det ->
            val box = det.box
            val rotatedBox = RectF(
                1f - box.right,
                1f - box.bottom,
                1f - box.left,
                1f - box.top,
            )
            det.copy(box = rotatedBox)
        }
        return result.copy(detections = rotated)
    }
}
