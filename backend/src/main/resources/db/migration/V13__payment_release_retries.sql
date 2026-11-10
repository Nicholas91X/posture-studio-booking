-- V13__payment_release_retries.sql
-- Step 9, after piece 4 (D-064): a release of a card hold that failed because Stripe did not answer is tried
-- again by a job, a few times with growing pauses, then given up and logged. Without it, the customer's card
-- would keep the hold for up to 7 days because of a problem of ours.

alter table payment
    -- how many releases failed so far
    add column release_attempts     integer         not null default 0,
    -- when to try again; null: no release owed (never failed, done, or given up)
    add column next_release_at      timestamptz;

alter table payment
    add constraint payment_release_attempts_ck check (release_attempts >= 0),
    -- only a hold still in place can be owed a release
    add constraint payment_next_release_ck     check (next_release_at is null or status = 'AUTHORIZED');

-- the job reads only the few rows that owe a release
create index payment_next_release_idx on payment (next_release_at) where next_release_at is not null;
