# COBUG Authentication System

COBUG authentication uses:
- Email/password registration with mandatory OTP verification before the account is created.
- Brevo HTTPS API for registration and password-reset OTP email delivery.
- Native Google OAuth for the Electron desktop application using a Google Desktop OAuth client and PKCE.
- Password reset through OTP verification followed by a new password.

## Local setup

Set these backend environment variables before testing authentication:

- `BREVO_API_KEY` — Brevo transactional email API key; backend-only.
- `COBUG_MAIL_FROM` — verified sender email in Brevo.
- `COBUG_MAIL_FROM_NAME` — sender display name (default `COBUG`).
- `GOOGLE_CLIENT_ID` — the full Google OAuth **Desktop application** client ID.
- `GOOGLE_CLIENT_SECRET` — optional; the desktop flow uses PKCE and does not require a secret.

The active mail implementation calls Brevo's HTTPS transactional email endpoint. SMTP configuration is not used. Do not set or depend on `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, or any `spring.mail.*` property.

The active Google implementation does not use Google Identity Services browser JavaScript or `VITE_GOOGLE_CLIENT_ID`. The COBUG desktop renderer asks the backend for an authorization URL, opens Google's sign-in page, and receives the resulting COBUG JWT through the OAuth callback window.

For packaged desktop testing, COBUG's renderer uses the stable local origin `http://127.0.0.1:43127`/`http://localhost:43127`. The backend Google callback is `http://127.0.0.1:8080/api/auth/google/callback`.

## Production

Keep `BREVO_API_KEY`, Google credentials, JWT secret, database credentials, GitHub App credentials, and other secrets out of Git and out of the Electron bundle. Configure them as backend environment variables.

The Brevo API key is never exposed to the frontend. Google authorization-code exchange is performed by the backend, and the frontend receives only the resulting COBUG session JWT.

## Existing local users

Existing accounts can continue to use email/password if they have `email_verified=true`. New email registrations are not inserted into the users table until their OTP is verified.
