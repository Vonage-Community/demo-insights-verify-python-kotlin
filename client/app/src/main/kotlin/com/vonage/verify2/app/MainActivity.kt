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
        Log.d("MyApp", "App running with $BACKEND_URL with USE_MOCK_CLIENT AS: ${BuildConfig.USE_MOCK_CLIENT}")
        super.onCreate(savedInstanceState)
        VGCellularRequestClient.initializeSdk(this.applicationContext)

        val client: VerifyApiClient = if (BuildConfig.USE_MOCK_CLIENT) {
            MockVerifyApiClient(channel = "sms_otp", verified = true)
        } else {
            RealVerifyApiClient
        }

        setContent { VerifyApp(client = client) }
    }
}
