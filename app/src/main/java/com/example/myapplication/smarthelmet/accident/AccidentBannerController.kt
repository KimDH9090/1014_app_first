package com.example.myapplication.smarthelmet.accident

import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

/**
 * 사고 배너 컨트롤러
 * - 최초 감지 즉시: "사고가 감지되었습니다" (빨간 배경)
 * - autoReportDelayMs 후: "119에 자동신고되었습니다" (주황 배경)
 * - 동일 사고 진행 중에는 추가 이벤트 무시(타이머 고정)
 */
class AccidentBannerController(
    private val lifecycleOwner: LifecycleOwner,
    private val bannerView: TextView,
    private val autoReportDelayMs: Long = 5_000L
) {
    private enum class Phase { IDLE, ALARM_SHOWN, REPORTED }

    private var phase: Phase = Phase.IDLE
    private var job: Job? = null

    /** 새 사고 타임스탬프 수신 시 호출 */
    fun onAccident(@Suppress("UNUSED_PARAMETER") tsIsoUtc: String) {
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        when (phase) {
            Phase.IDLE -> {
                phase = Phase.ALARM_SHOWN
                show("사고가 감지되었습니다", bg = "#CCFF3333")   // 빨강
                job?.cancel()
                job = lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    delay(autoReportDelayMs)
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        show("119에 자동신고되었습니다", bg = "#CCFF9900") // 주황
                        phase = Phase.REPORTED
                    }
                }
            }
            Phase.ALARM_SHOWN, Phase.REPORTED -> {
                // 진행 중/완료 상태면 무시 → 타이머/문구 유지
            }
        }
    }

    /** 다음 사고를 수용하고 싶을 때만 수동 호출 */
    fun reset() {
        job?.cancel(); job = null
        phase = Phase.IDLE
    }

    fun dispose() {
        job?.cancel(); job = null
        phase = Phase.IDLE
    }

    // ---- 내부 유틸 ----
    private fun show(text: String, bg: String) {
        // 항상 메인에서, 항상 최상단/가시화 보장
        bannerView.text = text
        bannerView.setBackgroundColor(Color.parseColor(bg))
        bannerView.visibility = View.VISIBLE
        bannerView.bringToFront()
        bannerView.animate().cancel()
        bannerView.alpha = 0f
        bannerView.animate().alpha(1f).setDuration(180).start()
    }
}
