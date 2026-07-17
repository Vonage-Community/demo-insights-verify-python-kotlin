import os
import logging
from dotenv import load_dotenv
from vonage import Auth, Vonage
from vonage_http_client import HttpRequestError
from vonage_verify import SilentAuthChannel, VerifyRequest, SmsChannel, EmailChannel, StartVerificationResponse
from vonage_identity_insights import (
    IdentityInsightsRequest,
    InsightsRequest,
    EmptyInsight,
    SimSwapInsight,
)

logger = logging.getLogger(__name__)
load_dotenv()

# Clients
verify_client = Vonage(
    Auth(
        application_id=os.environ["VONAGE_APPLICATION_ID"],
        private_key=os.environ["VONAGE_PRIVATE_KEY_PATH"],
    )
)

identity_insights_client = Vonage(
    Auth(
        application_id=os.environ["VONAGE_APPLICATION_ID"],
        private_key=os.environ["VONAGE_PRIVATE_KEY_PATH"],
    ),
    http_client_options={"api_host": "api-eu.vonage.com"}
)

def check_sim_swap(phone: str) -> bool | None:
    """
    Returns True if SIM was swapped, False if not, None if carrier doesn't permit check.
    """
    insights_request = IdentityInsightsRequest(
        phone_number=phone,
        purpose="FraudPreventionAndDetection",
        insights=InsightsRequest(
            format=EmptyInsight(),
            sim_swap=SimSwapInsight(period=240)
        )
    )
    return identity_insights_client.identity_insights.requests(insights_request).insights.sim_swap.is_swapped

def start_silent_auth(phone: str) -> dict:
    """
    Starts Silent Auth verification.
    Returns channel, request_id, and check_url.
    Raises HttpRequestError with 412 if Silent Auth unavailable.
    """
    request = VerifyRequest(
        brand="DemoApp",
        workflow=[SilentAuthChannel(to=phone)],
        coverage_check=True
    )
    response: StartVerificationResponse = verify_client.verify.start_verification(request)
    return {
        "channel": "silent_auth",
        "request_id": response.request_id,
        "check_url": response.check_url
    }

def start_sms(phone: str) -> dict:
    """
    Starts SMS OTP verification.
    Returns channel and request_id.
    """
    request = VerifyRequest(
        brand="DemoApp",
        workflow=[SmsChannel(to=phone)],
    )
    response = verify_client.verify.start_verification(request)
    return {"channel": "sms_otp", "request_id": response.request_id}

def start_email(phone: str, email: str) -> dict:
    """
    Starts email OTP verification.
    Returns request_id.
    """
    request = VerifyRequest(
        brand="DemoApp",
        workflow=[EmailChannel(to=email)],
    )
    response = verify_client.verify.start_verification(request)
    return {"request_id": response.request_id}

def check_code(request_id: str, code: str) -> bool:
    """
    Validates OTP code with Vonage. Returns True if verified.
    """
    response = verify_client.verify.check_code(request_id, code)
    return {"verified": True, "status": response.status}
