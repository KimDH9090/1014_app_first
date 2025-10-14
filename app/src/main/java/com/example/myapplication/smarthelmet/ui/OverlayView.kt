// app/src/main/java/com/example/myapplication/smarthelmet/ui/OverlayView.kt
package com.example.myapplication.smarthelmet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color      // ← 추가
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile private var model: OverlayModel? = null

    // 선 그리기용 페인트
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE          // ← 추가: 흰색으로 가시성 확보
        strokeWidth = 4f             // (원하면 두께 조절)
    }

    // 텍스트 그리기용 페인트
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE          // ← 추가: 흰색으로 가시성 확보
        textSize = 14f * resources.displayMetrics.scaledDensity
    }

    fun submit(m: OverlayModel?) {
        model = m
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = model ?: return
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)

        // Lines
        for (ln in m.lines) {
            pLine.strokeWidth = ln.strokePx
            canvas.drawLine(
                ln.x0 * w, ln.y0 * h,
                ln.x1 * w, ln.y1 * h,
                pLine
            )
        }

        // Texts
        for (t in m.texts) {
            pText.textSize = t.sizeSp * resources.displayMetrics.scaledDensity
            canvas.drawText(t.text, t.x * w, t.y * h, pText)
        }
    }
}
