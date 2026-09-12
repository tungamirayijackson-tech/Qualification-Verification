-- V10__qualification_added_action.sql
--
-- Widens the audit ledger's action vocabulary by one value.
--
-- Recording a qualification is reference data, and it is audited anyway: a qualification is the
-- precondition for issuing credentials against it, so whoever adds one decides what this
-- register is capable of attesting to. That is closer to admitting an institution than to
-- editing a lookup table, and both of those were already audited.
--
-- The CHECK is recreated rather than relaxed. A ledger whose action column accepts anything is
-- a ledger that will eventually contain a typo nobody can query for.

ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_action_enum;

ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_action_enum CHECK (action IN (
    'CREDENTIAL_ISSUED', 'CREDENTIAL_REVOKED', 'CREDENTIAL_IMPORTED',
    'VERIFICATION_RUN',  'SHARE_TOKEN_ISSUED', 'SHARE_TOKEN_REVOKED',
    'TRAIL_EXPORTED',    'CHAIN_VERIFIED',
    'INSTITUTION_ONBOARDED', 'KEY_ROTATED', 'QUALIFICATION_ADDED',
    'USER_LOGGED_IN',    'USER_LOGIN_FAILED', 'REPORT_GENERATED'
));
