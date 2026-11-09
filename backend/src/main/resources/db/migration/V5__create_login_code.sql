-- V5__create_login_code.sql
-- Access codes (D-014, guide 24). One table for every channel: the code goes to a destination
-- through a channel; only EMAIL today, step 8 piece 6 adds SMS with a new migration (D-042).

create table login_code (
    id              bigint          generated always as identity,
    channel         text            not null,
    -- the normalized email (D-020); from piece 6 also a phone number in E.164
    destination     text            not null,
    -- HMAC-SHA256 of destination and code with a secret key outside the database, in hex: never the code
    code_hash       text            not null,
    -- what the per-IP limit counts: the IPv4 address, or the /64 network of an IPv6 address
    -- (host bits set to zero). Personal data: rows are deleted after 24 hours (guide 24, 6.5)
    requested_from  inet            not null,
    -- written by Java with the Clock, as booking.created_at (D-046): expiry and limits count from here
    created_at      timestamptz     not null,
    expires_at      timestamptz     not null,
    attempts        integer         not null default 0,
    used_at         timestamptz,

    constraint login_code_pk            primary key (id),
    constraint login_code_channel_ck    check (channel in ('EMAIL')),
    -- 5 = LoginCode.MAX_ATTEMPTS (D-014)
    constraint login_code_attempts_ck   check (attempts between 0 and 5),
    constraint login_code_expiry_ck     check (expires_at > created_at)
);

-- the latest code of a destination, and the per-destination limit
create index login_code_destination_idx on login_code (channel, destination, created_at);
-- the per-IP limit
create index login_code_requested_from_idx on login_code (requested_from, created_at);
-- the hourly cleanup deletes by age (created_at is the last column of the other two indexes)
create index login_code_created_at_idx on login_code (created_at);
