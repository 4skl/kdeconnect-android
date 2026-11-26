/*
 * SPDX-FileCopyrightText: 2014 Ahmed I. Khalil <ahmedibrahimkhali@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mousepad

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

@LoadablePlugin
class MousePadPlugin : Plugin() {
    var isKeyboardEnabled: Boolean = true
        private set

    private var screenShareStateCallback: ((Boolean) -> Unit)? = null
    private var renderSurface: Surface? = null
    private var pendingStreamConfig: StreamConfig? = null

    private val streamRunning = AtomicBoolean(false)
    private var streamThread: Thread? = null
    private var activeSocket: Socket? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var pendingDx: Float = 0f
    private var pendingDy: Float = 0f
    private var pendingInMm: Boolean = false
    private var deltaFlushScheduled: Boolean = false

    private data class StreamConfig(
        val host: String,
        val port: Int,
        val width: Int,
        val height: Int
    )

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
            stopStreamDecoder()
            mainHandler.post { screenShareStateCallback?.invoke(false) }
            return
        }

        if (packet.getBoolean("startScreenShare", false)) {
            mainHandler.post { screenShareStateCallback?.invoke(true) }

            val host = packet.getString("streamHost", "")
            val port = packet.getInt("streamPort", 0)
            val width = packet.getInt("width", 640)
            val height = packet.getInt("height", 480)

            if (host.isNotEmpty() && port > 0) {
                val cfg = StreamConfig(host, port, width, height)
                pendingStreamConfig = cfg
                renderSurface?.let { surface ->
                    if (surface.isValid) startStreamDecoder(cfg, surface)
                }
            }
        }
    }

    fun setScreenShareCallbacks(onStateChanged: (Boolean) -> Unit) {
        this.screenShareStateCallback = onStateChanged
    }

    fun setRenderSurface(surface: Surface?) {
        this.renderSurface = surface
        if (surface != null && surface.isValid) {
            pendingStreamConfig?.let { startStreamDecoder(it, surface) }
        } else {
            stopStreamDecoder()
        }
    }

    fun clearScreenShareCallbacks() {
        this.screenShareStateCallback = null
        this.renderSurface = null
        this.pendingStreamConfig = null
        stopStreamDecoder()
    }

    private fun startStreamDecoder(cfg: StreamConfig, surface: Surface) {
        stopStreamDecoder()
        streamRunning.set(true)

        streamThread = Thread({
            var socket: Socket? = null
            var codec: MediaCodec? = null
            try {
                socket = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(cfg.host, cfg.port), 3000)
                }
                activeSocket = socket

                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    cfg.width,
                    cfg.height
                ).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                }

                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec.configure(format, surface, null, 0)
                codec.start()

                val dis = DataInputStream(socket.getInputStream())
                val bufferInfo = MediaCodec.BufferInfo()

                while (streamRunning.get()) {
                    val frameLen = dis.readInt()
                    if (frameLen <= 0 || frameLen > 2_000_000) break

                    val frameBytes = ByteArray(frameLen)
                    dis.readFully(frameBytes)

                    var queued = false
                    while (streamRunning.get() && !queued) {
                        val inIdx = codec.dequeueInputBuffer(5_000)
                        if (inIdx >= 0) {
                            codec.getInputBuffer(inIdx)?.apply {
                                clear()
                                put(frameBytes)
                            }
                            codec.queueInputBuffer(inIdx, 0, frameLen, System.nanoTime() / 1000L, 0)
                            queued = true
                        }
                        while (true) {
                            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
                            if (outIdx >= 0) {
                                codec.releaseOutputBuffer(outIdx, true)
                            } else {
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (streamRunning.get()) {
                    Log.e("MousePadPlugin", "H264 stream error", e)
                }
            } finally {
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
                if (activeSocket === socket) activeSocket = null
            }
        }, "ScreenShareH264Decoder").apply { start() }
    }

    private fun stopStreamDecoder() {
        streamRunning.set(false)
        try { activeSocket?.close() } catch (_: Exception) {}
        activeSocket = null
        streamThread?.interrupt()
        streamThread = null
    }

    fun startScreenShare(viewWidthMm: Float, viewHeightMm: Float, viewWidthPx: Int, viewHeightPx: Int) {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_SCREENSHARE)
        np["startScreenShare"] = true
        np["viewWidthMm"] = viewWidthMm.toDouble()
        np["viewHeightMm"] = viewHeightMm.toDouble()
        np["viewWidthPx"] = viewWidthPx
        np["viewHeightPx"] = viewHeightPx
        sendPacket(np)
    }

    fun stopScreenShare() {
        pendingStreamConfig = null
        stopStreamDecoder()
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_SCREENSHARE)
        np["stopScreenShare"] = true
        sendPacket(np)
    }

    fun switchScreen(viewWidthMm: Float, viewHeightMm: Float, viewWidthPx: Int, viewHeightPx: Int) {
        stopStreamDecoder()
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_SCREENSHARE)
        np["switchScreen"] = true
        np["viewWidthMm"] = viewWidthMm.toDouble()
        np["viewHeightMm"] = viewHeightMm.toDouble()
        np["viewWidthPx"] = viewWidthPx
        np["viewHeightPx"] = viewHeightPx
        sendPacket(np)
    }

    fun sendScreenShareViewport(viewWidthMm: Float, viewHeightMm: Float, viewWidthPx: Int, viewHeightPx: Int) {
        if (!device.isReachable) return
        val np = NetworkPacket(PACKET_TYPE_SCREENSHARE)
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

    fun hasMicPermission(): Boolean = isPermissionGranted(Manifest.permission.RECORD_AUDIO)

    fun sendLeftClick() {
        if (!device.isReachable) return
        sendPacket(NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST).apply { this["singleclick"] = true })
    }

    fun sendDoubleClick() {
        if (!device.isReachable) return
        sendPacket(NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST).apply { this["doubleclick"] = true })
    }

    fun sendMiddleClick() {
        if (!device.isReachable) return
        sendPacket(NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST).apply { this["middleclick"] = true })
    }

    fun sendRightClick() {
        if (!device.isReachable) return
        sendPacket(NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST).apply { this["rightclick"] = true })
    }

    fun sendSingleHold() {
        if (!device.isReachable) return
        sendPacket(NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST).apply { this["singlehold"] = true })
    }

    fun sendSingleRelease() {
        if (!device.isReachable) return
        sendPacket(NetworkPacket(PACKET_TYPE_MOUSEPAD_REQUEST).apply { this["singlerelease"] = true })
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