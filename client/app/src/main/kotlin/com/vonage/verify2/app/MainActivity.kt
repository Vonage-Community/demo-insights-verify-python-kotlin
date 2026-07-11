package com.vonage.verify2.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vonage.clientlibrary.VGCellularRequestClient
import com.vonage.clientlibrary.VGCellularRequestParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private const val BACKEND_URL = BuildConfig.BACKEND_URL
private const val DEFAULT_PHONE = BuildConfig.PHONE_NUMBER

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Needed for Silent Auth cellular requests
        VGCellularRequestClient.initializeSdk(this.applicationContext)

        setContent { VerifyApp() }
    }
}

/**
 * UI State Machine
 */
private sealed class VerifyUiState {
    data object EnterPhone: VerifyUiState()
    data object Loading: VerifyUiState()
    data class EnterSms(val requestId: String?) : VerifyUiState()
    data class EnterEmailOtp(val requestId: String) : VerifyUiState()
    data class Verified(val method: String) : VerifyUiState()
    data class Error(val message: String) : VerifyUiState()
}

/**
 * Backend check-code response model
 */
private data class CheckCodeResponse(
    val verified: Boolean,
    val status: String?
)
/**
 * Backend /send-email-otp response model
 */
private data class StartEmailVerificationResponse(
    val requestId: String
)
/**
 * Backend /verification response model
 */
private data class StartVerificationResponse(
    val requestId: String?,
    val checkUrl: String?,
    val channel: String
)


@Composable
fun VerifyApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            VerificationScreen()
        }
    }
}

@Composable
fun VerificationScreen() {
    var phone by remember { mutableStateOf(DEFAULT_PHONE) }
    var smsCode by remember { mutableStateOf("") }

    var uiState by remember { mutableStateOf<VerifyUiState>(VerifyUiState.EnterPhone) }
    var statusMessage by remember { mutableStateOf("") }
    var emailCode by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Verify your phone",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            enabled = uiState !is VerifyUiState.Loading,
            label = { Text("Phone number (with country prefix)") },
            placeholder = { Text("+34600000000") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )

        // Show SMS input only if we are in EnterSms state
        val requestIdForSms = (uiState as? VerifyUiState.EnterSms)?.requestId
        if (requestIdForSms != null) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = smsCode,
                onValueChange = { smsCode = it },
                enabled = uiState !is VerifyUiState.Loading,
                label = { Text("SMS code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Show Email OTP input only if we are in EnterEmailOtp state
        val requestIdForEmail = (uiState as? VerifyUiState.EnterEmailOtp)?.requestId
        if (requestIdForEmail != null) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = emailCode,
                onValueChange = { emailCode = it },
                enabled = uiState !is VerifyUiState.Loading,
                label = { Text("Email verification code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (uiState) {
            is VerifyUiState.Loading -> {
                CircularProgressIndicator()
            }

            is VerifyUiState.EnterPhone,
            is VerifyUiState.Error -> {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = phone.isNotBlank(),
                    onClick = {
                        scope.launch {
                            uiState = VerifyUiState.Loading
                            statusMessage = ""

                            try {
                                // 1) Start verification on backend
                                val start = startVerification(phone)
                                val requestId = start.requestId
                                val checkUrl = start.checkUrl
                                val channel = start.channel
                                Log.d("MyApp", "codeFromSa $checkUrl")

                                when (channel) {
                                    "silent_auth" -> {
                                        statusMessage = "Attempting silent authentication ..."
                                        try {
                                            val codeFromSa = checkSilentAuth(checkUrl!!)
                                            // Submit code to backend for final validation
                                            val result = submitCode(requestId, codeFromSa)
                                            if (result.verified) {
                                                uiState =
                                                    VerifyUiState.Verified("Silent Authentication")
                                                statusMessage = "Verified via Silent Authentication"
                                            } else {
                                                uiState = VerifyUiState.EnterSms(requestId)
                                                statusMessage =
                                                    "Silent auth didn't complete. Please enter SMS code."
                                            }
                                        } catch (e: Exception) {
                                            Log.d("MyApp", "SA failed with ${e.message}")
                                            uiState = VerifyUiState.EnterSms(requestId)
                                            statusMessage =
                                                "Silent Auth failed. Please enter the SMS code."
                                        }
                                    }

                                    "email_stepup" -> {
                                        Log.d("MyApp", "SIM swap flagged — routing to email verification")
                                        // SIM swap flagged
                                        val emailStart = startEmailVerification(phone)
                                        uiState = VerifyUiState.EnterEmailOtp(emailStart.requestId)
                                        statusMessage =
                                            "⚠️ Security check required. Please verify via email."
                                    }

                                    else -> {
                                        // "sms_otp": Silent Auth unavailable, no SIM swap
                                        uiState = VerifyUiState.EnterSms(requestId)
                                        statusMessage = "Please enter the SMS code"
                                    }
                                }
                            } catch (e: Exception) {
                                uiState = VerifyUiState.Error(e.message ?: "Unknown error")
                                statusMessage = "Unable to start verification: ${e.message}"
                            }
                        }
                    }
                ) {
                    Text("Start verification")
                }
            }

            is VerifyUiState.EnterSms -> {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = smsCode.isNotBlank(),
                    onClick = {
                        scope.launch {
                            // Capture requestId BEFORE changing uiState
                            val requestId = (uiState as? VerifyUiState.EnterSms)?.requestId
                                ?: return@launch

                            uiState = VerifyUiState.Loading
                            statusMessage = ""

                            try {
                                val result = submitCode(requestId, smsCode)
                                if (result.verified) {
                                    uiState = VerifyUiState.Verified("SMS")
                                    statusMessage = "Verified via SMS"
                                } else {
                                    uiState = VerifyUiState.EnterSms(requestId)
                                    statusMessage = "Invalid code. Please try again."
                                }
                            } catch (e: Exception) {
                                uiState = VerifyUiState.Error(e.message ?: "Unknown error")
                                statusMessage = "Error checking code: ${e.message}"
                            }
                        }
                    }
                ) {
                    Text("Submit code")
                }
            }

            is VerifyUiState.EnterEmailOtp -> {


                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = emailCode.isNotBlank(),
                    onClick = {
                        scope.launch {
                            // Capture requestId BEFORE changing uiState
                            val requestId = (uiState as? VerifyUiState.EnterEmailOtp)?.requestId
                                ?: return@launch
                            Log.d("MyApp", "email request id is $requestId")

                            uiState = VerifyUiState.Loading
                            statusMessage = ""

                            try {
                                val result = submitCode(requestId, emailCode)
                                if (result.verified) {
                                    uiState = VerifyUiState.Verified("Email")
                                    statusMessage = "Verified via Email"
                                } else {
                                    uiState = VerifyUiState.EnterEmailOtp(requestId)
                                    statusMessage = "Invalid code. Please try again."
                                }
                            } catch (e: Exception) {
                                uiState = VerifyUiState.Error(e.message ?: "Unknown error")
                                statusMessage = "Error checking code: ${e.message}"
                            }
                        }
                    }
                ) {
                    Text("Submit email code")
                }
            }




            is VerifyUiState.Verified -> {
                val method = (uiState as VerifyUiState.Verified).method
                Text("Success! Verified using $method.")
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        // Reset screen to try again
                        smsCode = ""
                        emailCode = ""
                        statusMessage = ""
                        uiState = VerifyUiState.EnterPhone
                    }
                ) {
                    Text("Verify another number")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (statusMessage.isNotBlank()) {
            Text(statusMessage)
        }
    }
}

/**
 * Backend call: POST /verification
 * Returns (request_id, check_url?)
 */

private suspend fun startVerification(phone: String): StartVerificationResponse =
    withContext(Dispatchers.IO) {
        val client = OkHttpClient()

        val json = Gson().toJson(mapOf("phone" to phone))
        val requestBody = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$BACKEND_URL/verification")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        Log.d("MyApp", "response is $response")
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw IOException("Start verification failed: HTTP ${response.code} - $errorBody")
        }

        val body = response.body?.string() ?: throw IOException("Empty response body")
        val jsonBody = Gson().fromJson(body, JsonObject::class.java)
        Log.d("MyApp", "body is $jsonBody")

        // Parse channel first — everything else depends on it
        val channel = jsonBody.get("channel")
            ?.takeIf { !it.isJsonNull }
            ?.asString ?: "sms_otp"
        Log.d("MyApp", "starting verification process with $channel")

        // check_url only matters for silent_auth
        val checkUrl = jsonBody.get("check_url")
            ?.takeIf { !it.isJsonNull }
            ?.asString

        // request_id is required for sms_otp and email_setup (needed for /check-code and /start-email)
        // for silent_auth it's still useful for submitCode() but not strictly required upfront
        val requestId = when (channel) {
            "email_stepup" -> null  // no Verify request started yet — requestId comes from /start-email
            "silent_auth" -> jsonBody.get("request_id")
                ?.takeIf { !it.isJsonNull }
                ?.asString ?: ""
            else -> jsonBody.get("request_id")
                ?.takeIf { !it.isJsonNull }
                ?.asString ?: throw IOException("Missing request_id")
        }

        StartVerificationResponse(requestId, checkUrl, channel)
    }
/**
 * Silent Auth cellular GET to check_url.
 * Expects a JSON body containing a "code" field.
 */
private suspend fun checkSilentAuth(url: String): String = withContext(Dispatchers.IO) {
    val params = VGCellularRequestParameters(
        url = url,
        headers = mapOf(),
        queryParameters = mapOf(),
        maxRedirectCount = 10
    )

    Log.d("MyApp", "checking SA code")

    val response = VGCellularRequestClient.getInstance()
        .startCellularGetRequest(params, false)

    val httpStatus = response.optInt("http_status", -1)
    val sdkError = response.optString("error", "")

    if (sdkError.isNotEmpty()) {
        throw IOException("Silent Auth SDK error: $sdkError")
    }

    if (httpStatus !in 200..299) {
        val rawBody = response.optString("response_raw_body", "")
        throw IOException("Silent Auth failed: HTTP $httpStatus - ${rawBody.take(200)}")
    }

    val bodyJsonObj = response.optJSONObject("response_body")
    val code = bodyJsonObj?.optString("code", null)

    if (code.isNullOrBlank()) {
        throw IOException("Silent Auth response missing 'code'")
    }

    code
}

private suspend fun startEmailVerification(phone: String): StartEmailVerificationResponse =
    withContext(Dispatchers.IO) {
        val client = OkHttpClient()

        val json = Gson().toJson(mapOf("phone" to phone))
        val requestBody = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$BACKEND_URL/send-email-otp")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw IOException("Start email verification failed: HTTP ${response.code} - $errorBody")
        }

        val body = response.body?.string() ?: throw IOException("Empty response body")
        val jsonBody = Gson().fromJson(body, JsonObject::class.java)
        val requestId = jsonBody.get("request_id")?.asString
            ?: throw IOException("Missing request_id")

        StartEmailVerificationResponse(requestId)
    }

/**
 * Backend call: POST /check-code
 * Used for SMS, Silent Auth, and Email OTP — channel-agnostic
 */

private suspend fun submitCode(requestId: String?, code: String): CheckCodeResponse = withContext(Dispatchers.IO) {
    val client = OkHttpClient()

    val json = Gson().toJson(mapOf("request_id" to requestId, "code" to code))
    val requestBody = json.toRequestBody("application/json".toMediaType())

    val request = Request.Builder()
        .url("$BACKEND_URL/check-code")
        .post(requestBody)
        .build()

    val response = client.newCall(request).execute()
    Log.d("MyApp", "response is $response")
    if (!response.isSuccessful) {
        val errorBody = response.body?.string() ?: "Unknown error"
        throw IOException("Check code failed: HTTP ${response.code} - $errorBody")
    }

    val body = response.body?.string() ?: throw IOException("Empty response body")
    val jsonBody = Gson().fromJson(body, JsonObject::class.java)
    Log.d("MyApp", "body  is $body")
    Log.d("MyApp", "jsonbody  is $jsonBody")
    CheckCodeResponse(
        verified = jsonBody.get("verified")?.asBoolean ?: false,
        status = jsonBody.get("status")?.asString
    )
}

///**
// * Backend call: POST /next
// * Explicitly requests fallback to SMS workflow
// */
//private suspend fun requestNextWorkflow(requestId: String): Unit = withContext(Dispatchers.IO) {
//    val client = OkHttpClient()
//
//    val json = Gson().toJson(mapOf("requestId" to requestId))
//    val requestBody = json.toRequestBody("application/json".toMediaType())
//
//    val request = Request.Builder()
//        .url("$BACKEND_URL/next")
//        .post(requestBody)
//        .build()
//
//    val response = client.newCall(request).execute()
//    if (!response.isSuccessful) {
//        val errorBody = response.body?.string() ?: "Unknown error"
//        throw IOException("Next workflow failed: HTTP ${response.code} - $errorBody")
//    }
//}
