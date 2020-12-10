package com.example.android.camera.utils

import android.graphics.*
import android.util.Log
import java.util.*
import kotlin.math.sqrt


interface Calibrator<T, F> {
    fun evaluateMetric(bmp: Bitmap, currentValue: Pair<T, F>)
}

// not used
class Ret {
    var continueLoop: Boolean = false
    var pixels: IntArray?= null
    constructor(continueLoop: Boolean, pixels: IntArray?) {
        this.continueLoop = continueLoop
        this.pixels = pixels
    }
}


class SimpleBrightPixelCalibrator(initialValue: Pair<Int, Int>, thresholdRatio: Double?, pixelStride: Int?, useOnlyGreenChannel: Boolean?) {
    var bestValue: Pair<Int, Int>
    var bestContrast: Double = 0.0

    var bestAbsoluteValue: Pair<Long,Long> = Pair(0, 0)
    var useOnlyGreenChannel = false

    // set to null to enable logging
    private val notLogging: Boolean? = null

    private var thresholdRatio: Double = 1.toDouble()/10000

    // stride to use when calculating RMSE
    private var pixelStride: Int = 1

    init {
        if (thresholdRatio != null) {
            this.thresholdRatio = thresholdRatio
        }
        if (pixelStride != null) {
            this.pixelStride = pixelStride
        }
        if (useOnlyGreenChannel != null) {
            this.useOnlyGreenChannel = useOnlyGreenChannel
        }
        this.bestValue = initialValue
    }


    /**
     * evaluates the metric for the given [bmp] and remembers the best [currentValue]
     * [absoluteValues] are only used for logging and can be omitted
     * */
    fun evaluateMetric(bmp: Bitmap, currentValue: Pair<Int, Int>, absoluteValues: Pair<Long, Long>) {
        val width = bmp.width
        val height = bmp.height
        val grayPixels = IntArray(height*width)
        // ogPixels used only for logging, can remove
        val ogPixels = IntArray(height*width)

        var time = System.currentTimeMillis()

        bmp.getPixels(ogPixels, 0, width, 0 ,0, width, height)
        val time2 = System.currentTimeMillis()
        toGrayscale(bmp).getPixels(grayPixels, 0, width, 0 ,0, width, height)
        val t3 = System.currentTimeMillis()
        val contrast = rmse(grayPixels, ogPixels)
        if (contrast > bestContrast) {
            notLogging ?: Log.d("__best value", "${absoluteValues} ${contrast}")
            bestContrast = contrast
            bestValue = currentValue
            bestAbsoluteValue = absoluteValues
        }
        notLogging ?: Log.d("__eval", "${absoluteValues} ${time2 -time} ${t3 - time2} ${System.currentTimeMillis()-t3} ${contrast}")
    }

    /**
     * returns a new [Bitmap] created from [bmp] and then converted to gratscale using only the green channel
     * */
    private fun toGrayscale(bmp: Bitmap): Bitmap {
        val width = bmp.width
        val height = bmp.height
        val bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val c = Canvas(bmpGrayscale)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f)

        // using only the green channel for grayscale calculation
        if (useOnlyGreenChannel) {
            val evenGrayScaleArray = cm.array
            cm.set(FloatArray(20) { i: Int -> if (i == 1 || i ==6 || i ==11 || i == 18) evenGrayScaleArray[i] else 0F })
        }

        notLogging ?: Log.d("__cm array", "${Arrays.toString(cm.array)}")
        val f = ColorMatrixColorFilter(cm)
        paint.colorFilter = f
        c.drawBitmap(bmp, 0.0F, 0.0F, paint)
        return bmpGrayscale
    }

    /**
     * Can remove [ogPixels]
     * */
    private fun rmse(grayPixels: IntArray, ogPixels: IntArray): Double {
        val t1 = System.currentTimeMillis()
        var graySum: Double = 0.0
        for (pixelValue: Int in grayPixels) {
            graySum += Color.red(pixelValue)
        }
        val mean = graySum/grayPixels.size
        val t2 = System.currentTimeMillis()
        var ms: Double = 0.0
        var pixIndex =0
        var totalValuesPolled = 0
        while(pixIndex < grayPixels.size) {
            val diff = mean - Color.red(grayPixels[pixIndex]).toDouble()
            ms += diff*diff
            totalValuesPolled++
            pixIndex += pixelStride
        }
        val t3 = System.currentTimeMillis()
        val ret = sqrt(ms/grayPixels.size)
        notLogging ?: Log.d("__rmse time", "${t2-t1} ${t3-t2} ${System.currentTimeMillis()-t3} $mean $totalValuesPolled")
        return ret
    }

}