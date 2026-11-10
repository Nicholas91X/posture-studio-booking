-- V14__payment_source.sql
-- Step 9, piece 5 (D-065, guide 34, part 3): where a payment comes from. REQUEST, the customer's request
-- (piece 2); LINK, a payment link sent by the practitioner for a session to be paid in the studio (D-059).
-- The webhook decides by this column what a payment does: the truth about our payments is in our database,
-- not in Stripe's metadata. Every existing row is a REQUEST.

alter table payment
    add column source text not null default 'REQUEST';

alter table payment
    add constraint payment_source_ck check (source in ('REQUEST', 'LINK'));
