package com.example.myapplication.smarthelmet

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager

/**
 * 라즈베리파이 엔드포인트
 * - host만 저장
 * - HTTP API 포트는 8000으로 고정
 * - 스트리밍(영상)은 5000 사용
 */
object PiEndpoint {
    private const val KEY_HOST = "pi_host"

    private const val API_PORT_FIXED = 8000         // ← HTTP API는 8000 고정
    private const val STREAM_PORT_DEFAULT = 5000    // ← 스트리밍 기본 포트 5000

    /** 스트리밍/RTSP URL에서 host만 추출해 저장 (포트는 저장하지 않음) */
    fun saveFromUrl(context: Context, url: String) {
        val u = runCatching { Uri.parse(url) }.getOrNull()
        val host = u?.host?.takeIf { it.isNotBlank() } ?: return
        saveHost(context, host)
    }

    fun saveHost(context: Context, host: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY_HOST, host)
            .apply()
    }

    fun host(context: Context, defaultHost: String = "10.42.0.1"): String {
        val p = PreferenceManager.getDefaultSharedPreferences(context)
        return p.getString(KEY_HOST, defaultHost) ?: defaultHost
    }

    // ===== HTTP API (8000 고정) =====
    fun apiBase(context: Context, defaultHost: String = "10.42.0.1"): String {
        val h = host(context, defaultHost)
        return "http://$h:$API_PORT_FIXED"
    }

    fun apiUrl(context: Context, path: String, defaultHost: String = "10.42.0.1"): String {
        val base = apiBase(context, defaultHost)
        val normalized = if (path.startsWith("/")) path else "/$path"
        return base + normalized
    }

    // (기존 코드 호환용) httpBase/httpUrl을 API로 포워딩
    fun httpBase(context: Context, defaultHost: String = "10.42.0.1", @Suppress("UNUSED_PARAMETER") defaultPort: Int = API_PORT_FIXED): String =
        apiBase(context, defaultHost)

    fun httpUrl(context: Context, path: String, defaultHost: String = "10.42.0.1", @Suppress("UNUSED_PARAMETER") defaultPort: Int = API_PORT_FIXED): String =
        apiUrl(context, path, defaultHost)

    // ===== 스트리밍(5000 유지) 헬퍼 =====
    fun streamHttpUrl(
        context: Context,
        path: String,
        defaultHost: String = "10.42.0.1",
        port: Int = STREAM_PORT_DEFAULT
    ): String {
        val h = host(context, defaultHost)
        val normalized = if (path.startsWith("/")) path else "/$path"
        return "http://$h:$port$normalized"
    }

    fun rtspUrl(
        context: Context,
        defaultHost: String = "10.42.0.1",
        port: Int = STREAM_PORT_DEFAULT,
        path: String = "/unicast"
    ): String {
        val h = host(context, defaultHost)
        val normalized = if (path.startsWith("/")) path else "/$path"
        return "rtsp://$h:$port$normalized"
    }

    fun clear(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(KEY_HOST)
            .apply()
    }
}
