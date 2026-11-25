/*
 * SPDX-FileCopyrightText: 2014 Ahmed I. Khalil <ahmedibrahimkhali@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mousepad

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.ZoomControls
import android.widget.TextView
import android.os.Bundle
import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.GestureDetectorCompat
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R
import java.io.ByteArrayOutputStream
import java.util.Base64

@LoadablePlugin
class MousePadPlugin : Plugin() {
    var isKeyboardEnabled: Boolean = true
        private set

    // Screen sharing variables
    private var isScreenSharing = false
    private var screenShareBitmap: Bitmap? = null
    private var zoomLevel = 1.0f
    private var panX = 0f
    private var panY = 0f
    private var gestureDetector: GestureDetectorCompat? = null
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var screenShareActivityCallback: ((Bitmap) -> Unit)? = null

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        this.isKeyboardEnabled = np.getBoolean("state", true)
        
        // Handle presenter packets for screen sharing
        if (np.type == "kdeconnect.presenter") {
            handlePresenterPacket(np)
            return true
        }
        
        return true
    }

    private fun handlePresenterPacket(packet: NetworkPacket) {
        if (packet.hasKey("screenData")) {
            // Handle screen data (image)
            val screenData = packet.getString("screenData")
            if (!screenData.isNullOrEmpty()) {
                try {
                    // Decode base64 encoded image data
                    val imageData = Base64.getDecoder().decode(screenData)
                    val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                    screenShareBitmap = bitmap
                    Log.d("MousePadPlugin", "Received screen data, updated bitmap")
                    // Notify the active ScreenShareActivity if it exists
                    screenShareActivityCallback?.let { callback ->
                        callback(bitmap)
                    }
                } catch (e: Exception) {
                    Log.e("MousePadPlugin", "Error decoding screen data", e)
                }
            }
        } else if (packet.hasKey("pointerPosition")) {
            // Handle pointer position updates
            val x = packet.getDouble("x", 0.0)
            val y = packet.getDouble("y", 0.0)
            Log.d("MousePadPlugin", "Pointer position update ($x, $y)")
        } else if (packet.hasKey("zoom")) {
            // Handle zoom changes
            val zoom = packet.getDouble("zoom", 1.0)
            this.zoomLevel = zoom.toFloat()
            Log.d("MousePadPlugin", "Zoom level updated to $zoomLevel")
        } else if (packet.hasKey("pan")) {
            // Handle panning
            val panX = packet.getDouble("panX", 0.0)
            val panY = packet.getDouble("panY", 0.0)
            this.panX = panX.toFloat()
            this.panY = panY.toFloat()
            Log.d("MousePadPlugin", "Pan updated to ($panX, $panY)")
        } else if (packet.hasKey("startScreenShare")) {
            // Start screen sharing mode
            startScreenSharing()
        } else if (packet.hasKey("stopScreenShare")) {
            // Stop screen sharing mode
            stopScreenSharing()
        }
    }

    private fun startScreenSharing() {
        isScreenSharing = true
        Log.d("MousePadPlugin", "Screen sharing started")
        // Send a response back to the computer that we've started screen sharing
        val np = NetworkPacket("kdeconnect.presenter")
        np["screenShareStarted"] = true
        device.sendPacket(np)
    }

    private fun stopScreenSharing() {
        isScreenSharing = false
        Log.d("MousePadPlugin", "Screen sharing stopped")
        // Send a response back to the computer that we've stopped screen sharing
        val np = NetworkPacket("kdeconnect.presenter")
        np["screenShareStopped"] = true
        device.sendPacket(np)
    }

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_mousepad)

    override fun getUiButtons(): List<PluginUiButton> {
        val mouseAndKeyboardInput = PluginUiButton(
            context.getString(R.string.open_mousepad),
            R.drawable.touchpad_plugin_action_24dp
        ) { parentActivity ->
            val intent = Intent(parentActivity, MousePadActivity::class.java)
            intent.putExtra(MousePadActivity.EXTRA_DEVICE_ID, device.deviceId)
            parentActivity.startActivity(intent)
        }
        
        // Add button for screen sharing
        val screenShareButton = PluginUiButton(
            context.getString(R.string.start_screen_share),
            R.drawable.screen_share_24px
        ) { parentActivity ->
            startScreenShareActivity(parentActivity)
        }
        
        return if (device.deviceType == DeviceType.TV) {
            val tvInput = PluginUiButton(
                context.getString(R.string.open_mousepad_tv),
                R.drawable.tv_remote_24px
            ) { parentActivity ->
                val intent = Intent(parentActivity, BigscreenActivity::class.java)
                intent.putExtra(BigscreenActivity.EXTRA_DEVICE_ID, device.deviceId)
                parentActivity.startActivity(intent)
            }
            if (preferences.getBoolean(context.getString(R.string.pref_bigscreen_hide_mouse_input), false)) {
                listOf(tvInput)
            } else {
                listOf(mouseAndKeyboardInput, tvInput)
            }
        } else {
            listOf(mouseAndKeyboardInput, screenShareButton)
        }
    }


    override val description: String
        get() = context.getString(R.string.pref_plugin_mousepad_desc_nontv)

    override val hasSettings: Boolean = true

    override val keepAliveWhenUnreachable: Boolean = true

    override val settingsScope = SettingsScope.APP_WIDE

    override fun getSettingsFragment(activity: Activity): PluginSettingsFragment {
        return if (device.deviceType == DeviceType.TV) {
            PluginSettingsFragment.newInstance(pluginKey, device.deviceId, R.xml.mousepadplugin_preferences, R.xml.mousepadplugin_preferences_tv)
        } else {
            PluginSettingsFragment.newInstance(pluginKey, device.deviceId, R.xml.mousepadplugin_preferences)
        }
    }

    fun sendMouseDelta(dx: Float, dy: Float) {
        if (!device.isReachable) return // Called continuously while moving, don't spam failures
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["dx"] = dx.toDouble()
        np["dy"] = dy.toDouble()
        sendPacket(np)
    }

    fun hasMicPermission(): Boolean {
        return isPermissionGranted(Manifest.permission.RECORD_AUDIO)
    }

    fun sendLeftClick() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["singleclick"] = true
        sendPacket(np)
    }

    fun sendDoubleClick() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["doubleclick"] = true
        sendPacket(np)
    }

    fun sendMiddleClick() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["middleclick"] = true
        sendPacket(np)
    }

    fun sendRightClick() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["rightclick"] = true
        sendPacket(np)
    }

    fun sendSingleHold() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["singlehold"] = true
        sendPacket(np)
    }

    fun sendSingleRelease() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["singlerelease"] = true
        sendPacket(np)
    }

    fun sendScroll(dx: Double, dy: Double) {
        if (!device.isReachable) return // Called continuously while scrolling, don't spam failures
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["scroll"] = true
        np["dx"] = dx
        np["dy"] = dy
        sendPacket(np)
    }

    fun sendLeft() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_DPAD_LEFT)
        sendPacket(np)
    }

    fun sendRight() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_DPAD_RIGHT)
        sendPacket(np)
    }

    fun sendUp() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_DPAD_UP)
        sendPacket(np)
    }

    fun sendDown() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_DPAD_DOWN)
        sendPacket(np)
    }

    fun sendSelect() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_ENTER)
        sendPacket(np)
    }

    fun sendHome() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["super"] = true // HACK to make it work on devices that don't support super as a special key
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_META_LEFT)
        device.sendPacket(np)
    }

    fun sendBack() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_ESCAPE)
        device.sendPacket(np)
    }

    fun sendClose() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["alt"] = true
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_F4)
        device.sendPacket(np)
    }

    fun sendText(content: String) {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["key"] = content
        sendPacket(np)
    }

    fun sendPacket(np: NetworkPacket) {
        device.sendPacket(np)
    }

    override val supportedPacketTypes = arrayOf(PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE, "kdeconnect.presenter")
    override val outgoingPacketTypes = arrayOf(PACKET_TYPE_MOUSEPAD_REQUEST, "kdeconnect.presenter")

    fun startScreenShareActivity(parentActivity: Activity) {
        val intent = Intent(parentActivity, ScreenShareActivity::class.java)
        intent.putExtra(ScreenShareActivity.EXTRA_DEVICE_ID, device.deviceId)
        parentActivity.startActivity(intent)
    }

    // Method to set callback for screen share activity updates
    fun setScreenShareActivityCallback(callback: (Bitmap) -> Unit) {
        this.screenShareActivityCallback = callback
    }

    // Method to clear callback when activity is destroyed
    fun clearScreenShareActivityCallback() {
        this.screenShareActivityCallback = null
    }

    // Method to get the current screen bitmap - for testing purposes or future implementation
    fun getCurrentScreenBitmap(): Bitmap? {
        return screenShareBitmap
    }

    companion object {
        const val PACKET_TYPE_MOUSEPAD_REQUEST: String = "kdeconnect.mousepad.request"
        private const val PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE = "kdeconnect.mousepad.keyboardstate"
    }
}
