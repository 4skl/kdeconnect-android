/*
 * SPDX-FileCopyrightText: 2014 Ahmed I. Khalil <ahmedibrahimkhali@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mousepad

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@LoadablePlugin
class MousePadPlugin : Plugin() {
    var isKeyboardEnabled: Boolean = true
        private set

    private var screenShareFrameCallback: ((Bitmap) -> Unit)? = null
    private var screenShareStateCallback: ((Boolean) -> Unit)? = null

    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private val latestFramePacket = AtomicReference<NetworkPacket?>(null)
    private val isDecoding = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pendingDx: Float = 0f
    private var pendingDy: Float = 0f
    private var pendingInMm: Boolean = false
    private var deltaFlushScheduled: Boolean = false

    private val flushDeltaRunnable = Runnable {
        deltaFlushScheduled = false
        val dx = pendingDx
        val dy = pendingDy
        val inMm = pendingInMm
        pendingDx = 0f
        pendingDy = 0f

        if ((dx != 0f || dy != 0f) && device.isReachable) {
            val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
            np["dx"] = dx.toDouble()
            np["dy"] = dy.toDouble()
            if (inMm) np["deltaInMm"] = true
            sendPacket(np)
        }
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type == PACKET_TYPE_SCREENSHARE) {
            handleScreenSharePacket(np)
            return true
        }

        this.isKeyboardEnabled = np.getBoolean("state", true)
        return true
    }

    private fun handleScreenSharePacket(packet: NetworkPacket) {
        if (packet.getBoolean("stopScreenShare", false)) {
            mainHandler.post { screenShareStateCallback?.invoke(false) }
            return
        }
        if (packet.getBoolean("startScreenShare", false)) {
            mainHandler.post { screenShareStateCallback?.invoke(true) }
            if (!packet.contains("screenData") && packet.payload == null) return
        }

        if (screenShareFrameCallback == null) return

        latestFramePacket.set(packet)
        if (isDecoding.compareAndSet(false, true)) {
            decodeExecutor.execute(::drainDecodeLoop)
        }
    }

    private fun drainDecodeLoop() {
        try {
            while (true) {
                val packet = latestFramePacket.getAndSet(null) ?: break
                if (screenShareFrameCallback == null) continue

                try {
                    val inlineStream = packet.payload?.inputStream
                    val inlineB64 = if (packet.contains("screenData")) packet.getString("screenData") else null

                    val bytes: ByteArray? = when {
                        inlineStream != null -> inlineStream.use { it.readBytes() }
                        !inlineB64.isNullOrEmpty() -> Base64.decode(inlineB64, Base64.DEFAULT)
                        else -> null
                    }

                    if (bytes != null && bytes.isNotEmpty()) {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            mainHandler.post {
                                screenShareFrameCallback?.invoke(bitmap)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MousePadPlugin", "Error decoding screen frame", e)
                }
            }
        } finally {
            isDecoding.set(false)
            if (latestFramePacket.get() != null && isDecoding.compareAndSet(false, true)) {
                decodeExecutor.execute(::drainDecodeLoop)
            }
        }
    }

    fun setScreenShareCallbacks(
        onFrame: (Bitmap) -> Unit,
        onStateChanged: (Boolean) -> Unit
    ) {
        this.screenShareFrameCallback = onFrame
        this.screenShareStateCallback = onStateChanged
    }

    fun clearScreenShareCallbacks() {
        this.screenShareFrameCallback = null
        this.screenShareStateCallback = null
        this.latestFramePacket.set(null)
    }

    fun startScreenShare(
        viewWidthMm: Float,
        viewHeightMm: Float,
        viewWidthPx: Int,
        viewHeightPx: Int
    ) {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_SCREENSHARE)
        np["startScreenShare"] = true
        np["viewWidthMm"] = viewWidthMm.toDouble()
        np["viewHeightMm"] = viewHeightMm.toDouble()
        np["viewWidthPx"] = viewWidthPx
        np["viewHeightPx"] = viewHeightPx
        sendPacket(np)
    }

    fun sendScreenShareViewport(
        viewWidthMm: Float,
        viewHeightMm: Float,
        viewWidthPx: Int,
        viewHeightPx: Int
    ) {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_SCREENSHARE)
        np["viewWidthMm"] = viewWidthMm.toDouble()
        np["viewHeightMm"] = viewHeightMm.toDouble()
        np["viewWidthPx"] = viewWidthPx
        np["viewHeightPx"] = viewHeightPx
        sendPacket(np)
    }

    fun stopScreenShare() {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_SCREENSHARE)
        np["stopScreenShare"] = true
        sendPacket(np)
    }

    fun switchScreen(viewWidthMm: Float, viewHeightMm: Float, viewWidthPx: Int, viewHeightPx: Int) {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_SCREENSHARE)
        np["switchScreen"] = true
        np["viewWidthMm"] = viewWidthMm.toDouble()
        np["viewHeightMm"] = viewHeightMm.toDouble()
        np["viewWidthPx"] = viewWidthPx
        np["viewHeightPx"] = viewHeightPx
        sendPacket(np)
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
            listOf(mouseAndKeyboardInput)
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
        if (!device.isReachable) return
        if (pendingInMm) flushDeltaRunnable.run()
        pendingInMm = false
        pendingDx += dx
        pendingDy += dy
        if (!deltaFlushScheduled) {
            deltaFlushScheduled = true
            mainHandler.postDelayed(flushDeltaRunnable, 16L)
        }
    }

    fun sendMouseDeltaMm(dxMm: Float, dyMm: Float) {
        if (!device.isReachable) return
        if (!pendingInMm && (pendingDx != 0f || pendingDy != 0f)) flushDeltaRunnable.run()
        pendingInMm = true
        pendingDx += dxMm
        pendingDy += dyMm
        if (!deltaFlushScheduled) {
            deltaFlushScheduled = true
            mainHandler.postDelayed(flushDeltaRunnable, 16L)
        }
    }

    fun hasMicPermission(): Boolean {
        return isPermissionGranted(Manifest.permission.RECORD_AUDIO)
    }

    fun sendLeftClick() {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["singleclick"] = true
        sendPacket(np)
    }

    fun sendDoubleClick() {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["doubleclick"] = true
        sendPacket(np)
    }

    fun sendMiddleClick() {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["middleclick"] = true
        sendPacket(np)
    }

    fun sendRightClick() {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["rightclick"] = true
        sendPacket(np)
    }

    fun sendSingleHold() {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["singlehold"] = true
        sendPacket(np)
    }

    fun sendSingleRelease() {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["singlerelease"] = true
        sendPacket(np)
    }

    fun sendScroll(dx: Double, dy: Double) {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["scroll"] = true
        np["dx"] = dx
        np["dy"] = dy
        sendPacket(np)
    }

    fun sendLeft() {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_DPAD_LEFT)
        sendPacket(np)
    }

    fun sendRight() {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_DPAD_RIGHT)
        sendPacket(np)
    }

    fun sendUp() {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_DPAD_UP)
        sendPacket(np)
    }

    fun sendDown() {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_DPAD_DOWN)
        sendPacket(np)
    }

    fun sendSelect() {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_ENTER)
        sendPacket(np)
    }

    fun sendHome() {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["super"] = true
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_META_LEFT)
        device.sendPacket(np)
    }

    fun sendBack() {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_ESCAPE)
        device.sendPacket(np)
    }

    fun sendClose() {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["alt"] = true
        np["specialKey"] = KeyListenerView.SpecialKeysMap.get(KeyEvent.KEYCODE_F4)
        device.sendPacket(np)
    }

    fun sendText(content: String) {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST)
        np["key"] = content
        sendPacket(np)
    }

    fun sendPacket(np: NetworkPacket) {
        if (!device.isReachable) return
        device.sendPacket(np)
    }

    override val supportedPacketTypes = arrayOf(PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE, PACKET_TYPE_SCREENSHARE)
    override val outgoingPacketTypes = arrayOf(PACKET_TYPE_MOUSEPAD_REQUEST, PACKET_TYPE_SCREENSHARE)

    companion object {
        const val PACKET_TYPE_MOUSEPAD_REQUEST: String = "kdeconnect.mousepad.request"
        private const val PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE = "kdeconnect.mousepad.keyboardstate"
        const val PACKET_TYPE_SCREENSHARE: String = "kdeconnect.mousepad.screenshare"
    }
}