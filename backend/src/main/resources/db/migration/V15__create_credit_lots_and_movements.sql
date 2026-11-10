-- V15__create_credit_lots_and_movements.sql
-- Step 10, piece 1 (D-069, guide 36): the credit ledger. Credits are movements that are never changed, and
-- the balance is their sum (D-009); every credit belongs to a lot with its own last valid day (D-010).
-- The database defends the ledger by itself, also against a bug in Java or an update typed by hand:
-- movements and extensions are append-only, a lot changes only its last valid day and only with its trace,
-- and no lot goes below zero. Only the values step 10 produces are in the checks (D-042, guide 36, 3.3):
-- CANCELLATION and CANCELLATION_RESTORE arrive with step 11.


-- ***** 1. BOOKING: paid with a credit *****

alter table booking drop constraint booking_paid_with_ck;

alter table booking
    -- same values as PaidWith
    add constraint booking_paid_with_ck      check (paid_with in ('CARD', 'IN_STUDIO', 'CREDIT')),
    -- a booking with a credit goes straight to the approval: it never waits for a payment (guide 36, part 11)
    add constraint booking_credit_no_hold_ck check (paid_with <> 'CREDIT' or status <> 'PENDING_PAYMENT');


-- ***** 2. PAYMENT: packages too (D-060, guide 36, part 10) *****

alter table payment
    -- what is paid: a session (every existing row) or a package. The default only fills the existing rows,
    -- then it goes: from now on Java always says the purpose
    add column purpose                  text        not null default 'BOOKING',
    add column package_offer_id         bigint,
    -- who buys the package; for a session it is the customer of the booking, so it is not copied here
    add column customer_id              bigint,
    -- what the customer saw on the Stripe page, copied when the session is created: the package on sale can
    -- change, or be deactivated, before the webhook arrives (guide 36, 10.2). The price is amount_cents
    add column package_credits          integer,
    add column package_validity_days    integer,
    alter column booking_id drop not null;

alter table payment alter column purpose drop default;

alter table payment
    add constraint payment_purpose_ck           check (purpose in ('BOOKING', 'PACKAGE')),
    add constraint payment_package_offer_fk     foreign key (package_offer_id) references package_offer (id),
    add constraint payment_customer_fk          foreign key (customer_id) references customer (id),
    -- the columns of each purpose, and only those. Every column that must be there has its own "is not null":
    -- a check accepts a row when its condition is NULL, and "null >= 2" is NULL, not false (guide 36, 10.3).
    -- A package is bought only by the customer's request (a link pays a session, D-065), and with the
    -- immediate capture (D-031) it is never AUTHORIZED nor CANCELED: PENDING, then SUCCEEDED or EXPIRED
    add constraint payment_purpose_columns_ck   check (
           (purpose = 'BOOKING' and booking_id is not null
                and package_offer_id is null and customer_id is null
                and package_credits is null and package_validity_days is null)
        or (purpose = 'PACKAGE' and booking_id is null
                and package_offer_id is not null and customer_id is not null
                and package_credits is not null and package_credits >= 2
                and package_validity_days is not null and package_validity_days > 0
                and source = 'REQUEST' and status in ('PENDING', 'SUCCEEDED', 'EXPIRED'))
    );

-- "the packages bought by this customer"
create index payment_customer_idx on payment (customer_id) where customer_id is not null;


-- ***** 3. CREDIT_LOT: credits with the same origin and the same last valid day *****

create table credit_lot (
    id                  bigint          generated always as identity,
    customer_id         bigint          not null,
    practitioner_id     bigint          not null,
    -- the credits are valid for this service only
    service_id          bigint          not null,
    -- same values as LotSource
    source              text            not null,
    -- PACKAGE: the payment that bought the lot, the package, and the price paid. The price is a copy across
    -- modules (credit does not read payment's tables, D-002), as booking.price_cents (D-011, guide 36, 4.3)
    payment_id          bigint,
    package_offer_id    bigint,
    price_cents         bigint,
    -- GRANT: who gave it and why (D-067). The "when" is created_at
    granted_by          bigint,
    note                text,
    -- the last day the credits can be used, a calendar day in Europe/Rome: on that day they work, the day
    -- after they do not (D-069, guide 36, part 8). A date, like working_schedule.valid_from (D-034).
    -- Changed only by an extension, forward only, with its row in credit_lot_extension (trigger below)
    valid_until         date            not null,
    -- written by Java with the Clock, as booking.created_at
    created_at          timestamptz     not null,

    constraint credit_lot_pk                        primary key (id),
    -- customers, practitioners, services and payments are never deleted: no cascade
    constraint credit_lot_customer_fk               foreign key (customer_id) references customer (id),
    constraint credit_lot_practitioner_fk           foreign key (practitioner_id) references practitioner (id),
    constraint credit_lot_service_fk                foreign key (service_id) references service (id),
    -- the service belongs to the practitioner of the lot (D-025, guide 36, 4.1)
    constraint credit_lot_service_practitioner_fk   foreign key (service_id, practitioner_id)
                                                    references service (id, practitioner_id),
    constraint credit_lot_payment_fk                foreign key (payment_id) references payment (id),
    constraint credit_lot_package_offer_fk          foreign key (package_offer_id) references package_offer (id),
    constraint credit_lot_granted_by_fk             foreign key (granted_by) references app_user (id),
    constraint credit_lot_source_ck                 check (source in ('PACKAGE', 'GRANT')),
    -- one payment buys one lot: a webhook delivered twice cannot create a second one (guide 36, 4.5).
    -- Many lots without a payment are fine: two NULLs are not equal in a unique index
    constraint credit_lot_payment_uq                unique (payment_id),
    -- a lot comes from a package if and only if it has its payment (Nicholas, 2026-11-10)
    constraint credit_lot_payment_ck                check ((source = 'PACKAGE') = (payment_id is not null)),
    -- the package and the price come with the payment, and only with it
    constraint credit_lot_package_columns_ck        check ((package_offer_id is null) = (payment_id is null)
                                                           and (price_cents is null) = (payment_id is null)),
    constraint credit_lot_price_cents_ck            check (price_cents > 0),
    -- a gift has its trace in its own row, so that a check can demand it (guide 36, 4.4)
    constraint credit_lot_grant_ck                  check ((source = 'GRANT') = (granted_by is not null)),
    constraint credit_lot_note_ck                   check ((note is null) = (granted_by is null)
                                                           and (note is null or btrim(note) <> ''))
);

-- the usable lots of a customer for a practitioner and a service, the one that ends first first (piece 3)
create index credit_lot_customer_usable_ix on credit_lot (customer_id, practitioner_id, service_id, valid_until);
-- the lots of a practitioner (panel lists, D-066)
create index credit_lot_practitioner_id_ix on credit_lot (practitioner_id);


-- ***** 4. CREDIT_MOVEMENT: the ledger *****

create table credit_movement (
    id                  bigint          generated always as identity,
    credit_lot_id       bigint          not null,
    -- +N or -1; an adjustment can be any number but zero
    amount              integer         not null,
    -- same values as MovementReason
    reason              text            not null,
    -- the booking that took the credit, or gave it back
    booking_id          bigint,
    -- ADJUSTMENT: who made it and why (D-067). The "when" is created_at
    created_by          bigint,
    note                text,
    -- written by Java with the Clock
    created_at          timestamptz     not null,

    constraint credit_movement_pk               primary key (id),
    constraint credit_movement_credit_lot_fk    foreign key (credit_lot_id) references credit_lot (id),
    constraint credit_movement_booking_fk       foreign key (booking_id) references booking (id),
    constraint credit_movement_created_by_fk    foreign key (created_by) references app_user (id),
    constraint credit_movement_reason_ck        check (reason in ('LOT_CREATED', 'BOOKING', 'BOOKING_RELEASE', 'ADJUSTMENT')),
    constraint credit_movement_amount_ck        check (amount <> 0),
    -- the shape of each reason (guide 36, 5.1)
    constraint credit_movement_shape_ck         check (
           (reason = 'LOT_CREATED'     and amount > 0  and booking_id is null)
        or (reason = 'BOOKING'         and amount = -1 and booking_id is not null)
        or (reason = 'BOOKING_RELEASE' and amount = 1  and booking_id is not null)
        or (reason = 'ADJUSTMENT'      and booking_id is null)
    ),
    -- an adjustment always says who and why; the other movements never do
    constraint credit_movement_trace_ck         check ((reason = 'ADJUSTMENT') = (created_by is not null)
                                                       and (note is null) = (created_by is null)
                                                       and (note is null or btrim(note) <> ''))
);

-- the "only once" rules (guide 36, 5.2), as partial unique indexes:
-- a lot is born once
create unique index credit_movement_one_lot_created_uq on credit_movement (credit_lot_id) where reason = 'LOT_CREATED';
-- a booking takes one credit at most
create unique index credit_movement_one_debit_per_booking_uq on credit_movement (booking_id) where reason = 'BOOKING';
-- a booking gives back one credit at most, whatever the reason (a rejection now, a cancellation at step 11):
-- even if a bug let a rejection and an expired answer both through, no credit would be given twice
create unique index credit_movement_one_return_per_booking_uq on credit_movement (booking_id)
    where amount > 0 and booking_id is not null;
-- the balance of a lot is the sum of its movements
create index credit_movement_credit_lot_id_ix on credit_movement (credit_lot_id);


-- ***** 5. CREDIT_LOT_EXTENSION: the history of the extensions (D-067, guide 36, part 9) *****

create table credit_lot_extension (
    id                  bigint          generated always as identity,
    credit_lot_id       bigint          not null,
    old_valid_until     date            not null,
    new_valid_until     date            not null,
    -- who extended it and why; the "when" is created_at
    extended_by         bigint          not null,
    note                text            not null,
    -- written by Java with the Clock
    created_at          timestamptz     not null,

    constraint credit_lot_extension_pk              primary key (id),
    constraint credit_lot_extension_credit_lot_fk   foreign key (credit_lot_id) references credit_lot (id),
    constraint credit_lot_extension_extended_by_fk  foreign key (extended_by) references app_user (id),
    -- forward only (guide 36, 9.2): moving it back would take away days the customer had, in silence
    constraint credit_lot_extension_forward_ck      check (new_valid_until > old_valid_until),
    constraint credit_lot_extension_note_ck         check (btrim(note) <> '')
);

create index credit_lot_extension_credit_lot_id_ix on credit_lot_extension (credit_lot_id);


-- ***** 6. THE DEFENSES OF THE LEDGER (guide 36, parts 6, 7, 9) *****
-- Triggers fire for every user, superuser included, which is what the application connects as today.
-- They guard against mistakes, not against an administrator: a superuser can switch them off. In production
-- the application will connect as a user that is not a superuser, without update, delete and truncate on
-- credit_movement (step 14). Errors use SQLSTATE class 23 (integrity), as the constraints do

-- the ledger only grows: rows are added, never changed nor deleted (a lot can change its last valid day: below)
create function credit_forbid_change() returns trigger language plpgsql as $$
begin
    raise exception '% on % is not allowed: the credit ledger only grows', tg_op, tg_table_name
        using errcode = 'restrict_violation';
end
$$;

create trigger credit_movement_append_only_tg
    before update or delete on credit_movement
    for each row execute function credit_forbid_change();

-- a truncate empties the table without touching the rows one by one: the row triggers above do not fire,
-- it needs its own trigger, per statement
create trigger credit_movement_no_truncate_tg
    before truncate on credit_movement
    for each statement execute function credit_forbid_change();

create trigger credit_lot_extension_append_only_tg
    before update or delete on credit_lot_extension
    for each row execute function credit_forbid_change();

create trigger credit_lot_extension_no_truncate_tg
    before truncate on credit_lot_extension
    for each statement execute function credit_forbid_change();

create trigger credit_lot_no_delete_tg
    before delete on credit_lot
    for each row execute function credit_forbid_change();

create trigger credit_lot_no_truncate_tg
    before truncate on credit_lot
    for each statement execute function credit_forbid_change();

-- A lot changes only its last valid day, and only together with the row of its extension: an update typed by
-- hand cannot move a date without leaving its trace (guide 36, 9.1). The extension row comes first: JPA writes
-- it at persist (its id comes from the database), before the update of the lot
create function credit_lot_only_extend() returns trigger language plpgsql as $$
begin
    if (new.id, new.customer_id, new.practitioner_id, new.service_id, new.source, new.payment_id,
        new.package_offer_id, new.price_cents, new.granted_by, new.note, new.created_at)
       is distinct from
       (old.id, old.customer_id, old.practitioner_id, old.service_id, old.source, old.payment_id,
        old.package_offer_id, old.price_cents, old.granted_by, old.note, old.created_at) then
        raise exception 'credit_lot %: only valid_until can change', old.id
            using errcode = 'restrict_violation';
    end if;
    if new.valid_until is distinct from old.valid_until and not exists (
            select 1 from credit_lot_extension e
             where e.credit_lot_id = new.id
               and e.old_valid_until = old.valid_until
               and e.new_valid_until = new.valid_until) then
        raise exception 'credit_lot %: valid_until changes only with its credit_lot_extension row', old.id
            using errcode = 'restrict_violation';
    end if;
    return new;
end
$$;

create trigger credit_lot_only_extend_tg
    before update on credit_lot
    for each row execute function credit_lot_only_extend();

-- No lot goes below zero (guide 36, part 7). A check sees one row, and "the sum of these rows" is many rows:
-- it takes a trigger, and a lock. Every debit of a lot waits on the lot's row, then sums.
-- FOR NO KEY UPDATE, not FOR UPDATE: two debits still queue (the lock conflicts with itself), but a credit
-- that comes back is not slowed down, because the foreign key check of its insert takes FOR KEY SHARE on the
-- lot, which does not conflict with it. Adding never needs the lock: it cannot go below zero.
-- WORKS ONLY IN READ COMMITTED, the default isolation we use (D-045): there every statement of a volatile
-- function (a trigger is one) takes a fresh snapshot, so the sum, run after the wait, sees the debit the other
-- transaction just committed. In REPEATABLE READ the snapshot is taken once, before the wait, and the sum would
-- miss it. Java checks the same thing under the same lock (CreditService, piece 3), for a readable answer
create function credit_movement_keep_balance() returns trigger language plpgsql as $$
declare
    balance bigint;
begin
    if new.amount < 0 then
        perform 1 from credit_lot where id = new.credit_lot_id for no key update;
        select coalesce(sum(amount), 0) into balance
          from credit_movement
         where credit_lot_id = new.credit_lot_id;
        if balance + new.amount < 0 then
            raise exception 'credit_lot %: balance cannot go below zero (balance %, movement %)',
                new.credit_lot_id, balance, new.amount
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end
$$;

create trigger credit_movement_keep_balance_tg
    before insert on credit_movement
    for each row execute function credit_movement_keep_balance();
