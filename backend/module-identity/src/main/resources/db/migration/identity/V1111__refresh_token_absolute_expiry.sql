-- =====================================================================================
-- Absolute session lifetime cap
-- =====================================================================================
--
-- Rotation resets expires_at to now() + ttl on every use (RefreshTokenService.rotate), so a
-- session that is used at least once per ttl window renews forever. That is a sliding window,
-- not a session lifetime: a device left signed in and opened occasionally never forces
-- re-authentication.
--
-- family_started_at is stamped once, at the family's birth (issueNewFamily), and copied
-- unchanged onto every successor produced by rotation. It lets rotate() enforce an absolute
-- cap measured from the original login, independent of how often the token is refreshed.
ALTER TABLE identity.refresh_tokens
    ADD COLUMN family_started_at TIMESTAMPTZ;

UPDATE identity.refresh_tokens SET family_started_at = issued_at WHERE family_started_at IS NULL;

ALTER TABLE identity.refresh_tokens
    ALTER COLUMN family_started_at SET NOT NULL;

CREATE INDEX ix_refresh_tokens_family_started ON identity.refresh_tokens (family_started_at);
