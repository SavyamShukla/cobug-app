# COBUG AI Integration System

## Providers

Each COBUG account now has persistent AI settings:

- `ollama` — local Ollama, free.
- `ollama-cloud` — Ollama Cloud, controlled by COBUG Cloud AI credits.
- `gemini` — Gemini API, controlled by the same Cloud AI entitlement.

The selected provider and model are stored per user in `ai_settings`. The existing `application.properties` values remain defaults for new accounts.

## Cloud credits

The development build uses `COBUG_AI_TEST_ACTIVATION=true` and grants 1000 credits through the Settings page without charging a payment method. One successful cloud AI request consumes one credit.

This is intentionally a **development/test activation**. Before public production, disable it:

```properties
cobug.ai.test-activation.enabled=false
```

and replace `POST /api/ai/settings/cloud/activate` with a payment-verified activation flow (for example Razorpay).

Credit transactions are persisted in `ai_credit_transactions`.

## Settings API

```text
GET  /api/ai/settings
PUT  /api/ai/settings
POST /api/ai/settings/cloud/activate
```

Cloud API keys remain backend environment variables. They are never returned to the frontend.

## Desktop migration later

The provider settings model is deliberately independent from the current web UI. When COBUG becomes a desktop application, the local Ollama connection can move from the backend machine to the user's own computer while keeping the same account-level provider/model settings.
