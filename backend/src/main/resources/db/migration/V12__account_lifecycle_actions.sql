-- V12__account_lifecycle_actions.sql
--
-- Widens the ledger's vocabulary by the four things an administrator can now do to an account
-- after creating it: suspend it, restore it, reset its password, reset its second factor.
--
-- Each is a change to who may act on this system's behalf, which is the same class of event as
-- creating the account in the first place. The password reset entry is worth a word: what is
-- recorded is that a reset happened and by whom, never the password -- an entry carrying a
-- credential would make the ledger, which is readable by every auditor and exportable to CSV, a
-- more attractive target than the account it describes.

ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_action_enum;

ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_action_enum CHECK (action IN (
    'CREDENTIAL_ISSUED', 'CREDENTIAL_REVOKED', 'CREDENTIAL_IMPORTED',
    'VERIFICATION_RUN',  'SHARE_TOKEN_ISSUED', 'SHARE_TOKEN_REVOKED',
    'TRAIL_EXPORTED',    'CHAIN_VERIFIED',
    'INSTITUTION_ONBOARDED', 'KEY_ROTATED', 'QUALIFICATION_ADDED',
    'USER_CREATED',      'USER_DISABLED',     'USER_RESTORED',
    'USER_PASSWORD_RESET', 'USER_MFA_RESET',
    'USER_LOGGED_IN',    'USER_LOGIN_FAILED',
    'REPORT_GENERATED'
));
