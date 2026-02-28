package com.example.meroscreenmirror

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var projectionManager: MediaProjectionManager
    private var pendingRes: String = "720"

    companion object {
        const val ACTION_STATUS = "com.example.meroscreenmirror.STATUS"
        const val EXTRA_STATUS = "status"
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(EXTRA_STATUS) ?: return
            runOnUiThread {
                webView.evaluateJavascript("setStatus('$status')", null)
            }
        }
    }

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_RESOLUTION, pendingRes)
            }
            startForegroundService(serviceIntent)
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun startCapture(res: String) {
            pendingRes = res
            runOnUiThread {
                launcher.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        @JavascriptInterface
        fun stopCapture() {
            stopService(Intent(this@MainActivity, ScreenCaptureService::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            addJavascriptInterface(AndroidBridge(), "Android")
            loadUrl("file:///android_asset/android_ui.html")
        }
        setContentView(webView)

        ContextCompat.registerReceiver(this, statusReceiver, IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
    }
}
