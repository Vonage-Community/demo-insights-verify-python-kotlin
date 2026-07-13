from fastapi import FastAPI
from pydantic import BaseModel, Field
import logging
from vonage_http_client import HttpRequestError
from vonage_handlers import (
    check_sim_swap,
    start_silent_auth,
    start_sms,
    start_email,
    check_code,
)

app = FastAPI()

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Change this to test different flows
CHANNEL = "silent_auth" # or "sms_otp", "email_stepup"
VERIFIED = True

class VerificationRequest(BaseModel):
    phone: str = Field(..., description="Phone number to verify")

@app.post("/verification")
async def verification(req: VerificationRequest):
    logger.info(f"Beginning MOCK authentication process for {CHANNEL}")
    if CHANNEL == "silent_auth":
        mock_phone = "9902345603"
        logger.info(f"Starting silent auth for {mock_phone}")
        
        try: 
            silent_auth_response = start_silent_auth(mock_phone)
            
            logger.info(f"Silent auth response: {silent_auth_response}")

            return silent_auth_response
        except HttpRequestError as e:
            if e.response and e.response.status_code == 412:
                return start_sms(req.phone)
    elif CHANNEL == "email_stepup":
        return {
            "channel": "email_stepup",
            "request_id": None
        }
    else:
        return {
            "channel": "sms_otp",
            "request_id": "fake-request-123"
        }

@app.post("/send-email-otp")
async def send_email_otp():
    return {"request_id": "fake-email-request-123"}

@app.post("/check-code")
async def check_code():
    return {"verified": VERIFIED, "status": None}
