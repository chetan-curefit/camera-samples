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
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.android.camera.utils.AutoFitSurfaceView
import com.example.android.camera.utils.OrientationLiveData
import com.example.android.camera.utils.getPreviewOutputSize
import com.example.android.camera2.video.BuildConfig
import com.example.android.camera2.video.CameraActivity
import com.example.android.camera2.video.R
import kotlinx.android.synthetic.main.fragment_camera.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.lang.Long.max
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.min

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
    private lateinit var viewFinder: AutoFitSurfaceView

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

    private var currentExposureValue: Long = (EXPOSURE_PRACTICAL_RANGE.range!!.upper+ EXPOSURE_PRACTICAL_RANGE.range.lower)/2
    private var currentISOValue: Long = (ISO_PRACTICAL_RANGE.range!!.lower + ISO_PRACTICAL_RANGE.range.upper)/2
    private var currentApertureValue: Float? = null

    private var exposureDeviceRange: EndPointRange? = null
    private var isoDeviceRange: EndPointRange? = null
    private var apertureDeviceRange: DiscretePointRange? = null

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
        viewFinder = view.findViewById(R.id.view_finder)
        isoSeekbar = view.findViewById(R.id.iso)
        exposureSeekbar = view.findViewById(R.id.exposure)
        apertureSeekbar = view.findViewById(R.id.aperture)
        isoText = view.findViewById(R.id.iso_title)
        exposureText = view.findViewById(R.id.exposureTitle)
        apertureText = view.findViewById(R.id.aperture_title)


        initialiseSeekBar(isoText, SEEKBAR_TYPE.ISO)
        initialiseSeekBar(exposureText, SEEKBAR_TYPE.EXPOSURE)
        initialiseSeekBar(apertureText, SEEKBAR_TYPE.APERTURE)


        viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {

                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                        viewFinder.display, characteristics, SurfaceHolder::class.java)
                Log.d(TAG, "View finder size: ${viewFinder.width} x ${viewFinder.height}")
                Log.d(TAG, "Selected preview size: $previewSize")
                viewFinder.setAspectRatio(previewSize.width, previewSize.height)

                // To ensure that size is set, initialize camera in the view's thread
                viewFinder.post { initializeCamera() }
            }
        })

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner, Observer {
                orientation -> Log.d(TAG, "Orientation changed: $orientation")
            })
        }

        setRanges()
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
    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {

        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(viewFinder.holder.surface, recorderSurface)

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
                            }
                        })

                        Log.d(TAG, "Recording started")

                        // Starts recording animation
                        //overlay.post(animationTask)
                    } else lifecycleScope.launch(Dispatchers.IO) {
                        isRecording = false

                        requireActivity().runOnUiThread { (view as ImageButton).isSelected = false }

                        // Unlocks screen rotation after recording finished
                        requireActivity().requestedOrientation =
                                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                        // Requires recording of at least MIN_REQUIRED_RECORDING_TIME_MILLIS
                        val elapsedTimeMillis = System.currentTimeMillis() - recordingStartMillis
                        if (elapsedTimeMillis < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
                            delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsedTimeMillis)
                        }

                        Log.d(TAG, "Recording stopped. Output file: $outputFile")
                        recorder.stop()


                        // Removes recording animation
                        //overlay.removeCallbacks(animationTask)

                        // Broadcasts the media file to the rest of the system
                        MediaScannerConnection.scanFile(
                                view.context, arrayOf(outputFile.absolutePath), null, null)

                        // Launch external activity via intent to play video recorded using our provider
                        startActivity(Intent().apply {
                            action = Intent.ACTION_VIEW
                            type = MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(outputFile.extension)
                            val authority = "${BuildConfig.APPLICATION_ID}.provider"
                            data = FileProvider.getUriForFile(view.context, authority, outputFile)
                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                        })

                        // Finishes our current camera screen
                        delay(CameraActivity.ANIMATION_SLOW_MILLIS)
                        navController.popBackStack()
                }
            }
        })

    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun initialiseSeekBar(textView: TextView, seekbarType: SEEKBAR_TYPE) {
        val seekBar = getSeekBar(seekbarType)
        // set the intiial seekbar progress
        val target = when (seekbarType) {
            SEEKBAR_TYPE.EXPOSURE -> currentExposureValue
            SEEKBAR_TYPE.ISO -> currentISOValue
            else -> null
        }
        setProgressPercent(target, seekBar!!, seekbarType)
        if (getCurrentSeekValue(seekbarType) != null) {
            textView.text = getSeekBarText(seekbarType) + " " + getCurrentSeekValue(seekbarType)
        }
        seekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
           override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {

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
    private fun setCaptureRequestValue(seekbarType: SEEKBAR_TYPE, progress: Int): Any? {
        val captureRequestValue = getRangedValue(seekbarType, progress)
        val captureRequest: CaptureRequest =  createCaptureRequest(seekbarType, captureRequestValue)

        session.setRepeatingRequest(captureRequest, null, cameraHandler)
        return captureRequestValue
    }

    private fun createCaptureRequest(): CaptureRequest {
        return createCaptureRequest(null, null)
    }

    private fun createCaptureRequest(seekbarType: SEEKBAR_TYPE?, newParamValue: Number?): CaptureRequest {
        return session.device.createCaptureRequest(if (isRecording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW).apply {
            // Add the preview and recording surface targets
            addTarget(viewFinder.holder.surface)
            if (isRecording) {
                addTarget(recorderSurface)
                // Sets user requested FPS for all targets
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(args.fps, args.fps))
            }
            // for controlling exposure and iso
            set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)

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
        val chosenRange = if (seekbarType == SEEKBAR_TYPE.EXPOSURE) EXPOSURE_PRACTICAL_RANGE else getDeviceRange(seekbarType) as EndPointRange?
        val deviceRange = getDeviceRange(seekbarType) as EndPointRange?
        if (chosenRange?.range != null && deviceRange?.range != null) {
            val calculatedValue: Long = chosenRange.range.lower + (chosenRange.range.upper - chosenRange.range.lower)*progress/100
            return adjustWithinRange(deviceRange.range, adjustWithinRange(chosenRange.range, calculatedValue))
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
        val key = getKey(seekbarType)
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

    private fun getKey(seekBarType: SEEKBAR_TYPE): CameraCharacteristics.Key<*> {
        return when (seekBarType) {
            SEEKBAR_TYPE.EXPOSURE -> CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            SEEKBAR_TYPE.ISO -> CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            SEEKBAR_TYPE.APERTURE -> CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES
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
    private  fun  setProgressPercent(target: Any?, seekBar: SeekBar, seekbarType: SEEKBAR_TYPE) {
        if (target == null) return
        val progress = when (seekbarType) {
            SEEKBAR_TYPE.EXPOSURE, SEEKBAR_TYPE.ISO -> getEndPointProgressPercent(target as Long, seekbarType)
            SEEKBAR_TYPE.APERTURE -> getDiscretePointProgressPercent(target as Float, seekbarType)
        }
        if (progress != null && progress <= 100) {
            seekBar.progress = progress.toInt()
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
        recorder.release()
        recorderSurface.release()
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        private const val RECORDER_VIDEO_BITRATE: Int = 10_000_000
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L

        private val ISO_PRACTICAL_RANGE = EndPointRange(Range<Long>(100, 3200))
        private val EXPOSURE_PRACTICAL_RANGE = EndPointRange(Range<Long>(100000, 50090000))

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
    }
}
