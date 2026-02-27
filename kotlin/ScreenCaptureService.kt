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
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.OutputStream
import java.net.Socket

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var socket: Socket? = null

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
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

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                virtualDisplay?.release()
                mediaCodec?.stop()
                mediaCodec?.release()
                stopSelf()
            }
        }, null)

        Thread { startStreaming() }.start()

        return START_NOT_STICKY
    }

    private fun startStreaming() {
        val width = 1280
        val height = 720
        val dpi = 320

        // Setup codec first
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
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

        // Connect with retry
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
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val index = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 10000)
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

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Screen Mirror", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mediaCodec?.stop()
        mediaCodec?.release()
        virtualDisplay?.release()
        mediaProjection?.stop()
        socket?.close()
    }
}