# Silent Auth + SIM Swap Demo

A full-stack demo application showcasing a layered mobile authentication flow using the Vonage Verify API. The backend is built with FastAPI (Python) and the client is an Android app built with Jetpack Compose.

## About This Repo

This repository demonstrates a progressive authentication flow that combines three [Vonage Network APIs](https://vonage.dev/4q6FbU6):

1. **SIM Swap** — checks whether a phone number's SIM has been swapped recently, flagging it as potentially compromised
2. **Silent Authentication** — performs a carrier-level identity check invisibly in the background, with no user input required
3. **Email OTP / SMS OTP** — fallback verification channels used when silent auth is unavailable or a SIM swap is detected

### Features

- SIM Swap detection with automatic step-up to email OTP
- Silent Authentication via the Vonage Verify API and the Vonage Android Client SDK
- Automatic fallback to SMS OTP when silent auth fails
- FastAPI backend with clean separation of verification logic
- Android client built with Jetpack Compose

### Built with

- [FastAPI](https://fastapi.tiangolo.com/)
- [Vonage Verify API](https://vonage.dev/4nYXkCu)
- [Vonage Android Client SDK](https://vonage.dev/4wgu8cJ)
- [Jetpack Compose](https://developer.android.com/compose)

### File structure

.
├── client/                          # Android app (Jetpack Compose)
│   └── app/src/main/kotlin/com/vonage/verify2/app/
│       ├── ApiClient.kt             # Vonage API calls + SilentAuthUnavailableException
│       ├── MainActivity.kt          # Entry point, SDK initialization
│       ├── VerificationScreen.kt    # UI + verification flow logic
│       └── VerifyUiState.kt         # UI state, data models, VerifyApiClient interface
│
└── server/                          # FastAPI backend (Python)
    ├── main.py                      # API endpoints
    ├── vonage_handlers.py           # Vonage SDK calls (SIM swap, silent auth, SMS, email)
    ├── requirements.txt
    └── .env                         # VONAGE_APPLICATION_ID, VONAGE_PRIVATE_KEY_PATH, etc.

## Getting Started

### Prerequisites

- Python 3.8+
- Android Studio
- A Vonage API account
- An [ngrok account and installation](https://vonage.dev/4d9waov)
- An Android device ([Silent Authentication](https://vonage.dev/4xvRbkH) requires a real device on cellular data -— it will not work on an emulator)

### Creating a Vonage Application

#### What is Vonage?

Vonage is a cloud-based communications platform that gives you the tools to embed communication capabilities directly into your own applications and services. Rather than building communication infrastructure from scratch, you can use Vonage APIs to add voice, video, messaging, verification, and other network-level capabilities to your products.

To run this demo, you need a Vonage developer account and a Verify application.

#### 1. Create a Vonage account

[Sign up for a free Vonage API account](https://vonage.dev/4z9aQsn).

#### 2. Create a Verify application

Create your Verify application in the [developer dashboard](https://dashboard.nexmo.com/) by navigating to **Applications** in the left-hand menu and clicking **Create new application**.

1. Give your application a name (e.g. `silent-auth-demo`)
2. Under **Capabilities**, toggle **Verify**
3. Click **Generate new application**

#### 3. Obtain your secrets

In the dashboard, open your application and click **Edit**, then click **Generate public and private key**. This will download a `private.key` file —- **keep this file private and do not share it anywhere it could be compromised**.

You will need the following secrets:

| Variable | Value |
|---|---|
| `VONAGE_APPLICATION_ID` | The ID of the Vonage application you created |
| `VONAGE_PRIVATE_KEY_PATH` | Path to the `private.key` file downloaded from the dashboard |
| `USER_PHONE_NUMBER` | Your real phone number (E.164 format, e.g. `+12015550123`) — used as the SMS OTP destination for testing |
| `USER_EMAIL` | Your email address — used as the email OTP destination for testing |

---

## Running the Server

### 1. Clone this repo

```bash
git clone <repo-url> && cd <repo-directory>/server
```

### 2. Set up your environment

Create and activate a Python virtual environment, then install dependencies:

```bash
python -m venv venv && source venv/bin/activate
pip install -r server/requirements.txt
```

### 3. Configure your environment variables

Copy `.env_template` to `.env` and fill in your values:

```
VONAGE_APPLICATION_ID=your-application-id
VONAGE_PRIVATE_KEY_PATH=path/to/private.key
USER_PHONE_NUMBER=+12015550123
USER_EMAIL=you@example.com
```

Learn more about environment variables [here](https://vonage.dev/4wteA6n).

### 4. Run the server

```bash
uvicorn main:app --reload --port 4000
```

The server will start at `http://0.0.0.0:4000`.

### 5. Spin up an ngrok tunnel

In a separate terminal window, run:

```
ngrok http 4000
```

This command will generate the public URLs your local server will tunnel to on port 4000. Take note of the public URL -– it should look something like this:

```bash
Forwarding                	https://some-public-url.ngrok-free.app -> http://localhost:4000
```

---

## Running the Android Client

### 1. Open the project

Open the `client/` directory in Android Studio.

### 2. Configure build variables

In your `local.properties` or `gradle.properties`, set:

```
BACKEND_URL=<your-ngrok-tunnel>
PHONE_NUMBER=+12015550123
```

> Note: Use your machine's local network IP address (not `localhost`) so the Android device can reach the backend over the same network.

### 3. Run on a real device

Connect a physical Android device over USB and run the app. Silent Authentication requires a real device on a cellular data connection -— it will not work on an emulator or over Wi-Fi.

---

## API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/verification` | Starts the auth flow: checks SIM swap, then initiates silent auth or steps up to email |
| `POST` | `/fallback-sms` | Sends an SMS OTP to the configured real number (demo/testing use) |
| `POST` | `/send-email-otp` | Sends an email OTP to the address on file for the given phone number |
| `POST` | `/check-code` | Verifies a submitted OTP code against a `request_id` |

---

## Authentication Flow

```
Client                  Backend                 Vonage
  |                        |                       |
  |-- POST /verification ->|                       |
  |                        |-- SIM Swap check ---->|
  |                        |<-- swapped: false ----|
  |                        |-- Silent Auth ------->|
  |                        |<-- request_id + ------|
  |                             check_url          |
  |<-- check_url ----------|                       |
  |                                                |
  |-- GET check_url (cellular) ------------------>|
  |<-- code --------------------------------------|
  |                        |                       |
  |-- POST /check-code --->|                       |
  |                        |-- verify code ------->|
  |<-- verified: true -----|                       |
```

If a SIM swap is detected, the flow steps up to email OTP. If silent auth fails on the client (no cellular, unsupported carrier), the client calls `/fallback-sms` and the user is prompted to enter an SMS code instead.

---

## References

- [Vonage Verify API Documentation](https://developer.vonage.com/en/verify/overview)
- [Vonage Network APIs — Silent Authentication](https://developer.vonage.com/en/network-apis/silent-auth/overview)
- [Vonage Network APIs — SIM Swap](https://developer.vonage.com/en/network-apis/sim-swap/overview)
- [Vonage Android Client SDK](https://developer.vonage.com/en/vonage-client-sdk/overview)