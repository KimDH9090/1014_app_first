package com.example.myapplication.smarthelmet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.myapplication.smarthelmet.record.RearCamDetectionEngine
import com.example.myapplication.smarthelmet.record.RearDetectionColors
import kotlin.math.min

class RearDetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    @Volatile
    private var sourceWidth: Int = 0

    @Volatile
    private var sourceHeight: Int = 0

    @Volatile
    private var detections: List<RearCamDetectionEngine.RearDetection> = emptyList()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
    }

    fun updateSourceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == sourceWidth && height == sourceHeight) return
        sourceWidth = width
        sourceHeight = height
        postInvalidateOnAnimation()
    }

    fun submit(result: RearCamDetectionEngine.RearDetectionResult?) {
        if (result == null) {
            detections = emptyList()
            postInvalidateOnAnimation()
            return
        }
        detections = result.detections
        if (sourceWidth == 0 || sourceHeight == 0) {
            sourceWidth = result.frameWidth
            sourceHeight = result.frameHeight
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val srcW = sourceWidth
        val srcH = sourceHeight
        if (srcW <= 0 || srcH <= 0) return
        if (detections.isEmpty()) return

        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val scale = min(vw / srcW, vh / srcH)
        val dw = srcW * scale
        val dh = srcH * scale
        val ox = (vw - dw) / 2f
        val oy = (vh - dh) / 2f

        for (d in detections) {
            val rect = mapRect(d.box, ox, oy, dw, dh)
            val stroke = RearDetectionColors.strokeColor(d.label)
            val fill = RearDetectionColors.fillColor(d.label, alpha = 80)
            val text = RearDetectionColors.textColor(d.label)

            fillPaint.color = fill
            strokePaint.color = stroke
            textPaint.color = text

            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, strokePaint)
            canvas.drawText("${d.label} ${(d.score * 100).toInt()}%", rect.left + 6f, (rect.top - 8f).coerceAtLeast(24f), textPaint)
        }
    }

    private fun mapRect(box: RectF, ox: Float, oy: Float, dw: Float, dh: Float): RectF {
        val left = ox + box.left * dw
        val top = oy + box.top * dh
        val right = ox + box.right * dw
        val bottom = oy + box.bottom * dh
        return RectF(left, top, right, bottom)
    }
}