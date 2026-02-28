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
    private var initialWidth = 1280
    private var initialHeight = 720

    @Volatile private var restarting = false
    @Volatile private var currentWidth = 1280
    @Volatile private var currentHeight = 720
    @Volatile private var running = false

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_RESOLUTION = "resolution"
        const val CHANNEL_ID = "ScreenMirrorChannel"
        const val PORT = 15557
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

        val res = intent.getStringExtra(EXTRA_RESOLUTION) ?: "720"
        val (w, h) = when (res) {
            "1080" -> Pair(1920, 1080)
            "480" -> Pair(854, 480)
            else -> Pair(1280, 720)
        }
        initialWidth = w
        initialHeight = h

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
        val dpi = 320
        val width = initialWidth
        val height = initialHeight
        currentWidth = width
        currentHeight = height

        fun connect(): OutputStream {
            while (true) {
                try {
                    socket = Socket(HOST, PORT)
                    Log.d("ScreenCapture", "Connected to $HOST:$PORT")
                    return socket!!.getOutputStream()
                } catch (e: Exception) {
                    Log.d("ScreenCapture", "Connect failed, retrying...")
                    Thread.sleep(500)
                }
            }
        }

        var outputStream = connect()

        // Send resolution config to PC
        outputStream.write("{\"width\":$width,\"height\":$height}\n".toByteArray())
        outputStream.flush()

        // Setup codec
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
                "ScreenMirror", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Codec setup failed: ${e.message}")
            return
        }

        broadcastStatus("streaming")
        running = true

        // Listen for resolution changes from PC
        Thread {
            try {
                val reader = socket!!.getInputStream().bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
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
                Log.d("ScreenCapture", "Config thread ended: ${e.message}")
            }
        }.start()

        val bufferInfo = MediaCodec.BufferInfo()

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
                    outputStream.write(data)
                    outputStream.flush()
                } catch (e: Exception) {
                    Log.e("ScreenCapture", "Write failed, reconnecting: ${e.message}")
                    outputStream = connect()
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
        broadcastStatus("idle")
    }
}
