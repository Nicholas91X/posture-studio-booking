-- V9__admin_without_phone.sql
-- The admin signs in by email only (D-056, modifies D-055): no verified phone, so no SMS sign-in.
-- A SIM swap (someone gets the operator to move the number to a new SIM) would otherwise open the most
-- valuable account with nothing but a phone call to a shop.

-- No admin can have a phone now: the API never let one through, but a row written by hand could have.
-- phone and phone_verified_at go together (app_user_phone_verified_ck)
update app_user set phone = null, phone_verified_at = null where role = 'ADMIN' and phone is not null;

alter table app_user
    add constraint app_user_admin_no_phone_ck check (role <> 'ADMIN' or phone is null);
