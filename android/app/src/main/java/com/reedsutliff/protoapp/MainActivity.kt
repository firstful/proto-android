package com.reedsutliff.protoapp

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // WebSockets (proto broker live stream) ride the WebView network stack.
            addJavascriptInterface(CfAccessJsBridge(), "CfAccessJsBridge")
            webViewClient = CfWebViewClient(Shim.SCRIPT)
        }
        setContentView(web)
        web.loadUrl(BuildConfig.PROTO_UI_URL)
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.apply {
            loadUrl("about:blank")
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }
}
