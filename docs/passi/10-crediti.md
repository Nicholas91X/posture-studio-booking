# Passo 10 — Crediti e pacchetti

> **Stato: chiuso** (2026-11-11). Pezzi 1–6 fatti, 998 test verdi (`mvnw clean test`). Prova manuale dell'acquisto con la Stripe CLI fatta il 2026-11-11. Unito in `main` con un merge del branch `feat/step-10-credits` (su indicazione di Nicholas, al posto della PR).

## Obiettivo
Il cliente compra un pacchetto di sedute e paga subito su Stripe; nasce un **lotto** di crediti con una scadenza. Una prenotazione usa da sola un credito quando c'è, e lo restituisce se la richiesta non va a buon fine. Il professionista regala, proroga e corregge, sempre con una traccia. Cliente e professionista vedono i crediti, ognuno solo i propri.

I pezzi:
1. Il registro dei crediti (migrazione V15) — ✅ (guida 36, D-069)
2. Acquisto di un pacchetto — ✅ (guida 37, D-070)
3. Prenotare con un credito — ✅ (guida 38, D-071)
4. Prenotazione dal pannello con credito — ✅ (guida 39, D-072)
5. Concessioni dal pannello — ✅ (guida 40, D-073)
6. Cosa si vede — ✅ (guida 41, D-074)

Decisioni preliminari: D-066 (i clienti di un professionista), D-067 (concessioni), D-068 (nessuna origine `LATE_PAYMENT`). Punto aperto 6 chiuso.

## Criteri di "fatto" (dal brief)

| Criterio | Stato | Dove si vede |
|---|---|---|
| Decisioni preliminari registrate, punto aperto 6 chiuso | ✅ | D-066, D-067, D-068; `requisiti.md` |
| Acquisto di un pacchetto in modalità di prova, con la Stripe CLI, documentato; lo stesso evento due volte → un solo lotto | ✅ 2026-11-11 | `stato.md`; guida 37, parte 12.1; `PackageWebhookTest`, `PackageWebhookConcurrencyTest` |
| Due richieste contemporanee sull'ultimo credito → una sola lo prende | ✅, con commit veri, nel database e in Java | `CreditBalanceConcurrencyTest`, `BookingCreditConcurrencyTest` |
| Movimenti immutabili, provato anche con SQL diretto | ✅ `update`, `delete` e `truncate` rifiutati dai trigger | `CreditLedgerImmutabilityTest` |
| Regola della scadenza provata sui cambi dell'ora | ✅ marzo e ottobre, e la mezzanotte di Roma | `ValidUntilTest` |
| Restituzione del credito su rifiuto, ritiro e risposta scaduta, una volta sola | ✅ | `BookingWithCreditTest`; una volta sola: `CreditServiceTest`, `CreditSchemaTest` (indice unico parziale in V15) |
| Concessioni con la traccia (chi, quando, perché) | ✅ omaggio, proroga, correzione; nota obbligatoria | `CreditConcessionsApiTest`, `CreditSchemaTest` |
| Riservatezza: il cliente vede solo i suoi lotti, il professionista solo i propri anche di un cliente comune; niente sotto `/api/public` | ✅ anche l'anonimo (401) e il ruolo sbagliato (403) | `CustomerCreditsApiTest`, `PanelCustomersApiTest`, `CreditConcessionsApiTest` |
| `modello-dati.md` e `architettura.md` aggiornati; test verdi | ✅ V15 | `modello-dati.md`, `architettura.md` |
| Branch e unione in `main` | ✅ merge (al posto della PR, su indicazione di Nicholas) | `main` |

**Da confermare con lo studio**: un credito si usa solo se la seduta cade entro il suo ultimo giorno valido (D-071). Se lo studio preferisce "basta che il credito valga il giorno della richiesta", si cambia un argomento.

**Lasciato al passo 14** (criterio scritto in `stato.md`): in produzione l'applicazione si collega con un utente non superuser, senza `UPDATE`, `DELETE` e `TRUNCATE` su `credit_movement`. I trigger fermano gli errori, ma un superuser li può spegnere.

## Cosa è stato costruito

### Pezzo 1 — Il registro (V15, D-069)
- `credit_lot`: un lotto per professionista, servizio e cliente, con l'origine (`PACKAGE` o `GRANT`), l'ultimo giorno valido come `date` di Roma e, per un pacchetto, il pagamento (`payment_id` unico, presente se e solo se l'origine è `PACKAGE`). Niente colonna "crediti iniziali": li dice il primo movimento.
- `credit_movement`: il registro a sola aggiunta. Motivi `LOT_CREATED`, `BOOKING` (−1), `BOOKING_RELEASE` (+1), `ADJUSTMENT` (±N, con la traccia). Indici unici parziali: un solo `LOT_CREATED` per lotto, un solo `BOOKING` e una sola restituzione per prenotazione.
- `credit_lot_extension`: le proroghe, con la vecchia e la nuova data, solo in avanti.
- **Trigger**: niente `update`, `delete`, `truncate` su movimenti e proroghe; il lotto cambia solo l'ultimo giorno valido, e solo se esiste la riga della proroga; il saldo non scende mai sotto zero, con il lucchetto sulla riga del lotto (regge in `READ COMMITTED`).
- In `payment` lo scopo (`BOOKING`, `PACKAGE`) e la fotografia del pacchetto (sedute e validità); in `booking.paid_with` il valore `CREDIT`.
- `ValidUntil` (Java): un giorno di calendario a Roma, con "copre questo istante?" e "N giorni dopo".
- `LedgerCleanup`, che spegne i trigger per pulire le tabelle, **solo nel codice di test**.

### Pezzo 2 — Acquisto di un pacchetto (D-070)
- `POST /api/customer/package-purchases` → pagina di Stripe di un'ora con **incasso immediato**; `GET` della lista e di uno, con lo stato `PENDING`, `PAID`, `EXPIRED`.
- `NewCheckoutSession` diventa una `sealed interface` con `BookingCheckout` e `PackageCheckout`.
- Il webhook si divide per scopo: per un pacchetto conta `payment_status = paid`; sulla riga bloccata il pagamento passa a `SUCCEEDED`, poi `CreditService.lotFromPackage` crea il lotto dalla fotografia. La validità parte dall'istante dell'evento di Stripe.
- Tre difese contro il doppione: la tabella degli eventi, il confronta-e-cambia sul pagamento, `payment_id` unico.
- Email "Acquisto confermato" al cliente; push "Pacchetto venduto" al professionista, con l'email di riserva.

### Pezzo 3 — Prenotare con un credito (D-071)
- La richiesta di sempre usa da sola un credito il cui ultimo giorno valido copre il giorno della seduta, del lotto che scade prima. Altrimenti si paga con la carta.
- Il −1 nasce con la richiesta (`PENDING_APPROVAL`), e l'approvazione non incassa niente. Rifiuto, ritiro e risposta scaduta: +1 sullo stesso lotto.
- Lucchetto sul cliente, poi sul lotto. Il 409 "nessun conto" solo se non c'è neanche un credito.
- `paidWith` negli eventi e nelle viste; email senza parole sulla carta.

### Pezzo 4 — Dal pannello (D-072)
- La prenotazione dal pannello usa da sola un credito del cliente con la stessa regola, altrimenti "in studio". La conferma dice del credito usato; una seduta pagata con un credito non riceve link di pagamento.

### Pezzo 5 — Concessioni (D-073)
- `POST /api/panel/credit-grants` (omaggio per email, su un proprio servizio in vendita, mai nel passato), `/credit-lots/{id}/extensions` (solo in avanti, anche su un lotto scaduto), `/credit-lots/{id}/adjustments` (±N, mai sotto zero).
- Firmate dall'account che agisce, con la nota obbligatoria; lotto proprio letto con il lucchetto; email al cliente per ognuna, **senza la nota**.

### Pezzo 6 — Cosa si vede (D-074)
- Cliente: `GET /api/customer/credits` (tutti i suoi lotti, con saldo, ultimo giorno e stato `USABLE`, `USED_UP`, `EXPIRED` deciso dal backend) e `/{id}` (la storia, senza le note).
- Professionista: `GET /api/panel/customers` ("i miei clienti", costruita da `booking`), `/customers/{id}/credits` (solo i propri lotti), `/credit-lots/{id}` (con la storia e le note).
- Due query per ogni lista, qualunque sia la lunghezza.

## Concetti chiave

**Un registro, non un contatore.** Il saldo non è una colonna da aggiornare: è la somma dei movimenti. Un contatore dice quanto c'è, un registro dice anche **perché**, ed è la storia che si mostra al cliente e che serve in caso di contestazione. La regola d'oro è la sola aggiunta: un errore non si cancella, si corregge con un movimento nuovo (`ADJUSTMENT`), come in contabilità.

**Difendere una regola nel database.** Java fa rispettare le regole, ma il database è l'ultima linea, perché lo scrivono anche le migrazioni, gli script e un domani un altro programma.
- I `check` e gli indici unici parziali dicono la forma di una riga ("un solo `BOOKING` per prenotazione").
- I trigger dicono quello che un `check` non sa: che una riga non cambia più, o che una somma su più righe non scende sotto zero.
- Attenzione all'ordine: PostgreSQL esegue i trigger `BEFORE` prima dei `check`, e i `check` in ordine alfabetico. Un test che viola due regole insieme non sa quale ha scattato.
- Attenzione al `NULL`: un `check` con `NULL` non è falso, è "sconosciuto", e passa. Le condizioni si scrivono con `is not null` espliciti.

**Il lucchetto nel trigger.** "Il saldo non scende sotto zero" è una somma letta e poi un `insert`: due transazioni possono leggere entrambe "1" e scrivere entrambe "−1". Il trigger prende prima il lucchetto sulla riga del lotto (`for no key update`), così la seconda aspetta la prima e, in `READ COMMITTED`, rilegge la somma con il movimento nuovo. In `REPEATABLE READ` vedrebbe la fotografia vecchia: la regola regge solo nel livello di isolamento predefinito, ed è scritto in D-069. L'ho provato togliendo il lucchetto: due transazioni prendevano l'ultimo credito.

**Un giorno di calendario non è un istante.** "Valido fino all'8 febbraio" vuol dire fino alla mezzanotte di Roma, che in UTC è alle 23 d'inverno e alle 22 d'estate. Per questo l'ultimo giorno è una `date` (`LocalDate`), e si confronta con il giorno di Roma di un istante, mai con un `timestamptz` calcolato una volta per tutte. "30 giorni" sono 30 giorni del calendario anche se uno ne ha 25 ore.

**Incasso immediato per un pacchetto.** Una seduta aspetta un'approvazione, quindi si blocca e si incassa dopo (D-031). Un pacchetto non aspetta niente: incasso subito, e la sessione completata dice `payment_status = paid`. Lo stesso webhook ragiona per **scopo** del pagamento, con uno `switch` che il compilatore controlla.

**La fotografia.** Prezzo, sedute e validità si copiano nel pagamento quando nasce la pagina di Stripe. Se il professionista cambia o ritira il pacchetto mentre il cliente paga, il cliente riceve quello che ha visto.

**`sealed` e `switch` esaustivi (Java 17–21).** `NewCheckoutSession`, `CreditEvent` e le transizioni su `PaidWith` sono `switch` **espressione** senza `default`: un caso nuovo non compila finché non è gestito. Attenzione: uno `switch` **istruzione** su un `enum` non è controllato dal compilatore, e un caso dimenticato passa in silenzio.

**Lucchetti e ordine.** Ogni prelievo prende il lucchetto sul lotto; una richiesta del cliente prende prima quello sul cliente (D-045), poi quello sul lotto. Sempre nello stesso ordine, per non incrociarsi in un *deadlock*. Due richieste dello stesso cliente si mettono in fila già sul cliente.

**Riservatezza per costruzione.** Le query del professionista portano sempre il suo id (`findByIdAndPractitionerId`), così il lotto di un altro non si trova: 404, come uno inesistente (D-026). Le note si escludono nel punto che costruisce la vista del cliente, con un parametro esplicito e un test che controlla che non escano mai.

**Moduli senza cicli.** `booking` dipende da `credit`; `payment` da `credit`, `catalog` e `customer`; `credit` da `catalog`, `customer` e `practitioner`; nessuno dipende da `payment` o da `notification`. "I miei clienti" ha bisogno di quattro moduli e la costruisce `booking`, l'unico che può chiedere a tutti, con domande su insiemi di id.

## Decisioni
D-066 (i clienti di un professionista), D-067 (concessioni), D-068 (nessun `LATE_PAYMENT`), D-069 (il registro: trigger, scadenza come data, un lotto per pagamento, proroga in avanti), D-070 (acquisto: un'ora, incasso immediato, lotto dalla fotografia), D-071 (prenotare con un credito; **regola da confermare con lo studio**), D-072 (dal pannello), D-073 (concessioni dal pannello), D-074 (cosa si vede). Note di stato su D-008, D-024, D-031. Eccezione sulle date in CLAUDE.md (`credit_lot.valid_until`).

## Errori fatti e come sono stati corretti

- **Gli script delle difese non lanciavano Maven** (pezzi 1–4, trovato al 4). Per provare che un test protegge una regola, tolgo la regola e guardo il test fallire. Gli script chiamavano `cmd /c mvnw.cmd` con un percorso relativo, che da Python non trovava il programma: Maven non partiva, e l'errore della shell sembrava un test fallito. Venti "difese provate" non provavano niente. Corretto con il percorso completo, il controllo che l'output contenga `Tests run:` e l'output salvato; rifatte tutte, 20 su 20. Lezione: **un test che fallisce deve fallire per la ragione giusta**, e va guardato il perché.
- **Una difesa che nessun test poteva vedere** (pezzo 6). La guida prometteva di togliere "il giorno di Roma" dal calcolo di `EXPIRED`, ma l'orologio dei test è alle 10 di Roma, quando il giorno UTC e quello di Roma coincidono. Sostituita con una difesa che i test vedono ("l'ultimo giorno vale ancora oggi"); il giorno di Roma lo garantisce `StudioTime.today`, provato al passo 6.
- **I `check` controllati in ordine alfabetico, e i trigger prima dei `check`** (pezzo 1). Un test che violava due regole vedeva scattare quella "sbagliata". Ogni test ora viola una regola sola.
- **La trappola del `NULL` nei `check`** (pezzo 1): una condizione con un `NULL` dà "sconosciuto", e la riga passa. Scritte con `is not null` espliciti.
- **Un `git checkout` che non ripristina un file nuovo** (pezzo 1). Dopo un esperimento, la V15 (non ancora nel repository) non è tornata com'era: `checkout` ripristina solo i file già tracciati. Recuperata dalla copia di riserva.
- **Il backend con le classi vecchie** (prova manuale). Un 404 "No static resource" su un'API nuova: il backend girava con un avvio precedente. Riavviato con `mvnw spring-boot:run`.
- **Un falso allarme mio** (prova manuale). Ho guardato l'acquisto 9 mentre Nicholas aveva pagato il 10. Lezione: confrontare gli id della sessione prima di concludere.
- **I test "a fette" senza il nuovo servizio** (pezzo 3). Quando `BookingService` ha avuto bisogno di `CreditService`, i test che caricano solo una parte dell'applicazione non partivano più: aggiunto `CreditService` all'`@Import`. I test JSON rigorosi hanno ricevuto il campo nuovo `paidWith`.

## Domande da colloquio

### Domanda 1: Come memorizzi un saldo che deve essere verificabile, come i crediti di un cliente?
**Risposta modello.** Con un registro a sola aggiunta: ogni variazione è una riga con importo, motivo e traccia, e il saldo è la somma. Così ho la storia, posso spiegare ogni numero, e un errore si corregge con un movimento nuovo invece di cancellare. Lo difendo anche nel database: un trigger rifiuta `update`, `delete` e `truncate`, e in produzione l'utente dell'applicazione non ha nemmeno quei permessi. Per le liste leggo le somme di tutti i lotti con una sola `group by`, non una query per lotto.

### Domanda 2: Due richieste prendono contemporaneamente l'ultimo credito. Come ne passa una sola?
**Risposta modello.** Con un lucchetto sulla riga del lotto, preso prima di leggere il saldo e tenuto fino al commit. La seconda transazione aspetta la prima, poi rilegge e trova zero. Lo faccio due volte: in Java, che sceglie il lotto e lo blocca, e in un trigger, che blocca e controlla la somma a ogni movimento, così vale anche per chi scrive senza passare dal mio codice. Il trigger regge in `READ COMMITTED`, dove ogni istruzione vede i commit appena fatti; in `REPEATABLE READ` vedrebbe una fotografia vecchia. L'ho provato con commit veri, e togliendo il lucchetto: passavano tutte e due.

### Domanda 3: Un credito vale "fino all'8 febbraio". Come lo salvi e come lo confronti?
**Risposta modello.** Come una data, non come un istante: `date` in SQL e `LocalDate` in Java, il giorno di calendario a Roma. Per sapere se copre una seduta, prendo il giorno di Roma dell'inizio della seduta e confronto due date. Se salvassi un istante, dovrei decidere una volta per tutte se la mezzanotte è alle 23 o alle 22 UTC, e con i cambi dell'ora sbaglierei di un'ora per metà dell'anno. Ho test sulle due domeniche del cambio dell'ora e sull'ultimo secondo del giorno.

### Domanda 4: Il webhook di un acquisto arriva due volte, o due volte nello stesso istante. Come eviti due lotti?
**Risposta modello.** Con tre livelli. La tabella degli eventi già visti, scritta nella stessa transazione del lavoro. Il passaggio del pagamento da `PENDING` a `SUCCEEDED` fatto sulla riga letta con il lucchetto: il secondo evento trova lo stato già cambiato e si ferma. E un vincolo unico su `payment_id` nel lotto, che regge anche se gli altri due avessero un buco. L'ho provato con due consegne in parallelo, e con `stripe events resend` nella prova manuale.

### Domanda 5: Il professionista deve poter correggere un saldo, ma il registro non si modifica. Come fai?
**Risposta modello.** La correzione è un movimento in più, `ADJUSTMENT`, positivo o negativo, con chi l'ha fatta, quando e una nota obbligatoria; un `check` nel database rifiuta una correzione senza traccia. L'unica vera modifica è la proroga della scadenza: il lotto cambia la data, ma solo se nella stessa transazione esiste la riga della proroga con la vecchia e la nuova data, e un trigger lo controlla. La storia resta intera.

### Domanda 6: Lo stesso lotto lo vedono il cliente e il professionista, ma le note sono solo per il professionista. Come lo garantisci?
**Risposta modello.** Con la regola nel punto che costruisce la vista: chi costruisce quella del cliente passa esplicitamente "senza note", e un test controlla che le note non arrivino mai al cliente, né nell'API né nelle email. Le query del professionista portano sempre il suo id, quindi il lotto di un altro non si trova: 404, come uno che non esiste, senza rivelare che c'è. I test di riservatezza provano anche il cliente in comune fra due professionisti.

### Domanda 7: Quando usi uno `switch` espressione su una `sealed interface` o un `enum`, e cosa ci guadagni?
**Risposta modello.** Quando ogni caso deve essere gestito: qui lo scopo di un pagamento, come è pagata una prenotazione, i tipi di evento dei crediti. Senza `default`, il compilatore controlla che i casi ci siano tutti, e un caso nuovo non compila finché non lo gestisco. Uno `switch` **istruzione** su un `enum` invece non viene controllato: un caso dimenticato passa in silenzio. È un errore che il compilatore trova al posto mio.
