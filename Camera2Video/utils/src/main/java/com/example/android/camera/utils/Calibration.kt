package com.example.android.camera.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

interface Calibrator {
    fun evaluateMetric(bmp: Bitmap, currentValue: Long): Boolean
}


class SimpleBrightPixelCalibrator: Calibrator {
   var bestValue: Long = 0

    private var thresholdRatio: Double = 0.000000000000001
    private var pixelStride: Int = 1

    constructor(initialValue: Long, thresholdRatio: Double?, pixelStride: Int?) {
        bestValue = initialValue
        if (thresholdRatio != null) {
            this.thresholdRatio = thresholdRatio
        }
        if (pixelStride != null) {
            this.pixelStride = pixelStride
        }
    }

    override fun evaluateMetric(bmp: Bitmap, currentValue: Long): Boolean {
        val width = bmp.width
        val height = bmp.height
        var numSaturatedRed = 0
        val pixels = IntArray(height*width)
        // can try and optimise the getPixels using stride
        bmp.getPixels(pixels, 0, width, 0 ,0, width, height)
        var pixIndex = 0
        var totalValuesPolled = 0
        while (pixIndex < pixels.size) {
            val pixelValue = pixels[pixIndex]
            val red = Color.red(pixelValue)
            if (red == 255) {
                numSaturatedRed++
            }
            totalValuesPolled++
            pixIndex += pixelStride
        }
        val cond = numSaturatedRed == 0
        // Log.d("__eval", "${cond} ${currentValue} " + numSaturatedRed.toString() + " " + totalValuesPolled.toString() + " " + numSaturatedRed.toDouble() / totalValuesPolled)
        if (cond) {
            bestValue = currentValue
            return true
        }
        return false
    }
}