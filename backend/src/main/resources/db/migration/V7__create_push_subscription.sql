-- V7__create_push_subscription.sql
-- The devices of the practitioners registered for push notifications (D-032, guide 28, part 6).
-- One row per browser on a device: what the browser's PushSubscription gives, as it gives it.

create table push_subscription (
    id                  bigint          generated always as identity,
    practitioner_id     bigint          not null,
    -- where the push service receives the messages for this browser. Checked when registered: https
    -- towards a known push service only (@PushEndpoint, guide 28, part 7). Never written in full in the logs
    endpoint            text            not null,
    -- the public key of the browser (P-256, uncompressed, base64url) and the shared auth secret (16 bytes,
    -- base64url): what the message is encrypted with (RFC 8291)
    p256dh              text            not null,
    auth                text            not null,
    -- the last registration: the frontend registers again at every visit; the oldest go first past the limit
    registered_at       timestamptz     not null default now(),

    constraint push_subscription_pk                 primary key (id),
    constraint push_subscription_practitioner_fk    foreign key (practitioner_id) references practitioner (id),
    -- one row per browser: registering again updates it (insert ... on conflict (endpoint) do update)
    constraint push_subscription_endpoint_uq        unique (endpoint)
);

-- the devices of a practitioner, at every request to approve
create index push_subscription_practitioner_idx on push_subscription (practitioner_id, registered_at);
