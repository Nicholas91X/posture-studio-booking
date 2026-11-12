# La disattivazione di un professionista: sedute future, crediti aperti, la gara con le prenotazioni — guida di studio

Questa guida accompagna il **pezzo 4 del passo 11** (D-077). Un professionista con sedute future non si disattiva: prima si cancellano. Riuscita la disattivazione, l'admin vede i crediti ancora validi dei suoi clienti; il cliente li vede come non utilizzabili.

```
POST /api/admin/practitioners/{id}/deactivate
     │
     ├─ lucchetto sulla riga del professionista: select ... for update
     ├─ sedute future che occupano l'orario?  → 409 PRACTITIONER_HAS_FUTURE_BOOKINGS
     ├─ disattiva l'account (D-052)
     └─ risposta: il professionista + i crediti ancora validi dei suoi clienti (l'avviso)

GET  /api/admin/practitioners/{id}/bookings            le sue sedute future (per sapere cosa cancellare)
POST /api/admin/practitioners/{id}/bookings/{b}/cancel  la cancellazione dello studio, fatta dall'admin
```

Teoria di riferimento: D-029 ("attivo" si chiede ad `auth`), D-044 (il ciclo tra moduli rotto con un'interfaccia), D-045 (`READ COMMITTED`, il lucchetto sul cliente), D-052 (la disattivazione tocca solo l'account), D-074 (lo stato dei lotti deciso dal backend), D-077 (le regole di questo pezzo), D-080 (la cancellazione dello studio).

Scelte di Nicholas (2026-11-12, "segui le tue raccomandazioni"): l'admin può fare la cancellazione dello studio per conto del professionista; il cliente vede uno stato a parte per i crediti di un professionista non più disponibile.

---

## Indice

- Parte 1 — Cosa c'è già
- Parte 2 — Quali sedute fermano la disattivazione
- Parte 3 — Il ciclo tra moduli
- Parte 4 — La gara con una prenotazione nuova, e i due lucchetti
- Parte 5 — L'urgenza: l'admin che cancella
- Parte 6 — L'avviso dei crediti
- Parte 7 — Cosa vede il cliente
- Parte 8 — I test
- Parte 9 — Cosa c'è da decidere
- Parte 10 — Com'è andata (implementazione)
- Domande da colloquio

---

## Parte 1 — Cosa c'è già

- `PractitionerService.deactivate`: disattiva l'account e basta (D-052). Accesso, sessioni aperte, elenco pubblico, catalogo, orari liberi e prenotazioni nuove seguono da soli, perché tutti chiedono ad `auth` se l'account è attivo.
- La richiesta del cliente controlla "professionista attivo" al primo passo (`catalog.serviceOnSale`), **senza lucchetti**.
- `BookingService.cancelByStudio(practitionerId, cancelledBy, bookingId)` (pezzo 3): prende già "chi" come parametro separato.
- Le dipendenze: `booking → practitioner`, `credit → practitioner, customer`. Nessuno dei due può essere chiamato da `practitioner` senza un ciclo.

---

## Parte 2 — Quali sedute fermano la disattivazione

"Sedute future in uno stato che occupa l'orario" (D-077): le stesse di `holdsTimeAt`, cioè `PENDING_APPROVAL` e `CONFIRMED`, più `PENDING_PAYMENT` **con il blocco ancora valido** (uno scaduto è già perso, e il lavoro lo chiude entro un minuto). "Future" vuol dire **non ancora finite** (`ends_at > now`): una seduta in corso adesso ferma la disattivazione, perché il professionista la sta facendo.

Una `PENDING_PAYMENT` valida dura al massimo 32 minuti: l'admin aspetta, oppure la lascia scadere. Nessuna API per cancellarla (il cliente sta pagando).

---

## Parte 3 — Il ciclo tra moduli

`practitioner` deve chiedere a `booking` ("ha sedute future?") e a `credit` ("quali crediti sono aperti?"), che dipendono già da lui. Le strade, come al passo 7 (D-044):

- **A — `practitioner` dichiara due interfacce, gli altri le implementano**: `FutureBookings` (implementata da `booking`) e `OutstandingCredits` (implementata da `credit`). `practitioner` le usa senza sapere chi c'è dietro. È l'inversione delle dipendenze di `OccupiedTime` (D-044) e `CardHolds` (D-046).
- B — la disattivazione si sposta in `booking`, che dipende già da tutti. Ma i dati e l'API dei professionisti sono di `practitioner` (D-052), e `booking` diventerebbe il posto di tutto.
- C — eventi: `practitioner` annuncia "sto per disattivare" e aspetta risposte. Un evento non è una domanda: nessuno risponde.

**Raccomandazione: A.** Due interfacce piccole, ognuna con un metodo solo, nel package di chi le usa.

---

## Parte 4 — La gara con una prenotazione nuova, e i due lucchetti

### 4.1 Il problema

La disattivazione **controlla** ("nessuna seduta futura") e poi **agisce** ("disattiva"). In mezzo, un cliente può prenotare:

```
T1 (richiesta)                         T2 (disattivazione)
"professionista attivo?" sì
                                       "sedute future?" 0   (T1 non ha ancora fatto commit)
                                       disattiva, commit
insert della seduta, commit
→ una seduta confermata con un professionista che non c'è più
```

È il "controlla e poi agisci" del limite delle prenotazioni (D-045): il controllo in Java non vede il lavoro non ancora confermato degli altri. Serve un **lucchetto che le due operazioni condividano**.

### 4.2 Quale riga, quali lucchetti

La riga naturale è quella del **professionista**: la riguarda la disattivazione, e ogni prenotazione la nomina. PostgreSQL ha quattro lucchetti di riga, e due servono qui:

| | `FOR KEY SHARE` | `FOR SHARE` | `FOR NO KEY UPDATE` | `FOR UPDATE` |
|---|---|---|---|---|
| `FOR KEY SHARE` | sì | sì | sì | **no** |
| `FOR UPDATE` | **no** | no | no | no |

("sì" = possono stare insieme.)

- **La prenotazione** prende `FOR KEY SHARE`: il più leggero. Due prenotazioni dello stesso professionista **non si aspettano** (è lo stesso lucchetto che l'`insert` prende comunque da solo per la sua foreign key verso `practitioner`).
- **La disattivazione** prende `FOR UPDATE`: l'unico che entra in conflitto con `FOR KEY SHARE`. Aspetta che finiscano le prenotazioni in corso, e ferma quelle nuove finché non ha finito.

Attenzione a `FOR NO KEY UPDATE`: è quello che usa un `update` normale, e **non** è in conflitto con `FOR KEY SHARE`. Usato per sbaglio, la gara resterebbe aperta. Per questo i due lucchetti si scrivono in SQL nativo, con la clausola esplicita, e non con `@Lock(PESSIMISTIC_WRITE)`, la cui traduzione dipende dalla versione di Hibernate.

### 4.3 L'ordine dentro la prenotazione

Il lucchetto da solo non basta: conta **quando** si guarda "attivo".

- La disattivazione prima: T2 tiene `FOR UPDATE`; T1 chiede `FOR KEY SHARE` e **aspetta**; T2 fa commit; T1 riparte e **solo adesso** chiede se il professionista è attivo. In `READ COMMITTED` ogni istruzione vede i dati confermati quando comincia, quindi vede la disattivazione: 404, nessuna seduta.
- La prenotazione prima: T1 tiene `FOR KEY SHARE`; T2 chiede `FOR UPDATE` e aspetta; T1 fa commit; T2 riparte, **solo adesso** conta le sedute future e vede quella di T1: 409.

Quindi: **il lucchetto è il primo passo** di ogni prenotazione nuova (del cliente e dal pannello), prima di guardare se il professionista è attivo. E **il lucchetto è il primo passo** della disattivazione, prima di contare.

L'ordine dei lucchetti nella richiesta diventa: professionista (`KEY SHARE`), poi cliente (D-045), poi lotto (D-069). La disattivazione prende solo il professionista: nessun incrocio.

---

## Parte 5 — L'urgenza: l'admin che cancella

D-052 aveva scartato il rifiuto della disattivazione perché "deve funzionare anche in urgenza". Con D-077 la disattivazione aspetta che le sedute siano cancellate, e oggi le cancella solo il professionista. Se è irraggiungibile, l'admin è fermo.

**Scelto (Nicholas)**: l'admin fa **la cancellazione dello studio per conto del professionista**, con lo stesso codice del pezzo 3 (`cancelByStudio`). Cambia solo chi firma: `cancelled_by` è l'account dell'admin, e la traccia lo distingue dal professionista. Il cliente riceve la stessa email ("lo studio ha dovuto cancellare la tua seduta"): per lui è lo studio.

API sotto `/api/admin/practitioners/{id}/bookings` (l'area dell'admin, D-051):
- `GET`: le sedute future che fermano la disattivazione (le stesse della parte 2), con id, servizio, orari, stato, come è pagata. **Senza** il nome del cliente: all'admin serve sapere cosa cancellare, non chi; l'email al cliente parte comunque.
- `POST /{bookingId}/cancel`: la cancellazione dello studio; una seduta di un altro professionista è un 404.

Il modulo è `booking`: le sedute sono sue. Il controller è nuovo (`BookingAdminController`), il servizio lo stesso.

---

## Parte 6 — L'avviso dei crediti

Riuscita la disattivazione, la risposta contiene il professionista **e** l'elenco dei **crediti ancora validi** dei suoi clienti: i lotti con saldo positivo e ultimo giorno valido non passato. Per ognuno: il cliente (nome, cognome, email: l'admin deve poterlo contattare per un rimborso o un accordo), il servizio, il saldo, l'ultimo giorno.

- È un **avviso**, non una regola: la disattivazione è già avvenuta. Cosa fare di quei crediti si decide fuori dal sistema (D-077); un rimborso segue la procedura dello studio.
- La risposta cambia forma: `{practitioner, outstandingCredits}` invece del solo professionista. È un cambio di contratto voluto, e i test che fissano la vecchia risposta vanno aggiornati.
- **Idempotente** come prima (D-052): disattivare un professionista già disattivato rifà gli stessi controlli e restituisce lo stesso avviso. Un professionista disattivato prima del passo 11 con sedute future riceve 409 anche adesso: è l'informazione giusta per l'admin.

---

## Parte 7 — Cosa vede il cliente

Nella vista dei crediti (D-074) un quarto stato, deciso dal backend: **`PRACTITIONER_UNAVAILABLE`**. Ordine delle regole:

1. saldo zero → `USED_UP`;
2. ultimo giorno passato → `EXPIRED` (scaduto lo sarebbe comunque);
3. professionista non attivo → `PRACTITIONER_UNAVAILABLE`;
4. altrimenti `USABLE`.

"Attivo" si chiede a `practitioner`, che lo chiede ad `auth` (D-029), con **una domanda su un insieme** di professionisti per tutta la lista. Se il professionista viene riattivato, i lotti tornano `USABLE` da soli: lo stato non si salva, si calcola.

---

## Parte 8 — I test

- **La regola**: una seduta confermata futura → 409 e account ancora attivo; una richiesta in attesa → 409; una `PENDING_PAYMENT` con il blocco valido → 409, scaduto → si disattiva; una seduta finita, cancellata o rifiutata → si disattiva; una seduta in corso → 409.
- **La strada dell'urgenza**: l'admin elenca le sedute future, ne cancella una (traccia con il suo account, credito al cliente), poi disattiva.
- **L'avviso**: i lotti validi dei suoi clienti con nome ed email; non quelli finiti, scaduti, o di un altro professionista.
- **La vista del cliente**: `PRACTITIONER_UNAVAILABLE` dopo la disattivazione, `USABLE` dopo la riattivazione; un lotto scaduto resta `EXPIRED`.
- **La gara, nei due ordini, con commit veri**: (a) una transazione tiene `FOR UPDATE` sulla riga e disattiva l'account; una richiesta parte e deve aspettare; al commit la richiesta riceve 404 e nessuna seduta nasce. (b) una transazione tiene `FOR KEY SHARE` e inserisce una seduta; la disattivazione parte e deve aspettare; al commit riceve 409.
- **Le difese tolte a turno**: il controllo delle sedute; il lucchetto della disattivazione (o `FOR NO KEY UPDATE` al suo posto: la gara (b) si riapre); il lucchetto della richiesta (la gara (a) si riapre); l'ordine "lucchetto, poi attivo"; lo stato nuovo nella vista.

---

## Parte 9 — Cosa c'è da decidere

| # | Scelta | Raccomandazione |
|---|---|---|
| 1 | Il ciclo tra moduli | `FutureBookings` e `OutstandingCredits` dichiarate da `practitioner` (parte 3) |
| 2 | La gara | `FOR UPDATE` alla disattivazione, `FOR KEY SHARE` all'inizio di ogni prenotazione, in SQL nativo; "attivo" guardato dopo il lucchetto (parte 4) |
| 3 | Le sedute che fermano | quelle che occupano l'orario e non sono finite (parte 2) |
| 4 | L'admin | elenco e cancellazione sotto `/api/admin/practitioners/{id}/bookings`, senza nomi dei clienti (parte 5) — **scelto da Nicholas** |
| 5 | L'avviso | nella risposta della disattivazione, con nome ed email del cliente (parte 6) |
| 6 | Il cliente | `PRACTITIONER_UNAVAILABLE`, dopo `USED_UP` ed `EXPIRED` (parte 7) — **scelto da Nicholas** |

---

## Parte 10 — Com'è andata (implementazione)

**File**: in `practitioner` le due interfacce (`FutureBookings`, `OutstandingCredits`), `OutstandingCredit`, `PractitionerDeactivationView`, il servizio nuovo `PractitionerDeactivation`, i due lucchetti nativi in `PractitionerRepository` (`lockForDeactivation`, `lockForBooking`), `PractitionerService.lockForBooking` e `activeAmong`; in `booking` `BookingFutureBookings`, `BookingAdminController`, `AdminBookingView`, la query delle sedute non finite e il lucchetto come primo passo della richiesta e della prenotazione dal pannello; in `credit` `CreditOutstanding`, la query dei lotti validi di un professionista, il quarto stato in `CreditLotSummary` e `CreditViews` che chiede chi è attivo; `ErrorCode.PRACTITIONER_HAS_FUTURE_BOOKINGS`. Nessuna migrazione.

**Una scelta di forma dettata dai test.** Il primo progetto aggiungeva le due interfacce a `PractitionerService`. Ma 14 contesti di test lo importano a mano, senza `booking` né `credit`: sarebbero diventati tutti rossi per una dipendenza che a loro non serve. Un servizio a parte (`PractitionerDeactivation`) tiene `PractitionerService` com'era. È anche più giusto così: la disattivazione con i controlli è l'unica operazione di `practitioner` che ha bisogno degli altri moduli.

**La trappola di `FOR NO KEY UPDATE`, vista davvero.** Togliendo `FOR UPDATE` e mettendo `FOR NO KEY UPDATE`, il test della gara (b) fallisce proprio perché la disattivazione **non aspetta** la prenotazione: i due lucchetti possono stare insieme, e la disattivazione conta zero sedute e va avanti. È il motivo per cui la clausola è scritta in SQL nativo.

**Come si prova che uno aspetta l'altro.** Una transazione del test prende il lucchetto e si ferma su un `CountDownLatch`; l'API vera parte in un altro thread; dopo mezzo secondo il test controlla che l'API **non abbia ancora risposto** (`isDone()` falso), poi libera la transazione e guarda la risposta. Senza il controllo del mezzo secondo, un test potrebbe passare anche se l'API non aspettasse affatto, per un caso di tempi.

**Un dettaglio della gara (a) senza il lucchetto della richiesta.** La richiesta aspetta lo stesso: l'`insert` della seduta prende da solo `FOR KEY SHARE` per la foreign key, e incontra il `FOR UPDATE` della disattivazione. Ma aspetta **dopo** aver chiesto se il professionista è attivo, e quando riparte inserisce la seduta: 201 invece di 404. Il lucchetto c'era già, era nel posto sbagliato. Per questo la difesa "lucchetto preso dopo il controllo" fallisce come quella "nessun lucchetto".

**I test**: `PractitionerDeactivationApiTest` (11: le sedute che fermano e quelle che no, la strada dell'urgenza con la firma dell'admin, una seduta di un altro professionista, l'area dell'admin, l'avviso, lo stato del cliente prima e dopo la riattivazione) e `PractitionerDeactivationRaceTest` (2, con commit veri, i due ordini). In `PractitionerAdminApiTest` la disattivazione legge `$.practitioner.active`: il contratto è cambiato apposta.

---

## Domande da colloquio

### Domanda 1: Un'operazione controlla una condizione e poi agisce. Come la proteggi da un'altra transazione che cambia la condizione in mezzo?
**Risposta modello.** Con un lucchetto che le due operazioni condividano, preso **prima** del controllo da entrambe. Qui la disattivazione prende `FOR UPDATE` sulla riga del professionista prima di contare le sedute, e ogni prenotazione prende `FOR KEY SHARE` sulla stessa riga prima di guardare se il professionista è attivo. In `READ COMMITTED` chi aspetta, quando riparte, legge i dati confermati dall'altro. Un controllo fatto prima del lucchetto non serve a niente: può essere già vecchio.

### Domanda 2: Perché `FOR KEY SHARE` per la prenotazione e non `FOR UPDATE` anche lì?
**Risposta modello.** Perché due prenotazioni dello stesso professionista non devono aspettarsi a vicenda: `FOR KEY SHARE` è compatibile con se stesso, `FOR UPDATE` no, e metterebbe in fila tutte le prenotazioni del professionista. Basta che il lucchetto della prenotazione sia in conflitto con quello della disattivazione, e `FOR KEY SHARE` lo è solo con `FOR UPDATE`. È lo stesso lucchetto che PostgreSQL prende da solo per una foreign key.

### Domanda 3: Un modulo deve chiedere qualcosa a un modulo che dipende già da lui. Cosa fai?
**Risposta modello.** Inverto la dipendenza: il modulo che chiede dichiara un'interfaccia con la domanda, nel suo package, e l'altro la implementa. A runtime Spring inietta l'implementazione; a compile time il primo modulo non conosce il secondo. Il ciclo sparisce, e la domanda ha un nome che dice cosa serve, non come è fatto l'altro modulo.

### Domanda 4: Uno stato che dipende da un altro dato (il professionista attivo) lo salvi o lo calcoli?
**Risposta modello.** Lo calcolo quando lo mostro. Salvato, andrebbe aggiornato in ogni lotto a ogni disattivazione e riattivazione, e prima o poi qualcuno se ne dimenticherebbe. Calcolato, è sempre giusto; il costo è una domanda in più su un insieme di id per tutta la lista.

### Domanda 5: Perché scrivere un lucchetto in SQL nativo invece di usare l'annotazione di JPA?
**Risposta modello.** Quando la scelta del lucchetto è la regola. `PESSIMISTIC_WRITE` dice "lucchetto in scrittura", e ogni dialetto lo traduce come vuole: su PostgreSQL può diventare `FOR UPDATE` o `FOR NO KEY UPDATE`, e il secondo non è in conflitto con `FOR KEY SHARE`. Qui la differenza apre o chiude la gara, quindi la clausola si scrive esplicita e un test la prova.
