package com.example.android.camera.utils

import android.graphics.*
import android.util.Log
import kotlin.math.pow
import kotlin.math.sqrt


interface Calibrator<T, F> {
    fun evaluateMetric(bmp: Bitmap, currentValue: Pair<T, F>)
}
class Ret {
    var continueLoop: Boolean = false
    var pixels: IntArray?= null
    constructor(continueLoop: Boolean, pixels: IntArray?) {
        this.continueLoop = continueLoop
        this.pixels = pixels
    }
}


class SimpleBrightPixelCalibrator(initialValue: Pair<Int, Int>, thresholdRatio: Double?, pixelStride: Int?) {
    var bestValue: Pair<Int, Int>
    var bestContrast: Double = 0.0

    private var timesCalled = 0
    
    private val notLogging: Boolean? = true

    private var thresholdRatio: Double = 1.toDouble()/10000
    private var pixelStride: Int = 1

    init {
        if (thresholdRatio != null) {
            this.thresholdRatio = thresholdRatio
        }
        if (pixelStride != null) {
            this.pixelStride = pixelStride
        }
        this.bestValue = initialValue
    }

    fun evaluateMetric(bmp: Bitmap, currentValue: Pair<Int, Int>, absoluteValues: Pair<Long, Long>) {
        val width = bmp.width
        val height = bmp.height
        val grayPixels = IntArray(height*width)
        val ogPixels = IntArray(height*width)

        var time = System.currentTimeMillis()


        // can try and optimise the getPixels using stride
        bmp.getPixels(ogPixels, 0, width, 0 ,0, width, height)
        val time2 = System.currentTimeMillis()
        toGrayscale1(bmp).getPixels(grayPixels, 0, width, 0 ,0, width, height)
        val t3 = System.currentTimeMillis()
        val contrast = rmse(grayPixels, ogPixels)
        if (contrast > bestContrast) {
            notLogging ?: Log.d("__best value", "${absoluteValues} ${contrast}")
            bestContrast = contrast
            bestValue = currentValue
        }
        notLogging ?: Log.d("__eval", "${absoluteValues} ${time2 -time} ${t3 - time2} ${System.currentTimeMillis()-t3} ${contrast}")
    }

    private fun toGrayscale1(bmp: Bitmap): Bitmap {
        val width = bmp.width
        val height = bmp.height
        val bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val c = Canvas(bmpGrayscale)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        val f = ColorMatrixColorFilter(cm)
        paint.colorFilter = f
        c.drawBitmap(bmp, 0.0F, 0.0F, paint)
        return bmpGrayscale
    }

    private fun rmse(grayPixels: IntArray, ogPixels: IntArray): Double {
        val t1 = System.currentTimeMillis()
        var mean: Double = grayPixels.sum().toDouble() /grayPixels.size
        val t2 = System.currentTimeMillis()
        var ms: Double = 0.0
        var pixIndex =0
        var totalValuesPolled = 0
        timesCalled++
        while(pixIndex < grayPixels.size) {
            val diff = mean - Color.red(grayPixels[pixIndex]).toDouble()
            ms += diff*diff
            totalValuesPolled++
            pixIndex += pixelStride
        }
        val t3 = System.currentTimeMillis()
        val ret = sqrt(ms/grayPixels.size)
        notLogging ?: Log.d("__rmse time", "${t2-t1} ${t3-t2} ${System.currentTimeMillis()-t3}")
        return ret
    }

}