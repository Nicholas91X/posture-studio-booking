-- V12__create_stripe_event.sql
-- Step 9, piece 3 (D-062, guide 32, part 5): the Stripe events already processed. Stripe delivers each
-- event AT LEAST once: a second delivery of the same id must change nothing.
-- Written in the same transaction as the processing: if processing fails the row goes too, and Stripe's
-- next attempt is processed as new.

create table stripe_event (
    -- evt_..., the id Stripe gives the event
    event_id            text            not null,
    -- the payment account whose endpoint received it (D-057): every account has its own webhook
    account             text            not null,
    type                text            not null,
    received_at         timestamptz     not null,

    constraint stripe_event_pk          primary key (event_id),
    constraint stripe_event_account_ck  check (account ~ '^[a-z][a-z0-9]{0,29}$')
);
