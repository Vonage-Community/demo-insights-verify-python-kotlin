require("dotenv").config();

const express = require("express");
const cors = require("cors");

const { Auth } = require("@vonage/auth");
const { Verify2 } = require("@vonage/verify2");

const app = express();

app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// Vonage auth
const credentials = new Auth({
  applicationId: process.env.VONAGE_APP_ID,
  privateKey: process.env.VONAGE_PRIVATE_KEY,
});

const verifyClient = new Verify2(credentials);

/**
 * Simple in-memory store for verification requests
 * Map<requestId, {
 *   requestId: string,
 *   phone: string,
 *   status: string,
 *   createdAt: string,
 *   updatedAt: string,
 *   lastEvent: object | null
 * }>
 */
const verificationStore = new Map();

/**
 * Validates required fields in request body
 */
function requireFields(obj, fields) {
  for (const f of fields) {
    if (!obj || obj[f] == null || obj[f] === "") return f;
  }
  return null;
}

/**
 * Health check endpoint
 */
app.get("/health", (req, res) => {
  res.json({ ok: true });
});

/**
 * Start verification:
 * - Creates Vonage request (silent_auth -> sms)
 * - Stores verification request in Map
 * - Returns request_id and check_url to client
 */
app.post("/verification", async (req, res) => {
  try {
    const missing = requireFields(req.body, ["phone"]);
    if (missing) {
      return res.status(400).json({ error: `Field '${missing}' is required.` });
    }

    const { phone } = req.body;

    console.log("Received verification request for:", phone);

    // Create Vonage verification request
    const result = await verifyClient.newRequest({
      brand: "DemoApp",
      workflow: [
        { channel: "silent_auth", to: phone },
        { channel: "sms", to: phone },
      ],
    });

    console.log("Vonage Verify2 newRequest result:", result);

    // Store verification request
    const now = new Date().toISOString();
    verificationStore.set(result.requestId, {
      requestId: result.requestId,
      phone,
      status: "pending", // Initial status
      createdAt: now,
      updatedAt: now,
      lastEvent: null,
    });

    return res.json({
      request_id: result.requestId,
      check_url: result.checkUrl || null,
    });
  } catch (error) {
    const status = error?.response?.status || 500;
    const details = error?.response?.data || error?.message;

    console.error("Error /verification:", details);
    return res.status(status).json({
      error: "Failed to start verification",
      details: typeof details === "string" ? details : undefined,
    });
  }
});

/**
 * Callback/webhook: Vonage notifies status updates
 * IMPORTANT:
 * - Must be idempotent (can be delivered multiple times)
 * - Should validate source (token/signature) in production
 * - Updates status from Vonage callback
 */
app.post("/callback", async (req, res) => {
  try {
    const { request_id, status } = req.body || {};
    
    if (!request_id) {
      return res.status(400).json({ error: "Missing request_id" });
    }

    console.log("Callback received:", { request_id, status });

    const entry = verificationStore.get(request_id);
    if (!entry) {
      console.warn("Callback for unknown request_id:", request_id);
      return res.status(200).json({ ok: true }); // Acknowledge even if unknown
    }

    // Update status from callback
    const updated = {
      ...entry,
      status: status || entry.status,
      updatedAt: new Date().toISOString(),
      lastEvent: req.body,
    };

    verificationStore.set(request_id, updated);

    console.log(`Callback updated: ${request_id} -> ${updated.status}`);

    return res.status(200).json({ ok: true });
  } catch (error) {
    console.error("Error processing callback:", error);
    return res.status(500).json({ error: "Internal server error" });
  }
});

/**
 * Get verification status
 * Client polls this to check verification status
 */
app.get("/status/:request_id", async (req, res) => {
  try {
    const { request_id } = req.params;
    const entry = verificationStore.get(request_id);

    if (!entry) {
      return res.status(404).json({ error: "Unknown request_id" });
    }

    return res.json({
      request_id,
      status: entry.status,
      updated_at: entry.updatedAt,
      completed: entry.status === "completed",
    });
  } catch (error) {
    console.error("Error /status:", error);
    return res.status(500).json({ error: "Internal server error" });
  }
});

/**
 * Check code (for SMS or Silent Auth code)
 * Backend validates the code with Vonage
 */
app.post("/check-code", async (req, res) => {
  try {
    const missing = requireFields(req.body, ["request_id", "code"]);
    if (missing) {
      return res.status(400).json({ error: `Field '${missing}' is required.` });
    }

    const { request_id, code } = req.body;

    const entry = verificationStore.get(request_id);
    if (!entry) {
      return res.status(404).json({ error: "Unknown request_id" });
    }

    console.log("Checking code for request:", request_id);

    // Check code with Vonage
    const result = await verifyClient.checkCode(request_id, code);
    console.log("Vonage Verify2 checkCode result:", result);

    const verified = result === "completed";

    // Update status in store
    if (verified) {
      const updated = {
        ...entry,
        status: "completed",
        updatedAt: new Date().toISOString(),
        lastEvent: { source: "check_code", result },
      };
      verificationStore.set(request_id, updated);
    }

    return res.json({
      verified,
      status: result || entry.status,
    });
  } catch (error) {
    const status = error?.response?.status || 500;
    const details = error?.response?.data || error?.message;

    console.error("Error /check-code:", details);

    // If it's an invalid code error, return 200 with verified: false
    if (status === 400 || status === 404) {
      return res.json({
        verified: false,
        error: typeof details === "string" ? details : "Invalid code",
      });
    }

    return res.status(status).json({
      error: "Failed to check code",
      details: typeof details === "string" ? details : undefined,
    });
  }
});

/**
 * Move to next workflow (explicit fallback to SMS)
 * Client can call this to explicitly skip Silent Auth and go to SMS
 */
app.post("/next", async (req, res) => {
  try {
    const missing = requireFields(req.body, ["requestId"]);
    if (missing) {
      return res.status(400).json({ error: `Field '${missing}' is required.` });
    }

    const { requestId } = req.body;

    const entry = verificationStore.get(requestId);
    if (!entry) {
      return res.status(404).json({ error: "Unknown request_id" });
    }

    console.log("Moving to next workflow (SMS) for:", requestId);

    // Call Vonage to move to next workflow
    const result = await verifyClient.nextWorkflow(requestId);
    console.log("Vonage nextWorkflow result:", result);

    // Update last event
    const updated = {
      ...entry,
      updatedAt: new Date().toISOString(),
      lastEvent: { source: "next_workflow", result },
    };
    verificationStore.set(requestId, updated);

    return res.status(200).json({ ok: true });
  } catch (error) {
    const status = error?.response?.status || 500;
    const details = error?.response?.data || error?.message;

    console.error("Error /next:", details);
    return res.status(status).json({
      error: "Failed to move workflow",
      details: typeof details === "string" ? details : undefined,
    });
  }
});

const PORT = process.env.PORT || 4000;
app.listen(PORT, () => {
  console.log(`Listening on port ${PORT}`);
});
