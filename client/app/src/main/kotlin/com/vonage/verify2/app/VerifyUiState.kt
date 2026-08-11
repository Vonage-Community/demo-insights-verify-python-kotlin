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