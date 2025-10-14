// app/src/main/java/com/example/myapplication/smarthelmet/WifiCommandClient.kt
package com.example.myapplication.smarthelmet

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 서버 명령 응답 */
data class CommandResult(val success: Boolean, val detailMessage: String?)

/** /api/ready_state 스냅샷 */
data class ReadyState(
    val state: String? = null,
    val event: String? = null,
    val eventId: Long = -1L,
    val ts: String? = null,
    val detail: String? = null
)

/**
 * Wi-Fi로 라즈베리파이(Flask)와 통신하는 클라이언트.
 * - 명령: POST /api/command {"command":"UNLOCKGO1"|"UNLOCKGO2"|...}
 * - 상태: GET  /api/ready_state
 * - 핑:   GET  /api/ping
 */
class WifiCommandClient(
    private val context: Context,
    client: OkHttpClient? = null
) {
    companion object {
        private const val TAG = "WifiCommandClient"
        private const val JSON_KEY_COMMAND = "command"
        private const val JSON_KEY_OK = "ok"
        private const val JSON_KEY_DETAIL = "detail"
        private const val JSON_KEY_MESSAGE = "message"

        private const val PATH_COMMAND = "/api/command"
        private const val PATH_READY = "/api/ready_state"
        private const val PATH_PING = "/api/ping"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val DEFAULT_POLL_MS = 1000L
    }

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** http://<host>:<port>/<path> */
    private fun url(path: String): String = PiEndpoint.httpUrl(context, path)

    // ---- 핑 ----
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        val u = url(PATH_PING)
        val t0 = SystemClock.elapsedRealtime()
        try {
            Log.i(TAG, "→ GET $u")
            val req = Request.Builder().url(u).get().header("Accept","application/json").build()
            http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                val dt = SystemClock.elapsedRealtime() - t0
                val okVal = runCatching { JSONObject(body).optBoolean(JSON_KEY_OK, res.isSuccessful) }
                    .getOrDefault(res.isSuccessful)
                Log.i(TAG, "← GET $u code=${res.code} ok=$okVal timeMs=$dt body=$body")
                okVal && res.isSuccessful
            }
        } catch (e: IOException) {
            Log.e(TAG, "ping failed url=$u", e); false
        } catch (t: Throwable) {
            Log.e(TAG, "ping unexpected url=$u", t); false
        }
    }

    // ---- ready_state 1회 ----
    suspend fun getReadyState(): ReadyState? = withContext(Dispatchers.IO) {
        val u = url(PATH_READY)
        val t0 = SystemClock.elapsedRealtime()
        try {
            Log.d(TAG, "→ GET $u")
            val req = Request.Builder().url(u).get().header("Accept","application/json").build()
            http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                val dt = SystemClock.elapsedRealtime() - t0
                if (!res.isSuccessful) {
                    Log.w(TAG, "← GET $u code=${res.code} timeMs=$dt body=$body")
                    return@withContext null
                }
                val j = runCatching { JSONObject(body) }.getOrNull()
                val snap = ReadyState(
                    state  = j?.optString("state", null),
                    event  = j?.optString("event", null),
                    eventId= j?.optLong("eventId", -1L) ?: -1L,
                    ts     = j?.optString("ts", null),
                    detail = j?.optString("detail", null)
                )
                Log.i(TAG, "← GET $u ok code=${res.code} timeMs=$dt state=${snap.state} event=${snap.event} id=${snap.eventId}")
                snap
            }
        } catch (e: IOException) {
            Log.e(TAG, "getReadyState failed url=$u", e); null
        } catch (t: Throwable) {
            Log.e(TAG, "getReadyState unexpected url=$u", t); null
        }
    }

    // ---- ready_state 폴링 Flow(변화시에만 emit) ----
    fun readyStateFlow(pollMillis: Long = DEFAULT_POLL_MS): Flow<ReadyState> = flow {
        var lastEventId: Long = Long.MIN_VALUE
        var lastState: String? = null
        while (currentCoroutineContext().isActive) {
            val s = getReadyState()
            if (s != null) {
                val changed = (s.eventId != -1L && s.eventId != lastEventId) || (s.state != lastState)
                if (changed) {
                    emit(s)
                    if (s.eventId != -1L) lastEventId = s.eventId
                    lastState = s.state
                }
            }
            delay(pollMillis)
        }
    }.catch { e -> Log.e(TAG, "Error in readyStateFlow", e) }

    // ---- 명령 전송 ----
    suspend fun sendCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        val normalized = normalizeCommand(command)
        val u = url(PATH_COMMAND)
        val t0 = SystemClock.elapsedRealtime()
        try {
            val json = JSONObject().apply { put(JSON_KEY_COMMAND, normalized) }.toString()
            val body = json.toRequestBody(JSON_MEDIA)
            Log.i(TAG, "→ POST $u cmd=$normalized body=$json")
            val req = Request.Builder().url(u).post(body).header("Accept","application/json").build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                val dt = SystemClock.elapsedRealtime() - t0
                val j = runCatching { JSONObject(text) }.getOrNull()
                val okFromJson = j?.optBoolean(JSON_KEY_OK, res.isSuccessful) ?: res.isSuccessful
                val detail = j?.optString(JSON_KEY_DETAIL).takeUnless { it.isNullOrBlank() }
                    ?: j?.optString(JSON_KEY_MESSAGE).takeUnless { it.isNullOrBlank() }
                    ?: text.ifBlank { null }
                Log.i(TAG, "← POST $u code=${res.code} ok=$okFromJson timeMs=$dt detail=${detail ?: "(none)"}")
                CommandResult(okFromJson && res.isSuccessful, detail)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send command '$normalized' url=$u", e)
            CommandResult(false, e.message)
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error on command '$normalized' url=$u", t)
            CommandResult(false, t.message)
        }
    }

    // 편의 함수
    suspend fun unlockStage1(): CommandResult = sendCommand("UNLOCKGO1")
    suspend fun unlockStage2(): CommandResult = sendCommand("UNLOCKGO2")

    // 🔧 과거 명령을 서버 관례로 정규화 (필요 최소 변경)
    private fun normalizeCommand(input: String): String {
        return when (val u = input.trim().uppercase()) {
            "UNLOCK", "UNLOCK1" -> "UNLOCKGO1"
            "LOCK", "LOCK1"     -> "LOCKGO1"   // 반납 버튼의 "LOCK"을 서버가 받는 "LOCKGO1"로 매핑
            else -> u
        }
    }

    // (옵션) 자동 UNLOCKGO2 유틸 — 기존 코드 유지
    private var lastHandledUnlockBack1Id: Long = Long.MIN_VALUE

    /** ready_state에서 UNLOCKBACK1을 새로 감지하면 자동으로 UNLOCKGO2 전송 */
    fun startAutoUnlockGo2(scope: CoroutineScope): Job = scope.launch {
        readyStateFlow().collect { st ->
            val ev  = st.event?.uppercase()
            val eid = st.eventId
            if (ev == "UNLOCKBACK1" && eid != -1L && eid != lastHandledUnlockBack1Id) {
                lastHandledUnlockBack1Id = eid
                Log.d(TAG, "UNLOCKBACK1 detected (id=$eid) → sending UNLOCKGO2")
                runCatching { sendCommand("UNLOCKGO2") }
                    .onSuccess { Log.d(TAG, "UNLOCKGO2 sent") }
                    .onFailure { Log.w(TAG, "UNLOCKGO2 send FAILED: ${it.message}") }
            }
        }
    }
}
