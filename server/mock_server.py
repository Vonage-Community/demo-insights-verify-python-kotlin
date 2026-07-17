# mock_server.py

import requests
import os
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from dotenv import load_dotenv
import uvicorn

load_dotenv()
USER_EMAIL_STORE = {os.environ["USER_PHONE_NUMBER"]: os.environ["USER_EMAIL"]}

app = FastAPI()

# ============================================================================
# Shared state
# ============================================================================

config = {
    "phone": "990123411",  # default to virtual no-swap number
}

request_store: dict = {}  # maps request_id -> channel

# ============================================================================
# Request models
# ============================================================================

class PhoneRequest(BaseModel):
    phone: str

class CheckCodeRequest(BaseModel):
    request_id: str
    code: str

class ConfigRequest(BaseModel):
    phone: str

# ============================================================================
# Config endpoint — test script sets the phone number to use
# ============================================================================

@app.post("/config")
async def set_config(req: ConfigRequest):
    config["phone"] = req.phone
    print(f"  ⚙️  Config updated — phone: {req.phone}")
    return {"ok": True}

# ============================================================================
# Verification endpoints
# ============================================================================

@app.post("/verification")
async def verification(req: PhoneRequest):
    phone = config["phone"]
    print(f"  /verification — client sent: {req.phone}, using: {phone}")

    from vonage_handlers import check_sim_swap, start_silent_auth, start_sms
    from vonage_handlers import check_code as vonage_check_code
    from vonage_http_client import HttpRequestError
    from vonage import Auth, Vonage


    try:
        sim_swapped = check_sim_swap(phone)
        print(f"  SIM swap result: {sim_swapped}")

        if sim_swapped or sim_swapped is None:
            print("  SIM swap flagged — stepping up to email")
            return {"channel": "email_stepup", "request_id": None}

        try:
            result = start_silent_auth(phone)
            request_store[result["request_id"]] = {"channel": result["channel"],
                                                   "check_url": result["check_url"]}
            print(f"  Silent Auth started — request_id: {result['request_id']} with phone: {phone}")
            print(f"  Stored as: {request_store[result["request_id"]]}")
            print(f"  Silent Auth started — result: {result}")
            
            verify_client = Vonage(
                    Auth(
                        application_id=os.environ["VONAGE_APPLICATION_ID"],
                        private_key=os.environ["VONAGE_PRIVATE_KEY_PATH"],
                    )
                )
            status = verify_client.verify.check_code(result["request_id"], "1111")
            if status.status != "complete":
                raise HTTPException(412)
            return result

        except HttpRequestError as e:
            if e.response and e.response.status_code == 412 or e.response.status_code == 409:
                print(f"  Silent Auth unavailable (412) — falling back to SMS with {req.phone}")
                result = start_sms(req.phone)
                request_store[result["request_id"]] = {"channel": "sms_otp"}
                print(f"  SMS started — request_id: {result['request_id']}")
                return result
            raise

    except Exception as e:
        print(f"  ❌ Error in /verification: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/send-email-otp")
async def send_email_otp(req: PhoneRequest):
    phone = config["phone"]
    print(f"  /send-email-otp — using phone: {phone}")

    from vonage_handlers import start_email

    email = os.environ.get("USER_EMAIL")
    if not email:
        raise HTTPException(status_code=500, detail="USER_EMAIL not set in .env")

    try:
        result = start_email(phone, email)
        request_store[result["request_id"]] = {"channel": "email"}
        print(f"  Email OTP started — request_id: {result['request_id']} with email: {email}")
        return result

    except Exception as e:
        print(f"  ❌ Error in /send-email-otp: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/check-code")
async def check_code(req: CheckCodeRequest):
    print(f"  /check-code — request_id: {req.request_id} code: {req.code}")

    from vonage_handlers import check_code as vonage_check_code
    from vonage import Auth, Vonage

    channel = request_store.get(req.request_id, "sms_otp")["channel"]
    print(f"  channel: {channel}")

    if channel == "silent_auth":
        # Sync silent auth — poll for status instead of submitting a code
        check_url = request_store.get(req.request_id)["check_url"]
        print(f"  polling for status at: {check_url}")
        try:
            verify_client = Vonage(
                Auth(
                    application_id=os.environ["VONAGE_APPLICATION_ID"],
                    private_key=os.environ["VONAGE_PRIVATE_KEY_PATH"],
                )
            )
            
            print(f"  /check-code — check_url: {req.request_id} code: {req.code}")
            # result = requests.get(check_url, allow_redirects=True)
            result = vonage_check_code(req.request_id, req.code)
            print(f"  Silent Auth sync result: {result}")
            
            return result

        except Exception as e:
            print(f"  ❌ Error in silent auth check: {e}")
            return {"verified": False, "status": str(e)}

    # SMS or email — submit the code as normal
    try:
        vonage_check_code(req.request_id, req.code)
        print(f"  ✅ Code verified")
        return {"verified": True}

    except Exception as e:
        print(f"  ❌ Code check failed: {e}")
        return {"verified": False, "status": str(e)}

# ============================================================================
# Run
# ============================================================================

if __name__ == "__main__":
    print("\n🚀 Mock server running on port 4001")
    print("   Control the scenario via POST /config")
    print("   e.g. curl -X POST http://localhost:4001/config -H 'Content-Type: application/json' -d '{\"phone\": \"+990123411\"}'")
    port = int(os.getenv("PORT", 4001))
    uvicorn.run(app, host="0.0.0.0", port=port)




# app = FastAPI()

# logging.basicConfig(level=logging.INFO)
# logger = logging.getLogger(__name__)

# # Change this to test different flows
# CHANNEL = "silent_auth" # or "sms_otp", "email_stepup", "silent_auth_fail"
# VERIFIED = True

# class VerificationRequest(BaseModel):
#     phone: str = Field(..., description="Phone number to verify")

# @app.post("/verification")
# async def verification(req: VerificationRequest):
#     logger.info(f"Beginning MOCK authentication process for {CHANNEL}")
#     if CHANNEL == "silent_auth":
#         mock_phone = "9902345603"
#         logger.info(f"Starting silent auth for {mock_phone}")
        
#         try: 
#             silent_auth_response = start_silent_auth(mock_phone)
            
#             logger.info(f"Silent auth response: {silent_auth_response}")

#             return silent_auth_response
#         except HttpRequestError as e:
#             if e.response and e.response.status_code == 412:
#                 return start_sms(req.phone)
#     elif CHANNEL == "email_stepup":
#         return {
#             "channel": "email_stepup",
#             "request_id": None
#         }
#     elif CHANNEL == "silent_auth_fail":
#         return {
#             "channel": "email_stepup",
#             "request_id": None
#         }
#     else:
#         return {
#             "channel": "sms_otp",
#             "request_id": "fake-request-123"
#         }

# @app.post("/send-email-otp")
# async def send_email_otp():
#     return {"request_id": "fake-email-request-123"}

# @app.post("/check-code")
# async def check_code():
#     return {"verified": VERIFIED, "status": None}
