// app/src/main/java/com/example/myapplication/smarthelmet/MjpegReader.kt
package com.example.myapplication.smarthelmet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.concurrent.TimeUnit
import kotlin.math.max

class MjpegReader(
    private val url: String,
    private val target: ImageView,
    private val scope: CoroutineScope = MainScope(),
    private val fpsCap: Int = 30,
    private val autoReconnect: Boolean = true,
    // ✅ 영상처리/상태 전달을 위한 선택적 콜백
    private val onFrame: ((Bitmap) -> Unit)? = null,
    private val onStatus: ((String) -> Unit)? = null,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)     // 연결 타임아웃(권장)
        .readTimeout(0, TimeUnit.MILLISECONDS)    // 스트리밍은 무제한
        .retryOnConnectionFailure(true)
        .build()

    private var job: Job? = null
    private var lastFrameTimeNs = 0L

    fun start() {
        stop()
        job = scope.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive) {
                try {
                    streamOnce()
                    if (!autoReconnect) break
                    delay(500)
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    postStatus("에러: ${e.message ?: e::class.java.simpleName}")
                    if (!autoReconnect) break
                    delay(800)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun streamOnce() {
        postStatus("연결 중…")
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val ctype = resp.header("Content-Type").orEmpty()
            val boundary = parseBoundary(ctype) ?: "frame"
            val bs = resp.body?.source() ?: throw EOFException("no body")
            postStatus("스트리밍 시작 (boundary=$boundary)")
            readMultipartLoop(bs, boundary)
        }
    }

    /** Content-Type: multipart/x-mixed-replace; boundary=frame → "frame" 추출 */
    private fun parseBoundary(contentType: String): String? {
        val key = "boundary="
        val i = contentType.indexOf(key, ignoreCase = true)
        if (i < 0) return null
        var b = contentType.substring(i + key.length).trim()
        if (b.startsWith("\"") && b.endsWith("\"") && b.length >= 2) {
            b = b.substring(1, b.length - 1)
        }
        return b
    }

    private suspend fun readMultipartLoop(source: BufferedSource, boundary: String) {
        val dashBoundary = "--$boundary"
        val finalBoundary = "$dashBoundary--"

        // 최초 boundary까지 스킵
        if (!skipUntilBoundaryLine(source, dashBoundary, finalBoundary)) return

        while (currentCoroutineContext().isActive) {
            val headers = readHeaders(source) ?: break
            val contentType = (headers["content-type"] ?: "").lowercase()
            val contentLength = headers["content-length"]?.toIntOrNull()

            if (!contentType.startsWith("image/jpeg")) {
                if (!skipUntilBoundaryLine(source, dashBoundary, finalBoundary)) break
                continue
            }

            // --- JPEG 본문 읽기 ---
            val jpegBytes: ByteArray = when {
                contentLength != null && contentLength > 0 -> {
                    // 일반 경로: Content-Length 신뢰
                    source.readByteArray(contentLength.toLong())
                }
                else -> {
                    // ✅ Fallback: Content-Length가 없을 때는 EOI(0xFFD9)까지 수신
                    readJpegUntilEoi(source)
                }
            }

            // FPS 제한: 나노초→밀리초 올림하여 delay
            val now = System.nanoTime()
            val minDeltaNs = if (fpsCap > 0) 1_000_000_000L / fpsCap else 0L
            val waitNs = max(0L, minDeltaNs - (now - lastFrameTimeNs))
            if (waitNs > 0) {
                val delayMs = (waitNs + 999_999L) / 1_000_000L
                delay(delayMs)
            }
            lastFrameTimeNs = System.nanoTime()

            // 디코드 & 표시/콜백
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)?.let { postFrame(it) }

            // JPEG 뒤 CRLF(\r\n) 0~1회 소비(있을 수도 없을 수도)
            consumeOptionalCrlf(source)

            // 다음 boundary 라인 처리
            if (source.exhausted()) break
            val line = source.readUtf8LineStrict()
            when {
                line == dashBoundary || line.startsWith("$dashBoundary;") -> continue
                line == finalBoundary -> break
                line.isBlank() -> { if (!skipUntilBoundaryLine(source, dashBoundary, finalBoundary)) break }
                else -> { if (!skipUntilBoundaryLine(source, dashBoundary, finalBoundary)) break }
            }
        }
    }

    /** 다음 boundary 라인까지 스킵. final boundary를 만나면 false */
    private fun skipUntilBoundaryLine(source: BufferedSource, dashBoundary: String, finalBoundary: String): Boolean {
        while (!source.exhausted()) {
            val line = source.readUtf8LineStrict()
            if (line == dashBoundary || line.startsWith("$dashBoundary;")) return true
            if (line == finalBoundary) return false
        }
        return false
    }

    /** 헤더 블록 읽기: 빈 줄까지 */
    private fun readHeaders(source: BufferedSource): Map<String, String>? {
        if (source.exhausted()) return null
        val map = LinkedHashMap<String, String>()
        while (true) {
            val line = source.readUtf8LineStrict()
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0 && idx < line.length - 1) {
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                map[key] = value
            }
        }
        return map
    }

    /** JPEG 뒤 CRLF(\r\n) 0~1회 소비 */
    private fun consumeOptionalCrlf(source: BufferedSource) {
        if (source.exhausted()) return
        source.request(1)
        val peek = source.peek()
        val b0 = peek.readByte().toInt()
        if (b0 == '\r'.code) {
            source.readByte()
            if (!source.exhausted()) {
                source.request(1)
                val b1 = source.peek().readByte().toInt()
                if (b1 == '\n'.code) source.readByte()
            }
        } else if (b0 == '\n'.code) {
            source.readByte()
        }
    }

    /** ✅ Fallback: Content-Length 없이도 EOI(0xFFD9)까지 JPEG 바디 수신 */
    private fun readJpegUntilEoi(source: BufferedSource, maxBytes: Int = 3_000_000): ByteArray {
        val out = ByteArrayOutputStream(200_000)
        var prev = -1
        var count = 0
        while (true) {
            if (source.exhausted()) throw EOFException("stream ended before EOI")
            source.request(1)
            val b = source.readByte().toInt() and 0xFF
            out.write(b)
            count++
            if (prev == 0xFF && b == 0xD9) break // JPEG EOI
            if (count > maxBytes) throw IllegalStateException("JPEG too large without EOI")
            prev = b
        }
        return out.toByteArray()
    }

    private fun postFrame(bmp: Bitmap) {
        // 화면 표시
        target.post { target.setImageBitmap(bmp) }
        // 선택적 콜백(영상처리 등)
        onFrame?.invoke(bmp)
        postStatus("재생 중…")
    }

    private fun postStatus(text: String) {
        // 상태 TextView가 있을 때만 갱신 (없으면 무시)
        val tv = (target.rootView.findViewById<android.widget.TextView>(com.example.myapplication.R.id.tvStatus))
        tv?.post { tv.text = text }
        onStatus?.invoke(text)
    }
}
