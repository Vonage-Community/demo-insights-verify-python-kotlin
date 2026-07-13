package com.vonage.verify2.app

sealed class VerifyUiState {
    data object EnterPhone : VerifyUiState()
    data object Loading : VerifyUiState()
    data class EnterSms(val requestId: String?) : VerifyUiState()
    data class EnterEmailOtp(val requestId: String) : VerifyUiState()
    data class Verified(val method: String) : VerifyUiState()
    data class Error(val message: String) : VerifyUiState()
}

data class CheckCodeResponse(val verified: Boolean, val status: String?)
data class StartEmailVerificationResponse(val requestId: String)
data class StartVerificationResponse(val requestId: String?, val checkUrl: String?, val channel: String)

interface VerifyApiClient {
    suspend fun startVerification(phone: String): StartVerificationResponse
    suspend fun checkSilentAuth(url: String): String
    suspend fun startEmailVerification(phone: String): StartEmailVerificationResponse
    suspend fun submitCode(requestId: String?, code: String): CheckCodeResponse
}

class MockVerifyApiClient(
    private val channel: String = "sms_otp",
    private val verified: Boolean = true
) : VerifyApiClient {

    override suspend fun startVerification(phone: String) = when (channel) {
        "silent_auth" -> StartVerificationResponse(
            requestId = "mock-request-123",
            checkUrl = "https://mock-check-url.com",
            channel = "silent_auth"
        )
        "email_stepup" -> StartVerificationResponse(
            requestId = null,
            checkUrl = null,
            channel = "email_stepup"
        )
        else -> StartVerificationResponse(
            requestId = "mock-request-123",
            checkUrl = null,
            channel = "sms_otp"
        )
    }

    override suspend fun checkSilentAuth(url: String) = "mock-code"

    override suspend fun startEmailVerification(phone: String) =
        StartEmailVerificationResponse("mock-email-request-123")

    override suspend fun submitCode(requestId: String?, code: String) =
        CheckCodeResponse(verified = verified, status = null)
}