-- V4__create_booking_and_approval_settings.sql

-- ***** PRACTITIONER SETTINGS *****

-- open future bookings per customer (D-023, D-041) and approval mode (D-030).
-- not null with a default: existing rows get the default
alter table practitioner
    add column max_future_bookings  integer not null default 4,
    add column booking_approval     text    not null default 'MANUAL',
    add constraint practitioner_max_future_bookings_ck check (max_future_bookings > 0),
    add constraint practitioner_booking_approval_ck    check (booking_approval in ('AUTOMATIC', 'MANUAL'));

-- response time for manual approval (D-030, D-042), in three steps (guide 18, 6.3).
-- 1. the column, with the default for the existing rows
alter table practitioner add column approval_window_hours integer not null default 12;

-- 2. an existing row with a notice shorter than the default would violate the constraint below
update practitioner set approval_window_hours = least(approval_window_hours, min_booking_notice_hours);

-- 3. only now the constraint, which every row already satisfies. The answer always comes before the
-- session starts (window <= notice), and well within the life of the hold on the card, about 7 days (D-031).
-- Same rule as PractitionerSettings.WindowWithinNotice and Practitioner.MAX_APPROVAL_WINDOW_HOURS
alter table practitioner add constraint practitioner_approval_window_hours_ck
    check (approval_window_hours >= 1
           and approval_window_hours <= min_booking_notice_hours
           and approval_window_hours <= 48);


-- ***** SERVICE *****

-- an evaluation visit: its overlap with a session of the other practitioner is flagged in the panel,
-- never prevented (one room, D-043). Existing services are sessions
alter table service add column evaluation boolean not null default false;


-- ***** BOOKING *****

-- A booking of a customer with a practitioner. Only the values step 7 produces are allowed (D-042):
-- steps 10 and 11 widen the checks with a new migration
create table booking (
    id                  bigint          generated always as identity,
    customer_id         bigint          not null,
    practitioner_id     bigint          not null,
    service_id          bigint          not null,
    starts_at           timestamptz     not null,
    -- stored: changing the duration of the service never moves a booked session
    ends_at             timestamptz     not null,
    status              text            not null,
    -- who booked: the customer, or the practitioner of the booking from the panel (D-024, D-042)
    booked_by           text            not null,
    -- CREDIT arrives with step 10
    paid_with           text            not null,
    -- copied from the service at booking time: changing the price never alters the past (D-011, D-022)
    price_cents         bigint          not null,
    -- end of the 30-minute hold while the customer pays (D-008)
    hold_expires_at     timestamptz     ,
    -- request + approval_window_hours, copied at request time (D-030)
    approval_deadline   timestamptz     ,
    -- approved or rejected, by the practitioner or automatically
    decided_at          timestamptz     ,
    -- the customer gave up: withdrawn now, cancelled from step 11
    cancelled_at        timestamptz     ,
    -- optimistic lock of JPA (@Version)
    version             bigint          not null default 0,
    created_at          timestamptz     not null default now(),

    constraint booking_pk                       primary key (id),
    -- customers, practitioners and services are never deleted: no cascade
    constraint booking_customer_fk              foreign key (customer_id) references customer (id),
    constraint booking_practitioner_fk          foreign key (practitioner_id) references practitioner (id),
    constraint booking_service_fk               foreign key (service_id) references service (id),
    -- the service belongs to the practitioner of the booking (same technique as D-025)
    constraint booking_service_practitioner_fk  foreign key (service_id, practitioner_id)
                                                references service (id, practitioner_id),
    constraint booking_times_ck                 check (ends_at > starts_at),
    -- same values as BookingStatus
    constraint booking_status_ck                check (status in ('PENDING_PAYMENT', 'PENDING_APPROVAL', 'CONFIRMED',
                                                                  'EXPIRED', 'REJECTED', 'APPROVAL_EXPIRED', 'WITHDRAWN')),
    constraint booking_booked_by_ck             check (booked_by in ('CUSTOMER', 'PRACTITIONER')),
    constraint booking_paid_with_ck             check (paid_with in ('CARD', 'IN_STUDIO')),
    -- paying in the studio is a choice of the practitioner, never of a customer booking alone (D-024)
    constraint booking_in_studio_ck             check (paid_with <> 'IN_STUDIO' or booked_by = 'PRACTITIONER'),
    constraint booking_price_cents_ck           check (price_cents > 0),
    -- "if pending payment, then it has its hold", written as "not A or B".
    -- The columns are not cleared when the status changes: they are history
    constraint booking_hold_ck                  check (status <> 'PENDING_PAYMENT'  or hold_expires_at   is not null),
    constraint booking_deadline_ck              check (status <> 'PENDING_APPROVAL' or approval_deadline is not null),
    -- Two bookings that hold the time cannot overlap for the same practitioner (D-013, D-042).
    -- Half-open ranges [start, end): back-to-back sessions (9-10, 10-11) are allowed.
    -- Partial: only the rows in these states are in the index, so a rejected or expired booking frees the time.
    -- A pending payment with an expired hold is still in it: an index predicate cannot use now() (guide 18, 3.5).
    -- Same states as BookingStatus.HOLDS_TIME
    constraint booking_no_overlap_ex            exclude using gist (
                                                    practitioner_id                 with =,
                                                    tstzrange(starts_at, ends_at)   with &&
                                                ) where (status in ('PENDING_PAYMENT', 'PENDING_APPROVAL', 'CONFIRMED'))
);

-- PostgreSQL does not index foreign keys by itself, and the index of the exclusion constraint holds only
-- the bookings that hold the time: the lists show the others too.
-- "the bookings of X in this period" (panel lists, occupied ranges)
create index booking_practitioner_id_starts_at_ix on booking (practitioner_id, starts_at);
-- "the future bookings of this customer" (limit, the customer's own list)
create index booking_customer_id_starts_at_ix on booking (customer_id, starts_at);
