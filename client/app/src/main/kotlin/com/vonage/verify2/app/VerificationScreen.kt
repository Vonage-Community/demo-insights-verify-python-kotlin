package com.vonage.verify2.app

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun VerifyApp(client: VerifyApiClient) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            VerificationScreen(client = client)
        }
    }
}

@Composable
fun VerificationScreen(client: VerifyApiClient) {
    var phone by remember { mutableStateOf(DEFAULT_PHONE) }
    var smsCode by remember { mutableStateOf("") }
    var emailCode by remember { mutableStateOf("") }
    var uiState by remember { mutableStateOf<VerifyUiState>(VerifyUiState.EnterPhone) }
    var statusMessage by remember { mutableStateOf("") }

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
        if ((uiState as? VerifyUiState.EnterSms)?.requestId != null) {
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
        if ((uiState as? VerifyUiState.EnterEmailOtp)?.requestId != null) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = emailCode,
                onValueChange = { emailCode = it },
                enabled = uiState !is VerifyUiState.Loading,
                label = { Text("Enter the code sent to your email on file") },
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
                                val start = client.startVerification(phone)
                                val requestId = start.requestId
                                val checkUrl = start.checkUrl
                                val channel = start.channel
                                Log.d("MyApp", "channel: $channel checkUrl: $checkUrl")

                                when (channel) {
                                    "silent_auth" -> {
                                        statusMessage = "Attempting silent authentication..."
                                        try {
                                            val codeFromSa = client.checkSilentAuth(checkUrl!!)
                                            Log.d("MyApp", "code from SA: $codeFromSa")
                                            val result = client.submitCode(requestId, codeFromSa)
                                            if (result.verified) {
                                                uiState = VerifyUiState.Verified("Silent Authentication")
                                                statusMessage = "Verified via Silent Authentication"
                                            } else {
                                                uiState = VerifyUiState.EnterSms(requestId)
                                                statusMessage = "Silent auth didn't complete. Please enter SMS code."
                                            }
                                        } catch (e: Exception) {
                                            Log.d("MyApp", "SA failed: ${e.message}")
                                            uiState = VerifyUiState.EnterSms(requestId)
                                            statusMessage = "Silent Auth failed. Please enter the SMS code."
                                        }
                                    }

                                    "email_stepup" -> {
                                        Log.d("MyApp", "SIM swap flagged — routing to email verification")
                                        try {
                                            val emailStart = client.startEmailVerification(phone)
                                            uiState = VerifyUiState.EnterEmailOtp(emailStart.requestId)
                                            statusMessage = "⚠️ SIM swap detected. Falling back to email on file."
                                        } catch (e: Exception) {
                                            uiState = VerifyUiState.Error(e.message ?: "Unknown error")
                                            statusMessage = "Failed to start email verification: ${e.message}"
                                        }
                                    }

                                    else -> {
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
                            val requestId = (uiState as? VerifyUiState.EnterSms)?.requestId
                                ?: return@launch

                            uiState = VerifyUiState.Loading
                            statusMessage = ""

                            try {
                                val result = client.submitCode(requestId, smsCode)
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
                            val requestId = (uiState as? VerifyUiState.EnterEmailOtp)?.requestId
                                ?: return@launch
                            Log.d("MyApp", "email request id: $requestId")

                            uiState = VerifyUiState.Loading
                            statusMessage = ""

                            try {
                                val result = client.submitCode(requestId, emailCode)
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
                Text("✅ Success! Verified using $method.")
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
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
