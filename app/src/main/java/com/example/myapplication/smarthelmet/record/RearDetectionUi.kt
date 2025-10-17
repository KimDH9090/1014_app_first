package com.example.myapplication.smarthelmet.record

import android.graphics.Color

object RearDetectionColors {
    private const val PERSON = 0xFF00C853.toInt()
    private const val BICYCLE = 0xFF2962FF.toInt()
    private const val VEHICLE = 0xFFD50000.toInt()

    fun strokeColor(label: String): Int = when (label) {
        RearCamDetectionEngine.Defaults.LABEL_PERSON -> PERSON
        RearCamDetectionEngine.Defaults.LABEL_BICYCLE -> BICYCLE
        RearCamDetectionEngine.Defaults.LABEL_VEHICLE -> VEHICLE
        else -> 0xFFFFAB00.toInt()
    }

    fun fillColor(label: String, alpha: Int = 80): Int {
        val base = strokeColor(label)
        val r = (base shr 16) and 0xFF
        val g = (base shr 8) and 0xFF
        val b = base and 0xFF
        return Color.argb(alpha.coerceIn(0, 255), r, g, b)
    }

    fun textColor(label: String): Int = strokeColor(label)
}