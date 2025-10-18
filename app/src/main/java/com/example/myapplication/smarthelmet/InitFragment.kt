package com.example.myapplication.smarthelmet

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

import com.example.myapplication.smarthelmet.RearCamDetectionManager

class InitFragment : Fragment() {

    private lateinit var btnUse: Button
    private lateinit var btnReturn: Button
    private lateinit var wifiClient: WifiCommandClient

    // 진행 감시 Job (대여/반납 공용)
    private var watchJob: Job? = null

    // 재시작 구분용 Run Id
    private var currentRunId: Long = 0L

    // 대여 플래그
    private var sentUnlockGo2 = false
    private var seenUnlockBack1 = false

    // 반납 플래그
    private var sentLockGo2 = false
    private var seenLockBack1 = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_init, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnUse = view.findViewById(R.id.btnUse)
        btnReturn = view.findViewById(R.id.btnReturn)
        wifiClient = WifiCommandClient(requireContext())

        // 요구사항: 진행 중에도 재탭 가능 (중지 후 처음부터 다시 시작)
        btnUse.setOnClickListener { startUseFlow() }
        btnReturn.setOnClickListener { startReturnFlow() }

    }

    // =========================
    // 대여(사용) 플로우
    // =========================
    private fun startUseFlow() {
        val myRun = nextRun()
        cancelCurrentRun()

        // 요구사항: 대여는 즉시 스트리밍 화면 진입
        goToStreamImmediately()

        viewLifecycleOwner.lifecycleScope.launch {
            // 1) 선행 수습 (2단계 대기, 또는 ERROR 정리)
            reconcileToIdle(myRun)

            // 2) 1단계 요청
            toast("대여를 시작합니다. (1/2) 착용을 감지할게요.")
            val s1 = wifiClient.unlockStage1()
            if (!isMyRun(myRun)) return@launch
            if (!s1.success) {
                val msg = (s1.detailMessage ?: "")
                when {
                    msg.contains("WAITING_UNLOCK_STAGE2", true) -> {
                        // 이미 UNLOCK 단계2 대기 → 바로 2단계
                        startUnlockWatcher(myRun)
                        val s2 = wifiClient.unlockStage2()
                        if (!isMyRun(myRun)) return@launch
                        if (!s2.success) { toast("대여 (2/2) 진행 실패. 다시 시도해 주세요."); return@launch }
                    }
                    msg.contains("WAITING_LOCK_STAGE2", true) -> {
                        // LOCK 2단계 잔여 → 정리 후 재시도
                        val lock2 = wifiClient.sendCommand("LOCKGO2")
                        if (!isMyRun(myRun)) return@launch
                        if (!lock2.success) { toast("이전 반납 잔여 정리에 실패했습니다."); return@launch }
                        val retry = wifiClient.unlockStage1()
                        if (!isMyRun(myRun)) return@launch
                        if (!retry.success) { toast("대여 (1/2) 시작 실패. 다시 시도해 주세요."); return@launch }
                        toast("헬멧 착용을 감지 중... (UNLOCKBACK1 대기)")
                        startUnlockWatcher(myRun)
                    }
                    else -> {
                        toast("대여 (1/2) 시작 실패. 다시 시도해 주세요.")
                        return@launch
                    }
                }
            } else {
                toast("헬멧 착용을 감지 중... (UNLOCKBACK1 대기)")
                startUnlockWatcher(myRun)
            }
        }
    }

    private fun startUnlockWatcher(runId: Long) {
        sentUnlockGo2 = false
        seenUnlockBack1 = false

        watchJob?.cancel()
        watchJob = viewLifecycleOwner.lifecycleScope.launch {
            wifiClient.readyStateFlow().collectLatest { s ->
                if (!isMyRun(runId)) return@collectLatest
                val ev = s.event
                val st = s.state

                when (ev) {
                    "UNLOCKBACK1" -> {
                        seenUnlockBack1 = true
                        toast("착용 확인! 대여를 마무리합니다. (2/2)")
                        val stage2 = wifiClient.unlockStage2()
                        if (!isMyRun(runId)) return@collectLatest
                        if (!stage2.success) { toast("대여 (2/2) 실패. 다시 시도해 주세요."); return@collectLatest }
                        sentUnlockGo2 = true
                    }
                    "UNLOCKBACK2" -> {
                        finishUnlock(runId); return@collectLatest
                    }
                    "STATE:ERROR" -> {
                        toast("장치 상태 오류가 감지됐어요. 잠시 후 다시 시도해 주세요.")
                        return@collectLatest
                    }
                }

                // 일부 FW: GO2 후 바로 IDLE 복귀
                if ((st?.equals("IDLE", true) == true) && (sentUnlockGo2 || seenUnlockBack1)) {
                    finishUnlock(runId); return@collectLatest
                }
            }
        }
    }

    private fun finishUnlock(runId: Long) {
        if (!isMyRun(runId)) return
        toast("대여가 시작되었습니다!")
        RearCamDetectionManager.start(requireContext())

        // Fragment state 저장 이후 navigate 경고 방지
        viewLifecycleOwner.lifecycleScope.launch {
            delay(400)
            if (isAdded && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                runCatching { findNavController().navigate(R.id.action_to_guide) }
            }
        }

        watchJob?.cancel(); watchJob = null
        sentUnlockGo2 = false; seenUnlockBack1 = false
    }

    // =========================
    // 반납(LOCK) 플로우
    // =========================
    private fun startReturnFlow() {
        val myRun = nextRun()
        cancelCurrentRun()

        viewLifecycleOwner.lifecycleScope.launch {
            // 1) 선행 수습 (WAITING_*_STAGE2 + ERROR 포함)
            reconcileToIdle(myRun)

            // 2) 반납 1단계
            toast("반납을 시작합니다. (1/2) 헬멧을 거치해 주세요.")
            val r = wifiClient.sendCommand("LOCK") // (클라이언트가 LOCK → LOCKGO1로 정규화)
            if (!isMyRun(myRun)) return@launch
            if (r.success) {
                // 사용자에게 JSON 원문 대신 친화적 메시지 제공
                toast("거치 상태를 확인 중... (LOCKBACK1 대기)")
                startLockWatcher(myRun)
                return@launch
            }

            // 실패 시: UNLOCK 2단계 잔여 등 케이스 처리
            val msg = r.detailMessage ?: ""
            if (msg.contains("WAITING_UNLOCK_STAGE2", true)) {
                toast("이전 대여 마무리 중입니다. (UNLOCKGO2)")
                val u2 = wifiClient.unlockStage2()
                if (!isMyRun(myRun)) return@launch
                if (!u2.success) { toast("대여 잔여 정리에 실패했습니다. 다시 시도해 주세요."); return@launch }
                // 다시 LOCK 1단계
                val r2 = wifiClient.sendCommand("LOCK")
                if (!isMyRun(myRun)) return@launch
                if (r2.success) {
                    toast("거치 상태를 확인 중... (LOCKBACK1 대기)")
                    startLockWatcher(myRun)
                } else {
                    toast("반납 시작에 실패했습니다. 다시 시도해 주세요.")
                }
            } else {
                toast("반납 시작에 실패했습니다. 다시 시도해 주세요.")
            }
        }
    }

    private fun startLockWatcher(runId: Long) {
        sentLockGo2 = false
        seenLockBack1 = false

        watchJob?.cancel()
        watchJob = viewLifecycleOwner.lifecycleScope.launch {
            wifiClient.readyStateFlow().collectLatest { s ->
                if (!isMyRun(runId)) return@collectLatest
                val ev = s.event
                val st = s.state

                when (ev) {
                    "LOCKBACK1" -> {
                        seenLockBack1 = true
                        toast("헬멧 분리 확인! 반납을 마무리합니다. (2/2)")
                        val r = wifiClient.sendCommand("LOCKGO2")
                        if (!isMyRun(runId)) return@collectLatest
                        if (!r.success) { toast("반납 (2/2) 실패. 다시 시도해 주세요."); return@collectLatest }
                        sentLockGo2 = true
                    }
                    "LOCKBACK2" -> {
                        finishLock(runId); return@collectLatest
                    }
                    "STATE:ERROR" -> {
                        toast("장치 상태 오류가 감지됐어요. 잠시 후 다시 시도해 주세요.")
                        return@collectLatest
                    }
                }

                // FW에 따라 GO2 후 바로 IDLE
                if ((st?.equals("IDLE", true) == true) && (sentLockGo2 || seenLockBack1)) {
                    finishLock(runId); return@collectLatest
                }
            }
        }
    }

    private fun finishLock(runId: Long) {
        if (!isMyRun(runId)) return
        toast("반납이 완료되었습니다. 감사합니다!")
        RearCamDetectionManager.stop()
        watchJob?.cancel(); watchJob = null
        sentLockGo2 = false; seenLockBack1 = false
    }

    // =========================
    // 상태 수습 (강화판)
    // =========================
    /**
     * 장치가 WAITING_*_STAGE2 또는 ERROR에 머무르면
     * 2단계를 적절히 실행해 **IDLE**로 수렴시킨다.
     */
    private suspend fun reconcileToIdle(runId: Long, maxRounds: Int = 10) {
        repeat(maxRounds) {
            if (!isMyRun(runId)) return
            val s = wifiClient.getReadyState() ?: return
            val state = s.state?.uppercase()
            val ev = s.event?.uppercase() ?: ""

            when (state) {
                "IDLE" -> return
                "WAITING_LOCK_STAGE2" -> {
                    toast("이전 반납 마무리 중(LOCKGO2)...")
                    val r = wifiClient.sendCommand("LOCKGO2")
                    if (!isMyRun(runId)) return
                    if (!r.success) return
                }
                "WAITING_UNLOCK_STAGE2" -> {
                    toast("이전 대여 마무리 중(UNLOCKGO2)...")
                    val r = wifiClient.unlockStage2()
                    if (!isMyRun(runId)) return
                    if (!r.success) return
                }
                "ERROR", "STATE:ERROR" -> {
                    // 이벤트 힌트 기반 에러 복구
                    when {
                        ev.contains("UNLOCKBACK1") -> {
                            toast("에러 복구: 대여 2단계 마무리(UNLOCKGO2)")
                            val r = wifiClient.unlockStage2()
                            if (!isMyRun(runId)) return
                            if (!r.success) return
                        }
                        ev.contains("LOCKBACK1") -> {
                            toast("에러 복구: 반납 2단계 마무리(LOCKGO2)")
                            val r = wifiClient.sendCommand("LOCKGO2")
                            if (!isMyRun(runId)) return
                            if (!r.success) return
                        }
                        else -> {
                            // 잠시 대기 후 재확인 (백엔드가 상태를 정리할 시간 제공)
                            delay(500)
                        }
                    }
                }
                else -> {
                    // 알 수 없는 상태 → 짧은 대기 후 재확인
                    delay(300)
                }
            }
            delay(400)
        }
    }

    // =========================
    // 공통
    // =========================
    private fun nextRun(): Long = (++currentRunId)
    private fun isMyRun(runId: Long): Boolean = (runId == currentRunId)

    private fun cancelCurrentRun() {
        watchJob?.cancel()
        watchJob = null

        sentUnlockGo2 = false
        seenUnlockBack1 = false

        sentLockGo2 = false
        seenLockBack1 = false
    }

    private fun toast(msg: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun goToStreamImmediately() {
        startActivity(Intent(requireContext(), StreamActivity::class.java))
    }

    // ---- 레이아웃 타입별 안전 배너 삽입 ----
}