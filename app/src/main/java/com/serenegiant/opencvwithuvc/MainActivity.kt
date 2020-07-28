/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2018 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */
package com.serenegiant.opencvwithuvc

import android.animation.Animator
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.View.OnLongClickListener
import android.widget.*
import android.widget.SeekBar.OnSeekBarChangeListener
import com.serenegiant.common.BaseActivity
import com.serenegiant.opencv.ImageProcessor
import com.serenegiant.opencv.ImageProcessor.ImageProcessorCallback
import com.serenegiant.opencvwithuvc.MainActivity
import com.serenegiant.usb.CameraDialog
import com.serenegiant.usb.CameraDialog.CameraDialogParent
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener
import com.serenegiant.usb.USBMonitor.UsbControlBlock
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usbcameracommon.UVCCameraHandlerMultiSurface
import com.serenegiant.utils.CpuMonitor
import com.serenegiant.utils.ViewAnimationHelper
import com.serenegiant.utils.ViewAnimationHelper.ViewAnimationListener
import com.serenegiant.widget.UVCCameraTextureView
import kotlinx.android.synthetic.main.activity_main.*
import java.nio.ByteBuffer
import java.util.*

class MainActivity : BaseActivity(), CameraDialogParent {
    /**
     * for accessing USB
     */
    private var mUSBMonitor: USBMonitor? = null

    /**
     * Handler to execute camera related methods sequentially on private thread
     */
    private var mCameraHandler: UVCCameraHandlerMultiSurface? = null
    private var mImageProcessor: ImageProcessor? = null
    private val cpuMonitor = CpuMonitor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DEBUG) Log.v(TAG, "onCreate:")
        setContentView(R.layout.activity_main)
        camera_button.setOnCheckedChangeListener(mOnCheckedChangeListener)
        capture_button.setOnClickListener(mOnClickListener)
        capture_button.visibility = View.INVISIBLE
        camera_view.setOnLongClickListener(mOnLongClickListener)
        brightness_button.setOnClickListener(mOnClickListener)
        contrast_button.setOnClickListener(mOnClickListener)
        reset_button.setOnClickListener(mOnClickListener)
        setting_seekbar.setOnSeekBarChangeListener(mOnSeekBarChangeListener)
        tools_layout.visibility = View.INVISIBLE
        value_layout.visibility = View.INVISIBLE
        cpu_load_textview.typeface = Typeface.MONOSPACE
        fps_textview.text = null
        fps_textview.typeface = Typeface.MONOSPACE
        mUSBMonitor = USBMonitor(this, mOnDeviceConnectListener)
        mCameraHandler = UVCCameraHandlerMultiSurface.createHandler(this, camera_view,
                1, PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_MODE)
    }

    override fun onStart() {
        super.onStart()
        if (DEBUG) Log.v(TAG, "onStart:")
        mUSBMonitor!!.register()
        queueEvent(mCPUMonitorTask, 1000)
        runOnUiThread(mFpsTask, 1000)
    }

    override fun onStop() {
        if (DEBUG) Log.v(TAG, "onStop:")
        removeEvent(mCPUMonitorTask)
        removeFromUiThread(mFpsTask)
        stopPreview()
        mCameraHandler!!.close()
        setCameraButton(false)
        super.onStop()
    }

    public override fun onDestroy() {
        if (DEBUG) Log.v(TAG, "onDestroy:")
        if (mCameraHandler != null) {
            mCameraHandler!!.release()
            mCameraHandler = null
        }
        if (mUSBMonitor != null) {
            mUSBMonitor!!.destroy()
            mUSBMonitor = null
        }
        super.onDestroy()
    }

    /**
     * event handler when click camera / capture button
     */
    private val mOnClickListener = View.OnClickListener { view ->
        when (view.id) {
            R.id.capture_button -> if (mCameraHandler!!.isOpened) {
                if (checkPermissionWriteExternalStorage() && checkPermissionAudio()) {
                    if (!mCameraHandler!!.isRecording) {
                        capture_button!!.setColorFilter(-0x10000) // turn red
                        mCameraHandler!!.startRecording()
                    } else {
                        capture_button!!.setColorFilter(0) // return to default color
                        mCameraHandler!!.stopRecording()
                    }
                }
            }
            R.id.brightness_button -> showSettings(UVCCamera.PU_BRIGHTNESS)
            R.id.contrast_button -> showSettings(UVCCamera.PU_CONTRAST)
            R.id.reset_button -> resetSettings()
        }
    }
    private val mOnCheckedChangeListener = CompoundButton.OnCheckedChangeListener { compoundButton, isChecked ->
        when (compoundButton.id) {
            R.id.camera_button -> if (isChecked && !mCameraHandler!!.isOpened) {
                CameraDialog.showDialog(this@MainActivity)
            } else {
                stopPreview()
            }
        }
    }

    /**
     * capture still image when you long click on preview image(not on buttons)
     */
    private val mOnLongClickListener = OnLongClickListener { view ->
        when (view.id) {
            R.id.camera_view -> if (mCameraHandler!!.isOpened) {
                if (checkPermissionWriteExternalStorage()) {
                    mCameraHandler!!.captureStill()
                }
                return@OnLongClickListener true
            }
        }
        false
    }

    private fun setCameraButton(isOn: Boolean) {
        if (DEBUG) Log.v(TAG, "setCameraButton:isOn=$isOn")
        runOnUiThread({
            if (camera_button != null) {
                try {
                    camera_button!!.setOnCheckedChangeListener(null)
                    camera_button!!.isChecked = isOn
                } finally {
                    camera_button!!.setOnCheckedChangeListener(mOnCheckedChangeListener)
                }
            }
            if (!isOn && capture_button != null) {
                capture_button!!.visibility = View.INVISIBLE
            }
        }, 0)
        updateItems()
    }

    private var mPreviewSurfaceId = 0
    private fun startPreview() {
        if (DEBUG) Log.v(TAG, "startPreview:")
        camera_view!!.resetFps()
        mCameraHandler!!.startPreview()
        runOnUiThread {
            try {
                val st = camera_view!!.surfaceTexture
                if (st != null) {
                    val surface = Surface(st)
                    mPreviewSurfaceId = surface.hashCode()
                    mCameraHandler!!.addSurface(mPreviewSurfaceId, surface, false)
                }
                capture_button!!.visibility = View.VISIBLE
                startImageProcessor(PREVIEW_WIDTH, PREVIEW_HEIGHT)
            } catch (e: Exception) {
                Log.w(TAG, e)
            }
        }
        updateItems()
    }

    private fun stopPreview() {
        if (DEBUG) Log.v(TAG, "stopPreview:")
        stopImageProcessor()
        if (mPreviewSurfaceId != 0) {
            mCameraHandler!!.removeSurface(mPreviewSurfaceId)
            mPreviewSurfaceId = 0
        }
        mCameraHandler!!.close()
        setCameraButton(false)
    }

    private val mOnDeviceConnectListener: OnDeviceConnectListener = object : OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) {
            Toast.makeText(this@MainActivity,
                    "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show()
        }

        override fun onConnect(device: UsbDevice?,
                               ctrlBlock: UsbControlBlock, createNew: Boolean) {
            if (DEBUG) Log.v(TAG, "onConnect:")
            mCameraHandler!!.open(ctrlBlock)
            startPreview()
            updateItems()
        }

        override fun onDisconnect(device: UsbDevice,
                                  ctrlBlock: UsbControlBlock) {
            if (DEBUG) Log.v(TAG, "onDisconnect:")
            if (mCameraHandler != null) {
                queueEvent({ stopPreview() }, 0)
                updateItems()
            }
        }

        override fun onDettach(device: UsbDevice) {
            Toast.makeText(this@MainActivity,
                    "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show()
        }

        override fun onCancel(device: UsbDevice) {
            setCameraButton(false)
        }
    }

    /**
     * to access from CameraDialog
     * @return
     */
    override fun getUSBMonitor(): USBMonitor {
        return mUSBMonitor!!
    }

    override fun onDialogResult(canceled: Boolean) {
        if (DEBUG) Log.v(TAG, "onDialogResult:canceled=$canceled")
        if (canceled) {
            setCameraButton(false)
        }
    }

    //================================================================================
    private val isActive: Boolean
        private get() = mCameraHandler != null && mCameraHandler!!.isOpened

    private fun checkSupportFlag(flag: Int): Boolean {
        return mCameraHandler != null && mCameraHandler!!.checkSupportFlag(flag.toLong())
    }

    private fun getValue(flag: Int): Int {
        return if (mCameraHandler != null) mCameraHandler!!.getValue(flag) else 0
    }

    private fun setValue(flag: Int, value: Int): Int {
        return if (mCameraHandler != null) mCameraHandler!!.setValue(flag, value) else 0
    }

    private fun resetValue(flag: Int): Int {
        return if (mCameraHandler != null) mCameraHandler!!.resetValue(flag) else 0
    }

    private fun updateItems() {
        runOnUiThread(mUpdateItemsOnUITask, 100)
    }

    private val mUpdateItemsOnUITask = Runnable {
        if (isFinishing) return@Runnable
        val visible_active = if (isActive) View.VISIBLE else View.INVISIBLE
        tools_layout!!.visibility = visible_active
        brightness_button!!.visibility = if (checkSupportFlag(UVCCamera.PU_BRIGHTNESS)) visible_active else View.INVISIBLE
        contrast_button!!.visibility = if (checkSupportFlag(UVCCamera.PU_CONTRAST)) visible_active else View.INVISIBLE
    }
    private var mSettingMode = -1

    /**
     * show setting view
     * @param mode
     */
    private fun showSettings(mode: Int) {
        if (DEBUG) Log.v(TAG, String.format("showSettings:%08x", mode))
        hideSetting(false)
        if (isActive) {
            when (mode) {
                UVCCamera.PU_BRIGHTNESS, UVCCamera.PU_CONTRAST -> {
                    mSettingMode = mode
                    setting_seekbar!!.progress = getValue(mode)
                    ViewAnimationHelper.fadeIn(value_layout, -1, 0, mViewAnimationListener)
                }
            }
        }
    }

    private fun resetSettings() {
        if (isActive) {
            when (mSettingMode) {
                UVCCamera.PU_BRIGHTNESS, UVCCamera.PU_CONTRAST -> setting_seekbar!!.progress = resetValue(mSettingMode)
            }
        }
        mSettingMode = -1
        ViewAnimationHelper.fadeOut(value_layout, -1, 0, mViewAnimationListener)
    }

    /**
     * hide setting view
     * @param fadeOut
     */
    protected fun hideSetting(fadeOut: Boolean) {
        removeFromUiThread(mSettingHideTask)
        if (fadeOut) {
            runOnUiThread({ ViewAnimationHelper.fadeOut(value_layout, -1, 0, mViewAnimationListener) }, 0)
        } else {
            try {
                value_layout!!.visibility = View.GONE
            } catch (e: Exception) {
                // ignore
            }
            mSettingMode = -1
        }
    }

    protected val mSettingHideTask = Runnable { hideSetting(true) }

    /**
     * callback listener to change camera control values
     */
    private val mOnSeekBarChangeListener: OnSeekBarChangeListener = object : OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar,
                                       progress: Int, fromUser: Boolean) {
            if (fromUser) {
                runOnUiThread(mSettingHideTask, SETTINGS_HIDE_DELAY_MS.toLong())
            }
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {
            runOnUiThread(mSettingHideTask, SETTINGS_HIDE_DELAY_MS.toLong())
            if (isActive && checkSupportFlag(mSettingMode)) {
                when (mSettingMode) {
                    UVCCamera.PU_BRIGHTNESS, UVCCamera.PU_CONTRAST -> setValue(mSettingMode, seekBar.progress)
                }
            } // if (active)
        }
    }
    private val mViewAnimationListener: ViewAnimationListener = object : ViewAnimationListener {
        override fun onAnimationStart(animator: Animator,
                                      target: View, animationType: Int) {

//			if (DEBUG) Log.v(TAG, "onAnimationStart:");
        }

        override fun onAnimationEnd(animator: Animator,
                                    target: View, animationType: Int) {
            val id = target.id
            when (animationType) {
                ViewAnimationHelper.ANIMATION_FADE_IN, ViewAnimationHelper.ANIMATION_FADE_OUT -> {
                    val fadeIn = animationType == ViewAnimationHelper.ANIMATION_FADE_IN
                    if (id == R.id.value_layout) {
                        if (fadeIn) {
                            runOnUiThread(mSettingHideTask, SETTINGS_HIDE_DELAY_MS.toLong())
                        } else {
                            value_layout!!.visibility = View.GONE
                            mSettingMode = -1
                        }
                    } else if (!fadeIn) {
//					target.setVisibility(View.GONE);
                    }
                }
            }
        }

        override fun onAnimationCancel(animator: Animator,
                                       target: View, animationType: Int) {

//			if (DEBUG) Log.v(TAG, "onAnimationStart:");
        }
    }

    //================================================================================
    private val mCPUMonitorTask: Runnable = object : Runnable {
        override fun run() {
            if (cpuMonitor.sampleCpuUtilization()) {
                runOnUiThread {
                    cpu_load_textview!!.text = String.format(Locale.US, "CPU:%3d/%3d/%3d",
                            cpuMonitor.cpuCurrent,
                            cpuMonitor.cpuAvg3,
                            cpuMonitor.cpuAvgAll)
                }
            }
            queueEvent(this, 1000)
        }
    }
    private val mFpsTask: Runnable = object : Runnable {
        override fun run() {
            val srcFps: Float = if (camera_view != null) {
                camera_view!!.updateFps()
                camera_view!!.fps
            } else {
                0.0f
            }
            val resultFps: Float = if (mImageProcessor != null) {
                mImageProcessor!!.updateFps()
                mImageProcessor!!.fps
            } else {
                0.0f
            }
            fps_textview!!.text = String.format(Locale.US, "FPS:%4.1f->%4.1f", srcFps, resultFps)
            runOnUiThread(this, 1000)
        }
    }

    //================================================================================
    @Volatile
    private var mIsRunning = false
    private var mImageProcessorSurfaceId = 0

    /**
     * start image processing
     * @param processing_width
     * @param processing_height
     */
    protected fun startImageProcessor(processing_width: Int, processing_height: Int) {
        if (DEBUG) Log.v(TAG, "startImageProcessor:")
        mIsRunning = true
        if (mImageProcessor == null) {
            mImageProcessor = ImageProcessor(PREVIEW_WIDTH, PREVIEW_HEIGHT,  // src size
                    MyImageProcessorCallback(processing_width, processing_height)) // processing size
            mImageProcessor!!.start(processing_width, processing_height) // processing size
            val surface = mImageProcessor!!.surface
            mImageProcessorSurfaceId = surface?.hashCode() ?: 0
            if (mImageProcessorSurfaceId != 0) {
                mCameraHandler!!.addSurface(mImageProcessorSurfaceId, surface, false)
            }
        }
    }

    /**
     * stop image processing
     */
    protected fun stopImageProcessor() {
        if (DEBUG) Log.v(TAG, "stopImageProcessor:")
        if (mImageProcessorSurfaceId != 0) {
            mCameraHandler!!.removeSurface(mImageProcessorSurfaceId)
            mImageProcessorSurfaceId = 0
        }
        if (mImageProcessor != null) {
            mImageProcessor!!.release()
            mImageProcessor = null
        }
    }

    /**
     * callback listener from `ImageProcessor`
     */
    protected inner class MyImageProcessorCallback(
            private val width: Int, private val height: Int) : ImageProcessorCallback {
        private val matrix = Matrix()
        private var mFrame: Bitmap? = null
        override fun onFrame(frame: ByteBuffer) {
            if (result_view != null) {
                val holder = result_view!!.holder
                if (holder == null
                        || holder.surface == null
                        || frame == null) return

//--------------------------------------------------------------------------------
// Using SurfaceView and Bitmap to draw resulted images is inefficient way,
// but functions onOpenCV are relatively heavy and expect slower than source
// frame rate. So currently just use the way to simply this sample app.
// If you want to use much efficient way, try to use as same way as
// UVCCamera class use to receive images from UVC camera.
//--------------------------------------------------------------------------------
                if (mFrame == null) {
                    mFrame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val scaleX = result_view!!.width / width.toFloat()
                    val scaleY = result_view!!.height / height.toFloat()
                    matrix.reset()
                    matrix.postScale(scaleX, scaleY)
                }
                try {
                    frame.clear()
                    mFrame!!.copyPixelsFromBuffer(frame)
                    val canvas = holder.lockCanvas()
                    if (canvas != null) {
                        try {
                            canvas.drawBitmap(mFrame, matrix, null)
                        } catch (e: Exception) {
                            Log.w(TAG, e)
                        } finally {
                            holder.unlockCanvasAndPost(canvas)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, e)
                }
            }
        }

        override fun onResult(type: Int, result: FloatArray) {
            // do something
        }

    }

    companion object {
        private const val DEBUG = true // TODO set false on release
        private const val TAG = "MainActivity"

        /**
         * set true if you want to record movie using MediaSurfaceEncoder
         * (writing frame data into Surface camera from MediaCodec
         * by almost same way as USBCameratest2)
         * set false if you want to record movie using MediaVideoEncoder
         */
        private const val USE_SURFACE_ENCODER = false

        /**
         * preview resolution(width)
         * if your camera does not support specific resolution and mode,
         * [UVCCamera.setPreviewSize] throw exception
         */
        private const val PREVIEW_WIDTH = 640

        /**
         * preview resolution(height)
         * if your camera does not support specific resolution and mode,
         * [UVCCamera.setPreviewSize] throw exception
         */
        private const val PREVIEW_HEIGHT = 480

        /**
         * preview mode
         * if your camera does not support specific resolution and mode,
         * [UVCCamera.setPreviewSize] throw exception
         * 0:YUYV, other:MJPEG
         */
        private const val PREVIEW_MODE = 1
        protected const val SETTINGS_HIDE_DELAY_MS = 2500
    }
}