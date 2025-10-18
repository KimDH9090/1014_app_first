@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.myapplication.smarthelmet

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// Media3
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

// 사고 폴링
import com.example.myapplication.smarthelmet.accident.SagoStatusPoller
// 사고 배너 컨트롤러 (5초 후 자동 ‘119에 자동신고되었습니다’)
import com.example.myapplication.smarthelmet.accident.AccidentAlertController

class RideGuideFragment : Fragment(R.layout.fragment_ride_guide) {

    // --- Streaming (RTSP 5000 고정, HTTP fallback도 5000) ---
    private fun buildRtspUrl(host: String) = "rtsp://$host:5000/unicast"
    private fun buildHttpStreamUrl(host: String) = "http://$host:5000/video_feed"

    private lateinit var bleManager: BleManager
    private lateinit var txtStatus: TextView

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var webView: WebView? = null
    private lateinit var loadingIndicator: View

    private val mainHandler = Handler(Looper.getMainLooper())
    private var didStartPlayback = false

    // --- HTTP API (포트 8000) ---
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var lastSignalHandled: String? = null
    private var sentUnlockGo2 = false
    private var sentLockGo2 = false

    // 사고 알림 폴러
    private var sagoPoller: SagoStatusPoller? = null

    // 사고 알림 팝업 컨트롤러 (30초 후 자동 신고 안내)
    private var bannerController: AccidentAlertController? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bleManager = BleManager(requireContext())

        txtStatus = view.findViewById(R.id.txtAccel)
        playerView = view.findViewById(R.id.playerView)
        loadingIndicator = view.findViewById(R.id.loadingIndicator)

        // 사고 알림 팝업 컨트롤러 (30초 후 자동 신고 안내)
        bannerController = AccidentAlertController(
            lifecycleOwner = viewLifecycleOwner,
            context = requireContext(),
            autoReportDelayMs = 30_000L,
            onRequirePause = { sagoPoller?.pause() },
            onAllowResume = { sagoPoller?.resume() }
        )

        // --- 호스트/포트 구성 ---
        val apiBase = PiEndpoint.httpBase(
            context = requireContext(),
            defaultHost = "10.42.0.1",
            defaultPort = 8000
        ) // 예: http://10.42.0.1:8000

        val host = PiEndpoint.host(requireContext(), "10.42.0.1")
        val rtspUrl = buildRtspUrl(host)
        val httpStreamUrl = buildHttpStreamUrl(host)

        initPlayer(rtspUrl, httpStreamUrl)
        bindBleFlows()

        // RTSP가 5초 내 READY되지 않으면 HTTP(WebView) 폴백
        mainHandler.postDelayed({
            if (!didStartPlayback) {
                switchToWebView(httpStreamUrl)
            }
        }, 5000)

        // 상태 폴링은 화면 진입 시 시작(명령 필요 없을 때도 서버 알림 수신 가능)
        startReadyStatePolling(apiBase)
    }

    // 상단 텍스트/토스트 헬퍼
    private fun setStatusText(text: String) {
        if (!isAdded) return
        requireActivity().runOnUiThread {
            txtStatus.text = text
        }
    }

    private fun toast(msg: String) {
        if (!isAdded) return
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ===============================
    // 대여/반납 시퀀스 (상위 버튼에서 호출)
    // ===============================
    fun startRentalSequence() {
        val apiBase = PiEndpoint.httpBase(requireContext(), "10.42.0.1", 8000)
        sentUnlockGo2 = false
        ioScope.launch {
            val ok = postCommand(apiBase, "UNLOCKGO1")
            if (ok) setStatusText("헬멧 착용 확인 중…")
            else toast("UNLOCKGO1 전송 실패")
        }
    }

    fun startReturnSequence() {
        val apiBase = PiEndpoint.httpBase(requireContext(), "10.42.0.1", 8000)
        sentLockGo2 = false
        ioScope.launch {
            val ok = postCommand(apiBase, "LOCKGO1")
            if (ok) setStatusText("헬멧 반납 확인 중…")
            else toast("LOCKGO1 전송 실패")
        }
    }

    // ===============================
    // /api/ready_state 폴링 및 신호 처리
    // ===============================
    private fun startReadyStatePolling(apiBase: String) {
        if (pollJob?.isActive == true) return
        pollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val obj = getJson("$apiBase/api/ready_state")
                    obj?.let { handleReadyState(it, apiBase) }
                } catch (_: Exception) {
                }
                delay(1000)
            }
        }
    }

    private fun handleReadyState(json: JSONObject, apiBase: String) {
        // 기대 포맷: { "signal": "UNLOCKBACK1" | "UNLOCKBACK2" | "LOCKBACK1" | "LOCKBACK2", "eventId": 123, ... }
        val signal = json.optString("signal", "")
        if (signal.isNullOrBlank()) {
            // 구(舊) 서버 호환: state만 오는 경우
            val state = json.optString("state", "")
            if (state == "RENTAL_READY") {
                setStatusText("대여 시작!")
            } else if (state == "RETURN_READY") {
                setStatusText("사용 완료")
            }
            return
        }

        // 중복 처리 방지
        if (signal == lastSignalHandled) return
        lastSignalHandled = signal

        when (signal) {
            "UNLOCKBACK1" -> {
                setStatusText("헬멧 착용")
                if (!sentUnlockGo2) {
                    sentUnlockGo2 = true
                    ioScope.launch {
                        val ok = postCommand(apiBase, "UNLOCKGO2")
                        if (!ok) {
                            toast("UNLOCKGO2 전송 실패")
                            sentUnlockGo2 = false
                        }
                    }
                }
            }
            "UNLOCKBACK2" -> {
                setStatusText("대여 시작!")
            }
            "LOCKBACK1" -> {
                setStatusText("헬멧 반납")
                if (!sentLockGo2) {
                    sentLockGo2 = true
                    ioScope.launch {
                        val ok = postCommand(apiBase, "LOCKGO2")
                        if (!ok) {
                            toast("LOCKGO2 전송 실패")
                            sentLockGo2 = false
                        }
                    }
                }
            }
            "LOCKBACK2" -> {
                setStatusText("사용 완료")
            }
            else -> {
                setStatusText(signal)
            }
        }
    }

    // ===============================
    // HTTP helpers
    // ===============================
    private suspend fun postCommand(apiBase: String, cmd: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$apiBase/api/command")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 7000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val body = JSONObject().put("command", cmd).toString()
            BufferedWriter(OutputStreamWriter(conn.outputStream, Charsets.UTF_8)).use {
                it.write(body)
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    private fun getJson(urlStr: String): JSONObject? {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection)
        return try {
            conn.connectTimeout = 4000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { br ->
                val txt = br.readText()
                JSONObject(txt)
            }
        } finally {
            conn.disconnect()
        }
    }

    // ===============================
    // 기존 BLE 센서 텍스트 바인딩 (필요시 유지)
    // ===============================
    private fun bindBleFlows() {
        viewLifecycleOwner.lifecycleScope.launch {
            bleManager.sensorFlow.collectLatest { payload ->
                val s = payload.toString(Charsets.UTF_8).trim()
                val label = when (s) {
                    "MAGNET_ON"  -> "잠금 상태: ON"
                    "MAGNET_OFF" -> "잠금 상태: OFF"
                    else         -> "센서: $s"
                }
                setStatusText(label)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            bleManager.ackFlow.collectLatest {
                // 필요시 ACK UI 반영
            }
        }
    }

    // ===============================
    // 스트리밍 (RTSP → WebView 폴백)
    // ===============================
    private fun initPlayer(rtspUrl: String, httpFallbackUrl: String) {
        loadingIndicator.visibility = View.VISIBLE

        player = ExoPlayer.Builder(requireContext()).build().also { exoPlayer ->
            playerView?.player = exoPlayer
            playerView?.useController = false

            exoPlayer.setMediaItem(MediaItem.fromUri(rtspUrl))
            exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        ExoPlayer.STATE_BUFFERING -> loadingIndicator.visibility = View.VISIBLE
                        ExoPlayer.STATE_READY -> {
                            loadingIndicator.visibility = View.GONE
                            didStartPlayback = true
                        }
                        ExoPlayer.STATE_ENDED -> loadingIndicator.visibility = View.GONE
                        else -> Unit
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    toast("영상 재생 오류: ${error.errorCodeName}")
                    // 바로 HTTP(WebView)로 폴백
                    switchToWebView(httpFallbackUrl)
                }
            })

            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    private fun switchToWebView(url: String) {
        playerView?.visibility = View.GONE
        loadingIndicator.visibility = View.VISIBLE

        val parent = playerView?.parent as? android.view.ViewGroup ?: return
        if (webView == null) {
            webView = WebView(requireContext()).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                webViewClient = object : WebViewClient() {
                    override fun onPageCommitVisible(view: WebView, url: String) {
                        loadingIndicator.visibility = View.GONE
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        if (request.isForMainFrame) {
                            loadingIndicator.visibility = View.GONE
                            toast("페이지 에러: ${error.description}")
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse
                    ) {
                        if (request.isForMainFrame) {
                            loadingIndicator.visibility = View.GONE
                            toast("HTTP ${errorResponse.statusCode}")
                        }
                    }
                }
            }
            val insertIndex = parent.indexOfChild(playerView)
            parent.addView(webView, insertIndex)
        }
        webView?.visibility = View.VISIBLE
        webView?.loadUrl(url)
    }

    // ===============================
    // 수명주기
    // ===============================
    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true

        // ✅ 스트리밍 화면 진입 시 컨트롤러 상태 리셋(이전 화면 잔상/텍스트 방지)
        bannerController?.reset()

        // 라즈베리파이 사고 상태 폴링 시작 (포트 5001: /accident/status)
        val baseUrlSago = "http://10.42.0.1:5001"
        sagoPoller = SagoStatusPoller(viewLifecycleOwner.lifecycleScope, baseUrlSago, 1000L).also { poller ->
            poller.start(
                onNewSago = { ts ->
                    // ✅ 팝업 표시 및 30초 후 자동 신고 알림 전환
                    bannerController?.onAccident(ts)
                }
            )
        }
    }

    override fun onStop() {
        super.onStop()
        player?.playWhenReady = false

        // 정리
        sagoPoller?.stop()
        sagoPoller = null
        bannerController?.dispose()
        bannerController = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacksAndMessages(null)

        pollJob?.cancel()
        ioScope.coroutineContext.cancelChildren()

        playerView?.player = null
        player?.release()
        player = null

        webView?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        webView = null
    }

}
