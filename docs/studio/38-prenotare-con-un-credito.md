# Prenotare con un credito — guida di studio

Questa guida accompagna il **pezzo 3 del passo 10**. Il cliente che ha un credito valido per quel professionista e quel servizio prenota **senza passare da Stripe**: il credito si **impegna** (movimento −1 sul lotto che scade prima), poi l'approvazione segue la strada di sempre (D-030). Se la richiesta non va a buon fine, il credito **torna** (+1) sullo stesso lotto.

```
POST /api/customer/bookings   (la stessa richiesta di oggi, nessun campo nuovo)
  → credito utilizzabile?  sì: PENDING_APPROVAL, paidWith CREDIT, −1 sul lotto       (niente Stripe)
                           no: PENDING_PAYMENT, paidWith CARD, pagina di Stripe      (come oggi)
approvazione → CONFIRMED (niente da incassare)
rifiuto, risposta scaduta, ritiro → +1 sullo stesso lotto
```

Teoria di riferimento: D-010 ("scade prima, si usa prima"), D-023 (limite), D-030 (approvazione), D-045 (lucchetto sul cliente, `READ COMMITTED`), D-046 (lucchetto sulla prenotazione nelle transizioni), D-061 (409 senza conto), D-063 (prima controllare, poi incassare, poi cambiare), D-069 (registro, saldo mai negativo, `ValidUntil`).

---

## Indice

- Parte 1 — Cosa c'è già, e dove si tocca
- Parte 2 — La domanda dello studio: un credito che scade prima della seduta
- Parte 3 — Carta o credito: chi sceglie
- Parte 4 — L'ordine della richiesta, e il 409 senza conto
- Parte 5 — "Scade prima, si usa prima": la query
- Parte 6 — L'ultimo credito conteso: quale lucchetto basta
- Parte 7 — Le transizioni: uno `switch` su come è pagata
- Parte 8 — Le email e cosa vede il cliente
- Parte 9 — I test
- Parte 10 — Cosa c'è da decidere
- Domande da colloquio

---

## Parte 1 — Cosa c'è già, e dove si tocca

C'è: la V15 con `paid_with = CREDIT` (mai `PENDING_PAYMENT`) e il registro con i suoi trigger; `credit_movement` con "un solo `BOOKING` e una sola restituzione per prenotazione"; la richiesta del cliente con il suo ordine (D-045); le transizioni con il lucchetto (D-046); il lavoro che chiude le richieste scadute (D-047).

Si tocca in cinque punti di `BookingService`, tutti oggi legati alla carta:

| Punto | Oggi | Con il credito |
|---|---|---|
| richiesta | sempre `PENDING_PAYMENT` e pagina di Stripe | `PENDING_APPROVAL` e −1, se c'è un credito |
| approvazione (anche automatica) | incasso, poi conferma | conferma e basta: il credito è già impegnato |
| rifiuto | annulla il blocco | +1 |
| ritiro | annulla il blocco | +1 |
| risposta scaduta (lavoro) | annulla il blocco | +1 |

Le dipendenze: `booking → credit`, che D-069 aveva previsto. `credit` non sa niente di prenotazioni oltre al loro id (D-026).

---

## Parte 2 — La domanda dello studio: un credito che scade prima della seduta

Il cliente ha un credito valido fino al **15 marzo**. Il 10 marzo prenota una seduta per il **20 marzo** (l'orizzonte è di 21 giorni). Lo si usa?

- **(a) Basta che sia valido quando prenota.** Generoso. Ma il credito "scade il 15" vorrebbe dire "prenotabile fino al 15", per sedute anche ad aprile: una frase che il cliente non legge così. E c'è un problema concreto: se la richiesta viene **rifiutata il 12**, il credito torna a un lotto che scade il 15, e se viene rifiutata il 16 torna a un lotto **già scaduto**. Il cliente l'ha perso per una decisione del professionista.
- **(b) Solo se la seduta cade entro l'ultimo giorno valido.** "Scade il 15" vuol dire "sedute entro il 15", che è quello che un cliente capisce. E il ritorno è sempre sano: rifiuto, scadenza della risposta e ritiro arrivano **prima dell'inizio** della seduta (la risposta scade prima dell'anticipo minimo, D-030), quindi un credito che torna trova il suo lotto ancora valido almeno fino al giorno della seduta.

**Raccomandazione: (b).** In codice è `validUntil.covers(inizioDellaSeduta)`, la stessa regola di D-069 con l'istante della seduta invece di adesso. Se lo studio preferirà la (a), cambia un solo argomento. Se il credito non copre la seduta, la richiesta va con la carta, come per chi non ha crediti: il frontend (passo 12) potrà dire "il tuo credito scade prima di questa data".

---

## Parte 3 — Carta o credito: chi sceglie

L'architettura dice: il credito si usa da solo. Le alternative: un campo `paidWith` nella richiesta, o un "usa il credito" che il cliente deve spuntare.

- Il cliente che ha comprato un pacchetto vuole usarlo: non ha motivo di pagare di nuovo con la carta la stessa seduta.
- Un campo in più è un'altra combinazione da provare (credito chiesto e non disponibile? carta chiesta con credito disponibile?), e una regola di business nel frontend se è lui a decidere quando mostrarlo.
- Un caso in cui il cliente preferirebbe la carta esiste appena: un credito che scade dopo e una seduta che vorrebbe pagare a parte. Rarissimo; e se un giorno servirà, sarà un campo facoltativo, aggiunto senza rompere niente.

**Raccomandazione: nessuna scelta.** Se c'è un credito utilizzabile, si usa; altrimenti la carta. La richiesta non cambia forma.

---

## Parte 4 — L'ordine della richiesta, e il 409 senza conto

Oggi (D-045, D-061): servizio in vendita → **409 se il professionista non ha un conto** → lucchetto sul cliente → limite → orario libero → blocchi scaduti → `insert` → pagina di Stripe.

Con il credito il 409 "prima di tutto" non va più bene così: un professionista **senza conto** può ricevere prenotazioni da chi ha un suo credito (un omaggio, pezzo 5), e gli va permesso. Il nuovo ordine:

1. servizio in vendita (404);
2. **controllo veloce, senza lucchetti**: se il cliente non ha un credito utilizzabile **e** il professionista non ha un conto → 409 `CARD_PAYMENTS_UNAVAILABLE`, come oggi, prima di bloccare qualunque cosa;
3. lucchetto sul cliente; limite (D-023 vale **anche con il credito**: il limite protegge il calendario, non i soldi); orario libero; blocchi scaduti;
4. **la scelta vera, sotto il lucchetto**: il lotto utilizzabile che scade prima, con il lucchetto sulla sua riga (parte 6);
5. **con il credito**: `insert` in `PENDING_APPROVAL` con la scadenza della risposta, movimento −1, poi approvazione automatica o richiesta al professionista, come dopo un pagamento;
6. **senza credito**: se il professionista non ha un conto → 409 (il caso raro in cui il credito del punto 2 è sparito nel frattempo); altrimenti `insert` in `PENDING_PAYMENT` e pagina di Stripe, come oggi.

Il punto 2 è una lettura senza lucchetto, quindi può sbagliare per un istante; non decide niente, serve solo a rispondere presto nel caso comune. Decide il punto 4.

Il prezzo della prenotazione resta fotografato (`price_cents`, D-011), anche pagata con un credito: il `check` lo vuole positivo, ed è il valore della seduta, utile al passo 11.

---

## Parte 5 — "Scade prima, si usa prima": la query

Un lotto è utilizzabile per questa seduta se è **di questo cliente, professionista e servizio**, **copre il giorno della seduta** (`valid_until >= giorno a Roma`, parte 2) e ha **saldo positivo**. Tra quelli, quello con `valid_until` più vicino; a parità, il più vecchio (id più basso).

```sql
select l.id
from credit_lot l join credit_movement m on m.credit_lot_id = l.id
where l.customer_id = :c and l.practitioner_id = :p and l.service_id = :s
  and l.valid_until >= :giornoDellaSeduta
group by l.id, l.valid_until
having sum(m.amount) > 0
order by l.valid_until, l.id
```

`having` è il `where` dei gruppi: filtra **dopo** la somma, cosa che un `where` non può fare. L'indice della V15 `(customer_id, practitioner_id, service_id, valid_until)` serve proprio a questa query. "Utilizzabile adesso" non serve scriverlo: la seduta è nel futuro, e un lotto che copre il suo giorno copre anche oggi.

Il FEFO (*first expired, first out*) è la regola dei magazzini con merce che scade: si usa prima quello che scade prima, così niente scade inutilizzato mentre si consuma il resto.

---

## Parte 6 — L'ultimo credito conteso: quale lucchetto basta

Il brief chiede di verificare se il lucchetto sul cliente (D-045) basta già.

**Il ragionamento.** Due richieste dello stesso cliente, un solo credito. La seconda aspetta il lucchetto sul cliente; quando la prima fa commit, la seconda esegue la query della parte 5 **dopo** l'attesa, e in `READ COMMITTED` ogni istruzione vede i commit fatti prima che parta: vede il −1, il lotto ha saldo 0, non è più utilizzabile. **Sì, basta.**

**Perché prendere comunque il lucchetto sul lotto.** D-069 ha deciso la regola: ogni movimento negativo prende il lucchetto sulla riga del lotto, e il trigger del saldo lo prende comunque. Il lucchetto sul cliente protegge le richieste del cliente; non protegge la **correzione** del professionista (pezzo 5), che non passa dal cliente. Prenderlo anche qui costa niente (nessuno lo aspetta, nel caso normale) e tiene una regola sola per tutti. Dopo il lucchetto il saldo **si rilegge**: se nel frattempo una correzione l'ha portato a zero, si prova il lotto successivo.

L'ordine dei lucchetti resta quello di D-069: cliente, poi lotto.

**La prova.** Un test con due richieste davvero contemporanee dello stesso cliente con un solo credito: una prende il credito, l'altra va con la carta (`PENDING_PAYMENT`), il saldo finisce a 0. Poi due esperimenti, come per le difese:
- tolto il lucchetto sul **lotto**: il test resta verde. È la prova che il lucchetto sul cliente basta;
- tolti **entrambi**: le due richieste vedono lo stesso credito, e la seconda è fermata dal **trigger** del saldo con un errore. L'ultima difesa del database funziona, ma il cliente riceverebbe un 500: per questo serve almeno un lucchetto in Java.

---

## Parte 7 — Le transizioni: uno `switch` su come è pagata

Oggi `captureAndConfirm`, `reject`, `withdraw` ed `expireApproval` chiamano la carta. Diventano uno `switch` su `paidWith`:

```java
boolean paid = switch (booking.getPaidWith()) {
    case CARD -> cards.capture(...) instanceof CaptureResult.Captured;
    case CREDIT -> true;   // impegnato alla richiesta: niente da incassare
    case IN_STUDIO -> throw new IllegalStateException(...);   // non aspetta mai un'approvazione
};
```

Qui uno `switch` **espressione**: assegna un valore, quindi il compilatore pretende tutti i casi. Se il passo 11 o un altro aggiungerà un modo di pagare, questo non compila finché qualcuno non decide cosa fa l'approvazione. È la differenza vista nella guida 37 (parte 6), usata stavolta a nostro favore.

`IN_STUDIO` lancia, invece di ignorare: una prenotazione dal pannello nasce confermata, e arrivare qui vorrebbe dire un errore di programmazione, che è meglio vedere.

**La restituzione.** `CreditService.release(bookingId)`: trova il movimento `BOOKING` di quella prenotazione e scrive `BOOKING_RELEASE` +1 **sullo stesso lotto**, che tiene la sua scadenza (D-010). Se la restituzione c'è già, non fa niente; e l'indice unico "una sola restituzione per prenotazione" (V15) lo garantisce comunque. Gira nella transazione della transizione (`MANDATORY`), con la prenotazione già bloccata: rifiuto e lavoro programmato non possono restituire due volte.

---

## Parte 8 — Le email e cosa vede il cliente

Le email di oggi parlano di carta: "l'importo è bloccato sulla carta", "il blocco sulla carta si annulla". Con il credito sarebbero false. Gli eventi `ApprovalRequested`, `BookingConfirmed`, `BookingRejected`, `ApprovalExpired`, `BookingWithdrawn` ricevono **`paidWith`**: è un fatto del momento, che un ascoltatore dopo il commit non dovrebbe ricostruire. `BookingEmails` cambia le frasi:

| Email | Carta (oggi) | Credito |
|---|---|---|
| richiesta inviata (cliente) | importo bloccato, si addebita solo se approvata | "hai usato un credito, è impegnato per questa richiesta; se non viene approvata torna disponibile" |
| da approvare (professionista) | "il blocco sulla carta del cliente si annulla" | "il credito torna al cliente" |
| rifiutata, scaduta, ritirata | "non ti è stato addebitato niente" | "il credito che avevi usato è di nuovo disponibile, con la sua scadenza" |

Cosa vede il cliente: `CustomerBookingView` riceve `paidWith` (lo vede già il professionista nelle sue liste); `paymentUrl` resta `null` per un credito, perché non c'è niente da pagare.

---

## Parte 9 — I test

| Test | Cosa prova |
|---|---|
| `CreditServiceTest` (con database) | FEFO: il lotto che scade prima, a parità il più vecchio; un lotto che non copre il giorno della seduta, uno a saldo zero, uno di un altro servizio o professionista non si usano; `commit` −1; `release` +1 sullo stesso lotto, una volta sola; nessuna restituzione per una prenotazione senza credito |
| `BookingWithCreditTest` (API, sessioni vere) | richiesta con credito → 201, `PENDING_APPROVAL`, `paidWith: CREDIT`, nessuna chiamata a Stripe, −1 sul lotto che scade prima; automatica → `CONFIRMED` senza incasso; approvata → `CONFIRMED` senza incasso; rifiutata, ritirata, scaduta (lavoro) → +1 sullo stesso lotto; credito che scade prima della seduta → carta; professionista senza conto ma credito → funziona; senza conto e senza credito → 409 senza lucchetti; limite raggiunto → 409 e nessun movimento |
| `BookingCreditConcurrencyTest` (commit veri) | due richieste dello stesso cliente nello stesso istante, un credito: una `CREDIT`, l'altra `CARD`, saldo 0; con gli esperimenti della parte 6 |
| `BookingEmailsTest` (aggiunte) | le frasi del credito, e nessuna parola sulla carta |

**Le difese tolte a turno**: la regola "copre il giorno della seduta" (con `covers(adesso)`), l'ordine FEFO, la restituzione nelle tre transizioni, l'approvazione senza incasso.

---

## Parte 10 — Cosa c'è da decidere

| Scelta | Raccomandazione | Dove |
|---|---|---|
| Credito che scade prima della seduta | non si usa: la seduta deve cadere entro l'ultimo giorno valido; altrimenti carta | parte 2 |
| Carta o credito | nessuna scelta del cliente: credito se c'è, altrimenti carta | parte 3 |
| Ordine e 409 | 409 veloce solo se non c'è né credito né conto; la scelta vera sotto il lucchetto; limite anche con il credito | parte 4 |
| FEFO | query con `having`, ordinata per `valid_until` e id | parte 5 |
| Lucchetti | cliente (basta, provato) e anche lotto, con il saldo riletto | parte 6 |
| Transizioni | `switch` espressione su `paidWith`; `release` nella stessa transazione, una volta sola | parte 7 |
| Email e vista | `paidWith` negli eventi e nella vista del cliente; frasi del credito | parte 8 |

Dopo l'implementazione: una scheda (D-071) e l'architettura ("Prenotazione con credito") aggiornata.

---

## Parte 11 — Com'è andata (implementazione)

Costruito come raccomandato nella parte 10 (D-071), su indicazione di Nicholas del 2026-11-11 ("dopo la guida, se non hai domande, procedi"). Nessuna migrazione: la V15 aveva già `paid_with = CREDIT` e il registro. 26 test nuovi, 959 in tutto, `mvnw clean test` verde.

**I file:**

| File | Cosa cambia |
|---|---|
| `CreditService` | `hasUsableCredit` (senza lucchetti), `lockUsableLot` (FEFO, lucchetto, saldo riletto), `commit` (−1), `release` (+1 sullo stesso lotto, una volta sola) |
| `CreditLotRepository` | la query FEFO con `group by ... having`, e `findLockedById` |
| `ValidUntil` | `dayOf(istante)`: il giorno di Roma, usato da `covers` e dalla query |
| `Booking` | `requestedWithCredit`: nasce `PENDING_APPROVAL`, `CREDIT`, con la scadenza della risposta |
| `BookingService` | il nuovo ordine della richiesta; `confirmPaid` e `releasePayment` con lo `switch` su `paidWith` al posto delle chiamate dirette alla carta |
| `BookingEvent` | `paidWith` in cinque eventi |
| `CustomerBookingView` | `paidWith` |
| `BookingEmails` | le frasi del credito |

**Test nuovi**: `CreditServiceTest` (9), `BookingWithCreditTest` (13), `BookingCreditConcurrencyTest` (2, commit veri), due casi in `BookingEmailsTest`. I test "a fette" (`@DataJpaTest`) che importano `BookingService` a mano ora importano anche `CreditService`: senza, il contesto non partiva. La risposta attesa di `BookingCustomerApiTest`, scritta per intero, ha ora `"paidWith": "CARD"`.

**Gli esperimenti della parte 6:**
- tolto il lucchetto sul **lotto**, il test di concorrenza resta verde, quattro volte su quattro: **il lucchetto sul cliente basta** per le richieste;
- tolti **entrambi**, fallisce: due richieste vedono lo stesso credito, e il trigger del saldo ferma la seconda.

*Aggiornamento del 2026-11-11, pezzo 4: l'osservazione qui sotto è risolta. Lo script non lanciava Maven ("mvnw.cmd non è riconosciuto"), e leggeva l'errore come un test fallito; vale per tutte le difese del passo, rifatte con lo script corretto (20 su 20, D-072, guida 39 parte 8).*

**Un'osservazione aperta, scritta com'è.** Dentro lo script che toglie le difese a turno, la **prima** esecuzione con il solo lucchetto sul lotto tolto era fallita. Lo script non salvava l'output, quindi non so perché. Ripetuta a mano, e poi tre volte di fila, è sempre passata, e il codice intatto è passato tre volte su tre. Il ragionamento dice che il risultato non dipende dai tempi (la seconda richiesta aspetta comunque il lucchetto sul cliente), quindi il sospetto è qualcosa dell'ambiente di quella esecuzione, ma è un sospetto, non una prova. Lezione per i prossimi script: **salvare sempre l'output** di ogni esecuzione, anche di quelle che sembrano andare come previsto.

**Le difese tolte a turno**, ognuna presa dal suo test:

| Tolto | Test che se ne accorge |
|---|---|
| "copre il giorno della seduta" (sostituito da "valido oggi") | `BookingWithCreditTest`: il credito che scade domenica pagherebbe la seduta di lunedì |
| L'ordine FEFO (invertito) | `CreditServiceTest` |
| La restituzione in rifiuto, ritiro e scadenza | `BookingWithCreditTest`, tre test |
| La conferma senza incasso (il credito che chiede la carta) | `BookingWithCreditTest` |

**Da confermare con lo studio**: la regola della parte 2. Se preferiranno "valido al momento della prenotazione", cambia l'istante passato a `lockUsableLot` e `hasUsableCredit`, e il test che oggi lo protegge cambierà verso.

---

## Domande da colloquio

### Domanda 1: Due richieste dello stesso cliente con un solo credito arrivano insieme. Cosa impedisce di usarlo due volte?

**Risposta modello.** Il lucchetto sulla riga del cliente mette in fila le sue richieste, e con `READ COMMITTED` la seconda, dopo l'attesa, rilegge i saldi e vede il credito già usato: va con la carta. In più prendo il lucchetto sul lotto, perché quello copre anche chi toglie crediti senza passare dal cliente, e un trigger nel database rifiuta comunque un saldo negativo. L'ho provato con due richieste davvero contemporanee, e togliendo i lucchetti uno alla volta.

### Domanda 2: Come scrivi in SQL "il lotto con saldo positivo che scade prima"?

**Risposta modello.** Unisco lotti e movimenti, filtro per cliente, professionista, servizio e scadenza nel `where`, raggruppo per lotto e tengo solo i gruppi con `having sum(amount) > 0`, perché il `where` lavora sulle righe prima della somma e non può filtrarla. Ordino per scadenza e, a parità, per id, così il risultato è deterministico. Un indice sulle colonne del filtro e della scadenza rende la query veloce.

### Domanda 3: Un credito vale fino al 15 marzo. Si può usare per una seduta del 20?

**Risposta modello.** Abbiamo deciso di no: la seduta deve cadere entro l'ultimo giorno valido. È quello che un cliente capisce leggendo "scade il 15", e soprattutto rende sicura la restituzione: un rifiuto arriva sempre prima della seduta, quindi il credito torna a un lotto ancora valido. Con la regola opposta un credito restituito potrebbe tornare a un lotto già scaduto, perso per una decisione che non è del cliente.

### Domanda 4: Hai un `enum` con tre modi di pagare e tre operazioni che dipendono da lui. Come eviti di dimenticare un caso?

**Risposta modello.** Con uno `switch` espressione senza `default`: per un `enum` il compilatore pretende tutti i valori, quindi un quarto modo di pagare non compila finché non decido cosa fa ogni operazione. Un caso che non può succedere lancia un'eccezione invece di essere ignorato, così un errore di programmazione si vede subito.

### Domanda 5: Perché il limite di prenotazioni future vale anche per chi paga con un credito?

**Risposta modello.** Perché il limite non riguarda i soldi ma il calendario: impedisce a un cliente di occupare settimane di orari. Chi ha un pacchetto da dieci sedute è proprio il cliente che potrebbe farlo. Il limite sta nello stesso punto della richiesta per tutti, prima della scelta tra credito e carta.

### Domanda 6: Dove metti un'informazione che serve all'email ma riguarda lo stato di un'altra entità?

**Risposta modello.** Se è un fatto del momento, nell'evento: l'ascoltatore gira dopo il commit, in un altro thread, e rileggere lo stato lì vorrebbe dire leggere quello di dopo, che potrebbe essere cambiato. Qui come è stata pagata la prenotazione sta nell'evento. I dati anagrafici invece, come il nome del cliente, si chiedono al modulo che li possiede, e non vanno negli eventi.

---

## Documentazione ufficiale

- PostgreSQL, `group by` e `having`: https://www.postgresql.org/docs/17/queries-table-expressions.html#QUERIES-GROUP
- PostgreSQL, isolamento `READ COMMITTED`: https://www.postgresql.org/docs/17/transaction-iso.html#XACT-READ-COMMITTED
- Java 21, `switch` ed esaustività (JEP 441): https://openjdk.org/jeps/441
