# Modello dei dati

Le tabelle del sistema e come si collegano. Questa è la **progettazione**: le tabelle vere nascono dalle migrazioni Flyway, dal passo 4 in poi. Se una migrazione si discosta da qui, si aggiorna questo file.

Modifiche dovute alle migrazioni:
- **V1 (passo 4)**: `customer.full_name` e `practitioner.display_name` sostituiti da `first_name` + `last_name`; `phone` spostato in `app_user` (facoltativo, per utenti di ogni ruolo); aggiunto `app_user.active` per la cancellazione morbida; ruolo `ADMIN` (D-021).
- **V2 (passo 5)**: `service` e `package_offer` come da progettazione, con `created_at`; foreign key composta per il servizio del pacchetto (D-025); indici su `practitioner_id`.
- **V3 (passo 6)**: `working_schedule`, `working_hours` e `unavailability` come da progettazione (D-034), con `created_at`; fasce all'ora piena o alla mezz'ora e non sovrapposte (*exclusion constraint* su `timerange`, estensione `btree_gist`, D-036); cascata da una versione alle sue fasce; in `practitioner` scatto solo 30 o 60 con default 60, `min_booking_notice_hours` e `booking_horizon_days` (D-033, D-035).
- **V8 (passo 8)**: `app_user.phone` in E.164, unico, scritto solo dopo la verifica, con `phone_verified_at` (D-055; i valori della V1 azzerati, mai scritti da nessun codice); in `login_code` il canale `SMS`, `purpose` (`SIGN_IN`, `VERIFY_PHONE`), `requested_by` (l'account che chiede una verifica) e `message_sent`.
- **V14 (passo 9, pezzo 5)**: `payment.source` (`REQUEST`, `LINK`): il pagamento di una richiesta o di un link di pagamento (D-065).
- **V13 (dopo il pezzo 4)**: in `payment` `release_attempts` e `next_release_at`, per riprovare gli annullamenti falliti (D-064).
- **V12 (passo 9, pezzo 3)**: `stripe_event` come da progettazione, con in più `account` (il conto il cui indirizzo l'ha ricevuto); scritta nella stessa transazione dell'elaborazione (D-062).
- **V11 (passo 9, pezzo 2)**: `payment.checkout_url`, l'indirizzo della pagina di Stripe (D-061). Il blocco dell'orario passa da 30 a 32 minuti nel codice (D-061), senza cambiare lo schema.
- **V10 (passo 9, pezzo 1)**: `practitioner_payment_account` (il conto di incasso di ogni professionista, D-057); `payment` solo per le sedute, con `account` (il conto su cui è fatto, mai cambiato), `version` e `created_at` scritto da Java; un solo `PENDING` per prenotazione (indice unico parziale). `purpose`, `package_offer_id`, `credit_lot_id` e `customer_id` arrivano con i pacchetti al passo 10 (D-060).
- **V9 (correzione del passo 8)**: `app_user_admin_no_phone_ck`, l'admin non ha un numero e accede solo con l'email (D-056).
- **V7 (passo 8)**: `push_subscription` riferita a `practitioner` invece che ad `app_user` (solo i professionisti ricevono push, D-054), con `registered_at`; `endpoint` unico; indice su `practitioner_id, registered_at`.
- **V6 (passo 8)**: `spring_session` e `spring_session_attributes`, le tabelle di Spring Session JDBC copiate dal suo schema 4.1.1 (D-051).
- **V5 (passo 8)**: `login_code` con `channel` + `destination` al posto di `email`, `requested_from` per il limite per IP, `created_at` scritto da Java, `code_hash` come HMAC con chiave segreta (D-050).
- **V4 (passo 7)**: `booking` come qui sotto, con `booked_by`, `paid_with` solo `CARD` e `IN_STUDIO`, i sette stati del passo 7, `IN_STUDIO` solo con `booked_by = PRACTITIONER`, e il vincolo di non sovrapposizione parziale su `tstzrange` (D-042); `service.evaluation` (D-043); in `practitioner` `max_future_bookings`, `booking_approval` e `approval_window_hours` con il `check` su due colonne (D-041, D-042).

Modifiche di progettazione:
- **2026-10-29**: niente IVA (D-022), un solo `price_cents` al posto di netto/aliquota/lordo; orizzonte e limite di prenotazioni future in `practitioner` (D-023); prenotazione dal pannello (D-024).
- **2026-11-06, passo 6**: versioni dello schema settimanale (`working_schedule`, D-034); scatto solo 30 o 60 con default 60 (D-033); orizzonte e anticipo minimo nella V3 (D-035).
- **2026-11-06**: approvazione e anticipo minimo in `practitioner`, nuovi stati e scadenza dell'approvazione in `booking` (D-030); stati `AUTHORIZED` e `CANCELED` in `payment` (D-031); tabella `push_subscription` (D-032).
- **2026-11-06, passo 7 (V4)**: in `booking` la colonna `booked_by`, `paid_with` con `CARD` e `IN_STUDIO`, `check` con i soli valori che il passo 7 produce (D-042); `service.evaluation` (D-043); limite di prenotazioni future con default 4 (D-041); tempo di risposta da 1 all'anticipo minimo e al massimo 48 ore (D-042).

Convenzioni:
- Nomi in inglese, `snake_case`.
- Chiavi primarie `id bigint` generate dal database (`generated always as identity`).
- Soldi in centesimi (`bigint`, suffisso `_cents`). Nessuna IVA (D-022): il prezzo salvato è quello mostrato e incassato.
- Istanti nel tempo in `timestamptz` (UTC). Orari settimanali in `time` (ora locale).
- Ogni tabella ha `created_at timestamptz not null default now()`.

## Mappa

```
practitioner ─┬─< working_schedule ──< working_hours
              ├─< unavailability
              ├─< service ──────< package_offer
              ├─< booking >───── customer
              └─< credit_lot >── customer
                     │
                     └─< credit_movement >── booking (opzionale)

payment ── booking      (pagamento di una seduta)
payment ── credit_lot   (pagamento di un pacchetto, dal passo 10)
practitioner ── practitioner_payment_account   (il conto di incasso, D-057)

login_code      (codici di accesso, legati a una destinazione: email, dal pezzo 6 anche il telefono)
app_user        (chi può accedere e con quale ruolo)
practitioner ──< push_subscription   (dispositivi per le notifiche push, D-054)
stripe_event    (eventi Stripe già elaborati)
```

`A ─< B` significa: un A ha molti B.

## Chi

### `app_user`
Chiunque possa accedere. Separato da cliente e professionista perché l'accesso è uguale per tutti, cambia solo il ruolo.

| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| email | text | unica, salvata in minuscolo |
| role | text | `CUSTOMER`, `PRACTITIONER` o `ADMIN` (D-021), senza default. Il primo `ADMIN` nasce all'avvio dall'email in `ADMIN_EMAIL` (D-052); i `PRACTITIONER` li crea l'admin, insieme alla loro riga `practitioner` |
| active | boolean | default true. Cancellazione morbida (*soft delete*) per ogni ruolo: un account disattivato non può accedere e la sua storia resta. Vuol dire "l'account esiste", non "prenotabile" (per le assenze ci sono le indisponibilità) |
| phone | text | facoltativo, **unico**, in E.164 (`+393331234567`), scritto solo dalla verifica con un codice via SMS (D-055); oggi solo cellulari italiani (regola di `PhoneNumber`); mai per l'admin (`check`, V9, D-056) |
| phone_verified_at | timestamptz | quando il numero è stato verificato; c'è se e solo se c'è `phone` (`check`) |

### `practitioner`
| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| user_id | bigint | → `app_user`, unico (un utente è al massimo un professionista) |
| first_name | text | |
| last_name | text | |
| cancellation_notice_hours | int | default 24 |
| single_credit_validity_days | int | default 90 |
| slot_step_minutes | int | ogni quanto può iniziare una seduta: solo 30 o 60, default 60 (D-033). V1 aveva `> 0` e default 30: li cambia la V3 |
| booking_horizon_days | int | quanti giorni avanti può prenotare il cliente, default 21 (D-023, da confermare). Migrazione V3, passo 6 (D-035) |
| max_future_bookings | int | prenotazioni future aperte al massimo per cliente con questo professionista, default 4 (D-023, D-041). Migrazione V4, passo 7 |
| booking_approval | text | `AUTOMATIC` o `MANUAL`, default `MANUAL` (D-030). Migrazione V4, passo 7 |
| approval_window_hours | int | tempo di risposta in modalità manuale, default 12, da 1 a `min_booking_notice_hours` e al massimo 48: un `check` su due colonne (D-030, D-042). Migrazione V4, passo 7 |
| min_booking_notice_hours | int | anticipo minimo per prenotare, default 24: si prenotano solo orari che iniziano più di N ore dopo (D-030). Migrazione V3, passo 6 |

Orizzonte, limite, anticipo minimo e approvazione valgono solo quando prenota il cliente, non per le prenotazioni fatte dal pannello (D-024), che però contano nel limite. Nel conteggio entrano le prenotazioni `PENDING_PAYMENT`, `PENDING_APPROVAL` (D-030) e `CONFIRMED` con `starts_at` nel futuro.

Un professionista nuovo (creato dall'admin, D-052) prende tutte le impostazioni dai default di queste colonne: Java non li ripete, e l'entità li rilegge dopo l'`insert` (`@Generated`).

### `customer`
| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| user_id | bigint | → `app_user`, unico (un utente è al massimo un cliente) |
| first_name | text | |
| last_name | text | |

### `login_code`
Migrazione V5, passo 8 (D-050). Vale solo l'ultimo codice chiesto per una destinazione.

| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| channel | text | `EMAIL` o `SMS` (V8, D-055) |
| destination | text | l'email normalizzata (D-020); dal pezzo 6 anche un numero in E.164 |
| purpose | text | `SIGN_IN` o `VERIFY_PHONE` (V8): l'ultimo codice si cerca per canale, destinazione e scopo; una verifica solo via SMS |
| requested_by | bigint | → `app_user`: solo per `VERIFY_PHONE`, l'account che chiede la verifica (`check`) |
| message_sent | boolean | falso per un accesso via SMS a un numero di nessuno: la riga esiste perché i limiti si comportino uguali; il tetto giornaliero conta solo i messaggi partiti. Sempre vero per l'email |
| code_hash | text | HMAC-SHA256 di destinazione e codice con una chiave segreta fuori dal database, in esadecimale: mai il codice in chiaro |
| requested_from | inet | per il limite per IP: l'indirizzo IPv4, o la rete `/64` di un indirizzo IPv6 (il resto azzerato) |
| created_at | timestamptz | scritto da Java con il `Clock`, come `booking` |
| expires_at | timestamptz | 10 minuti dopo la creazione (`check`: dopo `created_at`) |
| attempts | int | da 0 a 5 (`check`) |
| used_at | timestamptz | null finché non viene usato |

Indici `(channel, destination, created_at)`, `(requested_from, created_at)`, `(created_at)`. Le righe si cancellano 24 ore dopo la richiesta (lavoro programmato ogni ora): gli IP sono dati personali.

## Quando

### `working_schedule`
Una versione dello schema settimanale, valida da una data (D-034).

| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| practitioner_id | bigint | |
| valid_from | date | data locale da cui vale; `unique (practitioner_id, valid_from)` |

Per un giorno D vale la versione con la `valid_from` più recente non successiva a D. Una versione già iniziata non si modifica; una futura si sostituisce o si cancella.

### `working_hours`
Le fasce di una versione.

| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| schedule_id | bigint | → `working_schedule` (prima della D-034 era `practitioner_id`) |
| day_of_week | smallint | 1 = lunedì … 7 = domenica (come `java.time.DayOfWeek`) |
| start_time | time | all'ora piena o alla mezz'ora (D-033) |
| end_time | time | maggiore di `start_time`, all'ora piena o alla mezz'ora |

Più righe per lo stesso giorno = fasce separate (es. mattina e pomeriggio), che non si sovrappongono.

### `unavailability`
| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| practitioner_id | bigint | |
| starts_at | timestamptz | |
| ends_at | timestamptz | |
| reason | text | facoltativo, visibile solo al professionista |

`ends_at` maggiore di `starts_at`. Possono sovrapporsi tra loro; si cancellano davvero (D-035).

## Cosa si vende

### `service`
| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| practitioner_id | bigint | |
| name | text | es. "Seduta posturale" |
| duration_minutes | int | |
| price_cents | bigint | 3000 = 30 €, mostrato e incassato (D-022) |
| active | boolean | un servizio non si cancella mai, si disattiva |
| evaluation | boolean | default false: visita di valutazione, per la segnalazione della stanza unica; impostata dal professionista, non fotografata nella prenotazione (D-043). Migrazione V4, passo 7 |

### `package_offer`
Il pacchetto "in vendita".

| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| practitioner_id | bigint | |
| service_id | bigint | i crediti valgono per questo servizio; deve essere dello stesso professionista del pacchetto (foreign key composta, D-025) |
| name | text | es. "5 sedute" |
| sessions_count | int | |
| price_cents | bigint | prezzo dell'intero pacchetto |
| validity_days | int | es. 90 |
| active | boolean | |

## Cosa succede

### `booking`
| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| customer_id | bigint | |
| practitioner_id | bigint | |
| service_id | bigint | dello stesso professionista della prenotazione: *foreign key* composta verso `service (id, practitioner_id)`, come D-025 |
| starts_at | timestamptz | |
| ends_at | timestamptz | maggiore di `starts_at` |
| status | text | vedi sotto |
| booked_by | text | `CUSTOMER` o `PRACTITIONER` (dal pannello, D-024): chi ha prenotato. La persona si ricava da `customer_id` o `practitioner_id` (D-042) |
| paid_with | text | `CARD` o `IN_STUDIO` (prenotazione dal pannello senza crediti, D-040; solo con `booked_by = PRACTITIONER`). `CREDIT` si aggiunge al passo 10 |
| price_cents | bigint | fotografato al momento della prenotazione |
| hold_expires_at | timestamptz | obbligatoria per `PENDING_PAYMENT` (un `check`); resta valorizzata dopo |
| approval_deadline | timestamptz | obbligatoria per `PENDING_APPROVAL` (un `check`): richiesta + `approval_window_hours`, fotografata (D-030); resta valorizzata dopo |
| decided_at | timestamptz | quando è stata approvata o rifiutata, dal professionista o in automatico (D-030, D-042) |
| cancelled_at | timestamptz | quando il cliente ha rinunciato: ritiro (`WITHDRAWN`) dal passo 7, cancellazione dal passo 11 |
| version | bigint | per il blocco ottimistico di JPA, seconda difesa: le transizioni bloccano la riga con `select ... for update` (D-046) |
| created_at | timestamptz | il momento della richiesta, scritto da Java con il `Clock` (non dal default, come nelle altre tabelle): la scadenza dell'approvazione si conta da qui (D-030, D-046) |

Indici `(practitioner_id, starts_at)` e `(customer_id, starts_at)`. Il `check` di `status` contiene solo gli stati che il codice sa produrre: nella V4 i sette del passo 7 (da `PENDING_PAYMENT` a `WITHDRAWN` nella tabella qui sotto); gli altri arrivano con i passi 11 e successivi (D-042).

**Stati:**

| Stato | Significato | Occupa l'orario? |
|---|---|---|
| `PENDING_PAYMENT` | In attesa di pagamento, orario bloccato | sì |
| `PENDING_APPROVAL` | Importo bloccato sulla carta o credito impegnato, in attesa del professionista (D-030, D-031) | sì |
| `CONFIRMED` | Approvata e pagata (carta incassata o credito) | sì |
| `EXPIRED` | Pagamento non completato in tempo | no |
| `REJECTED` | Rifiutata dal professionista: blocco annullato o credito restituito (D-030) | no |
| `APPROVAL_EXPIRED` | Il professionista non ha risposto in tempo: come un rifiuto (D-030) | no |
| `WITHDRAWN` | Ritirata dal cliente mentre era in attesa di approvazione: nessun addebito (D-030) | no |
| `CANCELLED_IN_TIME` | Cancellata entro il preavviso, credito restituito | no |
| `CANCELLED_LATE` | Cancellata oltre il preavviso, seduta persa | no |
| `COMPLETED` | Seduta svolta | — |
| `NO_SHOW` | Il cliente non si è presentato | — |

**Regola nel database**: due prenotazioni negli stati che occupano l'orario non possono sovrapporsi per lo stesso professionista. Si ottiene con un *exclusion constraint* parziale di PostgreSQL sull'intervallo `[starts_at, ends_at)` (`tstzrange`, `where` sui tre stati che occupano; richiede l'estensione `btree_gist`, D-042).

**Stanza unica (2026-11-06)**: la sovrapposizione tra una visita di valutazione di un professionista e una seduta dell'altro **non si impedisce**, si segnala nel pannello (requisiti, "Calendario"; D-043). Il vincolo resta quindi per professionista. Un servizio di valutazione si riconosce da `service.evaluation`.

Attenzione: una prenotazione `PENDING_PAYMENT` con blocco scaduto occupa ancora l'orario finché il lavoro programmato non la porta a `EXPIRED`. Per questo il lavoro gira ogni minuto, e il calcolo degli orari liberi considera libere anche quelle con `hold_expires_at` passato.

### `credit_lot`
Un gruppo di crediti con la stessa origine e la stessa scadenza. **Un pacchetto acquistato è un lotto.** Anche una seduta singola cancellata in tempo diventa un lotto (da 1).

| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| customer_id | bigint | |
| practitioner_id | bigint | |
| service_id | bigint | per quale servizio valgono i crediti |
| source | text | `PACKAGE`, `CANCELLATION`, e l'origine della seduta omaggio (D-067, nome al passo 10). `LATE_PAYMENT` tolta (D-068): un pagamento tardivo si annulla |
| package_offer_id | bigint | solo se `PACKAGE` |
| origin_booking_id | bigint | solo se `CANCELLATION` (passo 11) |
| initial_credits | int | |
| expires_at | timestamptz | |
| price_cents | bigint | fotografato, solo se `PACKAGE` |

### `credit_movement`
Il registro. Non si modifica e non si cancella mai una riga: si aggiungono solo movimenti nuovi.

| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| credit_lot_id | bigint | |
| amount | int | +N o −1 |
| reason | text | `LOT_CREATED`, `BOOKING`, `CANCELLATION_RESTORE`, `APPROVAL_RELEASE` (credito che torna per rifiuto, scadenza o ritiro, D-030; nome da fissare al passo 10) |
| booking_id | bigint | per `BOOKING`, `CANCELLATION_RESTORE` e `APPROVAL_RELEASE` |

**Saldo** di un lotto = somma dei movimenti. Un lotto è utilizzabile se saldo > 0 e `expires_at` nel futuro. Quando si prenota con credito, si usa il lotto utilizzabile che scade prima.

### `practitioner_payment_account`
Il conto di incasso di un professionista, scelto dall'admin (D-057, V10). Decide solo il conto dei pagamenti **nuovi**.

| Colonna | Tipo | Note |
|---|---|---|
| practitioner_id | bigint | chiave primaria: al massimo un conto per professionista |
| account | text | il nome di un conto dichiarato in `PAYMENT_ACCOUNTS`: minuscole e cifre, iniziando con una lettera, al massimo 30 (`check`). Che sia configurato lo controlla Java, all'assegnazione e all'avvio |
| assigned_at | timestamptz | quando è stato assegnato; non cambia se si riassegna lo stesso conto |

Riassegnare è un `update`: la storia delle assegnazioni non si conserva, perché ogni pagamento ricorda il suo conto.

### `payment`
Dalla V10, solo per le **sedute** (D-060). Le colonne dei pacchetti arrivano al passo 10.

| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| booking_id | bigint | la seduta pagata; obbligatoria finché ci sono solo sedute (passo 10: facoltativa, con `purpose`) |
| source | text | `REQUEST` (richiesta del cliente) o `LINK` (link di pagamento di una seduta "in studio", D-065); il webhook decide da qui cosa fa il pagamento |
| account | text | **il conto su cui è stato fatto**, copiato dall'assegnazione del professionista alla creazione e mai cambiato. Incasso, annullamento e ogni altra operazione avvengono su questo conto, mai su quello attuale del professionista (D-057) |
| amount_cents | bigint | importo, dal prezzo fotografato della prenotazione (D-011); maggiore di zero |
| status | text | `PENDING` (sessione creata), `AUTHORIZED` (importo bloccato, D-031), `SUCCEEDED` (incassato), `CANCELED` (blocco annullato), `EXPIRED` (sessione scaduta o link invalidato; può tornare `AUTHORIZED` se Stripe dice che è stata pagata comunque, D-065) |
| stripe_checkout_session_id | text | unico, obbligatorio: la riga nasce dopo la risposta di Stripe |
| checkout_url | text | l'indirizzo della pagina di Stripe, obbligatorio (V11): il cliente lo ritrova finché il blocco vale (D-061) |
| stripe_payment_intent_id | text | unico, valorizzato dal webhook |
| release_attempts | int | annullamenti falliti finora (V13, D-064) |
| next_release_at | timestamptz | quando riprovare l'annullamento; `null` se niente è dovuto. Solo con `status = 'AUTHORIZED'` (`check`) |
| version | bigint | blocco ottimistico, seconda difesa (i passaggi leggono il pagamento con il lucchetto, D-063) |
| created_at | timestamptz | scritto da Java con il `Clock` |

Indice su `booking_id`. **Al massimo un pagamento `PENDING` per prenotazione**: indice unico parziale `payment_one_pending_per_booking_uq` (`where status = 'PENDING'`). La valuta è sempre l'euro, scritta nel codice che crea la sessione.

Previsto per il passo 10: `purpose` (`BOOKING` o `PACKAGE`), `package_offer_id`, `credit_lot_id` (valorizzato dopo la conferma), `customer_id`.

### `stripe_event`
Gli eventi di Stripe già elaborati (V12, D-062).

| Colonna | Tipo | Note |
|---|---|---|
| event_id | text | chiave primaria: l'id dell'evento Stripe (`evt_...`) |
| account | text | il conto di incasso il cui indirizzo ha ricevuto l'evento (D-057) |
| type | text | |
| received_at | timestamptz | |

Si scrive con `insert ... on conflict (event_id) do nothing` **nella stessa transazione** dell'elaborazione: nessuna riga inserita vuol dire evento già elaborato, e si ignora; se l'elaborazione fallisce, sparisce anche la riga e il nuovo tentativo di Stripe si elabora. Solo i tipi usati si registrano.

## Sessioni

### `spring_session`, `spring_session_attributes`
Le sessioni degli utenti collegati, gestite da **Spring Session JDBC** (D-051). Migrazione V6, copiata dallo schema di Spring Session 4.1.1: le tabelle sono **sue**, nomi e colonne non si cambiano, e non ci sono entità JPA. I tempi sono millisecondi dal 1970 (`bigint`). Gli attributi sono serializzati in Java (`bytea`). Da noi ce ne sono solo due: l'id dell'utente (`auth.userId`) e l'istante dell'accesso (`auth.signedInAt`). Spring Session cancella da solo, ogni minuto, le sessioni scadute (30 giorni di inattività).

## Notifiche

### `push_subscription`
Un dispositivo registrato per le notifiche push (D-032, D-054). Un professionista può averne più di uno, fino a 10. Migrazione V7.

| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| practitioner_id | bigint | → `practitioner`: solo i professionisti ricevono push |
| endpoint | text | unico: l'indirizzo del servizio push del browser, solo `https` verso un servizio push noto (`@PushEndpoint`). Mai intero nei log |
| p256dh | text | chiave pubblica P-256 del browser (Base64url), per cifrare il messaggio |
| auth | text | segreto di 16 byte del browser (Base64url), per cifrare il messaggio |
| registered_at | timestamptz | l'ultima registrazione: il frontend la ripete a ogni visita; oltre il limite escono le più vecchie |

La registrazione è un `insert ... on conflict (endpoint) do update`: lo stesso browser aggiorna la sua riga, anche se ora la usa un altro professionista. Quando il servizio push risponde 404 o 410, la riga si cancella.
