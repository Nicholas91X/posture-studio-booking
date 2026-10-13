# Modello dei dati

Le tabelle del sistema e come si collegano. Questa è la **progettazione**: le tabelle vere nascono dalle migrazioni Flyway, che Nicholas scrive nel passo 4 e seguenti. Se una migrazione si discosta da qui, si aggiorna questo file.

Convenzioni:
- Nomi in inglese, `snake_case`.
- Chiavi primarie `id bigint` generate dal database (`generated always as identity`).
- Soldi in centesimi (`bigint`, suffisso `_cents`).
- Aliquote IVA in punti base (`int`, suffisso `_bp`): 22% = `2200`.
- Istanti nel tempo in `timestamptz` (UTC). Orari settimanali in `time` (ora locale).
- Ogni tabella ha `created_at timestamptz not null default now()`.

## Mappa

```
practitioner ─┬─< working_hours
              ├─< unavailability
              ├─< service ──────< package_offer
              ├─< booking >───── customer
              └─< credit_lot >── customer
                     │
                     └─< credit_movement >── booking (opzionale)

payment ── booking      (pagamento di una seduta)
payment ── credit_lot   (pagamento di un pacchetto)

login_code      (codici di accesso, legati all'email)
app_user        (chi può accedere e con quale ruolo)
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
| role | text | `CUSTOMER` o `PRACTITIONER` |

### `practitioner`
| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| user_id | bigint | → `app_user` |
| display_name | text | |
| cancellation_notice_hours | int | default 24 |
| single_credit_validity_days | int | default 90 |
| slot_step_minutes | int | ogni quanto può iniziare una seduta, default 30 (punto aperto) |

### `customer`
| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| user_id | bigint | → `app_user` |
| full_name | text | |
| phone | text | facoltativo |

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

### `working_hours`
| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| practitioner_id | bigint | |
| day_of_week | smallint | 1 = lunedì … 7 = domenica (come `java.time.DayOfWeek`) |
| start_time | time | |
| end_time | time | maggiore di `start_time` |

Più righe per lo stesso giorno = fasce separate (es. mattina e pomeriggio).

### `unavailability`
| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| practitioner_id | bigint | |
| starts_at | timestamptz | |
| ends_at | timestamptz | |
| reason | text | facoltativo, visibile solo al professionista |

## Cosa si vende

### `service`
| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| practitioner_id | bigint | |
| name | text | es. "Seduta posturale" |
| duration_minutes | int | |
| net_price_cents | bigint | 3000 = 30 € |
| vat_rate_bp | int | |
| active | boolean | un servizio non si cancella mai, si disattiva |

### `package_offer`
Il pacchetto "in vendita".

| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| practitioner_id | bigint | |
| service_id | bigint | i crediti valgono per questo servizio |
| name | text | es. "5 sedute" |
| sessions_count | int | |
| net_price_cents | bigint | prezzo dell'intero pacchetto |
| vat_rate_bp | int | |
| validity_days | int | es. 90 |
| active | boolean | |

## Cosa succede

### `booking`
| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| customer_id | bigint | |
| practitioner_id | bigint | |
| service_id | bigint | |
| starts_at | timestamptz | |
| ends_at | timestamptz | |
| status | text | vedi sotto |
| paid_with | text | `CARD` o `CREDIT` |
| net_price_cents | bigint | fotografato al momento della prenotazione |
| vat_rate_bp | int | fotografato |
| gross_price_cents | bigint | fotografato, quello mostrato al cliente |
| hold_expires_at | timestamptz | solo per `PENDING_PAYMENT` |
| cancelled_at | timestamptz | |
| version | bigint | per il blocco ottimistico di JPA |

**Stati:**

| Stato | Significato | Occupa l'orario? |
|---|---|---|
| `PENDING_PAYMENT` | In attesa di pagamento, orario bloccato | sì |
| `CONFIRMED` | Pagata (carta o credito) | sì |
| `EXPIRED` | Pagamento non completato in tempo | no |
| `CANCELLED_IN_TIME` | Cancellata entro il preavviso, credito restituito | no |
| `CANCELLED_LATE` | Cancellata oltre il preavviso, seduta persa | no |
| `COMPLETED` | Seduta svolta | — |
| `NO_SHOW` | Il cliente non si è presentato | — |

**Regola nel database**: due prenotazioni negli stati che occupano l'orario non possono sovrapporsi per lo stesso professionista. Si ottiene con un *exclusion constraint* di PostgreSQL sull'intervallo `[starts_at, ends_at)` (richiede l'estensione `btree_gist`).

Attenzione: una prenotazione `PENDING_PAYMENT` con blocco scaduto occupa ancora l'orario finché il lavoro programmato non la porta a `EXPIRED`. Per questo il lavoro gira ogni minuto, e il calcolo degli orari liberi considera libere anche quelle con `hold_expires_at` passato.

### `credit_lot`
Un gruppo di crediti con la stessa origine e la stessa scadenza. **Un pacchetto acquistato è un lotto.** Anche una seduta singola cancellata in tempo diventa un lotto (da 1).

| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| customer_id | bigint | |
| practitioner_id | bigint | |
| service_id | bigint | per quale servizio valgono i crediti |
| source | text | `PACKAGE`, `CANCELLATION`, `LATE_PAYMENT` |
| package_offer_id | bigint | solo se `PACKAGE` |
| origin_booking_id | bigint | solo se `CANCELLATION` o `LATE_PAYMENT` |
| initial_credits | int | |
| expires_at | timestamptz | |
| net_price_cents | bigint | fotografato, solo se `PACKAGE` |
| vat_rate_bp | int | fotografato, solo se `PACKAGE` |
| gross_price_cents | bigint | fotografato, solo se `PACKAGE` |

### `credit_movement`
Il registro. Non si modifica e non si cancella mai una riga: si aggiungono solo movimenti nuovi.

| Colonna | Tipo | Note |
|---|---|---|
| id | bigint | |
| credit_lot_id | bigint | |
| amount | int | +N o −1 |
| reason | text | `LOT_CREATED`, `BOOKING`, `CANCELLATION_RESTORE` |
| booking_id | bigint | per `BOOKING` e `CANCELLATION_RESTORE` |

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
| amount_cents | bigint | importo lordo pagato |
| status | text | `PENDING`, `SUCCEEDED`, `EXPIRED` |
| stripe_checkout_session_id | text | unico |
| stripe_payment_intent_id | text | valorizzato dopo il pagamento |

### `stripe_event`
| Colonna | Tipo | Note |
|---|---|---|
| event_id | text | chiave primaria: l'id dell'evento Stripe |
| type | text | |
| received_at | timestamptz | |

Se un evento con lo stesso `event_id` è già presente, si ignora.

## Calcolo del prezzo lordo

`gross = net + arrotondamento(net × vat_bp / 10000)`, con arrotondamento al centesimo "half up". Il calcolo vive in un solo punto del backend e il risultato viene salvato, mai ricalcolato sullo storico.
