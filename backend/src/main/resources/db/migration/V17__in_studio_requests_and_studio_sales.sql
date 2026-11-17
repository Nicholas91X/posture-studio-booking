-- Step 13b (D-089): without a payment account everything is paid in the studio, and a package sold in the studio is
-- recorded from the panel.


-- ***** 1. BOOKINGS: a customer's request may be paid in the studio *****

-- V4 allowed IN_STUDIO only for a booking made by the practitioner (D-040). A customer of a practitioner without
-- an account now asks for a session paid in the studio too: it waits for the approval like a request paid with a
-- credit, and like it is never "waiting for payment" (nothing is paid online)
alter table booking drop constraint booking_in_studio_ck;

alter table booking
    add constraint booking_in_studio_no_hold_ck check (paid_with <> 'IN_STUDIO' or status <> 'PENDING_PAYMENT');


-- ***** 2. CREDITS: the lot of a package sold in the studio *****

-- who recorded the sale (the practitioner's account), as granted_by for a gift; when is created_at
alter table credit_lot
    add column sold_by bigint,
    add constraint credit_lot_sold_by_fk foreign key (sold_by) references app_user (id);

alter table credit_lot drop constraint credit_lot_source_ck;

-- same values as LotSource. STUDIO_SALE: a package paid in the studio, no row in payment (the system does not
-- record cash, D-089)
alter table credit_lot add constraint credit_lot_source_ck
    check (source in ('PACKAGE', 'GRANT', 'CANCELLATION', 'STUDIO_SALE'));

-- V15 tied the package and its price to the Stripe payment. Now two sources come from a package: PACKAGE (paid
-- online, with its payment) and STUDIO_SALE (paid in the studio, without). Both photograph which package and its
-- price (D-011); the other sources have neither. credit_lot_payment_ck (only PACKAGE has a payment) stays
alter table credit_lot drop constraint credit_lot_package_columns_ck;

alter table credit_lot add constraint credit_lot_package_columns_ck check (
        (package_offer_id is not null) = (source in ('PACKAGE', 'STUDIO_SALE'))
    and (price_cents is not null) = (source in ('PACKAGE', 'STUDIO_SALE'))
);

-- the trace of a sale in its own row, so that a check can demand it, as the gift's (V15)
alter table credit_lot add constraint credit_lot_sale_ck check ((source = 'STUDIO_SALE') = (sold_by is not null));

-- The REMINDER of V16: a new column of credit_lot joins the list of the columns that never change
create or replace function credit_lot_only_extend() returns trigger language plpgsql as $$
begin
    if (new.id, new.customer_id, new.practitioner_id, new.service_id, new.source, new.payment_id,
        new.package_offer_id, new.price_cents, new.granted_by, new.note, new.created_at, new.sold_by)
       is distinct from
       (old.id, old.customer_id, old.practitioner_id, old.service_id, old.source, old.payment_id,
        old.package_offer_id, old.price_cents, old.granted_by, old.note, old.created_at, old.sold_by) then
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

-- REMINDER, as in V16: a new column of credit_lot must be added to credit_lot_only_extend too.
