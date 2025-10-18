// app/src/main/java/com/example/myapplication/smarthelmet/accident/AccidentAlertController.kt
package com.example.myapplication.smarthelmet.accident

import android.content.Context
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 사고 알림 팝업 컨트롤러
 * 1. Raspberry Pi에서 새로운 "sago" 이벤트를 받으면 즉시 팝업을 띄운다.
 * 2. 사용자가 "사고 아님"을 누르면 팝업을 닫고 상태를 초기화한다.
 * 3. 30초가 지나도록 사용자가 응답하지 않으면 "119 신고" 안내 팝업으로 전환한다.
 *    팝업이 활성화된 동안에는 사고 폴러를 일시 정지시켜 중복 이벤트가 쌓이지 않도록 한다.
 */
class AccidentAlertController(
    private val lifecycleOwner: LifecycleOwner,
    private val context: Context,
    private val autoReportDelayMs: Long = 30_000L,
    private val onRequirePause: () -> Unit = {},
    private val onAllowResume: () -> Unit = {},
    private val onHandled: (String) -> Unit = {}
) {
    private enum class Phase { IDLE, ALERT, REPORT }

    private var phase: Phase = Phase.IDLE
    private var dialog: AlertDialog? = null
    private var messageView: TextView? = null
    private var timerJob: Job? = null
    private var activeTs: String? = null

    /** 새 사고 타임스탬프 수신 시 호출 */
    fun onAccident(tsIsoUtc: String) {
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (phase != Phase.IDLE) return

        phase = Phase.ALERT
        activeTs = tsIsoUtc
        onRequirePause.invoke()
        showAlertDialog()
    }

    /** 사용자 확인 등으로 다음 사고를 수용하고 싶을 때 호출 */
    fun reset() {
        resetInternal(resumePolling = true)
    }

    fun dispose() {
        resetInternal(resumePolling = true)
    }

    private fun showAlertDialog() {
        runOnMain {
            dismissDialog()
            val msgView = TextView(context).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(16), dp(24), dp(8))
                textSize = 18f
            }
            messageView = msgView
            val dlg = AlertDialog.Builder(context)
                .setTitle("사고 알림")
                .setView(msgView)
                .setPositiveButton("사고 아님", null)
                .setCancelable(false)
                .create()

            dlg.setCanceledOnTouchOutside(false)
            dlg.setOnShowListener {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    handleUserDismiss()
                }
            }
            dlg.setOnDismissListener {
                dialog = null
                if (phase == Phase.ALERT) {
                    handleUserDismiss()
                }
            }

            dialog = dlg
            dlg.show()
            startAlertCountdown()
        }
    }

    private fun showReportDialog() {
        timerJob?.cancel()
        phase = Phase.REPORT
        runOnMain {
            dismissDialog()
            val dlg = AlertDialog.Builder(context)
                .setTitle("119 신고")
                .setMessage("30초 동안 응답이 없어 119에 신고합니다.")
                .setPositiveButton("확인", null)
                .setCancelable(false)
                .create()

            dlg.setCanceledOnTouchOutside(false)
            dlg.setOnShowListener {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    completeReport()
                }
            }
            dlg.setOnDismissListener {
                dialog = null
                if (phase == Phase.REPORT) {
                    completeReport()
                }
            }

            dialog = dlg
            dlg.show()
        }
    }

    private fun handleUserDismiss() {
        resetInternal(resumePolling = true)
    }

    private fun completeReport() {
        resetInternal(resumePolling = true)
    }

    private fun resetInternal(resumePolling: Boolean) {
        timerJob?.cancel()
        timerJob = null
        phase = Phase.IDLE
        val handledTs = activeTs
        activeTs = null
        dismissDialog()
        handledTs?.let { onHandled(it) }
        if (resumePolling) {
            onAllowResume.invoke()
        }
    }

    private fun dismissDialog() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        messageView = null
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) { block() }
        }
    }

    private fun startAlertCountdown() {
        timerJob?.cancel()
        updateAlertMessage(autoReportDelayMs)
        timerJob = lifecycleOwner.lifecycleScope.launch {
            var remainingMs = autoReportDelayMs
            while (phase == Phase.ALERT && remainingMs > 0 && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                withContext(Dispatchers.Main) { updateAlertMessage(remainingMs) }
                val step = max(250L, minOf(1000L, remainingMs))
                delay(step)
                remainingMs -= step
            }
            if (phase == Phase.ALERT && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                withContext(Dispatchers.Main) { showReportDialog() }
            }
        }
    }

    private fun updateAlertMessage(remainingMs: Long) {
        val seconds = ((remainingMs + 999) / 1000).coerceAtLeast(0L).toInt()
        val text = "사고가 감지되었습니다\n자동 신고까지 ${seconds}초 남았습니다."
        messageView?.text = text
    }

    private fun dp(value: Int): Int {
        val density = context.resources.displayMetrics.density
        return (value * density + 0.5f).toInt()
    }
}
