-- V11__user_created_action.sql
--
-- Widens the audit ledger's action vocabulary by one value.
--
-- Creating an account is how every other power in this system is handed out: a REGISTRAR can
-- sign credentials on an institution's behalf, an ADMIN can admit institutions. "Who let them
-- in" is exactly the kind of question the ledger exists to answer, and it could not answer it
-- before, because nothing could create a user at all.

ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_action_enum;

ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_action_enum CHECK (action IN (
    'CREDENTIAL_ISSUED', 'CREDENTIAL_REVOKED', 'CREDENTIAL_IMPORTED',
    'VERIFICATION_RUN',  'SHARE_TOKEN_ISSUED', 'SHARE_TOKEN_REVOKED',
    'TRAIL_EXPORTED',    'CHAIN_VERIFIED',
    'INSTITUTION_ONBOARDED', 'KEY_ROTATED', 'QUALIFICATION_ADDED',
    'USER_CREATED',      'USER_LOGGED_IN',    'USER_LOGIN_FAILED',
    'REPORT_GENERATED'
));
