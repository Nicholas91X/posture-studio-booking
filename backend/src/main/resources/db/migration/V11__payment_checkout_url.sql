-- V11__payment_checkout_url.sql
-- Step 9, piece 2 (guide 31, part 9): the address of the Stripe page, kept so that the customer can find it
-- again (a lost response, "complete the payment" from the list) without asking Stripe at every reading.
-- A new migration and not a change to V10: V10 has already run, and a migration that ran is never changed.

-- No row exists yet (nothing wrote payments before this piece), so "not null" can be added at once
alter table payment add column checkout_url text not null;
