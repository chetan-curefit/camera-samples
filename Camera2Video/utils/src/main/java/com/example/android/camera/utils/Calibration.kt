package com.example.android.camera.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

interface Calibrator {
    fun evaluateMetric(bmp: Bitmap, currentValue: Long): Ret
}
class Ret {
    var continueLoop: Boolean = false
    var pixels: IntArray?= null
    constructor(continueLoop: Boolean, pixels: IntArray?) {
        this.continueLoop = continueLoop
        this.pixels = pixels
    }
}


class SimpleBrightPixelCalibrator: Calibrator {
   var bestValue: Long = 0

    private var thresholdRatio: Double = 1.toDouble()/10000
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

    override fun evaluateMetric(bmp: Bitmap, currentValue: Long): Ret {
        val width = bmp.width
        val height = bmp.height
        var numSaturatedRed = 0
        var numSaturatedBlue = 0
        var numSaturatedGreen =0
        var redAvg: Double = 0.0
        val pixels = IntArray(height*width)
        // can try and optimise the getPixels using stride
        bmp.getPixels(pixels, 0, width, 0 ,0, width, height)
        var pixIndex = 0
        var totalValuesPolled = 0
        while (pixIndex < pixels.size) {
            val pixelValue = pixels[pixIndex]
            val red = Color.red(pixelValue)
            if (red >= 255) {
                numSaturatedRed++
            }
            val green = Color.green(pixelValue)
            if (green >= 255) {
                numSaturatedGreen++
            }
            val blue = Color.blue(pixelValue)
            if (blue >= 255) {
                numSaturatedBlue++
            }
            redAvg = if (totalValuesPolled > 0) ((redAvg*totalValuesPolled)+red)/(totalValuesPolled+1) else 0.toDouble()
            totalValuesPolled++
            pixIndex += pixelStride
        }
        val cond = numSaturatedRed.toDouble() / totalValuesPolled <= thresholdRatio
        //Log.d("__eval", "${cond} ${currentValue} avgRed:${redAvg} " + "${numSaturatedRed}:${numSaturatedGreen}:${numSaturatedBlue}" + " " + totalValuesPolled.toString() + " " + "${numSaturatedRed.toDouble() / totalValuesPolled}:${numSaturatedGreen.toDouble()/totalValuesPolled}:${numSaturatedBlue.toDouble()/totalValuesPolled}")
        if (cond) {
            bestValue = currentValue
            //Log.d("__best", "${cond} ${currentValue} " + numSaturatedRed.toString() + " " + totalValuesPolled.toString() + " " + numSaturatedRed.toDouble() / totalValuesPolled)
            return Ret(true, pixels)
        }
        return Ret(false, pixels)
    }
}