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

import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.*
import com.serenegiant.common.BaseActivity
import com.serenegiant.usb.CameraDialog
import com.serenegiant.usb.CameraDialog.CameraDialogParent
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener
import com.serenegiant.usb.USBMonitor.UsbControlBlock
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usbcameracommon.UVCCameraHandlerMultiSurface
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : BaseActivity(), CameraDialogParent {
    /**
     * for accessing USB
     */
    private var mUSBMonitor: USBMonitor? = null

    /**
     * Handler to execute camera related methods sequentially on private thread
     */
    private var mCameraHandler: UVCCameraHandlerMultiSurface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DEBUG) Log.v(TAG, "onCreate:")
        setContentView(R.layout.activity_main)
        camera_button.setOnCheckedChangeListener(mOnCheckedChangeListener)
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

    private val mOnCheckedChangeListener = CompoundButton.OnCheckedChangeListener { compoundButton, isChecked ->
        when (compoundButton.id) {
            R.id.camera_button -> if (isChecked && !mCameraHandler!!.isOpened) {
                CameraDialog.showDialog(this@MainActivity)
            } else {
                stopPreview()
            }
        }
    }

    private fun setCameraButton(isOn: Boolean) {
        if (DEBUG) Log.v(TAG, "setCameraButton:isOn=$isOn")
        runOnUiThread({
            try {
                camera_button.setOnCheckedChangeListener(null)
                camera_button.isChecked = isOn
            } finally {
                camera_button.setOnCheckedChangeListener(mOnCheckedChangeListener)
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
            } catch (e: Exception) {
                Log.w(TAG, e)
            }
        }
        updateItems()
    }

    private fun stopPreview() {
        if (DEBUG) Log.v(TAG, "stopPreview:")
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
        get() = mCameraHandler != null && mCameraHandler!!.isOpened

    private fun updateItems() {
        runOnUiThread(mUpdateItemsOnUITask, 100)
    }

    private val mUpdateItemsOnUITask = Runnable {
        if (isFinishing) return@Runnable
        if (isActive) View.VISIBLE else View.INVISIBLE
    }

    private val mCPUMonitorTask: Runnable = object : Runnable {
        override fun run() {
            queueEvent(this, 1000)
        }
    }

    private val mFpsTask: Runnable = object : Runnable {
        override fun run() {
            runOnUiThread(this, 1000)
        }
    }

    //================================================================================

    companion object {
        private const val DEBUG = true // TODO set false on release
        private const val TAG = "MainActivity"

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
    }
}