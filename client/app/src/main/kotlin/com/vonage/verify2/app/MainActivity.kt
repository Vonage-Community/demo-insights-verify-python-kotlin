package com.vonage.verify2.app

import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.vonage.clientlibrary.VGCellularRequestClient

internal const val BACKEND_URL = BuildConfig.BACKEND_URL
internal const val DEFAULT_PHONE = BuildConfig.PHONE_NUMBER

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("MyApp", "App running with $BACKEND_URL")
        super.onCreate(savedInstanceState)
        VGCellularRequestClient.initializeSdk(this.applicationContext)

        val client: VerifyApiClient =
            RealVerifyApiClient

        setContent { VerifyApp(client = client) }
    }
}
