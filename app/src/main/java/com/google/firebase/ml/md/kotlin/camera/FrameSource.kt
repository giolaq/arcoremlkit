/*
 * Copyright 2019 Google LLC
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

package com.google.firebase.ml.md.kotlin.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Camera
import android.hardware.Camera.CameraInfo
import android.hardware.Camera.Parameters
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import com.google.android.gms.common.images.Size
import com.google.ar.core.Session
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.Scene
import com.google.firebase.ml.md.R
import com.google.firebase.ml.md.kotlin.Utils
import com.google.firebase.ml.md.kotlin.helpers.ImageConversion
import com.google.firebase.ml.md.kotlin.settings.PreferenceUtils
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata.ROTATION_90
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*

/**
 * Manages the camera and allows UI updates on top of it (e.g. overlaying extra Graphics). This
 * receives preview frames from the camera at a specified rate, sends those frames to detector as
 * fast as it is able to process.
 *
 *
 * This camera source makes a best effort to manage processing on preview frames as fast as
 * possible, while at the same time minimizing lag. As such, frames may be dropped if the detector
 * is unable to keep up with the rate of frames generated by the camera.
 */
@Suppress("DEPRECATION")
class FrameSource(private val graphicOverlay: GraphicOverlay) {

    private var scene: Scene? = null
    private var session: Session? = null
    private var sceneView: ArSceneView? = null
    @FirebaseVisionImageMetadata.Rotation
    private var rotation: Int = 0

    /** Returns the preview size that is currently in use by the underlying camera.  */
    internal var previewSize: Size? = null
        private set

    /**
     * Dedicated thread and associated runnable for calling into the detector with frames, as the
     * frames become available from the camera.
     */
    private var processingThread: Thread? = null
    private val processingRunnable = FrameProcessingRunnable()

    private val processorLock = Object()
    private var frameProcessor: FrameProcessor? = null

    /**
     * Map to convert between a byte array, received from the camera, and its associated byte buffer.
     * We use byte buffers internally because this is a more efficient way to call into native code
     * later (avoids a potential copy).
     *
     *
     * **Note:** uses IdentityHashMap here instead of HashMap because the behavior of an array's
     * equals, hashCode and toString methods is both useless and unexpected. IdentityHashMap enforces
     * identity ('==') check on the keys.
     */
    private val bytesToByteBuffer = IdentityHashMap<ByteArray, ByteBuffer>()
    private val context: Context = graphicOverlay.context

    val updateListener: Scene.OnUpdateListener = Scene.OnUpdateListener {
        processingRunnable.setNextFrame(it)
    }

    /**
     * Opens the camera and starts sending preview frames to the underlying detector. The supplied
     * surface holder is used for the preview so frames can be displayed to the user.
     *
     * @param scene the ar scene to use for the preview frames.
     * @throws IOException if the supplied surface holder could not be used as the preview display.
     */
    @Synchronized
    @Throws(IOException::class)
    internal fun start(scene: Scene?) {
        this.scene = scene
        if (scene == null) return


        graphicOverlay.setTransformInfo(Size(640, 480))
        scene.addOnUpdateListener(updateListener)


        processingThread = Thread(processingRunnable).apply {
            processingRunnable.setActive(true)
            start()
        }
    }

    /**
     * Closes the camera and stops sending frames to the underlying frame detector.
     *
     *
     * This camera source may be restarted again by calling [.start].
     *
     *
     * Call [.release] instead to completely shut down this camera source and release the
     * resources of the underlying detector.
     */
    @Synchronized
    internal fun stop() {
        processingRunnable.setActive(false)
        processingThread?.let {
            try {
                // Waits for the thread to complete to ensure that we can't have multiple threads executing
                // at the same time (i.e., which would happen if we called start too quickly after stop).
                it.join()
            } catch (e: InterruptedException) {
                Log.e(TAG, "Frame processing thread interrupted on stop.")
            }
            processingThread = null
        }

        scene?.removeOnUpdateListener(updateListener)

        // Release the reference to any image buffers, since these will no longer be in use.
        bytesToByteBuffer.clear()
    }

    /** Stops the camera and releases the resources of the camera and underlying detector.  */
    fun release() {
        graphicOverlay.clear()
        synchronized(processorLock) {
            stop()
            frameProcessor?.stop()
        }
    }

    fun setFrameProcessor(processor: FrameProcessor) {
        graphicOverlay.clear()
        synchronized(processorLock) {
            frameProcessor?.stop()
            frameProcessor = processor
        }
    }

    /**
     * Calculates the correct rotation for the given camera id and sets the rotation in the
     * parameters. It also sets the camera's display orientation and rotation.
     *
     * @param parameters the camera parameters for which to set the rotation.
     */
    private fun setRotation(camera: Camera, parameters: Parameters) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val degrees = when (val deviceRotation = windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> {
                Log.e(TAG, "Bad device rotation value: $deviceRotation")
                0
            }
        }

        val cameraInfo = CameraInfo()
        Camera.getCameraInfo(CAMERA_FACING_BACK, cameraInfo)
        val angle = (cameraInfo.orientation - degrees + 360) % 360
        // This corresponds to the rotation constants in FirebaseVisionImageMetadata.
        this.rotation = angle / 90
        camera.setDisplayOrientation(angle)
        parameters.setRotation(angle)
    }

    /**
     * This runnable controls access to the underlying receiver, calling it to process frames when
     * available from the camera. This is designed to run detection on frames as fast as possible
     * (i.e., without unnecessary context switching or waiting on the next frame).
     *
     *
     * While detection is running on a frame, new frames may be received from the camera. As these
     * frames come in, the most recent frame is held onto as pending. As soon as detection and its
     * associated processing is done for the previous frame, detection on the mostly recently received
     * frame will immediately start on the same thread.
     */
    private inner class FrameProcessingRunnable internal constructor() : Runnable {

        // This lock guards all of the member variables below.
        private val lock = Object()
        private var active = true

        // These pending variables hold the state associated with the new frame awaiting processing.
        private var pendingFrameData: ByteBuffer? = null
        private var pendingFrameDataArray: ByteArray? = null

        /** Marks the runnable as active/not active. Signals any blocked threads to continue.  */
        internal fun setActive(active: Boolean) {
            synchronized(lock) {
                this.active = active
                lock.notifyAll()
            }
        }

        internal fun setNextFrame(frameTime: FrameTime) {
            synchronized(lock) {
                pendingFrameData?.let {
                    //camera.addCallbackBuffer(it.array())
                    pendingFrameDataArray = null
                }

                //if (!bytesToByteBuffer.containsKey(data)) {
                //  Log.d(TAG, "Skipping frame. Could not find ByteBuffer associated with the image data from the camera.")
                // return
                //}

                if (session == null) {
                    return
                }

                val frame = sceneView?.arFrame ?: return

                // Copy the camera stream to a bitmap
                try {
                    frame.acquireCameraImage().use { image ->
                        if (image.format != ImageFormat.YUV_420_888) {
                            throw IllegalArgumentException(
                                    "Expected image in YUV_420_888 format, got format " + image.format)
                        }

                        Log.d("Hello", "Image acquired at $frameTime")
                        previewSize = Size(image.width, image.height)

                        val data =
                                ImageConversion.YUV_420_888toNV21(image)

                        pendingFrameDataArray = data

                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception copying image", e)
                }
                // pendingFrameData = bytesToByteBuffer[data]

                // Notify the processor thread if it is waiting on the next frame (see below).
                lock.notifyAll()
            }
        }

        /**
         * As long as the processing thread is active, this executes detection on frames continuously.
         * The next pending frame is either immediately available or hasn't been received yet. Once it
         * is available, we transfer the frame info to local variables and run detection on that frame.
         * It immediately loops back for the next frame without pausing.
         *
         *
         * If detection takes longer than the time in between new frames from the camera, this will
         * mean that this loop will run without ever waiting on a frame, avoiding any context switching
         * or frame acquisition time latency.
         *
         *
         * If you find that this is using more CPU than you'd like, you should probably decrease the
         * FPS setting above to allow for some idle time in between frames.
         */
        override fun run() {
            var data: ByteArray?

            while (true) {
                synchronized(lock) {
                    while (active && pendingFrameDataArray == null) {
                        try {
                            // Wait for the next frame to be received from the camera, since we don't have it yet.
                            lock.wait()
                        } catch (e: InterruptedException) {
                            Log.e(TAG, "Frame processing loop terminated.", e)
                            return
                        }

                    }

                    if (!active) {
                        // Exit the loop once this camera source is stopped or released.  We check this here,
                        // immediately after the wait() above, to handle the case where setActive(false) had
                        // been called, triggering the termination of this loop.
                        return
                    }

                    // Hold onto the frame data locally, so that we can use this for detection
                    // below.  We need to clear pendingFrameData to ensure that this buffer isn't
                    // recycled back to the camera before we are done using that data.
                    data = pendingFrameDataArray
                    pendingFrameDataArray = null
                }

                try {
                    synchronized(processorLock) {
                        val rotation = ROTATION_90

                        val frameMetadata = FrameMetadata(previewSize!!.width, previewSize!!.height, rotation)
                        data?.let {
                            frameProcessor?.process(it, frameMetadata, graphicOverlay)
                        }
                    }
                } catch (t: Exception) {
                    Log.e(TAG, "Exception thrown from receiver.", t)
                } finally {
                    data?.let {
                        //camera?.addCallbackBuffer(it.array())
                    }
                }
            }
        }
    }

    companion object {

        const val CAMERA_FACING_BACK = CameraInfo.CAMERA_FACING_BACK

        private const val TAG = "CameraSource"

        private const val MIN_CAMERA_PREVIEW_WIDTH = 400
        private const val MAX_CAMERA_PREVIEW_WIDTH = 1300
        private const val DEFAULT_REQUESTED_CAMERA_PREVIEW_WIDTH = 640
        private const val DEFAULT_REQUESTED_CAMERA_PREVIEW_HEIGHT = 360
        private const val REQUESTED_CAMERA_FPS = 30.0f

        /**
         * Selects the most suitable preview and picture size, given the display aspect ratio in landscape
         * mode.
         *
         *
         * It's firstly trying to pick the one that has closest aspect ratio to display view with its
         * width be in the specified range [[.MIN_CAMERA_PREVIEW_WIDTH], [ ][.MAX_CAMERA_PREVIEW_WIDTH]]. If there're multiple candidates, choose the one having longest
         * width.
         *
         *
         * If the above looking up failed, chooses the one that has the minimum sum of the differences
         * between the desired values and the actual values for width and height.
         *
         *
         * Even though we only need to find the preview size, it's necessary to find both the preview
         * size and the picture size of the camera together, because these need to have the same aspect
         * ratio. On some hardware, if you would only set the preview size, you will get a distorted
         * image.
         *
         * @param camera the camera to select a preview size from
         * @return the selected preview and picture size pair
         */
        private fun selectSizePair(camera: Camera, displayAspectRatioInLandscape: Float): CameraSizePair? {
            val validPreviewSizes = Utils.generateValidPreviewSizeList(camera)

            var selectedPair: CameraSizePair? = null
            // Picks the preview size that has closest aspect ratio to display view.
            var minAspectRatioDiff = Float.MAX_VALUE

            for (sizePair in validPreviewSizes) {
                val previewSize = sizePair.preview
                if (previewSize.width < MIN_CAMERA_PREVIEW_WIDTH || previewSize.width > MAX_CAMERA_PREVIEW_WIDTH) {
                    continue
                }

                val previewAspectRatio = previewSize.width.toFloat() / previewSize.height.toFloat()
                val aspectRatioDiff = Math.abs(displayAspectRatioInLandscape - previewAspectRatio)
                if (Math.abs(aspectRatioDiff - minAspectRatioDiff) < Utils.ASPECT_RATIO_TOLERANCE) {
                    if (selectedPair == null || selectedPair.preview.width < sizePair.preview.width) {
                        selectedPair = sizePair
                    }
                } else if (aspectRatioDiff < minAspectRatioDiff) {
                    minAspectRatioDiff = aspectRatioDiff
                    selectedPair = sizePair
                }
            }

            if (selectedPair == null) {
                // Picks the one that has the minimum sum of the differences between the desired values and
                // the actual values for width and height.
                var minDiff = Integer.MAX_VALUE
                for (sizePair in validPreviewSizes) {
                    val size = sizePair.preview
                    val diff = Math.abs(size.width - DEFAULT_REQUESTED_CAMERA_PREVIEW_WIDTH) + Math.abs(size.height - DEFAULT_REQUESTED_CAMERA_PREVIEW_HEIGHT)
                    if (diff < minDiff) {
                        selectedPair = sizePair
                        minDiff = diff
                    }
                }
            }

            return selectedPair
        }

        /**
         * Selects the most suitable preview frames per second range.
         *
         * @param camera the camera to select a frames per second range from
         * @return the selected preview frames per second range
         */
        private fun selectPreviewFpsRange(camera: Camera): IntArray? {
            // The camera API uses integers scaled by a factor of 1000 instead of floating-point frame
            // rates.
            val desiredPreviewFpsScaled = (REQUESTED_CAMERA_FPS * 1000f).toInt()

            // The method for selecting the best range is to minimize the sum of the differences between
            // the desired value and the upper and lower bounds of the range.  This may select a range
            // that the desired value is outside of, but this is often preferred.  For example, if the
            // desired frame rate is 29.97, the range (30, 30) is probably more desirable than the
            // range (15, 30).
            var selectedFpsRange: IntArray? = null
            var minDiff = Integer.MAX_VALUE
            for (range in camera.parameters.supportedPreviewFpsRange) {
                val deltaMin = desiredPreviewFpsScaled - range[Parameters.PREVIEW_FPS_MIN_INDEX]
                val deltaMax = desiredPreviewFpsScaled - range[Parameters.PREVIEW_FPS_MAX_INDEX]
                val diff = Math.abs(deltaMin) + Math.abs(deltaMax)
                if (diff < minDiff) {
                    selectedFpsRange = range
                    minDiff = diff
                }
            }
            return selectedFpsRange
        }
    }

    fun setSceneView(sceneView: ArSceneView?) {
        this.sceneView = sceneView
    }

    fun setSession(session: Session?) {
        this.session = session
    }

}
