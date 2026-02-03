package com.example.androidvivepython.ui

import android.content.Intent
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.example.androidvivepython.service.AgentService
import com.example.androidvivepython.ui.WebAppBridge

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startForegroundService(Intent(this, AgentService::class.java))

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(WebAppBridge(this), "AndroidBridge")

        // Load local UI shell. It can call http://127.0.0.1:8765 once the service is running.
        webView.loadUrl("file:///android_asset/www/index.html")
    }
}
