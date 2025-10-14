// app/src/main/java/com/example/myapplication/smarthelmet/StreamActivity.kt
package com.example.myapplication.smarthelmet

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.databinding.ActivityStreamBinding
import com.example.myapplication.smarthelmet.processing.FrameProcessor
import com.example.myapplication.smarthelmet.processing.LaneProcessorLite
import com.example.myapplication.smarthelmet.ui.OverlayModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 🚨 사고 폴링 + 배너 컨트롤러
import com.example.myapplication.smarthelmet.accident.SagoStatusPoller
import com.example.myapplication.smarthelmet.accident.AccidentBannerController

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

    // 영상처리(전면만)
    private var processor: FrameProcessor? = null
    private var lastProcMs: Long = 0L

    // 🚨 배너 & 폴러
    private var txtAccidentBanner: TextView? = null
    private var sagoPoller: SagoStatusPoller? = null
    private var autoHideJob: Job? = null
    private var bannerController: AccidentBannerController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(vb.root)

        // 화면 꺼짐 방지
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("stream_prefs", MODE_PRIVATE)

        // 좌상단 홈 → 종료, 시스템 뒤로가기도 종료
        vb.toolbar.setNavigationOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })

        // 우측 메뉴(옵션 / 객체 검출 진입)
        vb.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_options -> {
                    showOptionsSheet()
                    true
                }
                R.id.action_rear_ai -> {
                    startActivity(
                        Intent(
                            this,
                            com.example.myapplication.smarthelmet.rear.RearCamTestActivity::class.java
                        )
                    )
                    true
                }
                else -> false
            }
        }

        // ✅ 배너 참조: include 존재 시 사용, 없으면 동적 추가
        txtAccidentBanner = findViewById(R.id.txtAccidentBanner)
        if (txtAccidentBanner == null) {
            val root = vb.root as ViewGroup
            val banner = TextView(this).apply {
                id = View.generateViewId()
                text = "사고가 감지되었습니다"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundColor(0xCCFF3333.toInt())
                elevation = 24f
                translationZ = 24f
                visibility = View.GONE
                isClickable = false
                isFocusable = false
            }
            // Toolbar 바로 아래 인덱스에 삽입
            root.addView(banner, /* index = */ minOf(1, root.childCount))
            txtAccidentBanner = banner
        }
        // 진입 시 배너 초기화
        txtAccidentBanner?.apply {
            text = "사고가 감지되었습니다"
            visibility = View.GONE
            bringToFront()
        }

        // ✅ 배너 자동 전환 컨트롤러(5초 후 "119에 자동신고되었습니다")
        bannerController = AccidentBannerController(
            lifecycleOwner = this,
            bannerView = requireNotNull(txtAccidentBanner),
            autoReportDelayMs = 5_000L
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

        // ✅ 컨트롤러/배너 리셋(이전 잔여 텍스트 방지)
        bannerController?.reset()
        txtAccidentBanner?.apply {
            text = "사고가 감지되었습니다"
            visibility = View.GONE
            bringToFront()
        }

        // 🚨 라즈베리파이 sago 상태 폴링 시작 (포트 5001: /accident/status)
        val baseUrlSago = "http://10.42.0.1:5001"
        sagoPoller = SagoStatusPoller(lifecycleScope, baseUrlSago, intervalMs = 1000L).also { poller ->
            poller.start(
                onNewSago = { ts ->
                    // 컨트롤러가 즉시 "사고가 감지되었습니다" 표시 → 5초 후 자동 "119에 자동신고되었습니다"
                    bannerController?.onAccident(ts)
                }
            )
        }
    }

    override fun onStop() {
        reader?.stop()
        reader = null

        // 폴러/배너 정리
        sagoPoller?.stop(); sagoPoller = null
        autoHideJob?.cancel(); autoHideJob = null

        bannerController?.dispose(); bannerController = null

        super.onStop()
    }

    override fun onDestroy() {
        reader?.stop()
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
        prefs.edit { putString("pi_ip", ip) }
        frontSwap = s

        // 전면: 라이트 차선 가이드 활성화
        processor = LaneProcessorLite()
        startStream(url)
    }

    private fun startRear(ip: String) {
        val url = "http://$ip:5000/rear"  // dev=2 별칭
        setSubtitle("후면 dev=2 ($ip)")
        lastIpOnly = ip
        currentCam = Cam.REAR
        currentUsbDev = 2
        prefs.edit { putString("pi_ip", ip) }

        // 후면: 일단 처리 비활성
        processor = null
        startStream(url)
    }

    private fun startUsb(ip: String, dev: Int) {
        val url = if (dev == 2) "http://$ip:5000/rear"
        else "http://$ip:5000/usb_feed?dev=$dev"
        setSubtitle(if (dev == 2) "후면 dev=2 ($ip)" else "USB dev=$dev ($ip)")
        lastIpOnly = ip
        currentCam = if (dev == 2) Cam.REAR else Cam.USB_OTHER
        currentUsbDev = dev
        prefs.edit { putString("pi_ip", ip) }

        // USB 기타: 기본 비활성
        processor = null
        startStream(url)
    }

    private fun startStream(url: String) {
        if (lastUrl == url && reader != null) return
        reader?.stop()
        lastUrl = url
        vb.tvStatus.text = "연결 준비: $url"

        // onFrame 콜백에서 가벼운 처리 → OverlayView 반영
        reader = MjpegReader(
            url = url,
            target = vb.ivStream,
            scope = lifecycleScope,
            fpsCap = 30,
            autoReconnect = true,
            onFrame = { bmp ->
                // 처리 FPS 제한(약 10fps)
                val now = SystemClock.elapsedRealtime()
                if (now - lastProcMs < 80) return@MjpegReader
                lastProcMs = now

                val p = processor ?: return@MjpegReader
                lifecycleScope.launch(Dispatchers.Default) {
                    val overlay: OverlayModel? = p.processFrame(bmp)
                    withContext(Dispatchers.Main) {
                        vb.overlay.submit(overlay)
                    }
                }
            },
            onStatus = { s ->
                vb.tvStatus.post { vb.tvStatus.text = s }
            }
        ).also { it.start() }
    }

    private fun setSubtitle(text: String) {
        vb.toolbar.subtitle = text
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

    // (남겨둔) 배너 표시 유틸 — 다른 경로에서 쓸 수 있음
    private fun showAccidentBanner(text: String) {
        val banner = txtAccidentBanner ?: return
        banner.text = text
        banner.visibility = View.VISIBLE
        banner.alpha = 0f
        banner.bringToFront()
        banner.animate().alpha(1f).setDuration(180).start()

        autoHideJob?.cancel()
        autoHideJob = lifecycleScope.launch {
            delay(6000)
            banner.visibility = View.GONE
        }
    }

    // dp 유틸
    private fun dp(v: Int): Int {
        val d = resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }
}
