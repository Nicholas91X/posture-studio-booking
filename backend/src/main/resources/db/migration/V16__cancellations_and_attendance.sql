-- V16__cancellations_and_attendance.sql
-- Step 11, piece 1 (D-078, guide 42): the states after the confirmation, and the credits that come back.
-- A booking can now be cancelled by the customer (in time or late) or by the studio, and a past session
-- becomes completed or no-show. A cancellation that gives something back writes +1 on the same lot
-- (CANCELLATION_RESTORE) or a new lot of 1 (source CANCELLATION), never both: the existing index
-- "one return per booking" sees the two as the same thing (guide 42, 7.1).
-- PostgreSQL runs DDL in a transaction and Flyway runs each migration in one: between a "drop constraint"
-- and its "add constraint" no other transaction sees the table without the rule (guide 42, part 2).


-- ***** 1. BOOKING: the states after the confirmation *****

alter table booking drop constraint booking_status_ck;

-- same values as BookingStatus. booking_no_overlap_ex does NOT change: no new state holds the time
-- (a cancelled session frees it, a completed or no-show one is in the past)
alter table booking add constraint booking_status_ck check (status in (
    'PENDING_PAYMENT', 'PENDING_APPROVAL', 'CONFIRMED',
    'EXPIRED', 'REJECTED', 'APPROVAL_EXPIRED', 'WITHDRAWN',
    'CANCELLED_IN_TIME', 'CANCELLED_LATE', 'CANCELLED_BY_STUDIO',
    'COMPLETED', 'NO_SHOW'));

alter table booking
    -- who cancelled: the account that acted (the customer, the practitioner; one day maybe the admin, D-077),
    -- as granted_by and created_by of the credits (D-073). The "when" is cancelled_at, which the withdrawal
    -- already writes. Withdrawals keep their cancelled_at only: the existing rows have no "who"
    add column cancelled_by          bigint,
    -- who marked the attendance last, and when: a no-show, or its correction back to completed (guide 42,
    -- part 9). Null for a session completed by the job
    add column attendance_marked_by  bigint,
    add column attendance_marked_at  timestamptz,
    add constraint booking_cancelled_by_fk          foreign key (cancelled_by) references app_user (id),
    add constraint booking_attendance_marked_by_fk  foreign key (attendance_marked_by) references app_user (id),
    -- a cancellation always says who and when; "who" belongs only to the cancellations
    add constraint booking_cancellation_trace_ck    check (
        (status in ('CANCELLED_IN_TIME', 'CANCELLED_LATE', 'CANCELLED_BY_STUDIO'))
            = (cancelled_by is not null)
        and (cancelled_by is null or cancelled_at is not null)),
    -- a no-show always says who marked it; a mark (who and when together) belongs only to a past session
    add constraint booking_attendance_trace_ck      check (
        (attendance_marked_by is null) = (attendance_marked_at is null)
        and (status <> 'NO_SHOW' or attendance_marked_by is not null)
        and (attendance_marked_by is null or status in ('COMPLETED', 'NO_SHOW')));

-- The notice of cancellation photographed at booking time (guide 42, part 6): the rule the customer saw.
-- A practitioner who raises the notice later does not make the customers who already booked late.
-- In three steps, as V4 did for approval_window_hours:
-- 1. the column
alter table booking add column free_cancellation_until timestamptz;

-- 2. the existing rows, with the notice of their practitioner today: nobody could cancel before this step, so
-- this is the only notice that ever mattered for them
update booking b
   set free_cancellation_until = b.starts_at - make_interval(hours => p.cancellation_notice_hours)
  from practitioner p
 where p.id = b.practitioner_id;

-- 3. only now not null, and always before the start (the notice is at least one hour: practitioner_cancellation_notice_hours_ck of V1).
-- Whether that instant itself is still "in time" is the rule of piece 2
alter table booking
    alter column free_cancellation_until set not null,
    add constraint booking_free_cancellation_ck check (free_cancellation_until < starts_at);


-- ***** 2. CREDITS: the lot born from a cancellation, the credit that comes back *****

alter table credit_lot drop constraint credit_lot_source_ck;

-- same values as LotSource. CANCELLATION: a session paid by card and cancelled in time or by the studio, or a
-- credit whose lot ended too soon (minimum validity, D-076). Every CANCELLATION lot counts as "already
-- extended": a credit coming back to it is not extended again by a cancellation of the customer (guide 42,
-- part 8). No new column: the booking it comes from is in its LOT_CREATED movement (below)
alter table credit_lot add constraint credit_lot_source_ck
    check (source in ('PACKAGE', 'GRANT', 'CANCELLATION'));

alter table credit_movement drop constraint credit_movement_reason_ck;

-- same values as MovementReason. CANCELLATION_RESTORE: +1 on the same lot for a cancellation in time or by
-- the studio (D-075, D-076)
alter table credit_movement add constraint credit_movement_reason_ck
    check (reason in ('LOT_CREATED', 'BOOKING', 'BOOKING_RELEASE', 'ADJUSTMENT', 'CANCELLATION_RESTORE'));

alter table credit_movement drop constraint credit_movement_shape_ck;

-- A LOT_CREATED may now carry a booking: the lot of 1 born from that booking's cancellation. Then the index
-- credit_movement_one_return_per_booking_uq (V15: one positive movement with a booking, per booking) refuses
-- a second credit for the same cancellation, whether it is a +1 or a new lot (guide 42, 7.1).
-- That a LOT_CREATED with a booking is in a CANCELLATION lot, and the other way round, is a rule across two
-- tables: Java keeps it, in the one method that creates those lots (piece 2)
alter table credit_movement add constraint credit_movement_shape_ck check (
       (reason = 'LOT_CREATED'          and amount > 0  and (booking_id is null or amount = 1))
    or (reason = 'BOOKING'              and amount = -1 and booking_id is not null)
    or (reason = 'BOOKING_RELEASE'      and amount = 1  and booking_id is not null)
    or (reason = 'CANCELLATION_RESTORE' and amount = 1  and booking_id is not null)
    or (reason = 'ADJUSTMENT'           and booking_id is null)
);

-- REMINDER for every future migration on credit_lot: the trigger function credit_lot_only_extend (V15) lists
-- one by one the columns that must not change. A new column of credit_lot must be added there too, with
-- "create or replace function", or an update typed by hand could change it in silence (guide 42, 7.2).
-- This migration adds no column to credit_lot.
