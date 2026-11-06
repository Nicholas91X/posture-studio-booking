-- V2__create_service_and_package_offer.sql

-- Service offered by a practitioner. Never deleted: deactivated with active = false
create table service (
    id                  bigint          generated always as identity,
    practitioner_id     bigint          not null,
    name                text            not null,
    duration_minutes    integer         not null,
    -- the only price: shown to the customer and charged, no VAT (D-022).
    -- bookings keep their own copy, so changing it never alters the past (D-011)
    price_cents         bigint          not null,
    active              boolean         not null default true,
    created_at          timestamptz     not null default now(),

    constraint service_pk                       primary key (id),
    constraint service_practitioner_fk          foreign key (practitioner_id) references practitioner (id),
    -- name must contain something other than spaces (null is rejected by not null)
    constraint service_name_ck                  check (btrim(name) <> ''),
    constraint service_duration_minutes_ck      check (duration_minutes > 0),
    constraint service_price_cents_ck           check (price_cents > 0),
    -- redundant with the primary key, but required as target of
    -- package_offer_service_practitioner_fk (D-025)
    constraint service_id_practitioner_id_uk    unique (id, practitioner_id)
);

create index service_practitioner_id_ix on service (practitioner_id);

-- Package on sale. A purchased package is not stored here: it becomes a credit lot (D-010)
create table package_offer (
    id                  bigint          generated always as identity,
    practitioner_id     bigint          not null,
    -- the credits of the package are valid for this service
    service_id          bigint          not null,
    name                text            not null,
    sessions_count      integer         not null,
    -- price of the whole package (D-022)
    price_cents         bigint          not null,
    validity_days       integer         not null,
    active              boolean         not null default true,
    created_at          timestamptz     not null default now(),

    constraint package_offer_pk                         primary key (id),
    constraint package_offer_practitioner_fk            foreign key (practitioner_id) references practitioner (id),
    constraint package_offer_service_fk                 foreign key (service_id) references service (id),
    -- the service must belong to the same practitioner as the package (D-025).
    -- both columns are not null, so the check always runs (MATCH SIMPLE skips rows with a null)
    constraint package_offer_service_practitioner_fk    foreign key (service_id, practitioner_id)
                                                        references service (id, practitioner_id),
    constraint package_offer_name_ck                    check (btrim(name) <> ''),
    -- a package of one session would be a single session sold in a different way
    constraint package_offer_sessions_count_ck          check (sessions_count >= 2),
    constraint package_offer_price_cents_ck             check (price_cents > 0),
    constraint package_offer_validity_days_ck           check (validity_days > 0)
);

create index package_offer_practitioner_id_ix on package_offer (practitioner_id);
