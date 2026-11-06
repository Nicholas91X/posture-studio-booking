-- V3__create_schedule_and_booking_settings.sql

-- ***** EXTENSIONS AND TYPES *****

-- = on plain columns inside a GiST index, needed by working_hours_no_overlap_ex.
-- Trusted extension since PostgreSQL 13: the database owner can create it. Step 7 needs it too (D-013)
create extension if not exists btree_gist;

-- a range of times of day. The two-argument constructor builds [start, end):
-- start included, end excluded, the same convention as the free slot calculation (D-035)
create type timerange as range (subtype = time);


-- ***** PRACTITIONER SETTINGS *****

-- slot step: only 30 or 60 minutes, hourly by default (D-033). V1 accepted any positive value, default 30.
-- Existing rows keep their value: a new default only applies to new rows
alter table practitioner
    drop constraint practitioner_slot_step_minutes_ck,
    add constraint practitioner_slot_step_minutes_ck check (slot_step_minutes in (30, 60)),
    alter column slot_step_minutes set default 60;

-- a customer books only slots starting more than N hours from now (D-030);
-- and at most N days ahead (D-023, column brought forward from step 7 by D-035).
-- not null with a default: existing rows get the default
alter table practitioner
    add column min_booking_notice_hours integer not null default 24,
    add column booking_horizon_days     integer not null default 21,
    add constraint practitioner_min_booking_notice_hours_ck check (min_booking_notice_hours > 0),
    add constraint practitioner_booking_horizon_days_ck     check (booking_horizon_days > 0);


-- ***** SCHEDULE *****

-- A version of the weekly schedule, valid from a local date (D-034).
-- For a day D the version with the latest valid_from not after D applies.
-- A version already started is never changed (a rule in Java: it depends on today)
create table working_schedule (
    id                  bigint          generated always as identity,
    practitioner_id     bigint          not null,
    valid_from          date            not null,
    created_at          timestamptz     not null default now(),

    constraint working_schedule_pk                          primary key (id),
    -- practitioners are never deleted: no cascade
    constraint working_schedule_practitioner_fk             foreign key (practitioner_id) references practitioner (id),
    -- two versions from the same day would be ambiguous. Its index also serves "the versions of X"
    constraint working_schedule_practitioner_valid_from_uk  unique (practitioner_id, valid_from)
);

-- A working band of a version: local times of day, converted to instants only when slots are computed.
-- Several rows on the same day are separate bands (morning, afternoon)
create table working_hours (
    id                  bigint          generated always as identity,
    schedule_id         bigint          not null,
    -- 1 = Monday ... 7 = Sunday, as java.time.DayOfWeek.getValue()
    day_of_week         smallint        not null,
    start_time          time            not null,
    end_time            time            not null,
    created_at          timestamptz     not null default now(),

    constraint working_hours_pk                 primary key (id),
    -- a band means nothing without its version, and future versions can be deleted (D-034)
    constraint working_hours_schedule_fk        foreign key (schedule_id) references working_schedule (id)
                                                on delete cascade,
    constraint working_hours_day_of_week_ck     check (day_of_week between 1 and 7),
    -- no band across midnight
    constraint working_hours_times_ck           check (end_time > start_time),
    -- bands start and end on the hour or on the half hour, seconds included (D-033)
    constraint working_hours_start_time_ck      check (extract(minute from start_time) in (0, 30)
                                                       and extract(second from start_time) = 0),
    constraint working_hours_end_time_ck        check (extract(minute from end_time) in (0, 30)
                                                       and extract(second from end_time) = 0),
    -- two bands of the same version and day cannot overlap; touching bands (9-13, 13-14) can.
    -- Its GiST index starts with schedule_id: it also serves "the bands of a version"
    constraint working_hours_no_overlap_ex      exclude using gist (
                                                    schedule_id                     with =,
                                                    day_of_week                     with =,
                                                    timerange(start_time, end_time) with &&
                                                )
);

-- An absence of the practitioner: a single event, so instants (D-035).
-- Absences may overlap each other: the calculation removes their union
create table unavailability (
    id                  bigint          generated always as identity,
    practitioner_id     bigint          not null,
    starts_at           timestamptz     not null,
    ends_at             timestamptz     not null,
    -- visible to the practitioner only, never in public APIs
    reason              text            ,
    created_at          timestamptz     not null default now(),

    constraint unavailability_pk                primary key (id),
    constraint unavailability_practitioner_fk   foreign key (practitioner_id) references practitioner (id),
    constraint unavailability_times_ck          check (ends_at > starts_at)
);

-- "the absences of X in this period"
create index unavailability_practitioner_id_starts_at_ix on unavailability (practitioner_id, starts_at);
