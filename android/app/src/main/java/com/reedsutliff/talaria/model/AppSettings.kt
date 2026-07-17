package com.reedsutliff.talaria.model

import com.reedsutliff.talaria.BuildConfig

data class AppSettings(
    val serverUrl: String = BuildConfig.TALARIA_WS_URL,
    val cfClientId: String = BuildConfig.CF_ACCESS_CLIENT_ID,
    val cfClientSecret: String = BuildConfig.CF_ACCESS_CLIENT_SECRET,
    val lastThreadId: String? = null,
    val sttEnabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    val selectedProfile: String = "default",
    val playbackSpeed: Float = 1.0f,
)
