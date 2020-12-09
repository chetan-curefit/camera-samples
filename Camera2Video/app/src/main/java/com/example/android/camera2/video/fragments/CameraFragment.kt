/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2.video.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.*
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.android.camera.utils.*
import com.example.android.camera2.video.BuildConfig
import com.example.android.camera2.video.CameraActivity
import com.example.android.camera2.video.R
import kotlinx.android.synthetic.main.fragment_camera.*
import kotlinx.coroutines.*
import java.io.File
import java.lang.Long.max
import java.lang.Runnable
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.min
import kotlin.properties.Delegates

sealed class ValueRange
data class EndPointRange(val range: Range<Long>?): ValueRange()
data class DiscretePointRange(val range: FloatArray?): ValueRange()

class CameraFragment : Fragment() {

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
    }

    /** File where the recording will be saved */
    private val outputFile: File by lazy { createFile(requireContext(), "mp4") }

    /**
     * Setup a persistent [Surface] for the recorder so we can use it as an output target for the
     * camera session without preparing the recorder
     */
    private val recorderSurface: Surface by lazy {

        // Get a persistent Surface from MediaCodec, don't forget to release when done
        val surface = MediaCodec.createPersistentInputSurface()

        // Prepare and release a dummy MediaRecorder with our new surface
        // Required to allocate an appropriately sized buffer before passing the Surface as the
        //  output target to the capture session
        createRecorder(surface).apply {
            prepare()
            release()
        }

        surface
    }

    /** Saves the video recording */
    private val recorder: MediaRecorder by lazy { createRecorder(recorderSurface) }

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
            overlay.foreground = Color.argb(150, 255, 255, 255).toDrawable()
            // Wait for ANIMATION_FAST_MILLIS
            overlay.postDelayed({
                // Remove white flash animation
                overlay.foreground = null
                // Restart animation recursively
                overlay.postDelayed(animationTask, CameraActivity.ANIMATION_FAST_MILLIS)
            }, CameraActivity.ANIMATION_FAST_MILLIS)
        }
    }

    /** Where the camera preview is displayed */
    private lateinit var textureView: TextureView
    private val textureListener = object: TextureView.SurfaceTextureListener {
        @RequiresApi(Build.VERSION_CODES.N)
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            textureView.post{ initializeCamera() }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) = Unit
        @RequiresApi(Build.VERSION_CODES.N)
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) = Unit
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?) = false
    }


    /** Overlay on top of the camera preview */
    private lateinit var overlay: View

    /** Captures frames from a [CameraDevice] for our video recording */
    private lateinit var session: CameraCaptureSession

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice


    /** the seekbars for iso and exposure */
    private lateinit var isoSeekbar: SeekBar
    private lateinit var exposureSeekbar: SeekBar
    private lateinit var apertureSeekbar: SeekBar

    /** textview for iso and seekbars */
    private lateinit var isoText: TextView
    private lateinit var exposureText: TextView
    private lateinit var apertureText: TextView

    /** Textview for timer */
    private lateinit var timerText: TextView

    private lateinit var greenMaskSwitch: Switch

    private var currentExposureValue: Long = (EXPOSURE_PRACTICAL_RANGE.range!!.upper+ EXPOSURE_PRACTICAL_RANGE.range.lower)/2
    private var currentISOValue: Long = (ISO_PRACTICAL_RANGE.range!!.lower + ISO_PRACTICAL_RANGE.range.upper)/2
    private var currentApertureValue: Float? = null

    private var exposureDeviceRange: EndPointRange? = null
    private var isoDeviceRange: EndPointRange? = null
    private var apertureDeviceRange: DiscretePointRange? = null

    private var timerHandler: Handler = Handler()
    private lateinit var timerRunnable: Runnable
    private var timerSecondsElapsed: Long = 0
    private val timerInterval = 1000

    private var calibrationThread = HandlerThread("calibration")
    private lateinit var calibrationHandler: Handler
    private lateinit var calibrator: SimpleBrightPixelCalibrator
    private var calibrationTimeInterval: Long = 50
    private var isCalibrating by Delegates.observable(false) { property, oldValue, newValue ->
        greenMaskSwitch?.isClickable = !newValue
    }
    private var isLatestFrame = false
    private var currentCalibrationAbsoluteValue: Long = 0
    private var currentCalibratedSeekBar: SEEKBAR_TYPE = SEEKBAR_TYPE.EXPOSURE
    private var currentEndPointRangeCalibrationValue: Int = 0
    private lateinit var calibrateButton: ImageButton
    private lateinit var calibrateText: TextView
    private var calibrationIterations by Delegates.notNull<Int>()
    private var calibratedIsoValue: Int = 0
    private var calibratedExposureValue: Int = 0
    @RequiresApi(Build.VERSION_CODES.N)
    private val calibrationRunnable: Runnable = Runnable {
        notLogging ?: Log.d("__calibration runnable", "${calibratedIsoValue} ${calibratedExposureValue}")
        if (calibratedIsoValue <= 100 && calibratedExposureValue <= 100) {
            //isLatestFrame = true
            //currentCalibrationAbsoluteValue = valueToTry as Long
            notLogging ?: Log.d("__changing value to", currentCalibratedSeekBar.toString() + " " +currentCalibrationAbsoluteValue.toString() + " " +currentEndPointRangeCalibrationValue)
           // val seekBar = getSeekBar(currentCalibratedSeekBar)
            valueChanged = System.currentTimeMillis()
            //seekBar?.progress = currentEndPointRangeCalibrationValue
            isoSeekbar.progress = calibratedIsoValue
            exposureSeekbar.progress = calibratedExposureValue
            calibrationHandler.postDelayed(calibrationEvaluateRunnable, 150)
        } else {
            // delaying so that the last evaluation is also included in best
            calibrationHandler.postDelayed( {
                notLogging ?: Log.d("__finish calibrating", " ")
                isoSeekbar.progress = calibrator.bestValue.first
                exposureSeekbar.progress = calibrator.bestValue.second
                isCalibrating = false
                requireActivity().runOnUiThread {calibrateText.text = START_CALIBRATING_TEXT}
            }, 200)
        }
    }

    private var useOnlyGreenChannelForEvaluation: Boolean = false

    private val evaluatorThread = HandlerThread("evaluatorThread")
    private lateinit var evaluatorHandler: Handler

    // set to null to enable logging
    private val notLogging: Boolean? = true

    private var valueChanged: Long = 0


    private lateinit var textureSurface: Surface

    private var isRecording: Boolean = false

    private var recordingStartMillis: Long = 0L

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_camera, container, false)

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        overlay = view.findViewById(R.id.overlay)

        textureView = view.findViewById(R.id.view_finder)

        textureView.surfaceTextureListener = textureListener

        isoSeekbar = view.findViewById(R.id.iso)
        exposureSeekbar = view.findViewById(R.id.exposure)
        apertureSeekbar = view.findViewById(R.id.aperture)

        isoText = view.findViewById(R.id.iso_title)
        exposureText = view.findViewById(R.id.exposureTitle)
        apertureText = view.findViewById(R.id.aperture_title)

        calibrateButton = view.findViewById(R.id.calibrate_button)
        calibrateText = view.findViewById(R.id.calibrate_text)

        timerText = view.findViewById(R.id.timertext)

        greenMaskSwitch = view.findViewById(R.id.green_mask_switch)
        greenMaskSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
                if (!isCalibrating) {
                    useOnlyGreenChannelForEvaluation = isChecked
                    if (isChecked) {
                        buttonView?.text = "Green Channel only"
                    } else {
                        buttonView?.text = "Using all channels"
                    }
                }
            }
        })

        // SAM conversion: https://kotlinlang.org/docs/reference/java-interop.html#sam-conversions
        timerRunnable = Runnable {
            if (isRecording) {
                timerHandler.postDelayed(timerRunnable, timerInterval.toLong())
            }
            timerSecondsElapsed += 1
            val secondsElapsed = timerSecondsElapsed
            val seconds = secondsElapsed % 60
            val minutes = (secondsElapsed / 60) % 60
            val hours = secondsElapsed / 3600
            val secondsText = if (seconds > 9) seconds.toString() else "0${seconds}"
            val minutesText = if (minutes > 9) "$minutes:" else "0${minutes}:"
            val hoursText = if (hours > 0) "$hours:" else ""
            timerText.text = "${hoursText}${minutesText}${secondsText}"
        }

        calibrationThread.start()
        calibrationHandler = Handler(calibrationThread.looper)

        evaluatorThread.start()
        evaluatorHandler = Handler(evaluatorThread.looper)


        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner, Observer {
                orientation -> Log.d(TAG, "Orientation changed: $orientation")
            })
        }

        setRanges()
        setCharacteristicValues()

        initialiseSeekBar(isoText, SEEKBAR_TYPE.ISO)
        initialiseSeekBar(exposureText, SEEKBAR_TYPE.EXPOSURE)
        initialiseSeekBar(apertureText, SEEKBAR_TYPE.APERTURE)

    }

    /** Creates a [MediaRecorder] instance using the provided [Surface] as input */
    private fun createRecorder(surface: Surface) = MediaRecorder().apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(outputFile.absolutePath)
        setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
        if (args.fps > 0) setVideoFrameRate(args.fps)
        setVideoSize(args.width, args.height)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setInputSurface(surface)
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating request
     */
    @RequiresApi(Build.VERSION_CODES.N)
    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {

        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        textureSurface = Surface(textureView.surfaceTexture)

        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(textureSurface, recorderSurface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        // Sends the capture request as frequently as possible until the session is torn down or
        //  session.stopRepeating() is called
        // capture request for the surface
        session.setRepeatingRequest(createCaptureRequest(), null, cameraHandler)

        capture_button.setOnClickListener(object : View.OnClickListener {
            override fun onClick(view: View) {
                if (!isRecording)
                    lifecycleScope.launch(Dispatchers.IO) {
                        isRecording = true

                        timerSecondsElapsed = 0

                        timerHandler.post(timerRunnable)

                        // Prevents screen rotation during the video recording
                        requireActivity().requestedOrientation =
                                ActivityInfo.SCREEN_ORIENTATION_LOCKED

                        // Start recording repeating requests, which will stop the ongoing preview
                        //  repeating requests without having to explicitly call `session.stopRepeating`
                        session.setRepeatingRequest(createCaptureRequest(), null, cameraHandler)

                        // Finalizes recorder setup and starts recording
                        recorder.apply {
                            // Sets output orientation based on current sensor value at start time
                            relativeOrientation.value?.let { setOrientationHint(it) }
                            prepare()
                            start()
                        }

                        recordingStartMillis = System.currentTimeMillis()

                        requireActivity().runOnUiThread(object : Runnable {
                            override fun run() {
                                (view as ImageButton).isSelected = true
                                timerText.visibility = View.VISIBLE
                            }
                        })

                        Log.d(TAG, "Recording started")

                        // Starts recording animation
                        //overlay.post(animationTask)
                    } else lifecycleScope.launch(Dispatchers.IO) {
                        isRecording = false

                        timerHandler.removeCallbacks(timerRunnable)
                        requireActivity().runOnUiThread {
                            (view as ImageButton).isSelected = false
                            timerText.visibility = View.INVISIBLE
                        }

                        // Unlocks screen rotation after recording finished
                        requireActivity().requestedOrientation =
                                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                        // Requires recording of at least MIN_REQUIRED_RECORDING_TIME_MILLIS
                        val elapsedTimeMillis = System.currentTimeMillis() - recordingStartMillis
                        if (elapsedTimeMillis < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
                            delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsedTimeMillis)
                        }

                        Log.d(TAG, "Recording stopped. Output file: ${outputFile}")
                        recorder.stop()

                        val renamedFile = renameFile(outputFile, "iso:${currentISOValue}_exp:${currentExposureValue}_aper:${currentApertureValue}", "mp4")
                        Log.d(TAG, "Recording stopped. Output file: $renamedFile")


                        // Removes recording animation
                        //overlay.removeCallbacks(animationTask)

                        // Broadcasts the media file to the rest of the system

                        MediaScannerConnection.scanFile(
                                view.context, arrayOf(renamedFile.absolutePath), null, null)

                        // Launch external activity via intent to play video recorded using our provider
                        startActivity(Intent().apply {
                            action = Intent.ACTION_VIEW
                            type = MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(renamedFile.extension)
                            val authority = "${BuildConfig.APPLICATION_ID}.provider"
                            data = FileProvider.getUriForFile(view.context, authority, renamedFile)
                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                        })

                        // Finishes our current camera screen
                        delay(CameraActivity.ANIMATION_SLOW_MILLIS)
                        navController.popBackStack()
                }
            }
        })

        calibrateButton.setOnClickListener {
            if (!isCalibrating) {
                calibrateText.text = CALIBRATING_TEXT
                startCalibrating(CALIBRATION_ORDER[0])
            }
        }

    }


    @RequiresApi(Build.VERSION_CODES.N)
    private fun startCalibrating(seekBarType: SEEKBAR_TYPE) {
        isCalibrating = true

        initialiseCalibrationValues()


        calibrationIterations = getCalibrationIterations(seekBarType)
        notLogging ?: Log.d("__startCalibrating", " ")
        currentCalibratedSeekBar = seekBarType
        calibrator = SimpleBrightPixelCalibrator(Pair(calibratedIsoValue, calibratedExposureValue), null, null, true)

        if (CALIBRATION_ORDER.indexOf(seekBarType) == 0) {
            calibrationHandler.postDelayed(Runnable {
                notLogging ?: Log.d("__calibration handler", seekBarType.toString())
                calibrationRunnable.run()
            }, 500)
        } else {
            calibrationHandler.post{
                notLogging ?: Log.d("__calibration handler", seekBarType.toString())
                calibrationRunnable.run()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun setParamForNextCalibrationIteration() {
        if (calibratedIsoValue + 100/7 <= 100) {
            calibratedIsoValue += 100/7
        } else {
            calibratedExposureValue += 100/5
            calibratedIsoValue = getProgressPercent(ISO_INITIAL_CALIBRATION_VALUE, SEEKBAR_TYPE.ISO) as Int
        }
        calibrationHandler.post(calibrationRunnable)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun initialiseCalibrationValues() {
        val isoProgress = getProgressPercent(ISO_INITIAL_CALIBRATION_VALUE, SEEKBAR_TYPE.ISO)
        val exposureProgress = getProgressPercent(EXPOSURE_INITIAL_CALIBRATION_VALUE, SEEKBAR_TYPE.EXPOSURE)
        if (isoProgress != null) {
            notLogging ?: Log.d("__initialising iso", "__")
            isoSeekbar?.progress = isoProgress
        }
        if (exposureProgress != null) exposureSeekbar?.progress = exposureProgress
    }

    @RequiresApi(Build.VERSION_CODES.N)
    val calibrationEvaluateRunnable = Runnable {
        isLatestFrame = false
        val seekBar = getSeekBar(currentCalibratedSeekBar)
        val bitmap = textureView.bitmap
        //setCaptureRequestValue(seekBarType, currentEndPointRangeCalibrationValue)
        val __calibratedIsoValue = calibratedIsoValue
        val __calibratedExposureValue = calibratedExposureValue
        val __absoluteIsoValue = getRangedValue(SEEKBAR_TYPE.ISO, __calibratedIsoValue)
        val __absoluteExposureValue = getRangedValue(SEEKBAR_TYPE.EXPOSURE, __calibratedExposureValue)
        notLogging ?: Log.d("__calling eval", (System.currentTimeMillis() - valueChanged).toString() + " $__absoluteIsoValue $__absoluteExposureValue")
        evaluatorHandler.post{ calibrator.evaluateMetric(bitmap, Pair(__calibratedIsoValue, __calibratedExposureValue), Pair(__absoluteIsoValue as Long, __absoluteExposureValue as Long))}
        setParamForNextCalibrationIteration()

        notLogging ?: Log.d("__current exp", (System.currentTimeMillis() - valueChanged).toString() + " " + currentEndPointRangeCalibrationValue.toString() + " " + currentCalibrationAbsoluteValue.toString() + " "  + (currentEndPointRangeCalibrationValue.toInt() + 100/calibrationIterations <= 100))
        valueChanged = System.currentTimeMillis()

    }

    private fun getCalibrationIterations(seekbarType: SEEKBAR_TYPE): Int {
        return when(seekbarType) {
            SEEKBAR_TYPE.EXPOSURE -> 30
            SEEKBAR_TYPE.ISO ->  30
            else -> 10
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun getInitialCalibrationValues(seekbarType:SEEKBAR_TYPE): Number {
        return when(seekbarType) {
            SEEKBAR_TYPE.EXPOSURE -> EXPOSURE_INITIAL_CALIBRATION_VALUE
            SEEKBAR_TYPE.ISO -> ISO_INITIAL_CALIBRATION_VALUE
            SEEKBAR_TYPE.APERTURE -> (getDeviceRange(seekbarType) as DiscretePointRange).range!!.get(0)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun initialiseSeekBar(textView: TextView, seekbarType: SEEKBAR_TYPE) {
        val seekBar = getSeekBar(seekbarType)
        // set the intiial seekbar progress
        val target = when (seekbarType) {
            SEEKBAR_TYPE.EXPOSURE -> currentExposureValue
            SEEKBAR_TYPE.ISO -> currentISOValue
            SEEKBAR_TYPE.APERTURE -> apertureDeviceRange?.range?.get(0)
        }
        setProgressPercent(target, seekbarType)
        if (getCurrentSeekValue(seekbarType) != null) {
            textView.text = getSeekBarText(seekbarType) + " " + getCurrentSeekValue(seekbarType)
        }
        seekBar!!.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
           override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
               notLogging ?: Log.d("__onprogress changed", (System.currentTimeMillis() - valueChanged).toString() + " " + seekbarType.toString() + " " + progress)
               val newCaptureRequestValue = setCaptureRequestValue(seekbarType, progress)
               if (newCaptureRequestValue != null) {
                   textView.text = getSeekBarText(seekbarType) + " " + newCaptureRequestValue
               }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // not implemented
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // not implemented
            }
        })
    }

    private fun getSeekBarText(seekbarType: SEEKBAR_TYPE): String {
        var seekBarText: String = ""
        when (seekbarType) {
            SEEKBAR_TYPE.EXPOSURE -> seekBarText = "Exposure"
            SEEKBAR_TYPE.ISO -> seekBarText = "Iso"
        }
        return seekBarText
    }


    // creates new captureRequest
    @RequiresApi(Build.VERSION_CODES.N)
    private fun setCaptureRequestValue(seekBarType: SEEKBAR_TYPE, progress: Int): Any? {
        val captureRequestValue = getRangedValue(seekBarType, progress)
        if (isCalibrating) {
            currentCalibrationAbsoluteValue = captureRequestValue as Long
            notLogging ?: Log.d("__setCaptureReqValue", (System.currentTimeMillis() - valueChanged).toString() + " " +currentCalibrationAbsoluteValue.toString())
        }
        val captureRequest: CaptureRequest =  createCaptureRequest(seekBarType, captureRequestValue)

        session.setRepeatingRequest(captureRequest, null, cameraHandler)
        return captureRequestValue
    }

    private fun createCaptureRequest(): CaptureRequest {
        return createCaptureRequest(null, null)
    }

    private fun createCaptureRequest(seekbarType: SEEKBAR_TYPE?, newParamValue: Number?): CaptureRequest {
        return session.device.createCaptureRequest(if (isRecording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW).apply {
            // Add the preview and recording surface targets
            addTarget(textureSurface)
            if (isRecording) {
                addTarget(recorderSurface)
                // Sets user requested FPS for all targets
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(args.fps, args.fps))
            }
            // for controlling exposure and iso
            set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)

            notLogging ?: Log.d("__creating capture req", seekbarType.toString() + " " + (System.currentTimeMillis() - valueChanged).toString() + " " +newParamValue.toString())

            if (newParamValue != null) {
                when (seekbarType) {
                    SEEKBAR_TYPE.EXPOSURE -> {
                        currentExposureValue = newParamValue as Long
                    }
                    SEEKBAR_TYPE.ISO -> {
                        currentISOValue = newParamValue as Long
                    }
                    SEEKBAR_TYPE.APERTURE -> {
                        currentApertureValue = newParamValue as Float
                    }
                }
            }

            set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureValue)
            set(CaptureRequest.SENSOR_SENSITIVITY, currentISOValue!!.toInt())
            set(CaptureRequest.LENS_APERTURE, currentApertureValue)
        }.build()
    }

    /** To get the characteristic value after seek */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun getRangedValue(seekbarType: SEEKBAR_TYPE, progress: Int): Number? {
        // not using the full exposure range since it gives bad output
        return when(seekbarType) {
            SEEKBAR_TYPE.ISO, SEEKBAR_TYPE.EXPOSURE -> getEndPointRangedValue(seekbarType, progress)
            SEEKBAR_TYPE.APERTURE -> getDiscretePointRangedValue(seekbarType, progress)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun getEndPointRangedValue(seekbarType: SEEKBAR_TYPE?, progress: Int): Long? {
        if (seekbarType == null) return null

        // not using the full exposure range since it gives bad output
        val chosenRange = getIntersectedRange(getDeviceRange(seekbarType) as EndPointRange, getPracticalRange(seekbarType) as EndPointRange)
        val deviceRange = getDeviceRange(seekbarType) as EndPointRange?
        if (chosenRange?.range != null && deviceRange?.range != null) {
            return chosenRange.range.lower + (chosenRange.range.upper - chosenRange.range.lower)*min(progress, 100)/100
        }
        return null
    }

    private fun getDiscretePointRangedValue(seekbarType: SEEKBAR_TYPE, progress: Int): Float? {
        val discretePointRange = when (seekbarType) {
            SEEKBAR_TYPE.APERTURE -> getDeviceRange(seekbarType) as DiscretePointRange?
            else -> return null
        }
        if (discretePointRange?.range != null) {
            return discretePointRange.range[((progress / 101.toDouble())*discretePointRange.range.size).toInt()]
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun adjustWithinRange(range: Range<Long>, calculatedValue: Long): Long {
        return min(range.upper, max(range.lower, calculatedValue))
    }

    private fun getDeviceRange(seekbarType: SEEKBAR_TYPE): ValueRange? {
        val key = getCameraCharacteristicsRangeKey(seekbarType)
        return when(seekbarType) {
            SEEKBAR_TYPE.EXPOSURE -> if (exposureDeviceRange != null) exposureDeviceRange else EndPointRange( characteristics.get(key as CameraCharacteristics.Key<Range<Long>>) )
            SEEKBAR_TYPE.ISO -> if (isoDeviceRange != null) isoDeviceRange else EndPointRange(characteristics.get(key as CameraCharacteristics.Key<Range<Long>>))
            SEEKBAR_TYPE.APERTURE -> if (apertureDeviceRange != null) apertureDeviceRange else DiscretePointRange(characteristics.get(key as CameraCharacteristics.Key<FloatArray>))
        }
    }

    private fun getPracticalRange(seekbarType: SEEKBAR_TYPE): ValueRange? {
        return when(seekbarType) {
            SEEKBAR_TYPE.EXPOSURE -> EXPOSURE_PRACTICAL_RANGE
            SEEKBAR_TYPE.ISO ->  ISO_PRACTICAL_RANGE
            SEEKBAR_TYPE.APERTURE -> null
        }
    }

    private fun getCameraCharacteristicsRangeKey(seekBarType: SEEKBAR_TYPE): CameraCharacteristics.Key<*> {
        return when (seekBarType) {
            SEEKBAR_TYPE.EXPOSURE -> CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            SEEKBAR_TYPE.ISO -> CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            SEEKBAR_TYPE.APERTURE -> CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES
        }
    }

    private fun getCaptureRequestKey(seekbarType: SEEKBAR_TYPE): CaptureRequest.Key<*> {
        return when (seekbarType) {
            SEEKBAR_TYPE.EXPOSURE -> CaptureRequest.SENSOR_EXPOSURE_TIME
            SEEKBAR_TYPE.ISO -> CaptureRequest.SENSOR_SENSITIVITY
            SEEKBAR_TYPE.APERTURE -> CaptureRequest.LENS_APERTURE
        }
    }

    private fun getSeekBar(seekBartype: SEEKBAR_TYPE): SeekBar? {
        return when(seekBartype) {
            SEEKBAR_TYPE.ISO -> isoSeekbar
            SEEKBAR_TYPE.EXPOSURE -> exposureSeekbar
            SEEKBAR_TYPE.APERTURE -> apertureSeekbar
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private  fun  setProgressPercent(target: Any?, seekbarType: SEEKBAR_TYPE) {
        if (target == null) return
        val seekBar = getSeekBar(seekbarType)
        val progress = getProgressPercent(target, seekbarType)
        if (progress != null && progress <= 100) {
            seekBar?.progress = progress.toInt()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun getProgressPercent(target: Any, seekbarType: SEEKBAR_TYPE): Int? {
        return when (seekbarType) {
            SEEKBAR_TYPE.EXPOSURE, SEEKBAR_TYPE.ISO -> getEndPointProgressPercent(target as Long, seekbarType)
            SEEKBAR_TYPE.APERTURE -> getDiscretePointProgressPercent(target as Float, seekbarType)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun getEndPointProgressPercent(target: Long, seekbarType: SEEKBAR_TYPE): Int? {
        val intersectedRange = getIntersectedRange(getDeviceRange(seekbarType) as EndPointRange, getPracticalRange(seekbarType) as EndPointRange) ?: return null
        return (abs(target - intersectedRange.range!!.lower) * 100/(intersectedRange.range.upper - intersectedRange.range.lower)).toInt()
    }

    private fun getDiscretePointProgressPercent(target: Float, seekbarType: SEEKBAR_TYPE): Int? {
        val discreteRange = when(seekbarType) {
            SEEKBAR_TYPE.APERTURE -> getDeviceRange(seekbarType) as DiscretePointRange
            else -> return null
        }
        if (discreteRange.range != null) {
            discreteRange.range.sort()
            return discreteRange.range.indexOfFirst { target == it }
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun getIntersectedRange(range1: EndPointRange?, range2: EndPointRange?): EndPointRange? {
        if (range1 == null || range2 == null) {
            return null
        }
        return EndPointRange(Range(max(range1.range!!.lower, range2.range!!.lower), min(range2.range.upper, range1.range.upper)))
    }

    private fun setRanges() {
        isoDeviceRange = getDeviceRange(SEEKBAR_TYPE.ISO) as EndPointRange
        exposureDeviceRange = getDeviceRange(SEEKBAR_TYPE.EXPOSURE) as EndPointRange
        apertureDeviceRange = getDeviceRange(SEEKBAR_TYPE.APERTURE) as DiscretePointRange
    }

    // only initialising aperture since others are already initialised
    private fun setCharacteristicValues() {
        currentApertureValue = currentApertureValue ?: apertureDeviceRange?.range?.get(0)
    }

    private fun getCurrentSeekValue(seekbarType: SEEKBAR_TYPE): Any? {
        return when(seekbarType) {
            SEEKBAR_TYPE.ISO -> currentISOValue
            SEEKBAR_TYPE.EXPOSURE -> currentExposureValue
            SEEKBAR_TYPE.APERTURE -> currentApertureValue
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
            manager: CameraManager,
            cameraId: String,
            handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when(error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Creates a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine)
     */
    private suspend fun createCaptureSession(
            device: CameraDevice,
            targets: List<Surface>,
            handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Creates a capture session using the predefined targets, and defines a session state
        // callback which resumes the coroutine once the session is configured
        device.createCaptureSession(targets, object: CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    private fun isHardwareLevelSupported(c: CameraCharacteristics, requiredLevel: Int): Boolean {
        val sortedHWLevels: Array<Int> = arrayOf(
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL,
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED,
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3
        )

        val deviceLevel: Int? = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)

        if (requiredLevel == deviceLevel) {
            return true
        }

        for (sortedLevel in sortedHWLevels) {
            if (sortedLevel == requiredLevel) {
                return true
            } else if (sortedLevel == deviceLevel) {
                return false
            }
        }

        return false
    }

    override fun onStop() {
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        calibrationThread.quitSafely()
        evaluatorThread.quitSafely()
        recorder.release()
        recorderSurface.release()
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        private const val RECORDER_VIDEO_BITRATE: Int = 10_000_000
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L

        private val ISO_PRACTICAL_RANGE = EndPointRange(Range<Long>(100, Long.MAX_VALUE))
        private val EXPOSURE_PRACTICAL_RANGE = EndPointRange(Range<Long>(3000000, 50090000))
        private val ISO_INITIAL_CALIBRATION_VALUE: Long = 350
        private val EXPOSURE_INITIAL_CALIBRATION_VALUE: Long = 15000000

        private val START_CALIBRATING_TEXT = "Press to calibrate"
        private val CALIBRATING_TEXT = "Calibrating"

        private val CALIBRATION_ORDER = arrayOf(SEEKBAR_TYPE.ISO, SEEKBAR_TYPE.EXPOSURE)

        enum class SEEKBAR_TYPE {
            EXPOSURE,
            ISO,
            APERTURE
        }



        /** Creates a [File] named with the current date and time */
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.getExternalFilesDir(null), "VID_${sdf.format(Date())}.$extension")
        }

        private fun renameFile(fromFile: File, newName: String, extension: String): File {
            val to = File(fromFile.parent, "${fromFile.name.replace(".${extension}", "")}_${newName}.$extension")
            fromFile.renameTo(to)
            return to
        }
    }
}
