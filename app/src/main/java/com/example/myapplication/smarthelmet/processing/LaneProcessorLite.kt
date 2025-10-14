// app/src/main/java/com/example/myapplication/smarthelmet/processing/LaneProcessorLite.kt
package com.example.myapplication.smarthelmet.processing

import android.graphics.Bitmap
import android.graphics.Color
import com.example.myapplication.smarthelmet.ui.LineN
import com.example.myapplication.smarthelmet.ui.OverlayModel
import com.example.myapplication.smarthelmet.ui.TextN
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class LaneProcessorLite(
    private val roiBottomRatio: Float = 0.4f,   // 하단 40%만 사용
    private val downW: Int = 160               // 처리 해상도(너비)
) : FrameProcessor {

    override fun processFrame(bmp: Bitmap): OverlayModel? {
        if (bmp.width < 8 || bmp.height < 8) return null

        // 축소
        val scale = downW.toFloat() / bmp.width
        val downH = max(8, (bmp.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(bmp, downW, downH, true)

        // ROI(아랫부분)
        val roiY0 = (downH * (1f - roiBottomRatio)).roundToInt().coerceIn(0, downH - 1)
        val roiH = downH - roiY0
        if (roiH < 4) return null

        // 컬럼 평균 밝기 & 1차 미분
        val colMean = FloatArray(downW)
        val tmp = IntArray(downW * roiH)
        small.getPixels(tmp, 0, downW, 0, roiY0, downW, roiH)

        for (x in 0 until downW) {
            var s = 0f
            var i = x
            var r: Int; var g: Int; var b: Int
            var cnt = 0
            while (i < tmp.size) {
                val c = tmp[i]
                r = (c shr 16) and 0xFF
                g = (c shr 8) and 0xFF
                b = c and 0xFF
                // 빠른 Y(휘도) 근사
                s += (0.299f * r + 0.587f * g + 0.114f * b)
                i += downW
                cnt++
            }
            colMean[x] = s / max(1, cnt)
        }

        val diff = FloatArray(downW)
        for (x in 1 until downW) diff[x] = colMean[x] - colMean[x - 1]

        // 좌/우 절댓값 피크
        val mid = downW / 2
        val leftIdx = argmaxAbs(diff, 1, max(2, mid - 2))
        val rightIdx = argmaxAbs(diff, max(mid + 1, 2), downW - 2)

        if (leftIdx < 0 || rightIdx < 0 || rightIdx - leftIdx < downW * 0.2f) {
            // 신뢰도 낮으면 중앙 안내선만
            return OverlayModel(
                lines = listOf(
                    LineN(0.5f, 0f, 0.5f, 1f, 3f)
                ),
                texts = listOf(TextN(0.02f, 0.96f, "LaneLite: calibrating…"))
            )
        }

        val center = (leftIdx + rightIdx) * 0.5f
        val offset = center / downW - 0.5f // (-0.5..0.5)
        val offsetPct = (offset * 100f)

        // 정규화 좌표로 라인 만들기 (ROI 전체 높이로 시각화)
        val yTop = (roiY0.toFloat() / downH)
        val yBot = 1f
        val lx = leftIdx / downW.toFloat()
        val rx = rightIdx / downW.toFloat()
        val cx = center / downW.toFloat()

        return OverlayModel(
            lines = listOf(
                LineN(lx, yTop, lx, yBot, 3f),
                LineN(rx, yTop, rx, yBot, 3f),
                LineN(cx, 0f,  cx,  1f,   4f)
            ),
            texts = listOf(TextN(0.02f, 0.96f, "offset ≈ ${"%.1f".format(offsetPct)} %"))
        )
    }

    private fun argmaxAbs(a: FloatArray, from: Int, to: Int): Int {
        var best = -1
        var bv = -1f
        val lo = max(1, from)
        val hi = min(a.size - 2, to)
        for (i in lo..hi) {
            val v = abs(a[i])
            if (v > bv) { bv = v; best = i }
        }
        return best
    }
}
