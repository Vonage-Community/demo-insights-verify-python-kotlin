import os
import logging
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from vonage_handlers import (
    check_sim_swap,
    start_silent_auth,
    start_sms,
    start_email,
    check_code,
)

load_dotenv()

# USe in-memory storage to simulate a database; this is not appropriate for a production grade application
USER_EMAIL_STORE = {os.environ["USER_PHONE_NUMBER"]: os.environ["USER_EMAIL"]}

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI()


class VerificationRequest(BaseModel):
    phone: str = Field(..., description="Phone number to verify")


class CheckCodeRequest(BaseModel):
    request_id: str
    code: str


@app.post("/verification")
async def start_verification(req: VerificationRequest):
    """
    Starts the verification process:
    - Check if SIM is swapped-- if sim_swapped: True, fallback to email
    - If sim_swapped: False, begin Silent Authentication
    """
    phone = req.phone
    logger.info(f"Beginning authentication process for: {phone}")

    try:
        sim_swapped = check_sim_swap(phone)
        logger.info(f"Swap status for {phone}: {sim_swapped}")

        if sim_swapped or sim_swapped is None:
            logger.info("SIM swap flagged, stepping up to email")
            return {"channel": "email_stepup", "request_id": None}

        return start_silent_auth(phone)

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/fallback-sms")
async def fallback_sms(req: VerificationRequest):

    phone = req.phone
    # Uncomment the line below and comment out the line above
    # to test this app with a Virtual Operator number and receive an OTP
    # to a real phone number
    # phone = os.environ["USER_PHONE_NUMBER"]

    logger.info(f"SMS fallback requested, sending to: {phone}")
    try:
        return start_sms(phone)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/send-email-otp")
async def send_email_otp(req: VerificationRequest):
    """
    Starts the verification process via email:
    - Check if there is a stored email -- if no email, raise exception
    - If there is an email, begin verification
    """
    phone = req.phone

    logger.info(f"Stepping up to email for verification process for: {phone}")
    email = USER_EMAIL_STORE.get(phone)
    logger.info(f"Getting email address from simulated user database: {email}")

    if not email:
        raise HTTPException(status_code=404, detail="No email on file for this number")

    logger.info(f"Beginning email verification for: {email}")
    try:
        return start_email(phone, email)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/check-code")
async def verify_code(req: CheckCodeRequest):
    request_id = req.request_id
    code = req.code
    logger.info(f"Checking code for request_id: {request_id} with code: {code}")
    try:
        response = check_code(request_id, code)
        logger.info("Verification success!")
        return response
    except Exception as e:
        logger.info("Verification failure")
        return {"verified": False, "status": str(e)}


if __name__ == "__main__":
    import uvicorn

    port = int(os.getenv("PORT", 4000))
    uvicorn.run(app, host="0.0.0.0", port=port)
