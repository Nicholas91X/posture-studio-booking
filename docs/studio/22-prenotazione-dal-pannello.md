# La prenotazione dal pannello — guida di studio

Questa guida accompagna il **pezzo 5 del passo 7**. Il professionista prenota **a nome di un suo cliente** (D-024): senza crediti, che arrivano al passo 10, quindi sempre **pagata in studio** (D-040) e **confermata subito**.

```
POST /api/panel/bookings
{"serviceId": 3, "startsAt": "2026-11-09T09:30:00+01:00",
 "customer": {"email": "mario.rossi@example.com", "firstName": "Mario", "lastName": "Rossi"}}
```

Teoria di riferimento: D-020 (email normalizzata), D-021 (un ruolo per utente), D-023 e D-024 (cosa non vale dal pannello), D-030, D-040, D-045 (lucchetto sul cliente), D-046 (eventi).

---

## Parte 1 — Cosa si costruisce

| Classe | Dove | Cosa fa |
|---|---|---|
| `AccountService.customerAccountFor(email)` | `auth` | Trova o crea l'account `CUSTOMER` per quell'email; lo riattiva se era disattivato |
| `ValidEmail` | `auth` | Vincolo Bean Validation: la stessa regola del `record` `Email`, con un 400 sul campo |
| `CustomerService.findOrCreate(...)` | `customer` | Trova o crea il cliente di quell'account |
| `ScheduleService.requireWorkingTime(...)` | `schedule` | La seduta sta dentro una fascia di lavoro e non tocca un'assenza |
| `Booking.bookedFromPanel(...)` | `booking` | `CONFIRMED`, `PRACTITIONER`, `IN_STUDIO`, decisa adesso |
| `BookingService.bookFromPanel(...)` | `booking` | Il caso d'uso |
| `POST /api/panel/bookings`, `GET /api/panel/bookings/{id}` | `booking` | 201 + `Location`, e la lettura di una prenotazione propria |

Dipendenze nuove: `customer` → `auth`. Il verso resta uno: `booking` → `customer` → `auth`.

---

## Parte 2 — Prima scelta: quali regole del calendario valgono dal pannello

I documenti dicono cosa **non** vale: niente anticipo minimo, orizzonte, limite, approvazione (D-023, D-024, D-030). Non dicono se valgono **griglia**, **fasce di lavoro** e **assenze**.

| Regola | Dal pannello | Perché |
|---|---|---|
| Anticipo, orizzonte, limite, approvazione | **no** | Già deciso (D-023, D-024, D-030). La prenotazione **conta** nel limite quando poi prenota il cliente |
| Fasce di lavoro | **sì** | Requisiti, "Calendario": "non lavorano mai fuori dal proprio schema". Una seduta fuori orario sarebbe un errore di battitura, non una scelta |
| Assenze | **sì** | Le ha inserite il professionista stesso: prenotare durante le ferie è uno sbaglio |
| Griglia (scatto) | **no** | La griglia serve a non lasciare mezz'ore vuote quando prenotano i clienti (D-030, D-033). Il professionista decide da solo: dopo una valutazione 9:00–9:30 deve poter mettere una seduta alle 9:30 anche con lo scatto di un'ora (requisiti, "Calendario") |
| Nel passato | **no** | Una prenotazione è per il futuro; le sedute già fatte non si registrano qui |
| Sovrapposizioni | **no** | Il vincolo del database, come sempre (D-013) |

**Raccomandazione:** questa tabella. Un solo codice d'errore anche qui, `SLOT_NOT_AVAILABLE`.

Per le fasce non si può riusare il calcolo degli orari liberi, come fa la richiesta del cliente (guida 19, parte 3): il calcolo applica griglia, anticipo e orizzonte. Serve una domanda più piccola a `schedule`: "questo intervallo sta dentro una fascia della versione valida quel giorno, e fuori dalle assenze?". La conversione da ora locale a istante si fa come nel calcolo, con `ZonedDateTime.of` giorno per giorno (D-035), così i cambi dell'ora sono gestiti allo stesso modo.

---

## Parte 3 — Seconda scelta: chi è il cliente

### 3.1 Il problema

Il professionista scrive nome, cognome ed email. Il cliente può:
- **non esistere**: va creato (D-024), con il suo account (`app_user` con ruolo `CUSTOMER`);
- **esistere già**: si usa quello. Come lo si riconosce? Dall'**email**, che è unica e normalizzata (D-020): `Mario.Rossi@Example.com` e `mario.rossi@example.com` sono lo stesso cliente;
- **avere l'account disattivato**: si **riattiva** (D-024, conseguenze), non se ne crea un secondo (l'email è unica);
- **essere un professionista o l'admin**: un utente ha un solo ruolo (D-021), quindi 409 `EMAIL_NOT_A_CUSTOMER`.

### 3.2 Perché per email e non scegliendo da un elenco

Un elenco dei clienti da cui scegliere apre la domanda ancora aperta "quali clienti può vedere un professionista" (D-024, da chiarire al passo 13). Con l'email il professionista non vede nessun elenco: scrive l'email che il cliente gli ha dato, e il sistema trova o crea.

Se il cliente esiste già, nome e cognome **non si sovrascrivono**: il professionista potrebbe averli scritti in modo diverso, e i dati del cliente sono del cliente.

### 3.3 Tutto nella stessa transazione

Account, cliente e prenotazione nascono **insieme**. Se l'orario non va bene, il `rollback` toglie anche il cliente appena creato: niente clienti "fantasma" per prenotazioni fallite.

### 3.4 L'email valida: una regola in un posto solo

Il `record` `Email` (D-020) rifiuta un'email non valida con `IllegalArgumentException`, che il gestore degli errori trasforma in un **500**. Per avere un 400 sul campo serve un vincolo Bean Validation. `jakarta.validation.constraints.Email` accetta anche `mario@localhost`, che il nostro `Email` rifiuta: due regole diverse. Meglio un vincolo nostro, `@ValidEmail`, che prova a costruire un `Email` e dice di no se fallisce: la regola resta **una sola**.

---

## Parte 4 — Il resto, in breve

- **Lucchetto sul cliente** (D-045): la prenotazione dal pannello conta nel limite, quindi prende lo stesso lucchetto della richiesta del cliente.
- **Nessuna transizione**: nasce già `CONFIRMED`. La macchina a stati riguarda i passaggi, non la nascita; `bookedFromPanel` è l'unico modo di creare una prenotazione confermata.
- **`decided_at` = adesso**: l'ha decisa il professionista, prenotando.
- **Evento `BookingConfirmed`**: al passo 8 il cliente riceverà l'email di conferma, come per una prenotazione approvata.
- **Blocchi scaduti e vincolo**: la stessa scadenza pigra e la stessa traduzione (`23P01` o `40P01` → `SLOT_NOT_AVAILABLE`) della richiesta del cliente.
- **Servizio**: solo un servizio proprio e attivo (`serviceOnSale`), altrimenti 404.
- **Segnalazione della stanza unica prima di confermare** (punto aperto 12, "quando compare"): arriva con le liste del pezzo 6; qui la prenotazione non si blocca, come deciso (D-043).

---

## Parte 5 — I test

| # | Caso | Atteso |
|---|---|---|
| 1 | Cliente nuovo, lunedì 9:00 | `CONFIRMED`, `PRACTITIONER`, `IN_STUDIO`; cliente e account creati con nome, cognome ed email minuscola; evento `BookingConfirmed` |
| 2 | Stessa email scritta in maiuscolo | lo stesso cliente, nome non sovrascritto |
| 3 | Account del cliente disattivato | riattivato, stesso id |
| 4 | Email di un professionista | 409 `EMAIL_NOT_A_CUSTOMER` |
| 5 | Lunedì 9:30 con scatto di un'ora | accettata: la griglia non vale dal pannello |
| 6 | Oggi alle 15:00 (dentro l'anticipo) e lunedì 14 dicembre (oltre l'orizzonte) | accettate |
| 7 | Lunedì 13:00 (pausa), 12:30–13:30 (a cavallo della fine della fascia), sabato | `SLOT_NOT_AVAILABLE` |
| 8 | Dentro un'assenza | `SLOT_NOT_AVAILABLE` |
| 9 | Oggi alle 9:00 (già passato) | `SLOT_NOT_AVAILABLE` |
| 10 | Sopra una prenotazione esistente | `SLOT_NOT_AVAILABLE` dal **vincolo**, tradotto: qui non c'è un controllo in Java prima, quindi il test prova in modo deterministico la traduzione della guida 19, parte 6 |
| 11 | Cliente con il limite già raggiunto | accettata; e dopo, la richiesta del cliente stesso trova il limite |
| 12 | Servizio di un altro professionista | 404 |
| 13 | API: 201 con `Location`; email non valida → 400 su `customer.email`; cliente mancante → 400; `GET` della propria, 404 per quella di un altro | come detto |

---

## Parte 6 — Cosa è stato deciso

Scelte secondo le raccomandazioni di questa guida, su indicazione di Nicholas (D-048): la tabella della parte 2 (fasce e assenze sì, griglia no); cliente per email, creato o riattivato nella stessa transazione, nome non sovrascritto; `@ValidEmail` con la regola di `Email`.

---

## Domande da colloquio

### Domanda 1: Un'operazione crea un cliente e una prenotazione. Come eviti un cliente "fantasma" se la prenotazione fallisce?
**Risposta modello.** Le metto nella stessa transazione: il servizio della prenotazione chiama quello dei clienti, che partecipa alla transazione esistente (`REQUIRED`). Se l'orario non va bene, l'eccezione annulla tutto, cliente compreso. Moduli diversi, un solo database, una sola transazione: è uno dei vantaggi concreti del monolite modulare rispetto ai microservizi.

### Domanda 2: Hai una regola di validazione nel dominio (un *value object*) e vuoi un 400 sul campo. Come eviti di scriverla due volte?
**Risposta modello.** Scrivo un vincolo Bean Validation che delega al *value object*: prova a costruirlo e, se lancia l'eccezione, dice "non valido". Così la regola resta in un posto solo, e le annotazioni standard come `@Email`, che hanno una regola diversa, non creano casi in cui la validazione passa e il dominio rifiuta con un 500.

### Domanda 3: Come gestisci "trova o crea" su un campo unico, come l'email?
**Risposta modello.** Cerco per il valore normalizzato, e creo solo se non c'è; l'unicità la garantisce comunque il database. Se due richieste creano lo stesso utente nello stesso istante, una delle due viola il vincolo `unique` e fallisce: è raro, e la seconda richiesta, ripetuta, troverà l'utente. Tratto i casi di confine: account disattivato da riattivare, email che appartiene a un utente con un altro ruolo.

---

## Documentazione ufficiale

- Jakarta Bean Validation, vincoli personalizzati: https://jakarta.ee/specifications/bean-validation/3.1/jakarta-validation-spec-3.1#constraintsdefinitionimplementation
- Jakarta Bean Validation, validazione a cascata (`@Valid` su un campo): https://jakarta.ee/specifications/bean-validation/3.1/jakarta-validation-spec-3.1#constraintdeclarationvalidationprocess-validationroutine-graphvalidation
