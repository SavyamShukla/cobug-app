# COBUG Razorpay Payment Setup

COBUG subscriptions use a clean, straightforward Razorpay checkout flow.

## Plans & Pricing

- **COBUG Plus**: ₹1/month (100 paise on backend)
  - Unlocks BYO API keys (Gemini, ChatGPT, Ollama, Groq) and Code Context awareness in chat
- **COBUG Pro**: ₹2/month (200 paise on backend)
  - Full Plus features + autonomous agentic editing on editor
- **COBUG Go**: Free forever (deterministic local intent engine + BYO keys for chat)

## Required terminal variables (PowerShell)

Run these in the same PowerShell window that starts Spring Boot:

```powershell
$env:RAZORPAY_KEY_ID="rzp_test_..."
$env:RAZORPAY_KEY_SECRET="..."
```

Then start the backend normally:

```powershell
cd D:\Coding\Projects\COBUG\COBUG_APP\backend\cobug-api
.\mvnw.cmd spring-boot:run
```

The frontend never receives `RAZORPAY_KEY_SECRET`.

## Optional webhook

For production/reliable asynchronous payment confirmation, create a Razorpay webhook pointing to:

```text
POST https://YOUR_BACKEND_HOST/api/payments/razorpay/webhook
```

Set the webhook secret before starting Spring Boot:

```powershell
$env:RAZORPAY_WEBHOOK_SECRET="your-webhook-secret"
```

The checkout flow itself verifies the payment signature server-side and checks that the Razorpay payment is `captured`, so the webhook is an additional reconciliation path.

## Test mode

Use Razorpay Test Mode keys first. Once verified, replace them with Live Mode keys through environment variables; no source-code change is required.
