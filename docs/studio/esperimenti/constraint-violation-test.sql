begin;
insert into app_user (role, email, phone, active) values ('CUSTOMER', 'test@gmail.com', '33317530645', true) returning id, role, email, phone, active;
rollback;

-- Un utente con ruolo GUEST
begin;
insert into app_user (role, email, phone, active) values ('GUEST', 'test@gmail.com', '33317530645', true) returning id, role, email, phone, active;
-- atteso: fallito, con violazione del vincolo di check sulla colonna role
rollback;

BEGIN
ERROR:  new row for relation "app_user" violates check constraint "app_user_role_ck"
DETAIL:  Failing row contains (1, GUEST, test@gmail.com, 33317530645, t, 2026-10-19 08:25:18.955731+00).
ROLLBACK

-- Un utente senza ruolo
begin;
insert into app_user (email, phone, active) values ('test@gmail.com', '33317530645', true) returning id, role, email, phone, active;
-- atteso: fallito, con violazione del vincolo di check sulla colonna role
rollback;

BEGIN
ERROR:  null value in column "role" of relation "app_user" violates not-null constraint
DETAIL:  Failing row contains (2, null, test@gmail.com, 33317530645, t, 2026-10-19 08:29:49.039019+00).
ROLLBACK

-- Un utente con email Mario@Test.it
begin;
insert into app_user (role, email, phone, active) values ('CUSTOMER', 'Mario@Test.it', '33317530645', true) returning id, role, email, phone, active;
-- atteso: rifiutato: app_user_email_ck
rollback;

ERROR:  new row for relation "app_user" violates check constraint "app_user_email_ck"
DETAIL:  Failing row contains (4, CUSTOMER, Mario@Test.it, 33317530645, t, 2026-10-19 08:31:01.836981+00).
WARNING:  there is no transaction in progress
ROLLBACK

-- Due utenti con la stessa email minuscola, nella stessa transazione
begin;
insert into app_user (role, email, phone, active) values ('CUSTOMER', 'test@gmail.com', '33317530645', true) returning id, role, email, phone, active;
insert into app_user (role, email, phone, active) values ('CUSTOMER', 'test@gmail.com', '33317530645', true) returning id, role, email, phone, active;
-- atteso: il primo passa, il secondo è rifiutato: app_user_email_uk
rollback;

BEGIN
 id |   role   |     email      |    phone    | active 
----+----------+----------------+-------------+--------
  5 | CUSTOMER | test@gmail.com | 33317530645 | t
(1 row)

INSERT 0 1
ERROR:  duplicate key value violates unique constraint "app_user_email_uk"
DETAIL:  Key (email)=(test@gmail.com) already exists.
ROLLBACK

-- 	Un utente con un id scelto da te
begin;
insert into app_user (id, role, email, phone, active) values (100, 'CUSTOMER', 'test@gmail.com', '33317530645', true) returning id, role, email, phone, active;
-- atteso: rifiutato: la colonna è identity always (il messaggio lo dice)
rollback;

BEGIN
ERROR:  cannot insert a non-DEFAULT value into column "id"
DETAIL:  Column "id" is an identity column defined as GENERATED ALWAYS.
HINT:  Use OVERRIDING SYSTEM VALUE to override.
ROLLBACK

-- Un cliente con un user_id che non esiste (es. 999999)
begin;
insert into customer (user_id, first_name, last_name) values (100, 'John', 'Doe') returning id, user_id, first_name, last_name;
-- atteso: rifiutato: customer_user_fk
rollback;

BEGIN
ERROR:  insert or update on table "customer" violates foreign key constraint "customer_user_fk"
DETAIL:  Key (user_id)=(100) is not present in table "app_user".
ROLLBACK

-- Due clienti per lo stesso utente
begin;
insert into customer (user_id, first_name, last_name) values (7, 'John', 'Doe') returning id, user_id, first_name, last_name;
insert into customer (user_id, first_name, last_name) values (7, 'John', 'Doe') returning id, user_id, first_name, last_name;
-- atteso: il secondo è rifiutato: customer_user_id_uk
rollback;

BEGIN
 id | user_id | first_name | last_name 
----+---------+------------+-----------
  2 |       7 | John       | Doe
(1 row)

INSERT 0 1
ERROR:  duplicate key value violates unique constraint "customer_user_id_uk"
DETAIL:  Key (user_id)=(7) already exists.
ROLLBACK

-- Un professionista con preavviso 0
begin;
insert into practitioner (user_id, first_name, last_name, cancellation_notice_hours) values (8, 'John', 'Doe', 0) returning id, user_id, first_name, last_name, cancellation_notice_hours;
-- atteso: rifiutato: practitioner_cancellation_notice_hours_ck
rollback;

BEGIN
ERROR:  new row for relation "practitioner" violates check constraint "practitioner_cancellation_notice_hours_ck"
DETAIL:  Failing row contains (1, 8, John, Doe, 0, 90, 30, 2026-10-19 08:41:21.693202+00).
ROLLBACK

-- Un professionista con preavviso NULL scritto esplicitamente
begin;
insert into practitioner (user_id, first_name, last_name, cancellation_notice_hours) values (9, 'John', 'Doe', null) returning id, user_id, first_name, last_name, cancellation_notice_hours;
-- atteso: rifiutato da not null
rollback;

BEGIN
ERROR:  null value in column "cancellation_notice_hours" of relation "practitioner" violates not-null constraint
DETAIL:  Failing row contains (2, 9, John, Doe, null, 90, 30, 2026-10-19 08:42:20.483955+00).
ROLLBACK

-- 	Un professionista senza nominare le tre impostazioni
begin;
insert into practitioner (user_id, first_name, last_name) values (8, 'John', 'Doe') returning id, user_id, first_name, last_name, cancellation_notice_hours, single_credit_validity_days, slot_step_minutes;
-- atteso: riuscito, con i valori di default
rollback;

BEGIN
 id | user_id | first_name | last_name | cancellation_notice_hours | single_credit_validity_days | slot_step_minutes 
----+---------+------------+-----------+---------------------------+-----------------------------+-------------------
  4 |       8 | John       | Doe       |                        24 |                          90 |                30
(1 row)

INSERT 0 1
ROLLBACK

-- Un utente con id 7, che è un customer, viene cancellato
begin;
delete from app_user where id = 7;
-- atteso: rifiutato da foreign key constraint customer_user_fk
rollback;

BEGIN
ERROR:  update or delete on table "app_user" violates foreign key constraint "customer_user_fk" on table "customer"
DETAIL:  Key (id)=(7) is still referenced from table "customer".
ROLLBACK