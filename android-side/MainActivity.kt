package com.example.meroscreenmirror

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var prefs: SharedPreferences
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(250, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(250, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    companion object {
        const val ACTION_STATUS = "com.example.meroscreenmirror.STATUS"
        const val EXTRA_STATUS = "status"
        const val PREFS_NAME = "screen_mirror_prefs"
        const val KEY_DEVICES = "paired_devices"
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
            }
            startForegroundService(serviceIntent)
        }
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun startCapture() {
            runOnUiThread {
                launcher.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        @JavascriptInterface
        fun stopCapture() {
            stopService(Intent(this@MainActivity, ScreenCaptureService::class.java))
        }

        @JavascriptInterface
        fun getPairedDevices(): String {
            val stored = prefs.getString(KEY_DEVICES, "[]") ?: "[]"
            val start = System.currentTimeMillis()
            return try {
                val request = Request.Builder()
                    .url("http://127.0.0.1:${ScreenCaptureService.HTTP_PORT}/api/devices")
                    .build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: stored
                prefs.edit().putString(KEY_DEVICES, body).apply()
                body
            } catch (e: Exception) {
                val arr = JSONArray(stored)
                for (i in 0 until arr.length()) arr.getJSONObject(i).put("online", false)
                arr.toString()
            }
        }

        @JavascriptInterface
        fun confirmPair(code: String, name: String): String {
            // Send pair request to PC via HTTP (through ADB tunnel)
            return try {
                val deviceName = name.ifEmpty { android.os.Build.MODEL }
                val url = "http://127.0.0.1:${ScreenCaptureService.HTTP_PORT}/api/pair/confirm?code=$code&name=${java.net.URLEncoder.encode(deviceName, "UTF-8")}"
                val request = Request.Builder().url(url).post(FormBody.Builder().build()).build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: "{\"ok\":false,\"msg\":\"no response\"}"
                val json = JSONObject(body)
                if (json.optBoolean("ok")) {
                    // Save paired device
                    savePairedDevice(json.optString("id"), json.optString("name", deviceName))
                }
                body
            } catch (e: Exception) {
                "{\"ok\":false,\"msg\":\"${e.message}\"}"
            }
        }
    }

    private fun savePairedDevice(id: String, name: String) {
        val stored = prefs.getString(KEY_DEVICES, "[]") ?: "[]"
        val arr = JSONArray(stored)
        val obj = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("online", false)
        }
        arr.put(obj)
        prefs.edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            addJavascriptInterface(AndroidBridge(), "Android")
            loadUrl("file:///android_asset/android_ui.html")
        }
        setContentView(webView)

        ContextCompat.registerReceiver(
            this, statusReceiver,
            IntentFilter(ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
    }
}
