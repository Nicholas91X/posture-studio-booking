-- V10__create_payment_accounts_and_payment.sql
-- Step 9, piece 1 (D-057, guide 30): which Stripe account each practitioner collects on, and the payments.

-- 1. The account assigned to a practitioner, chosen by the admin (guide 30, part 5). At most one row per
-- practitioner; reassigning is an update. The old assignment is not kept: every payment keeps its own
-- account (below), which is the only history that matters. The database checks the FORMAT of the name;
-- whether the name is configured only Java can know (PaymentAccounts, checked at startup and when assigning)
create table practitioner_payment_account (
    practitioner_id     bigint          not null,
    -- the name of an account declared in PAYMENT_ACCOUNTS: lower case letters and digits, it ends up in the
    -- name of an environment variable (STRIPE_<NAME>_SECRET_KEY). Never renamed (guide 30, 3.4)
    account             text            not null,
    assigned_at         timestamptz     not null,
    created_at          timestamptz     not null default now(),

    constraint practitioner_payment_account_pk              primary key (practitioner_id),
    constraint practitioner_payment_account_practitioner_fk foreign key (practitioner_id) references practitioner (id),
    constraint practitioner_payment_account_account_ck      check (account ~ '^[a-z][a-z0-9]{0,29}$')
);

-- 2. A payment of a session through Stripe Checkout (guide 30, part 7). Only what the sessions need: the
-- packages of step 10 add purpose, package_offer_id, credit_lot_id and customer_id with a migration of
-- their own (D-042). The currency is always euro, written where the Checkout session is created
create table payment (
    id                          bigint          generated always as identity,
    booking_id                  bigint          not null,
    -- the account the payment was made on, copied from the practitioner's assignment when it was created.
    -- Capture and cancel always go to THIS account, never to the practitioner's current one (D-057)
    account                     text            not null,
    -- copied from booking.price_cents (D-011, D-022)
    amount_cents                bigint          not null,
    status                      text            not null,
    -- the row is born after Stripe answered with the session (guide 30, 7.3)
    stripe_checkout_session_id  text            not null,
    -- arrives with the payment, in the webhook
    stripe_payment_intent_id    text,
    version                     bigint          not null default 0,
    -- written by Java with the Clock, as booking.created_at
    created_at                  timestamptz     not null default now(),

    constraint payment_pk                   primary key (id),
    constraint payment_booking_fk           foreign key (booking_id) references booking (id),
    constraint payment_account_ck           check (account ~ '^[a-z][a-z0-9]{0,29}$'),
    constraint payment_amount_cents_ck      check (amount_cents > 0),
    -- the five states of step 9 (guide 30, 7.2)
    constraint payment_status_ck            check (status in ('PENDING', 'AUTHORIZED', 'SUCCEEDED', 'CANCELED', 'EXPIRED')),
    constraint payment_checkout_session_uq  unique (stripe_checkout_session_id),
    constraint payment_payment_intent_uq    unique (stripe_payment_intent_id)
);

-- PostgreSQL does not index foreign keys by itself: the payments of a booking are read by booking_id
create index payment_booking_idx on payment (booking_id);

-- at most one payment waiting for the customer per booking (guide 30, 7.4): a partial unique index, the
-- uniqueness holds only for the rows of the where. Many EXPIRED payments for one booking are fine
create unique index payment_one_pending_per_booking_uq on payment (booking_id) where status = 'PENDING';
