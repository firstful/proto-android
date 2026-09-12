package com.reedsutliff.protoapp

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * Cloudflare Access service-token plumbing for the WebView client.
 *
 * Every HTTP surface gets CF-Access-Client-Id/Secret headers, only for
 * *.ffilt.us hosts (never for third-party content):
 *  - WebView page loads + GET/HEAD resources: shouldInterceptRequest re-issue
 *  - webapp XHR/fetch (incl. POST bodies — NOT interceptable in WebView):
 *    injected JS shim reads headers from the CfAccessJsBridge and patches
 *    window.fetch + XMLHttpRequest
 */
object CfAccess {
    val CLIENT_ID: String = BuildConfig.CF_ACCESS_CLIENT_ID
    val CLIENT_SECRET: String = BuildConfig.CF_ACCESS_CLIENT_SECRET

    fun isFfilt(host: String?): Boolean = host != null && host.endsWith(".ffilt.us")

    fun headersJson(): String = JSONObject().apply {
        put("CF-Access-Client-Id", CLIENT_ID)
        put("CF-Access-Client-Secret", CLIENT_SECRET)
    }.toString()
}

/** OkHttp interceptor adding the CF service-token pair to *.ffilt.us requests. */
class CfAccessInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val reqHost = req.url.host
        if (!CfAccess.isFfilt(reqHost) || CfAccess.CLIENT_ID.isBlank()) {
            return chain.proceed(req)
        }
        val withCf = req.newBuilder()
            .header("CF-Access-Client-Id", CfAccess.CLIENT_ID)
            .header("CF-Access-Client-Secret", CfAccess.CLIENT_SECRET)
            .build()
        return chain.proceed(withCf)
    }
}

// Shared OkHttp client (single instance — WebView interceptor + anything else).
object ProtoHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(CfAccessInterceptor())
        .build()
}

/**
 * CORS-free passthrough client for the webview's POST/PUT bodies:
 * the JS shim routes non-GET requests to the native bridge; each is issued
 * here through the header-injecting client and the raw body is returned.
 */
object NativeHttp {
    @JvmStatic
    fun fetch(url: String, method: String, headersJson: String, body: String?): String {
        val b = okhttp3.RequestBody.create(
            "application/json; charset=utf-8".toMediaTypeOrNull(),
            body?.toByteArray() ?: ByteArray(0)
        )
        val rb = Request.Builder().url(url).method(method, b)
        val hs = JSONObject(headersJson)
        val keys = hs.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            rb.header(k, hs.getString(k))
        }
        ProtoHttp.client.newCall(rb.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            return JSONObject()
                .put("status", resp.code)
                .put("body", text)
                .toString()
        }
    }

    @JvmStatic
    fun cfHeaders(): String = CfAccess.headersJson()
}

/** JS bridge the injected shim uses for *.ffilt.us requests WebView can't intercept. */
class CfAccessJsBridge {
    @android.webkit.JavascriptInterface
    fun cfHeaders(): String = CfAccess.headersJson()

    @android.webkit.JavascriptInterface
    fun fetch(url: String, method: String, headersJson: String, body: String?): String =
        NativeHttp.fetch(url, method, headersJson, body)
}

/**
 * WebViewClient that routes *.ffilt.us GET/HEAD loads through the CF-injected
 * OkHttp client. (POST bodies cannot be forwarded — handled by the JS shim.)
 */
class CfWebViewClient(private val shim: String) : WebViewClient() {
    private val ok = ProtoHttp.client

    override fun shouldInterceptRequest(
        view: WebView, request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url
        if (!CfAccess.isFfilt(url.host) || CfAccess.CLIENT_ID.isBlank()) return null
        val method = request.method
        if (method != "GET" && method != "HEAD") return null
        val rb = Request.Builder().url(url.toString()).method(method, null)
        for ((k, v) in request.requestHeaders) rb.header(k, v)
        rb.header("CF-Access-Client-Id", CfAccess.CLIENT_ID)
        rb.header("CF-Access-Client-Secret", CfAccess.CLIENT_SECRET)
        return try {
            ok.newCall(rb.build()).execute().use { resp ->
                val mime = resp.header("Content-Type")?.substringBefore(";")?.trim() ?: "text/html"
                val body = resp.body?.bytes() ?: ByteArray(0)
                WebResourceResponse(mime, "utf-8", ByteArrayInputStream(body))
                    .apply { responseHeaders = resp.headers.toMultimap().mapKeys { it.key.lowercase() }.filterKeys { it !in setOf("content-encoding", "transfer-encoding", "content-length") }.mapValues { it.value.joinToString(", ") } }
            }
        } catch (e: Exception) {
            null // let WebView handle failures natively
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        view.evaluateJavascript(Shim.SCRIPT, null)
    }
}

/** Injected once per page: patches fetch + XHR to route *ffilt.us calls via bridge. */
object Shim {
    val SCRIPT = """
        (function(){
          if (window.__protoCfShim) return;
          window.__protoCfShim = true;
          var bridge = window.CfAccessJsBridge;
          if (!bridge) return;
          var HDRS = JSON.parse(bridge.cfHeaders());
          function isFfilt(u){ try { return new URL(u, location.href).host.endsWith('.ffilt.us'); } catch(e){ return false; } }
          var of = window.fetch;
          window.fetch = function(input, init){
            init = init || {};
            var u = (input && input.url) ? input.url : ('' + input);
            if (isFfilt(u)) {
              init.headers = Object.assign({}, (input && input.headers) || {}, HDRS, init.headers || {});
              input = u;
            }
            return of.call(window, input, init);
          };
          var oo = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function(m, u){
            this.__pUrl = u;
            return oo.apply(this, arguments);
          };
          var os = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send = function(body){
            if (isFfilt(this.__pUrl)) {
              for (var k in HDRS) this.setRequestHeader(k, HDRS[k]);
            }
            return os.apply(this, arguments);
          };
        })();
    """.trimIndent()
}
