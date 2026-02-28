package com.example.meroscreenmirror

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.OutputStream
import java.net.Socket

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var socket: Socket? = null
    private var controlSocket: Socket? = null

    @Volatile private var restarting = false
    @Volatile private var currentWidth = 1280
    @Volatile private var currentHeight = 720
    @Volatile private var running = false

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "ScreenMirrorChannel"
        const val VIDEO_PORT = 15557
        const val CONTROL_PORT = 15558
        const val HTTP_PORT = 8080
        const val HOST = "127.0.0.1"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Mirror")
            .setContentText("Streaming screen...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        startForeground(1, notification)

        val resultCode = intent!!.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)!!

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                virtualDisplay?.release()
                try { mediaCodec?.stop() } catch (e: Exception) {}
                try { mediaCodec?.release() } catch (e: Exception) {}
                stopSelf()
            }
        }, null)

        Thread { startStreaming() }.start()

        return START_NOT_STICKY
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(MainActivity.ACTION_STATUS).apply {
            putExtra(MainActivity.EXTRA_STATUS, status)
            setPackage(packageName)
        })
    }

    private fun startStreaming() {
        fun connectVideo(): OutputStream {
            while (true) {
                try {
                    socket = Socket(HOST, VIDEO_PORT)
                    Log.d("ScreenCapture", "Video connected to $HOST:$VIDEO_PORT")
                    return socket!!.getOutputStream()
                } catch (e: Exception) {
                    Log.d("ScreenCapture", "Video connect failed: ${e.message}, retrying...")
                    Thread.sleep(500)
                }
            }
        }

        fun connectControl(): Socket {
            while (true) {
                try {
                    val s = Socket(HOST, CONTROL_PORT)
                    Log.d("ScreenCapture", "Control connected on port $CONTROL_PORT")
                    return s
                } catch (e: Exception) {
                    Log.d("ScreenCapture", "Control connect failed, retrying...")
                    Thread.sleep(500)
                }
            }
        }

        val videoOut = connectVideo()
        controlSocket = connectControl()

        val controlReader = controlSocket!!.getInputStream().bufferedReader()
        val resLine = try { controlReader.readLine() } catch (e: Exception) { null }
        if (resLine != null) {
            try {
                val j = org.json.JSONObject(resLine)
                currentWidth = j.getInt("width")
                currentHeight = j.getInt("height")
                Log.d("ScreenCapture", "Initial resolution: ${currentWidth}x${currentHeight}")
            } catch (e: Exception) {
                Log.e("ScreenCapture", "Failed to parse initial resolution: ${e.message}")
            }
        }

        val width = currentWidth
        val height = currentHeight

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                val bitrate = when { width >= 1920 -> 4_000_000; width >= 1280 -> 2_000_000; else -> 1_000_000 }
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val inputSurface = mediaCodec!!.createInputSurface()
            mediaCodec!!.start()
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ScreenMirror", width, height, 320,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Codec setup failed: ${e.message}")
            return
        }

        broadcastStatus("streaming")
        running = true

        Thread {
            try {
                while (running) {
                    val line = controlReader.readLine() ?: break
                    Log.d("ScreenCapture", "Control message: $line")
                    try {
                        val j = org.json.JSONObject(line)
                        val newWidth = j.getInt("width")
                        val newHeight = j.getInt("height")
                        if (newWidth != currentWidth || newHeight != currentHeight) {
                            restartCodec(newWidth, newHeight)
                        }
                    } catch (e: Exception) {
                        Log.e("ScreenCapture", "Config parse error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.d("ScreenCapture", "Control thread ended: ${e.message}")
            }
        }.start()

        val bufferInfo = MediaCodec.BufferInfo()
        var out = videoOut

        while (running) {
            if (restarting) { Thread.sleep(50); continue }
            val index = try {
                mediaCodec!!.dequeueOutputBuffer(bufferInfo, 10000)
            } catch (e: Exception) {
                break
            }
            if (index >= 0) {
                val buffer = mediaCodec!!.getOutputBuffer(index)!!
                val data = ByteArray(bufferInfo.size)
                buffer.get(data)
                try {
                    out.write(data)
                    out.flush()
                } catch (e: Exception) {
                    Log.e("ScreenCapture", "Write failed, reconnecting: ${e.message}")
                    out = connectVideo()
                }
                mediaCodec!!.releaseOutputBuffer(index, false)
            }
        }
    }

    private fun restartCodec(width: Int, height: Int) {
        restarting = true
        Thread.sleep(100)

        try { mediaCodec?.stop() } catch (e: Exception) {}
        try { mediaCodec?.release() } catch (e: Exception) {}
        mediaCodec = null

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            val bitrate = when { width >= 1920 -> 4_000_000; width >= 1280 -> 2_000_000; else -> 1_000_000 }
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val inputSurface = mediaCodec!!.createInputSurface()
        mediaCodec!!.start()
        virtualDisplay!!.resize(width, height, 320)
        virtualDisplay!!.surface = inputSurface

        currentWidth = width
        currentHeight = height
        restarting = false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Screen Mirror", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        try { mediaCodec?.stop() } catch (e: Exception) {}
        try { mediaCodec?.release() } catch (e: Exception) {}
        mediaCodec = null
        try { virtualDisplay?.release() } catch (e: Exception) {}
        virtualDisplay = null
        try { mediaProjection?.stop() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
        try { controlSocket?.close() } catch (e: Exception) {}
        broadcastStatus("idle")
    }
}
