package com.reedsutliff.protoapp

import android.app.Application
import android.util.Log

/**
 * PROTO Android client.
 *
 * CF token strategy: bundled at build time via BuildConfig.CF_ACCESS_CLIENT_ID /
 * CF_ACCESS_CLIENT_SECRET (CI -P flags). Scoped to proto.ffilt.us only — the
 * dedicated Cloudflare Access app rejects the token on any other hostname.
 */
class ProtoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Telemetry.send("proto-android ${BuildConfig.VERSION_NAME} app_launch")
        Log.i("ProtoApp", "launched v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH})")
    }
}
