# Modello dei dati

Le tabelle del sistema e come si collegano. Questa è la **progettazione**: le tabelle vere nascono dalle migrazioni Flyway, dal passo 4 in poi. Se una migrazione si discosta da qui, si aggiorna questo file.

Modifiche dovute alle migrazioni:
- **V1 (passo 4)**: `customer.full_name` e `practitioner.display_name` sostituiti da `first_name` + `last_name`; `phone` spostato in `app_user` (facoltativo, per utenti di ogni ruolo); aggiunto `app_user.active` per la cancellazione morbida; ruolo `ADMIN` (D-021).
- **V2 (passo 5)**: `service` e `package_offer` come da progettazione, con `created_at`; foreign key composta per il servizio del pacchetto (D-025); indici su `practitioner_id`.
- **V3 (passo 6)**: `working_schedule`, `working_hours` e `unavailability` come da progettazione (D-034), con `created_at`; fasce all'ora piena o alla mezz'ora e non sovrapposte (*exclusion constraint* su `timerange`, estensione `btree_gist`, D-036); cascata da una versione alle sue fasce; in `practitioner` scatto solo 30 o 60 con default 60, `min_booking_notice_hours` e `booking_horizon_days` (D-033, D-035).
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
payment ── credit_lot   (pagamento di un pacchetto)

login_code      (codici di accesso, legati all'email)
app_user ──< push_subscription   (chi può accedere e con quale ruolo; dispositivi per le notifiche push)
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
| role | text | `CUSTOMER`, `PRACTITIONER` o `ADMIN` (D-021), senza default |
| active | boolean | default true. Cancellazione morbida (*soft delete*) per ogni ruolo: un account disattivato non può accedere e la sua storia resta. Vuol dire "l'account esiste", non "prenotabile" (per le assenze ci sono le indisponibilità) |
| phone | text | facoltativo, non unico (unicità, formato e verifica solo se diventerà un modo per accedere: requisiti, punto aperto 7) |

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

### `customer`
| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| user_id | bigint | → `app_user`, unico (un utente è al massimo un cliente) |
| first_name | text | |
| last_name | text | |

### `login_code`
| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| email | text | |
| code_hash | text | mai il codice in chiaro |
| expires_at | timestamptz | 10 minuti dopo la creazione |
| attempts | int | massimo 5 |
| used_at | timestamptz | null finché non viene usato |

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
| version | bigint | per il blocco ottimistico di JPA |

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
| source | text | `PACKAGE`, `CANCELLATION`, `LATE_PAYMENT` (probabilmente inutile con D-031: un pagamento tardivo si annulla; da confermare al passo 10) |
| package_offer_id | bigint | solo se `PACKAGE` |
| origin_booking_id | bigint | solo se `CANCELLATION` o `LATE_PAYMENT` |
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

### `payment`
| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| purpose | text | `BOOKING` o `PACKAGE` |
| booking_id | bigint | se `BOOKING` |
| package_offer_id | bigint | se `PACKAGE` |
| credit_lot_id | bigint | se `PACKAGE`, valorizzato dopo la conferma |
| customer_id | bigint | |
| amount_cents | bigint | importo pagato |
| status | text | `PENDING`, `AUTHORIZED` (importo bloccato, solo sedute, D-031), `SUCCEEDED`, `CANCELED` (blocco annullato, D-031), `EXPIRED` |
| stripe_checkout_session_id | text | unico |
| stripe_payment_intent_id | text | valorizzato dopo il pagamento |

### `stripe_event`
| Colonna | Tipo | Note |
|---|---|---|
| event_id | text | chiave primaria: l'id dell'evento Stripe |
| type | text | |
| received_at | timestamptz | |

Se un evento con lo stesso `event_id` è già presente, si ignora.

## Notifiche

### `push_subscription`
Un dispositivo registrato per le notifiche push (D-032). Un utente può averne più di uno.

| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| user_id | bigint | → `app_user` |
| endpoint | text | unico: l'indirizzo del servizio push del browser |
| p256dh | text | chiave pubblica del dispositivo, per cifrare il messaggio |
| auth | text | segreto del dispositivo, per cifrare il messaggio |

Quando il servizio push risponde che il dispositivo non esiste più, la riga si cancella. Migrazione del passo 8 (D-039; prima era prevista al passo 7).
