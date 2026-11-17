# Passo 13b — Contanti e agende a confronto

> **Stato: in revisione** (2026-11-17), branch `feat/step-13b-cash-and-agendas`. 1174 test del backend, 17 test end-to-end e 16 prove unitarie del frontend verdi.

## Obiettivo

Tre cose:
- un professionista senza conto Stripe lavora "in studio": i clienti prenotano online senza pagare, i pacchetti si vendono in studio e si registrano dal pannello;
- dal pannello, una prenotazione per un cliente si può far pagare in studio anche se il cliente ha crediti;
- una sezione del pannello confronta le agende dei professionisti, solo come occupato e libero, e trova gli orari liberi insieme.

Il codice l'ha scritto Claude, backend compreso, come previsto dal brief. Niente guide di studio.

## Criteri di "fatto" (dal brief)

- [x] **Decisioni registrate, requisiti aggiornati, nota sul punto 18**: D-089, D-090, D-091. `requisiti.md` aggiornato in "Prenotazione e pagamento", "Prenotazione dal pannello", "Pacchetti" e "Riservatezza del calendario"; il punto aperto 18 dice che le condizioni descrivono i due modi di pagare. Rimandi nelle schede modificate (D-024, D-049, D-084).
- [x] **I quattro pezzi funzionano; test verdi**: 37 test nuovi nel backend (1174 in tutto); 2 test end-to-end nuovi (17 in tutto). Schermate nuove guardate su un telefono simulato.
- [x] **stato.md aggiornato; PR verso main; riepilogo**.

## Cosa è stato costruito

### Pezzo 1 — Prenotare senza conto

| File | Cosa fa |
|---|---|
| `V17__in_studio_requests_and_studio_sales.sql` | Toglie `booking_in_studio_ck` (prima "in studio" era solo del professionista) e aggiunge "in studio non è mai in attesa di pagamento". La parte sui lotti è del pezzo 2 |
| `Booking.requestedWithoutCard` | Era `requestedWithCredit`: una richiesta senza niente da pagare online, con un credito o in studio |
| `BookingService.request` | Senza credito e senza conto: `requestWithoutCard` con `IN_STUDIO`. Via il controllo che dava 409 prima di scrivere |
| `BookingService.confirmPaid`, `releasePayment` | `IN_STUDIO`: niente da incassare, niente da restituire. Prima era "impossibile" e lanciava un'eccezione |
| `BookingEmails` | Richiesta, conferma e richiesta non andata a buon fine dicono "si paga in studio" |
| `practitioner/PaymentMode`, `PublicPractitioner.paymentMode` | `CARD` o `IN_STUDIO` al posto del booleano `onlineBooking` |
| `PaymentAccountService.unassign`, `DELETE /api/admin/practitioners/{id}/payment-account` | L'admin toglie un conto |
| Sito: `PractitionerFacts`, `HomeClient`, `SlotsClient`, `PractitionerClient`, `BookingClient`, `BookingCard` | "Prenoti online, paghi in studio"; "pagherai X € in studio" prima di prenotare; pacchetti "acquistabili in studio"; la seduta "confermata: la paghi in studio" |

### Pezzo 2 — Pacchetto venduto in studio

| File | Cosa fa |
|---|---|
| V17 (parte sui lotti) | `credit_lot.sold_by`, l'origine `STUDIO_SALE`, i `check` che legano origine, pagamento, pacchetto, prezzo e traccia; la funzione del trigger che elenca anche `sold_by` |
| `CreditLot.studioSale`, `LotSource.STUDIO_SALE` | Il lotto con la fotografia del pacchetto e chi l'ha venduto |
| `credit/StudioSales`, `NewStudioSale`, `POST /api/panel/package-sales` | Pacchetto proprio in vendita (altrimenti 404), cliente per email, lotto e movimento `LOT_CREATED`. Nessuna riga in `payment` |
| `components/panel/StudioSaleDialog.tsx` | "Registra un pacchetto venduto in studio", da "I miei clienti" e dalla pagina del cliente |

### Pezzo 3 — Credito o studio dal pannello

| File | Cosa fa |
|---|---|
| `NewPanelBooking.inStudio` | Facoltativo: `true` fa pagare in studio anche con un credito |
| `GET /api/panel/bookings/credit-check` (`PanelCreditQuery`, `PanelCreditCheck`) | C'è un credito con **questo** professionista? Fino a quando? Non crea niente |
| `AccountService.customerAccountOf`, `CustomerService.findByEmail`, `CreditService.usableCreditUntil` | Le tre domande senza scrittura che servono alla risposta |
| `BookForCustomerClient.tsx` | Nella conferma, "Come si paga": credito o studio, solo se il credito c'è |

### Pezzo 4 — Agende a confronto

| File | Cosa fa |
|---|---|
| `schedule/WorkingIntervals`, `WorkingTime`, `ScheduleService.workingTimeOf` | Orari di lavoro di un periodo come intervalli, e assenze senza il motivo |
| `booking/BusyRange`, `BookingRepository.findBusy` | La query che seleziona solo professionista, inizio e fine |
| `booking/FreeTogether` | Funzione pura: lavoro meno occupato e assenze, intersecato tra i professionisti, finestre lunghe almeno la durata, non nel passato |
| `booking/AgendaService`, `AgendaPanelController`, `AgendaView`, `TimeSpan`, `AgendaPeriod`, `FreeTogetherQuery` | `GET /api/panel/agendas` e `/agendas/free-together` |
| `practitioner/ActivePractitioner`, `PractitionerService.activeByName` | Chi è attivo, solo per nome |
| `components/calendar/WeekGrid.tsx` | Colonne qualsiasi (un professionista in un giorno), blocchi che non sono link, fasce di sfondo |
| `app/pannello/agende` | Un giorno, una colonna per professionista più "Insieme", la legenda e i liberi insieme della settimana; scelta di chi e della durata nell'indirizzo |

## Concetti chiave

**Una regola, una fonte.** "Si paga in studio" non è un'impostazione: lo decide il conto. Con due fonti (il conto e un interruttore), un giorno si contraddicono: un conto assegnato con la carta spenta, o il contrario. Con una sola, lo stato sbagliato non si può nemmeno scrivere.

**Lo `switch` che costringe a decidere (Java 21).** `confirmPaid` e `releasePayment` sono `switch` sull'`enum` `PaidWith` senza `default`. Quando `IN_STUDIO` è diventato possibile anche per una richiesta, il compilatore non ha dovuto indovinare niente: c'era già un ramo, che diceva "impossibile". È bastato cambiarlo in "niente da fare", con due etichette nello stesso ramo, `case CREDIT, IN_STUDIO -> true`.

**Riservatezza per costruzione.** La query delle agende scrive `select new BusyRange(b.practitionerId, b.startsAt, b.endsAt)`: un'*espressione costruttore* JPQL che legge tre colonne e basta. Cliente, servizio e stato non arrivano in memoria, quindi un campo aggiunto per sbaglio alla risposta non può farli uscire. Il test lo controlla campo per campo.

**Intervalli semiaperti `[inizio, fine)`.** Una seduta 9–10 e una 10–11 non si toccano: le 10:00 appartengono solo alla seconda. Con questa convenzione le operazioni sugli intervalli (unire, sottrarre, intersecare) non hanno casi speciali, e i confini si provano con tre test: lunga esattamente la durata (sì), un minuto meno (no), una seduta che finisce all'inizio della finestra (la lascia intera).

**Una funzione pura in mezzo.** Il calcolo dei "liberi insieme" non conosce né il database né l'orologio: riceve gli intervalli e "adesso". Si prova in pochi millisecondi con orari scritti a mano; il servizio intorno raccoglie i dati con una domanda per modulo.

**Fotografia anche senza pagamento.** Il lotto venduto in studio copia sedute, validità e prezzo dal pacchetto **nel momento della vendita**, come il pacchetto pagato online li copia dal pagamento (D-069). Cambiare il pacchetto domani non cambia quello che il cliente ha comprato oggi.

**PostgreSQL controlla i `check` in ordine alfabetico.** Con più vincoli violati dalla stessa riga, l'errore nomina il primo per nome. Un test che vuole provare un vincolo deve scrivere una riga che viola **solo** quello (errore 1).

**Validazione su più campi in un `record`.** "Da non dopo a, al massimo 31 giorni" riguarda due campi: un metodo `@AssertTrue boolean isPeriod()` nel `record` della query. Bean Validation lo tratta come una proprietà `period`, e l'errore 400 nomina quella.

## Decisioni

- **D-089**: senza conto si paga in studio; pacchetti venduti in studio (`STUDIO_SALE`); `paymentMode`; l'admin toglie un conto; la nota per i testi legali.
- **D-090**: dal pannello, credito o studio; `inStudio` e `credit-check`.
- **D-091**: agende a confronto, solo occupato e libero; "liberi insieme".

## Errori fatti e come sono stati corretti

1. **Due test vecchi sui lotti sono falliti dopo la V17.** Volevano provare `credit_lot_payment_ck`, ma la mia regola nuova `credit_lot_package_columns_ck` viene prima in ordine alfabetico e scattava lei. Le righe dei test ora violano solo il vincolo che vogliono provare.
2. **Un mio test provava due vincoli di fila.** Dopo la prima violazione la transazione del test è interrotta e la seconda istruzione fallisce per quello (`current transaction is aborted`). Diviso in due test.
3. **`requireActive` aveva perso il suo `@Transactional`.** Il metodo nuovo `activeByName` era finito tra il commento con l'annotazione e il metodo vecchio. Visto rileggendo il file subito dopo, prima di qualunque test; rimesso a posto.
4. **Il contratto scaricato era quello vecchio.** Fermare Maven non aveva fermato il backend Java avviato nella sessione di prima, che rispondeva ancora sulla 8082. Visto perché nel contratto mancavano i campi nuovi. Fermato quel processo per PID; ora controllo sempre che la porta sia libera.
5. **Un test end-to-end cercava "Acquistabile in studio"**, ma nei test del cliente Anna non ha pacchetti. Errore del test.
6. **Nelle agende i blocchi "occupato" avevano lo stesso colore dell'orario di lavoro.** Visto nella schermata; l'orario di lavoro ora è celeste.

## Domande da colloquio

### Domanda 1: Il pagamento in studio va attivato con un'impostazione o dedotto dal conto di incasso?
**Risposta modello.** Dedotto dal conto: una regola, una fonte. Con un'impostazione separata possono esistere stati incoerenti (conto assegnato ma pagamento online spento), e qualcuno deve decidere chi vince. Se domani serve davvero un professionista con conto che non vuole la carta, si aggiunge una scelta esplicita, con una decisione scritta.

### Domanda 2: Come mostri a un professionista l'agenda di un altro senza rivelare i suoi clienti?
**Risposta modello.** La query non li legge proprio: un'espressione costruttore che seleziona professionista, inizio e fine in un `record` di tre campi. La risposta ha un tipo suo, mai riusato da altre API, e un test controlla l'insieme esatto dei campi a ogni livello. È riservatezza per costruzione, non per filtro: un filtro si dimentica, una colonna mai letta no.

### Domanda 3: Come calcoli gli orari in cui più persone sono libere?
**Risposta modello.** Per ognuno, orari di lavoro meno occupato e assenze, dopo aver unito gli intervalli che si toccano; poi l'intersezione tra tutti, e infine solo le finestre lunghe almeno la durata e non nel passato. Intervalli semiaperti, così il confine è senza ambiguità. È una funzione pura, provata ai confini senza database.

### Domanda 4: Aggiungi un valore a un `enum` e un vincolo `check` nel database. Cosa deve cambiare insieme?
**Risposta modello.** Una migrazione nuova (mai modificare una già eseguita) che toglie e rimette il `check` con il valore nuovo; il valore nell'`enum` Java; ogni `switch` senza `default` che il compilatore segnala; e qui anche la funzione del trigger che elenca le colonne immutabili, se la tabella riceve una colonna. Poi i test che controllano i vincoli, attenti all'ordine alfabetico in cui PostgreSQL li valuta.

### Domanda 5: Un pacchetto venduto in contanti: perché non una riga nella tabella dei pagamenti?
**Risposta modello.** Perché quella tabella è il registro di Stripe: ogni riga ha una sessione, uno stato che arriva dal webhook, un conto. Un incasso in contanti non ha niente di tutto questo, e il sistema non deve fingere di averlo verificato. Il lotto ha un'origine sua (`STUDIO_SALE`), la fotografia del pacchetto e chi l'ha registrato: è la traccia che serve.

### Domanda 6: Il professionista deve scegliere tra credito e pagamento in studio. Come lo sa prima di confermare?
**Risposta modello.** Il pannello chiede `credit-check` con servizio, orario ed email: il backend risponde con la stessa regola della prenotazione (il lotto utilizzabile che scade prima), senza lucchetti e senza creare il cliente. La prenotazione poi decide di nuovo sotto i suoi lucchetti: la domanda serve a mostrare la scelta, non a garantirla.
