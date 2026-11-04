-- COBUG authentication hardening
-- Existing installations using Hibernate ddl-auto=update can apply these columns automatically.
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS auth_provider VARCHAR(30) NOT NULL DEFAULT 'EMAIL';
ALTER TABLE users ADD COLUMN IF NOT EXISTS google_subject VARCHAR(255);
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;

CREATE TABLE IF NOT EXISTS email_verification_tokens (
    id UUID PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_email_verification_token_hash ON email_verification_tokens(token_hash);
CREATE INDEX IF NOT EXISTS idx_email_verification_user ON email_verification_tokens(user_id);

-- IMPORTANT: Existing accounts created by the old COBUG development auth system
-- are not automatically trusted. Review/verify them before production.

-- OTP authentication (current system):
-- Registration creates the users row only after the OTP is verified.
-- Password reset also requires OTP verification before a new password is accepted.
CREATE TABLE IF NOT EXISTS auth_otp_challenges (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    purpose VARCHAR(30) NOT NULL,
    otp_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_sent_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    registration_name VARCHAR(160),
    registration_password_hash VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_auth_otp_email_purpose ON auth_otp_challenges(email, purpose);
CREATE INDEX IF NOT EXISTS idx_auth_otp_hash ON auth_otp_challenges(otp_hash);
