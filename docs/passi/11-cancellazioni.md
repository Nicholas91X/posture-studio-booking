# Passo 11 — Cancellazioni

> **Stato: chiuso** (2026-11-12). Pezzi 1–5 fatti, 1120 test verdi (`mvnw clean test`). Branch `feat/step-11-cancellations`, unito in `main` con un merge (Nicholas ha preferito il merge alla PR del brief, come al passo 10).

## Obiettivo
Il cliente cancella una seduta confermata: in tempo riceve il credito, tardi lo perde. Lo studio può cancellare una seduta, e il cliente non perde niente. Le sedute passate diventano "svolte" o "non presentato". Un professionista con sedute future non si disattiva.

I pezzi:
1. La migrazione V16 — ✅ (guida 42, D-078)
2. La cancellazione del cliente — ✅ (guida 43, D-079)
3. La cancellazione da parte dello studio — ✅ (guida 44, D-080)
4. La disattivazione di un professionista — ✅ (guida 45, D-081)
5. Dopo la seduta — ✅ (guida 46, D-082)

Decisioni preliminari: D-071 confermata dallo studio; D-075 (cancellazione da parte dello studio, chiude il punto aperto 2), D-076 (validità minima del credito restituito, modifica D-010), D-077 (crediti di un professionista che lascia, modifica D-052). Procedura del rimborso a mano in `docs/procedure-studio.md`.

## Criteri di "fatto" (dal brief)

| Criterio | Stato | Dove si vede |
|---|---|---|
| Decisioni registrate; punti aperti chiusi; D-010 e D-052 modificate | ✅ | D-075–D-077 (e D-078–D-082 dei pezzi); punto aperto 2 in `requisiti.md`; intestazioni di D-010, D-015, D-052, D-071 |
| Cancellazione in tempo e tardiva provate al confine del preavviso | ✅ un secondo prima, l'istante esatto (in tempo), un secondo dopo, l'inizio | `BookingCancellationApiTest` |
| Credito restituito: stesso lotto, oppure lotto nuovo con validità minima, e un secondo giro senza nuovo allungamento | ✅ il giorno minimo prima, uguale, dopo; il giro della carta | `CreditCancellationServiceTest`, `BookingCancellationApiTest` |
| Cancellazione da parte dello studio con i tre modi di pagamento | ✅ carta (anche dentro il preavviso), credito (anche già allungato), in studio; e le richieste in attesa | `BookingStudioCancellationApiTest` |
| Criterio di D-065 provato con il percorso vero | ✅ link dall'API del pannello, cancellazione dall'API del cliente, poi il pagamento; nei due ordini | `BookingCancellationLinkTest` |
| Doppia cancellazione → un solo credito; corse provate | ✅ due cancellazioni nello stesso istante con commit veri; la corsa con il link nei due ordini; nel database l'indice "una sola restituzione" anche per il lotto nuovo | `BookingCancellationLinkTest`, `CreditCancellationSchemaTest` |
| Disattivazione bloccata con sedute future; avviso dei crediti; lotti non utilizzabili per il cliente | ✅ con la gara contro una prenotazione nuova, nei due ordini, con commit veri | `PractitionerDeactivationApiTest`, `PractitionerDeactivationRaceTest` |
| `COMPLETED` automatico e `NO_SHOW` dal pannello | ✅ con la finestra di 7 giorni al secondo | `BookingAfterSessionTest` |
| Procedura di rimborso manuale scritta per lo studio | ✅ prima la correzione, poi il rimborso | `docs/procedure-studio.md` |
| `modello-dati.md` e `architettura.md` aggiornati; test verdi; PR verso `main` | ✅ / ✅ / ✅ con un merge, scelta di Nicholas | `modello-dati.md` (V16), `architettura.md` (cancellazioni, disattivazione, dopo la seduta) |
| Chiusura come da CLAUDE.md, con riepilogo | ✅ | questo documento, `stato.md` |

## Cosa è stato costruito

### Pezzo 1 — La migrazione V16 (D-078)
- `booking`: cinque stati (`CANCELLED_IN_TIME`, `CANCELLED_LATE`, `CANCELLED_BY_STUDIO`, `COMPLETED`, `NO_SHOW`), nessuno che occupa l'orario; `cancelled_by` (l'account che ha cancellato, preteso dalle tre cancellazioni e solo da loro); `attendance_marked_by` e `attendance_marked_at`; **`free_cancellation_until`**, il preavviso fotografato alla prenotazione, riempito per le righe esistenti e sempre prima dell'inizio.
- Crediti: origine `CANCELLATION`, motivo `CANCELLATION_RESTORE`; il `LOT_CREATED` di un lotto `CANCELLATION` porta la prenotazione, così l'indice che c'era già ferma un secondo credito per la stessa cancellazione, qualunque sia la strada.
- `BookingStatus` con le frecce nuove; `V16BackfillTest`, la prima prova di una migrazione su righe esistenti (Flyway fino alla V15, righe vecchie, poi la V16).

### Pezzo 2 — La cancellazione del cliente (D-079)
- `POST /api/customer/bookings/{id}/cancel`: solo le proprie confermate, non dall'inizio della seduta; in tempo fino a `free_cancellation_until` compreso.
- `CreditService.restoreOnCancellation` (stesso lotto o lotto nuovo con la validità minima, una volta sola per il cliente) e `lotFromCancellation` (carta); in studio niente.
- `PaymentLinkClosing` in `payment`: dopo il commit, con il solo lucchetto del pagamento e `REQUIRES_NEW`, fa scadere il link aperto di una seduta "in studio".
- Evento `BookingCancelled`: email al cliente, push al professionista (con email di riserva). La vista del cliente con `freeCancellationUntil`, `cancelledAt`, `returnedCredit`.

### Pezzo 3 — La cancellazione da parte dello studio (D-080)
- `POST /api/panel/bookings/{id}/cancel`: una confermata → `CANCELLED_BY_STUDIO` a prescindere dal preavviso, con la validità minima sempre garantita; una richiesta in attesa → il rifiuto di sempre (`rejectLocked`). Risposta `PanelCancellationView` con il credito tornato.

### Pezzo 4 — La disattivazione di un professionista (D-081)
- `PractitionerDeactivation`: `FOR UPDATE` sulla riga del professionista, poi il conteggio delle sedute non finite (409 `PRACTITIONER_HAS_FUTURE_BOOKINGS`), poi l'account; la risposta con i crediti ancora validi dei suoi clienti.
- Ogni prenotazione nuova prende `FOR KEY SHARE` sulla stessa riga come primo passo. Le due interfacce `FutureBookings` e `OutstandingCredits` di `practitioner`, implementate da `booking` e `credit`.
- `BookingAdminController`: le sedute che fermano la disattivazione e la cancellazione dello studio fatta dall'admin. Lo stato `PRACTITIONER_UNAVAILABLE` nella vista dei crediti.

### Pezzo 5 — Dopo la seduta (D-082)
- Il lavoro `completeSessionsOver`: un `update` in blocco ogni minuto. `/no-show` e `/attended` nel pannello, per 7 giorni dalla fine. `?period=upcoming|past` per "le mie sedute".

## Concetti chiave

**Il confine di una regola di tempo.** Prima si decide se l'istante esatto è dentro o fuori, e lo si scrive in un posto solo ("fino a" comprende l'ultimo istante: `!now.isAfter(limite)`). Poi lo si prova con un orologio che il test controlla, in tre punti: un secondo prima, l'istante esatto, un secondo dopo. Solo la riga dell'istante esatto distingue `<` da `≤`: senza, entrambe le regole passerebbero. Lo stesso per la finestra dei 7 giorni e per la fine della seduta del lavoro.

**Fotografare una regola.** Il preavviso è un'impostazione che cambia; il cliente ha prenotato con quello di quel giorno. Come il prezzo (D-011), si salva alla nascita della prenotazione (`free_cancellation_until`). Una regola interna che vale per il futuro (la validità di un credito nuovo) si legge invece quando serve.

**Una macchina a stati che cresce.** Lo `switch` espressione senza `default` su un `enum` non compila finché ogni stato nuovo non ha le sue frecce; le frecce vecchie le proteggono i test di prima, rimasti uguali. In PostgreSQL `drop` + `add` di un `check` nella stessa migrazione sono atomici, perché anche il DDL sta in una transazione.

**Una regola sola per un fatto solo.** "Una cancellazione restituisce al massimo un credito" ha due strade (+1 sul lotto, o un lotto nuovo). Due vincoli separati, uno per strada, lascerebbero passare tutte e due: un `check` o un `unique` vede una tabella sola. Facendo portare la prenotazione anche al movimento che fa nascere il lotto nuovo, l'indice che c'era già vede le due strade come la stessa cosa.

**Cercare un giro in una regola che regala qualcosa.** Si elencano le azioni che l'utente controlla e ogni modo in cui la cosa regalata nasce, e si prova a ripetere. "Si allunga una volta" chiudeva il giro del pacchetto ma non quello della carta. Un'azione che l'utente non controlla (lo studio che cancella) non può essere un passo di un giro, e lì il limite si può togliere.

**Compensazioni verso un servizio esterno.** Se il sistema resta corretto anche quando la chiamata non avviene (qui il webhook annulla comunque un pagamento arrivato tardi), la chiamata è una cortesia: dopo il commit, fuori dai lucchetti della transazione principale, con l'errore solo registrato. Dentro la transazione avrebbe preso due lucchetti nell'ordine opposto al webhook: un possibile deadlock.

**`@TransactionalEventListener` e le transazioni.** Gira dopo il commit, ma mentre Spring sta ancora chiudendo quella transazione: un `@Transactional` di default si unirebbe a lei, che non accetta più lavoro (`No active transaction`). Serve `REQUIRES_NEW`. E un'eccezione dell'ascoltatore non arriva al chiamante: Spring la registra e va avanti.

**Idempotenza, su due livelli.** Gli effetti (un solo credito) li garantiscono il lucchetto sulla riga e un vincolo del database. La risposta è una scelta a parte: il secondo clic riceve lo stesso risultato invece di un errore.

**Controlla-e-agisci tra due operazioni diverse.** La disattivazione e una prenotazione nuova condividono un lucchetto sulla riga del professionista, preso **prima** del controllo da entrambe. Quale lucchetto conta: `FOR KEY SHARE` è compatibile con se stesso (due prenotazioni non si aspettano) ed è in conflitto solo con `FOR UPDATE`; `FOR NO KEY UPDATE`, quello di un `update` normale, non lo è, e la gara resterebbe aperta. Per questo i lucchetti sono scritti in SQL nativo.

**Il ciclo tra moduli.** Chi chiede dichiara un'interfaccia con la domanda, chi sa la implementa (`FutureBookings`, `OutstandingCredits`, come `OccupiedTime` al passo 7).

**Un `update` in blocco e un'azione contemporanea.** In `READ COMMITTED` PostgreSQL, dopo l'attesa, rilegge la riga e rivaluta il `where`: la guardia sullo stato di partenza basta, senza un lucchetto esplicito.

## Decisioni
D-071 (confermata dallo studio), D-075 (cancellazione da parte dello studio; conferma D-015), D-076 (validità minima, una volta sola, eccezione dello studio; modifica D-010), D-077 (professionista che lascia; modifica D-052), D-078 (V16), D-079 (cancellazione del cliente), D-080 (cancellazione dello studio), D-081 (disattivazione), D-082 (dopo la seduta).

## Errori fatti e come sono stati corretti

1. **Un test che spostava una seduta a metà** (pezzo 1). La suite completa, non i test scelti a mano, ha trovato un aiuto di `PaymentLinkApiTest` che cambiava l'inizio di una seduta con SQL e lasciava il preavviso fotografato al vecchio orario. Il vincolo nuovo "il preavviso prima dell'inizio" l'ha rifiutato. Corretto il test. Lezione: la suite completa sempre, prima di dire "fatto".
2. **Un'affermazione sbagliata nella guida** (pezzo 2). La guida diceva che un'eccezione dell'ascoltatore dopo il commit sarebbe arrivata al cliente come un 500. Togliendo il `catch` il test restava verde: Spring registra l'eccezione e va avanti (gira in `afterCompletion`). Guida e commento corretti. Lezione: una difesa che nessun test vede va capita, non solo segnalata.
3. **Un test che leggeva con SQL prima del `flush`** (pezzo 2). `cancelled_by` risultava `null` perché la modifica dell'entità era ancora solo in memoria. Il test ora fa `flush` prima di leggere, e così scatta anche il `check` della traccia.
4. **Un contratto JSON cambiato senza accorgersene** (pezzo 2). Tre campi nuovi nella vista del cliente: `BookingCustomerApiTest`, che confronta la risposta campo per campo, l'ha fermato. Era il suo scopo: un cambio del contratto deve essere una scelta. Aggiornato il contratto atteso.
5. **`Map.of` con più di 10 coppie** (pezzo 1). Con dodici stati il test del diagramma non compilava: `Map.of` ha versioni solo fino a 10 coppie. Sostituito con `Map.ofEntries`.
6. **Il lucchetto c'era, ma nel posto sbagliato** (pezzo 4, trovato togliendo le difese). Senza il lucchetto all'inizio della richiesta, la richiesta aspettava comunque la disattivazione (all'`insert`, per la foreign key), ma **dopo** aver già chiesto se il professionista era attivo: ripartiva e creava la seduta. E con `FOR NO KEY UPDATE` al posto di `FOR UPDATE` la disattivazione non aspettava proprio.
7. **Un test che falliva per il motivo sbagliato** (pezzo 5). Tolta la guardia del lavoro, il test falliva per un `check` del database su una seduta cancellata, e nascondeva il caso "non presentato", che il database non protegge. Il test è stato diviso in due. Lezione: guardare sempre perché un test fallisce.
8. **Gli script delle difese** (miei, pezzi 4 e 5): due volte il testo da sostituire non combaciava con il codice, e lo script si è fermato prima di toccare il file. Controllato ogni volta che i file fossero intatti, corretto il testo, rilanciato.

## Domande da colloquio

### Domanda 1: Una regola dice "si può cancellare gratis fino a 24 ore prima". Come la implementi e come la provi?
**Risposta modello.** Calcolo il limite una volta, alla prenotazione (inizio meno il preavviso), e lo salvo: così un cambio dell'impostazione non tocca chi ha già prenotato. Decido che l'istante esatto è ancora in tempo ("fino a" lo comprende) e lo scrivo in un metodo solo. Lo provo con un orologio che controllo, un secondo prima, all'istante esatto e un secondo dopo: l'istante esatto è l'unico punto che distingue `<` da `≤`.

### Domanda 2: Come impedisci che un doppio clic restituisca due crediti?
**Risposta modello.** Con due livelli. Il lucchetto sulla riga della prenotazione mette in fila le due richieste: la seconda, dopo l'attesa, trova la seduta già cancellata e restituisce lo stesso risultato senza fare niente. E un indice unico parziale nel database accetta un solo movimento positivo per prenotazione: siccome anche il lotto nuovo nasce con un movimento che porta la prenotazione, l'indice vede come la stessa cosa il +1 e il lotto nuovo. Il secondo livello serve per il bug che un giorno salterà il primo.

### Domanda 3: Dopo il commit devi far scadere una pagina di pagamento su Stripe. Dove e come?
**Risposta modello.** Prima verifico che la correttezza non dipenda da questo: qui un pagamento arrivato tardi viene annullato dal webhook. Allora è una compensazione di cortesia: un ascoltatore dopo il commit, fuori dal lucchetto della prenotazione (dentro avrei preso due lucchetti nell'ordine opposto al webhook), con `REQUIRES_NEW` per la sua scrittura e gli errori registrati come avvisi. Se la pagina risulta appena pagata, non la tocco: il webhook arriva e annulla il blocco.

### Domanda 4: Due operazioni diverse, una disattivazione e una prenotazione, possono incrociarsi. Come le metti in fila senza mettere in fila tutte le prenotazioni?
**Risposta modello.** Con due lucchetti diversi sulla stessa riga: la prenotazione prende `FOR KEY SHARE`, compatibile con se stesso, la disattivazione `FOR UPDATE`, l'unico in conflitto con lui. Tutte e due lo prendono prima del loro controllo; in `READ COMMITTED` chi aspetta legge, ripartendo, quello che l'altro ha confermato. Lo scrivo in SQL nativo, perché `@Lock(PESSIMISTIC_WRITE)` può diventare `FOR NO KEY UPDATE`, che non è in conflitto con `FOR KEY SHARE`. E lo provo con un test che tiene il lucchetto aperto e controlla che l'altra operazione aspetti davvero.

### Domanda 5: Una regola "si allunga una volta sola". Come verifichi che non si possa aggirare?
**Risposta modello.** Cerco un giro: parto da ogni modo in cui la cosa allungata nasce (qui un credito da un pacchetto, da un omaggio, da una seduta pagata con carta) e ripeto le azioni che l'utente controlla, prenotare e cancellare in tempo. Il credito nato dalla carta aveva già i suoi 90 giorni: se "già allungato" avesse contato solo per i lotti nati da un allungamento, il giro sarebbe ripartito. E le azioni che l'utente non controlla, come la cancellazione dello studio, non possono far parte di un giro.

### Domanda 6: Come provi una migrazione che riempie una colonna nelle righe esistenti?
**Risposta modello.** I test di solito partono da un database vuoto e migrato fino all'ultima versione, dove quell'`update` non tocca niente. Serve un test a parte: Flyway fino alla versione prima (`target`), righe scritte come le scriveva il codice di allora, poi la migrazione nuova, e il controllo dei valori riempiti e del `not null`. In un database suo, perché gli altri test lo vogliono all'ultima versione.
