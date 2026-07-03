import os
import logging
from datetime import datetime
from typing import Optional, Dict, Any
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from vonage import Auth, Vonage
from vonage_verify import SilentAuthChannel, VerifyRequest, SmsChannel

# Load environment variables
load_dotenv()

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Initialize FastAPI app
app = FastAPI()

# Add CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Vonage auth setup
verify_client = Vonage(
    Auth(
        application_id=os.environ["VONAGE_APPLICATION_ID"],
        private_key=os.environ["VONAGE_PRIVATE_KEY_PATH"],
    )
)


# ============================================================================
# In-memory verification store
# ============================================================================
verification_store: Dict[str, Dict[str, Any]] = {}

# ============================================================================
# Request/Response Models
# ============================================================================

class VerificationRequest(BaseModel):
    phone: str = Field(..., description="Phone number to verify")

class CallbackRequest(BaseModel):
    request_id: str
    status: Optional[str] = None

class CheckCodeRequest(BaseModel):
    request_id: str
    code: str

class NextWorkflowRequest(BaseModel):
    requestId: str

class StatusResponse(BaseModel):
    request_id: str
    status: str
    updated_at: str
    completed: bool

# ============================================================================
# Utility Functions
# ============================================================================

def require_fields(obj: Dict[str, Any], fields: list) -> Optional[str]:
    """
    Validates required fields in request body.
    Returns the first missing field name or None if all present.
    """
    for field in fields:
        if not obj or obj.get(field) is None or obj.get(field) == "":
            return field
    return None

def get_iso_now() -> str:
    """Returns current timestamp in ISO format."""
    return datetime.utcnow().isoformat() + "Z"

# ============================================================================
# Endpoints
# ============================================================================

@app.get("/health")
async def health_check():
    """Health check endpoint."""
    return {"ok": True}

@app.post("/verification")
async def start_verification(req: VerificationRequest):
    """
    Start verification:
    - Creates Vonage request (silent_auth -> sms)
    - Stores verification request in memory
    - Returns request_id and check_url to client
    """
    try:
        phone = req.phone

        logger.info(f"Received verification request for: {phone}")

        # Create Vonage verification request
        verify_request = VerifyRequest(
            brand="DemoApp",
            workflow=[SilentAuthChannel(to=req.phone),
                    SmsChannel(to=req.phone)],
            coverage_check=True
        )

        result = verify_client.verify.start_verification(verify_request)

        logger.info(f"Vonage Verify2 newRequest result: {result}")

        # Store verification request
        now = get_iso_now()
        verification_store[result.request_id] = {
            "requestId": result.request_id,
            "phone": phone,
            "status": "pending",
            "createdAt": now,
            "updatedAt": now,
            "lastEvent": None,
        }

        return {
            "request_id": result.request_id,
            "check_url": getattr(result, "check_url", None),
        }

    except Exception as error:
        status_code = getattr(error.response, "status_code", 500) if hasattr(error, "response") else 500
        details = getattr(error.response, "data", str(error)) if hasattr(error, "response") else str(error)

        logger.error(f"Error /verification: {details}")
        raise HTTPException(
            status_code=status_code,
            detail={
                "error": "Failed to start verification",
                "details": details if isinstance(details, str) else None,
            },
        )

@app.post("/callback")
async def callback(req: CallbackRequest):
    """
    Callback/webhook: Vonage notifies status updates.
    
    IMPORTANT:
    - Must be idempotent (can be delivered multiple times)
    - Should validate source (token/signature) in production
    - Updates status from Vonage callback
    """
    try:
        request_id = req.request_id
        status_update = req.status

        if not request_id:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Missing request_id",
            )

        logger.info(f"Callback received: {{'request_id': {request_id}, 'status': {status_update}}}")

        entry = verification_store.get(request_id)
        if not entry:
            logger.warning(f"Callback for unknown request_id: {request_id}")
            return {"ok": True}  # Acknowledge even if unknown

        # Update status from callback
        updated = {
            **entry,
            "status": status_update or entry.get("status"),
            "updatedAt": get_iso_now(),
            "lastEvent": req.dict(),
        }

        verification_store[request_id] = updated

        logger.info(f"Callback updated: {request_id} -> {updated['status']}")

        return {"ok": True}

    except Exception as error:
        logger.error(f"Error processing callback: {error}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Internal server error",
        )

@app.get("/status/{request_id}")
async def get_status(request_id: str):
    """
    Get verification status.
    Client polls this to check verification status.
    """
    try:
        entry = verification_store.get(request_id)

        if not entry:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Unknown request_id",
            )

        return {
            "request_id": request_id,
            "status": entry.get("status"),
            "updated_at": entry.get("updatedAt"),
            "completed": entry.get("status") == "completed",
        }

    except HTTPException:
        raise
    except Exception as error:
        logger.error(f"Error /status: {error}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Internal server error",
        )

@app.post("/check-code")
async def check_code(req: CheckCodeRequest):
    """
    Check code (for SMS or Silent Auth code).
    Backend validates the code with Vonage.
    """
    try:
        request_id = req.request_id
        code = req.code

        entry = verification_store.get(request_id)
        if not entry:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Unknown request_id",
            )

        logger.info(f"Checking code for request: {request_id}")

        # Check code with Vonage
        result = verify_client.verify.check_code(request_id, code)
        logger.info(f"Vonage Verify2 checkCode result: {result}")

        print("RESULT: ===> ", result)

        verified = result.model_dump()["status"] == "completed"

        # Update status in store
        if verified:
            updated = {
                **entry,
                "status": "completed",
                "updatedAt": get_iso_now(),
                "lastEvent": {"source": "check_code", "result": result},
            }
            verification_store[request_id] = updated

        return {
            "verified": verified,
            "status": result.model_dump()["status"],
        }

    except HTTPException:
        raise
    except Exception as error:
        status_code = getattr(error.response, "status_code", 500) if hasattr(error, "response") else 500
        details = getattr(error.response, "data", str(error)) if hasattr(error, "response") else str(error)

        logger.error(f"Error /check-code: {details}")

        # If it's an invalid code error, return 200 with verified: false
        if status_code in [400, 404]:
            return {
                "verified": False,
                "error": details if isinstance(details, str) else "Invalid code",
            }

        raise HTTPException(
            status_code=status_code,
            detail={
                "error": "Failed to check code",
                "details": details if isinstance(details, str) else None,
            },
        )

@app.post("/next")
async def next_workflow(req: NextWorkflowRequest):
    """
    Move to next workflow (explicit fallback to SMS).
    Client can call this to explicitly skip Silent Auth and go to SMS.
    """
    try:
        request_id = req.requestId

        entry = verification_store.get(request_id)
        if not entry:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Unknown request_id",
            )

        logger.info(f"Moving to next workflow (SMS) for: {request_id}")

        # Call Vonage to move to next workflow
        result = verify_client.verify.trigger_next_workflow(request_id)
        print(f"RESULT: ===> {result}")
        logger.info(f"Vonage nextWorkflow result: {result}")

        # Update last event
        updated = {
            **entry,
            "updatedAt": get_iso_now(),
            "lastEvent": {"source": "next_workflow", "result": result},
        }
        verification_store[request_id] = updated

        return {"ok": True}

    except HTTPException:
        raise
    except Exception as error:
        status_code = getattr(error.response, "status_code", 500) if hasattr(error, "response") else 500
        details = getattr(error.response, "data", str(error)) if hasattr(error, "response") else str(error)

        logger.error(f"Error /next: {details}")
        raise HTTPException(
            status_code=status_code,
            detail={
                "error": "Failed to move workflow",
                "details": details if isinstance(details, str) else None,
            },
        )

# ============================================================================
# Run the application
# ============================================================================

if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", 4000))
    uvicorn.run(app, host="0.0.0.0", port=port)
    print(f"Listening on port {port}")
