# Dopo la seduta: svolta, non presentato, passato e futuro — guida di studio

Questa guida accompagna il **pezzo 5 del passo 11**, l'ultimo: le sedute confermate finite diventano `COMPLETED` da sole; il professionista può segnare `NO_SHOW`, e correggere, entro **7 giorni** dalla fine (Nicholas, 2026-11-12); "le mie sedute" del cliente distingue passato e futuro. **Nessun effetto sui crediti**: un "non presentato" è come una cancellazione tardiva (requisiti), e il credito era già usato.

```
lavoro ogni minuto      CONFIRMED con ends_at <= now  ──►  COMPLETED     (un update in blocco)

pannello                POST /api/panel/bookings/{id}/no-show    CONFIRMED, COMPLETED  ──►  NO_SHOW
                        POST /api/panel/bookings/{id}/attended   CONFIRMED, NO_SHOW    ──►  COMPLETED
                        dall'inizio della seduta fino a 7 giorni dopo la sua fine

cliente                 GET /api/customer/bookings?period=upcoming   non finite, la più vicina prima
                        GET /api/customer/bookings?period=past       finite, la più recente prima
```

Teoria di riferimento: D-042 (le colonne di momento), D-046 (macchina a stati, lucchetto), D-047 (lavori programmati, sicuri con due istanze), D-078 (frecce e traccia del pezzo 1: `attendance_marked_by`, `attendance_marked_at`).

---

## Indice

- Parte 1 — Il lavoro: da confermata a svolta
- Parte 2 — Il professionista segna e corregge
- Parte 3 — La finestra dei 7 giorni, e i suoi confini
- Parte 4 — Passato e futuro per il cliente
- Parte 5 — I test
- Parte 6 — Cosa c'è da decidere
- Parte 7 — Com'è andata (implementazione)
- Domande da colloquio

---

## Parte 1 — Il lavoro: da confermata a svolta

Come la scadenza dei blocchi (D-047): **un solo `update` in blocco**, ogni minuto, con lo stato di partenza nel `where`:

```sql
update booking set status = 'COMPLETED', version = version + 1
where status = 'CONFIRMED' and ends_at <= :now
```

- **Perché in blocco e non una transazione per seduta** (come le approvazioni scadute): qui non c'è niente fuori dal database (nessuna carta da annullare, nessun evento, nessuna email). Un'istruzione sola è atomica, e con due istanze dell'applicazione la seconda trova le righe già `COMPLETED` (il `where` le esclude): lavoro doppio, effetto singolo.
- **La guardia nel `where`**: se il professionista ha già segnato `NO_SHOW`, la riga non è più `CONFIRMED` e il lavoro non la tocca. Il lucchetto non serve, perché l'`update` di PostgreSQL rilegge la riga dopo aver aspettato un'altra transazione che la stava cambiando, e la salta se non soddisfa più il `where` (in `READ COMMITTED`).
- **La versione a mano**: un `update` JPQL scavalca `@Version`, come in D-045.
- **`ends_at`, non `starts_at`**: una seduta in corso non è ancora svolta. Fino alla fine resta `CONFIRMED`: il professionista può segnare un "non presentato" anche prima (parte 2), e la cancellazione è già rifiutata dall'inizio (D-079).
- **Nessun evento, nessuna email**: "la tua seduta è stata svolta" non dice niente al cliente. Nessun effetto sulla traccia: `attendance_marked_by` resta vuoto (la V16 lo permette per `COMPLETED`).

---

## Parte 2 — Il professionista segna e corregge

Due azioni, come approvazione e rifiuto (una risposta è un passo della macchina a stati, D-028):

- `POST /api/panel/bookings/{id}/no-show`: da `CONFIRMED` (il cliente non è venuto e il lavoro non è ancora passato, o la seduta è ancora in corso) o da `COMPLETED` (il lavoro l'aveva chiusa come svolta) a `NO_SHOW`.
- `POST /api/panel/bookings/{id}/attended`: la correzione, da `NO_SHOW` a `COMPLETED`; anche da `CONFIRMED`, per chi vuole chiudere una seduta in corso a mano.

Le frecce ci sono già dalla V16. Ogni segno scrive **chi** (l'account che agisce, da `CurrentUser`) e **quando**: l'ultimo segno, senza storia (D-078). Con il lucchetto sulla seduta, come ogni transizione (D-046), per non incrociarsi con il lavoro.

**Idempotenti**: `no-show` su una seduta già `NO_SHOW` risponde 200 con la seduta com'è, senza riscrivere chi e quando (il segno vero è il primo); lo stesso `attended` su una `COMPLETED`. Ogni altro stato (cancellata, rifiutata, ...) è un 409 dalla macchina a stati. La seduta di un altro professionista è un 404.

**Nessun effetto sui crediti**, nei due versi: "non presentato" non toglie niente (il credito o il pagamento erano già usati), "svolta" non restituisce niente. Nessuna email al cliente: se lo studio vorrà avvisare di un "non presentato", si aggiunge un evento.

---

## Parte 3 — La finestra dei 7 giorni, e i suoi confini

Il segno è aperto **dall'inizio della seduta** fino a **7 giorni dopo la sua fine, compreso** (`ends_at + 7 giorni`, come durata: 168 ore). Prima: 409 **`BOOKING_NOT_STARTED`** (un "non presentato" per una seduta di domani non ha senso). Dopo: 409 **`ATTENDANCE_WINDOW_CLOSED`**.

- **Una costante nel codice**, `Booking.ATTENDANCE_WINDOW`, non un'impostazione del professionista: una regola dello studio, uguale per tutti, senza effetti sui soldi. Se un giorno servirà diversa per professionista, diventa una colonna come il preavviso.
- **Compreso**, come `free_cancellation_until` (D-079): "entro 7 giorni" comprende l'ultimo istante.
- **I confini si provano al secondo**, come al pezzo 2: un secondo prima dell'inizio (409), l'inizio esatto (aperto), la fine + 7 giorni esatta (aperto), un secondo dopo (409).

---

## Parte 4 — Passato e futuro per il cliente

Oggi `GET /api/customer/bookings` restituisce tutte le sedute, la più lontana nel futuro per prima. Il brief vuole che "le mie sedute" distingua passato e futuro, e la regola (cosa è "passato") la decide il backend (D-005).

**Strade**:
- **A — un parametro**, `?period=upcoming` (non finite: `ends_at > now`, la più vicina per prima) o `?period=past` (finite, la più recente per prima). Senza parametro, la lista di oggi, **invariata**: il contratto esistente non cambia.
- B — una risposta in due parti, `{upcoming: [...], past: [...]}`: cambia la forma della risposta di oggi.
- C — un campo in ogni seduta (`past: true`): il frontend dovrebbe ancora dividere e ordinare.

**Raccomandazione: A.** L'ordine diverso è la ragione vera: nel futuro si vuole la prossima seduta in cima, nel passato la più recente. Una seduta **in corso** è nel futuro (non è finita). Il parametro divide per tempo, non per stato: una seduta futura cancellata compare in `upcoming` con il suo stato, e il frontend sceglie se mostrarla.

Un valore sconosciuto di `period` è un 400, come ogni parametro sbagliato.

---

## Parte 5 — I test

- **Il lavoro** (orologio spostato): una confermata finita da un secondo → `COMPLETED`; finita esattamente adesso → `COMPLETED` (`<=`); in corso → resta `CONFIRMED`; una `NO_SHOW`, una cancellata, una richiesta in attesa → non toccate; la traccia resta vuota; due esecuzioni di fila → lo stesso risultato.
- **I segni**: `no-show` da `CONFIRMED` e da `COMPLETED`, con chi e quando; `attended` da `NO_SHOW`; il secondo `no-show` non cambia chi e quando; nessun movimento di credito nuovo; 404 per un altro professionista; 409 per una cancellata.
- **La finestra**, al secondo: prima dell'inizio, l'inizio, la fine + 7 giorni, un secondo dopo.
- **Il cliente**: `upcoming` e `past` con le sedute giuste nell'ordine giusto, una in corso in `upcoming`, senza parametro la lista di sempre, un valore sconosciuto → 400.
- **Le difese tolte a turno**: la guardia `CONFIRMED` nel `where` del lavoro (una `NO_SHOW` tornerebbe `COMPLETED`), `ends_at` al posto di `starts_at`, i due confini della finestra, l'ordine di `upcoming`.

---

## Parte 6 — Cosa c'è da decidere

| # | Scelta | Raccomandazione |
|---|---|---|
| 1 | La finestra | **7 giorni dalla fine, compreso** (scelto da Nicholas), dall'inizio della seduta; costante nel codice (parte 3) |
| 2 | Il lavoro | un `update` in blocco ogni minuto, su `ends_at <= now`, nessun evento (parte 1) |
| 3 | Le azioni | `/no-show` e `/attended`, idempotenti, con chi e quando, nessun effetto sui crediti (parte 2) |
| 4 | Passato e futuro | `?period=upcoming|past`, senza parametro la lista di oggi (parte 4) |

**Scelte**: la finestra di 7 giorni da Nicholas ("segui la raccomandazione"); il resto secondo le raccomandazioni (D-082).

---

## Parte 7 — Com'è andata (implementazione)

**File**: `Booking` (`ATTENDANCE_WINDOW`, `markNoShow`, `markAttended`, `attendanceClosedAt`, le due colonne della traccia mappate), `BookingRepository` (`completeAllOver`, le due liste per tempo), `BookingService` (`completeSessionsOver`, `markNoShow`, `markAttended`, `attendanceTarget`, `listForCustomer` con il periodo), `BookingJobs`, `BookingPeriod`, i due controller; due codici d'errore. Nessuna migrazione.

**Una difesa presa due volte, e la lezione.** Tolta la guardia `CONFIRMED` dal `where` del lavoro, il primo test (che metteva insieme una seduta "non presentato" e una cancellata) falliva con un errore del database, non con la sua asserzione: il lavoro portava a `COMPLETED` anche la seduta cancellata, e il `check` della traccia della V16 lo rifiutava (una seduta svolta non ha un "chi ha cancellato"). Bello, ma nascondeva l'altro caso: per un "non presentato" il database **non** protesta (un segno è ammesso anche su `COMPLETED`), e lì solo la guardia difende. Il test è diviso in due, e la difesa tolta ora è presa dall'asserzione sul "non presentato". Quando un test fallisce, conviene guardare **perché**: un fallimento per il motivo sbagliato può coprire un caso scoperto.

**Il parametro sconosciuto**: `BookingPeriod.fromQuery` lancia una `ResponseStatusException` 400, che `ResponseEntityExceptionHandler` (da cui deriva l'`ApiExceptionHandler`) traduce in un `ProblemDetail`, come gli altri 400.

**I test**: `BookingAfterSessionTest` (17: il lavoro al confine della fine, in corso, la guardia sul "non presentato" e sulla cancellata, due esecuzioni; i segni con chi e quando, la correzione, il secondo segno, nessun credito toccato; la finestra al secondo; un altro professionista, una seduta cancellata; le liste del cliente e il 400).

---

## Domande da colloquio

### Domanda 1: Un lavoro programmato cambia lo stato di molte righe. Una transazione per riga o un'istruzione sola?
**Risposta modello.** Dipende da cosa succede fuori dal database. Se ogni riga chiama un servizio esterno (annullare una carta, mandare un'email), una transazione per riga: un errore su una non deve annullare le altre, né le chiamate già fatte. Se è solo un cambio nel database, un `update` in blocco con lo stato di partenza nel `where`: atomico, veloce, e sicuro con due istanze, perché la seconda non trova più niente da cambiare.

### Domanda 2: Un `update` in blocco e un'azione dell'utente cambiano la stessa riga insieme. Cosa succede in PostgreSQL?
**Risposta modello.** Chi arriva secondo aspetta il lucchetto della riga. In `READ COMMITTED`, quando riparte, PostgreSQL rilegge la versione appena confermata e rivaluta il `where`: se lo stato non è più quello di partenza, la riga si salta. Per questo la guardia nel `where` basta, senza un lucchetto esplicito, a non riportare a "svolta" una seduta appena segnata "non presentato".

### Domanda 3: Un'impostazione o una costante?
**Risposta modello.** Una costante finché la regola è una sola per tutti e nessuno ha chiesto di cambiarla: un'impostazione è una colonna, una validazione, un campo nel pannello e un caso in più nei test. Diventa un'impostazione quando c'è un motivo vero per cui due professionisti la vogliono diversa, o quando cambia le regole verso il cliente (come il preavviso di cancellazione).

### Domanda 4: Come aggiungi un filtro a un'API senza rompere chi la usa già?
**Risposta modello.** Con un parametro facoltativo, la cui assenza dà esattamente la risposta di prima. La forma della risposta non cambia, cambiano solo le righe e il loro ordine quando il parametro c'è. Un test che fissa la risposta senza parametro dimostra che il contratto vecchio è intatto.
