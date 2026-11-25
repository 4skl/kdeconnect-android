/*
 * SPDX-FileCopyrightText: 2024 Your Name <your.email@example.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mousepad

import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.GestureDetectorCompat
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.base.BaseActivity
import org.kde.kdeconnect.extensions.viewBinding
import org.kde.kdeconnect.ui.PermissionsAlertDialogFragment
import org.kde.kdeconnect.ui.compose.components.addDeviceDisconnectedOverlay
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityScreenShareBinding
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.View
import java.util.Base64

class ScreenShareActivity : BaseActivity<ActivityScreenShareBinding>() {

    private lateinit var plugin: MousePadPlugin
    private var screenBitmap: Bitmap? = null
    private var zoomLevel = 1.0f
    private var panX = 0f
    private var panY = 0f
    private var isScaling = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    
    // Gesture detectors for zoom and pan
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var scaleGestureDetector
    
    override val binding: ActivityScreenShareBinding by viewBinding(ActivityScreenShareBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        if (deviceId == null) {
            Log.e("ScreenShareActivity", "No device ID provided")
            finish()
            return
        }
        
        plugin = KdeConnect.getInstance().getDevicePlugin<MousePadPlugin>(deviceId)
            ?: return finish()
            
        binding.root.addDeviceDisconnectedOverlay(plugin.device)

        // Register callback with the plugin to receive screen updates
        plugin.setScreenShareActivityCallback { bitmap ->
            updateScreenImage(bitmap)
        }

        // Initialize gesture detectors
        initializeGestures()

        // Set up UI controls
        setupUIControls()
        
        // Start screen sharing mode
        startScreenSharing()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister callback when activity is destroyed
        plugin.clearScreenShareActivityCallback()
    }

    private fun initializeGestures() {
        // Create gesture detector for handling taps and other gestures
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Send pointer click to computer
                sendPointerClick(e.x, e.y)
                return true
            }
            
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Handle double tap if needed
                return true
            }
            
            override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                // Handle panning
                panX -= distanceX
                panY -= distanceY
                updateScreenDisplay()
                return true
            }
        })
        
        // Create scale gesture detector for zooming
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                val scaleFactor = detector.scaleFactor
                zoomLevel *= scaleFactor
                
                // Limit zoom levels
                zoomLevel = Math.max(0.1f, Math.min(zoomLevel, 5.0f))
                
                updateScreenDisplay()
                return true
            }
        })
    }

    private fun setupUIControls() {
        binding.zoomInButton.setOnClickListener { 
            zoomLevel *= 1.2f
            zoomLevel = Math.max(0.1f, Math.min(zoomLevel, 5.0f))
            updateScreenDisplay()
        }
        
        binding.zoomOutButton.setOnClickListener { 
            zoomLevel *= 0.8f
            zoomLevel = Math.max(0.1f, Math.min(zoomLevel, 5.0f))
            updateScreenDisplay()
        }
        
        binding.resetZoomButton.setOnClickListener { 
            zoomLevel = 1.0f
            panX = 0f
            panY = 0f
            updateScreenDisplay()
        }
    }

    private fun startScreenSharing() {
        // Send a packet to the computer to indicate we're starting screen sharing
        val np = plugin.device.createPacket("kdeconnect.presenter")
        np["startScreenShare"] = true
        plugin.device.sendPacket(np)
    }

    private fun sendPointerClick(x: Float, y: Float) {
        // Send pointer click position to the computer
        val np = plugin.device.createPacket("kdeconnect.presenter")
        np["pointerPosition"] = true
        np["x"] = x.toDouble()
        np["y"] = y.toDouble()
        plugin.device.sendPacket(np)
    }

    fun updateScreenImage(bitmap: Bitmap) {
        this.screenBitmap = bitmap
        updateScreenDisplay()
    }

    private fun updateScreenDisplay() {
        if (screenBitmap == null) return
        
        val imageView = binding.screenDisplay
        val matrix = Matrix()
        
        // Apply zoom and pan transformations
        matrix.postScale(zoomLevel, zoomLevel)
        matrix.postTranslate(panX, panY)
        
        imageView.setImageBitmap(screenBitmap)
        imageView.imageMatrix = matrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Handle touch events for panning and scaling
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isScaling = false
            }
            MotionEvent.ACTION_UP -> {
                isScaling = false
            }
        }
        
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        
        // Handle panning with touch drag
        if (event.action == MotionEvent.ACTION_MOVE && !isScaling) {
            val deltaX = event.x - lastTouchX
            val deltaY = event.y - lastTouchY
            
            panX += deltaX
            panY += deltaY
            
            lastTouchX = event.x
            lastTouchY = event.y
            
            updateScreenDisplay()
        }
        
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        // Stop screen sharing when going back
        val np = plugin.device.createPacket("kdeconnect.presenter")
        np["stopScreenShare"] = true
        plugin.device.sendPacket(np)
        
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
    }
}