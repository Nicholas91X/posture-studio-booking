-- V1__create_app_user_customer_practitioner.sql

create table app_user (
    id          bigint       generated always as identity,
    role        text         not null,
    email       text         not null,
    phone       text         ,
    active      boolean      not null default true,
    created_at  timestamptz  not null default now(),

    constraint app_user_pk           primary key (id),
    constraint app_user_email_uk     unique (email),
    -- role can only be one of the following values
    constraint app_user_role_ck      check (role in ('CUSTOMER', 'PRACTITIONER', 'ADMIN')),
    -- email should be stored in lowercase to avoid duplicates with different cases
    constraint app_user_email_ck     check (email = lower(email))
);

-- Customer table
create table customer (
    id          bigint          generated always as identity,
    user_id     bigint          not null,
    first_name  text            not null,
    last_name   text            not null,
    created_at  timestamptz     not null default now(),

    constraint customer_pk              primary key (id),
    -- user_id must be unique in the customer table, as each user can only be a customer once
    constraint customer_user_id_uk      unique (user_id),
    -- user_id must reference an existing user in the app_user table
    constraint customer_user_fk         foreign key (user_id) references app_user (id)
);

-- Practitioner table
create table practitioner (
    id                          bigint          generated always as identity,
    user_id                     bigint          not null,
    first_name                  text            not null,
    last_name                   text            not null,
    cancellation_notice_hours   integer         not null default 24,
    single_credit_validity_days integer         not null default 90,
    slot_step_minutes           integer         not null default 30,
    created_at                  timestamptz     not null default now(),

    constraint practitioner_pk                              primary key (id),
    constraint practitioner_user_id_uk                      unique (user_id),
    -- user_id must reference an existing user in the app_user table
    constraint practitioner_user_fk                         foreign key (user_id) references app_user (id),
    constraint practitioner_cancellation_notice_hours_ck    check (cancellation_notice_hours > 0),
    constraint practitioner_single_credit_validity_days_ck  check (single_credit_validity_days > 0),
    constraint practitioner_slot_step_minutes_ck            check (slot_step_minutes > 0)
);