-- =====================================================================================
-- Align token-hash columns with Hibernate's VARCHAR expectation under stringtype=unspecified
--
-- The JDBC stringtype=unspecified flag (required so that inet/citext columns accept String
-- values) makes Hibernate infer char(64) columns as bpchar, which fails schema validation.
-- Changing these columns to varchar(64) makes both the inet write path and Hibernate's validate
-- mode happy simultaneously. The data is unaffected: the stored SHA-256 hex strings are exactly
-- 64 chars and have no business being blank-padded.
-- =====================================================================================

ALTER TABLE identity.refresh_tokens ALTER COLUMN token_hash TYPE varchar(64);
ALTER TABLE identity.password_reset_tokens ALTER COLUMN token_hash TYPE varchar(64);
ALTER TABLE identity.user_invitations ALTER COLUMN token_hash TYPE varchar(64);
ALTER TABLE identity.mfa_recovery_codes ALTER COLUMN code_hash TYPE varchar(64);
