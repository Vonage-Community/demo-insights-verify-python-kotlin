package com.vonage.verify2.app

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vonage.clientlibrary.VGCellularRequestClient
import com.vonage.clientlibrary.VGCellularRequestParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private val httpClient = OkHttpClient()
private val gson = Gson()

// Helper to avoid repeating boilerplate on every request
private fun JsonObject.getString(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull }?.asString

private suspend fun post(url: String, body: Map<String, Any?>): JsonObject =
    withContext(Dispatchers.IO) {
        val requestBody = gson.toJson(body).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(requestBody).build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw IOException("HTTP ${response.code} - $errorBody")
        }

        val responseBody = response.body?.string() ?: throw IOException("Empty response body")
        gson.fromJson(responseBody, JsonObject::class.java)
    }
object RealVerifyApiClient : VerifyApiClient {
    override suspend fun startVerification(phone: String): StartVerificationResponse {
        val json = post("$BACKEND_URL/verification", mapOf("phone" to phone))
        Log.d("MyApp", "startVerification response: $json")

        val channel = json.getString("channel") ?: "sms_otp"
        Log.d("MyApp", "channel: $channel")

        val checkUrl = json.getString("check_url")

        val requestId = when (channel) {
            "email_stepup" -> null
            "silent_auth" -> json.getString("request_id") ?: ""
            else -> json.getString("request_id") ?: throw IOException("Missing request_id")
        }

        return StartVerificationResponse(requestId, checkUrl, channel)
    }


    override suspend fun checkSilentAuth(url: String): String = withContext(Dispatchers.IO) {
        val params = VGCellularRequestParameters(
            url = url,
            headers = mapOf(),
            queryParameters = mapOf(),
            maxRedirectCount = 10
        )

        Log.d("MyApp", "checking silent auth")

        val response = VGCellularRequestClient.getInstance()
            .startCellularGetRequest(params, false)

        val httpStatus = response.optInt("http_status", -1)
        val sdkError = response.optString("error", "")

        if (sdkError.isNotEmpty()) throw IOException("Silent Auth SDK error: $sdkError")
        if (httpStatus !in 200..299) {
            val rawBody = response.optString("response_raw_body", "")
            throw IOException("Silent Auth failed: HTTP $httpStatus - ${rawBody.take(200)}")
        }

        val code = response.optJSONObject("response_body")?.optString("code", null)
        if (code.isNullOrBlank()) throw IOException("Silent Auth response missing 'code'")

        code
    }

    override suspend fun startEmailVerification(phone: String): StartEmailVerificationResponse {
        val json = post("$BACKEND_URL/send-email-otp", mapOf("phone" to phone))
        val requestId = json.getString("request_id") ?: throw IOException("Missing request_id")
        return StartEmailVerificationResponse(requestId)
    }

    override suspend fun submitCode(requestId: String?, code: String): CheckCodeResponse {
        val json = post("$BACKEND_URL/check-code", mapOf("request_id" to requestId, "code" to code))
        Log.d("MyApp", "submitCode response: $json")
        return CheckCodeResponse(
            verified = json.get("verified")?.asBoolean ?: false,
            status = json.getString("status")
        )
    }
}
