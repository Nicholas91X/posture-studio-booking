-- V8__phone_and_sms_codes.sql
-- The access code by SMS, from a verified number (requirements, open point 7; D-055, guide 29).

-- ***** app_user: the phone becomes a way to sign in *****

-- V1 created phone as free text, and no code ever wrote it. From now on the column means "a verified
-- number": a value nobody verified cannot stay. The system is not online yet (step 14)
update app_user set phone = null where phone is not null;

alter table app_user add column phone_verified_at timestamptz;

alter table app_user
    -- E.164: +, country code, number, at most 15 digits. Which numbers are accepted (Italian mobiles only)
    -- is a rule of Java (PhoneNumber), which can change without a migration; the format is the database's
    add constraint app_user_phone_e164_ck       check (phone ~ '^\+[1-9][0-9]{7,14}$'),
    -- written only by the verification (guide 29, 2.4): a number and the instant it was verified, or neither
    add constraint app_user_phone_verified_ck   check ((phone is null) = (phone_verified_at is null)),
    -- one account per number: nobody can take a number without receiving its code (several nulls are fine)
    add constraint app_user_phone_uq            unique (phone);

-- ***** login_code: the SMS channel, the purpose, who asks a verification, whether a message left *****

alter table login_code drop constraint login_code_channel_ck;

alter table login_code
    add constraint login_code_channel_ck check (channel in ('EMAIL', 'SMS')),
    -- a verification code never signs in, a sign-in code never verifies (guide 29, 3.2).
    -- The default is what every existing row is: an email sign-in code. Java always writes it
    add column purpose          text        not null default 'SIGN_IN',
    -- the account that asked to verify the number: the code confirms it for that account only (guide 29, 3.3)
    add column requested_by     bigint,
    -- false for an SMS sign-in to a number that is nobody's: the row exists only so that the limits behave
    -- the same (guide 29, 4.3); the daily cap counts the messages that really left
    add column message_sent     boolean     not null default true;

alter table login_code
    add constraint login_code_purpose_ck            check (purpose in ('SIGN_IN', 'VERIFY_PHONE')),
    add constraint login_code_requested_by_fk       foreign key (requested_by) references app_user (id),
    add constraint login_code_requested_by_ck       check ((purpose = 'VERIFY_PHONE') = (requested_by is not null)),
    -- a number is verified by SMS only
    add constraint login_code_verify_by_sms_ck      check (purpose = 'SIGN_IN' or channel = 'SMS'),
    -- an email always leaves (D-050: the code, or "account deactivated")
    add constraint login_code_email_sent_ck         check (channel = 'SMS' or message_sent);

-- the per-account limit of the verifications, and the foreign key
create index login_code_requested_by_idx on login_code (requested_by, created_at);
