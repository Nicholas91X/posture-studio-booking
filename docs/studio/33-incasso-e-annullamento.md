# Incasso e annullamento veri — guida di studio

Questa guida accompagna il **pezzo 4 del passo 9**. Dal pezzo 3 il webhook porta le prenotazioni in attesa di approvazione con l'importo **bloccato** sulla carta. Ora serve l'ultimo tratto (D-031): all'**approvazione** si incassa, al **rifiuto**, al **ritiro** e alla **scadenza della risposta** si annulla il blocco. Oggi lo fa per finta `LoggingCardHolds`, che scrive nel log e **conferma senza incassare**: questo pezzo la sostituisce, ed è per questo che i pezzi 3 e 4 vanno in `main` insieme.

Teoria di riferimento: D-031 (blocco e incasso), D-046 (`CardHolds`, lucchetto sulla prenotazione, ordine delle operazioni), D-047 (lavoro delle risposte scadute), D-057 (si opera sul conto del pagamento), D-061 (tempi d'attesa verso Stripe, doppia scrittura), D-062 (webhook, confronta-e-cambia), guida 31 parte 4 (doppia scrittura).

---

## Indice

- Parte 1 — Cosa c'è già, e cosa manca
- Parte 2 — Cosa dice Stripe (verificato)
- Parte 3 — Prima scelta: prima incassare, poi confermare
- Parte 4 — Seconda scelta: un incasso rifiutato è una risposta, non un'eccezione
- Parte 5 — Cosa vede il professionista
- Parte 6 — "Stripe ha incassato, il nostro commit è fallito": la riconciliazione
- Parte 7 — L'annullamento che fallisce
- Parte 8 — I lucchetti: pagamento e prenotazione, in che ordine
- Parte 9 — Un errore trovato nel pezzo 3: l'oggetto vecchio in memoria
- Parte 10 — I test
- Parte 11 — Cosa c'è da decidere
- Parte 12 — Com'è andata (implementazione)
- Parte 13 — Dopo la revisione di Nicholas (D-064)
- Domande da colloquio

---

## Parte 1 — Cosa c'è già, e cosa manca

**C'è:** `CardHolds` (`capture`, `cancel`) dichiarata da `booking` e chiamata da approvazione, rifiuto, ritiro e scadenza della risposta (D-046, D-047); il pagamento `AUTHORIZED` con il suo PaymentIntent e il suo conto (pezzi 1–3); `StripeGateway.cancelPaymentIntent` (pezzo 3).

**Manca:** l'incasso su Stripe; l'implementazione vera di `CardHolds`; cosa succede se l'incasso è rifiutato; il caso "incassato ma commit fallito"; il test completo di D-057 (cambiare conto non sposta incasso e annullamento).

---

## Parte 2 — Cosa dice Stripe (verificato)

Sulla documentazione, il 2026-11-10:
- si incassa un PaymentIntent **solo** nello stato `requires_capture`; altrimenti Stripe risponde con un errore (`payment_intent_unexpected_state`);
- un PaymentIntent non incassato viene **annullato da Stripe dopo 7 giorni** dalla creazione, e arriva l'evento `payment_intent.canceled`;
- dopo l'incasso il PaymentIntent è `succeeded`, e arriva l'evento `payment_intent.succeeded`.

**Non verificato**: se un blocco annullato costa una commissione. La documentazione dice che un blocco si annulla (non si rimborsa), e che le commissioni di un pagamento **incassato** non tornano indietro; su un blocco mai incassato non c'è una frase ufficiale. Da controllare nella prova manuale, guardando i movimenti del saldo del conto di prova dopo un rifiuto (nota per il documento del passo).

---

## Parte 3 — Prima scelta: prima incassare, poi confermare

Oggi `confirm` fa così (D-046):

```java
booking.approve(now);                                  // 1. in memoria: CONFIRMED
cards.capture(booking.getId(), booking.getPriceCents()); // 2. Stripe
events.publishEvent(BookingConfirmed.of(booking));      // 3. evento
```

Se l'incasso fallisce lanciando un'eccezione, tutta la transazione si annulla e l'oggetto in memoria non conta più: va bene **solo se** qualcuno lascia uscire l'eccezione. Ma nell'approvazione **automatica** (dentro il webhook, D-062) un'eccezione vuol dire "Stripe, riprova": e se l'incasso è rifiutato per sempre (blocco già sparito), Stripe riproverebbe per tre giorni, ogni volta con lo stesso rifiuto.

Proposta: **controllare, incassare, e solo dopo cambiare**:

```java
booking.checkMove(CONFIRMED);              // 1. il passaggio è permesso? (un passaggio vietato non arriva mai a Stripe)
switch (cards.capture(...)) {              // 2. Stripe
    case Captured c -> { booking.approve(now); events.publishEvent(...); }   // 3. solo ora CONFIRMED
    case Refused r  -> ...                                                    //    niente è cambiato
}
```

Così, se l'incasso è rifiutato, l'oggetto in memoria è ancora com'era, e chi chiama può decidere cosa fare **senza** buttare via la transazione.

---

## Parte 4 — Seconda scelta: un incasso rifiutato è una risposta, non un'eccezione

Due tipi di fallimento, molto diversi:

| | Esempio | Riprovare serve? | Come lo diciamo |
|---|---|---|---|
| **Temporaneo** | Stripe non risponde | Sì, tra poco | `ServiceUnavailableException` → 503, tutto annullato |
| **Definitivo** | Blocco scaduto o già annullato (`payment_intent_unexpected_state`), carta rifiutata (`CardException`) | No: la risposta sarà la stessa | `Refused`, un **valore** di ritorno |

```java
sealed interface CaptureResult {
    record Captured() implements CaptureResult {}
    record Refused(String reason) implements CaptureResult {}
}
```

Perché un valore e non un'eccezione "di dominio" che qualcuno cattura? Per una **trappola di Spring**: se un metodo `@Transactional` di un altro bean lancia un'eccezione, la transazione condivisa viene segnata **"solo rollback"**, anche se poi qualcuno la cattura. Al commit arriva una `UnexpectedRollbackException`, e tutto il lavoro si perde lo stesso. Un valore di ritorno non segna niente. È lo stesso ragionamento di `CodeCheck` (D-050): **gli esiti attesi sono valori, le eccezioni sono per l'inatteso**.

Poi, a seconda di chi ha chiesto:
- **approvazione del professionista** → `Refused` diventa un **409** `CARD_CAPTURE_FAILED`; la richiesta resta `PENDING_APPROVAL` (il brief);
- **approvazione automatica** (nel webhook) → la richiesta resta `PENDING_APPROVAL` e il professionista riceve la notifica "da approvare", come in modalità manuale: decide lui (di solito rifiuta, e il cliente riceve "non addebitato"). Il webhook risponde 200: niente da riprovare.

---

## Parte 5 — Cosa vede il professionista

Il 409 `CARD_CAPTURE_FAILED`, con un testo che il pannello (passo 13) traduce: *"Il pagamento non è più disponibile sulla carta del cliente: la richiesta non si può confermare. Puoi rifiutarla; se non rispondi, scade da sola."* Il cliente, al rifiuto o alla scadenza, riceve l'email di sempre: niente addebitato.

Un rifiuto definitivo subito dopo il pagamento è quasi impossibile (il blocco dura 7 giorni, la risposta al massimo 48 ore, D-042). Succede se qualcuno annulla il blocco a mano dalla dashboard di Stripe, o se una carta viene bloccata nel frattempo.

---

## Parte 6 — "Stripe ha incassato, il nostro commit è fallito": la riconciliazione

È il caso pericoloso della doppia scrittura (guida 31, parte 4), annunciato da D-046: l'incasso **sposta soldi**, e non si "annulla" con un rollback.

```
approva → lucchetti → Stripe incassa ✓ → aggiorna il pagamento → commit ✗ (database giù, riavvio)
```

Risultato: sul conto Stripe i soldi ci sono; da noi pagamento `AUTHORIZED` e prenotazione `PENDING_APPROVAL`. Chi rimette le cose a posto? **Stripe stesso**, con l'evento `payment_intent.succeeded`, che arriva dopo ogni incasso:

1. si trova il pagamento dal PaymentIntent (con il lucchetto);
2. confronta-e-cambia `AUTHORIZED` → `SUCCEEDED`. **Nel caso normale** il nostro commit è già avvenuto, il pagamento è già `SUCCEEDED`: zero da fare;
3. se invece era ancora `AUTHORIZED`, il nostro commit era fallito: si conferma la prenotazione (`BookingService.paymentCaptured`), **senza** chiamare di nuovo Stripe, e il cliente riceve la conferma. Un incasso avviene solo dopo un'approvazione: confermare è rispettare quella decisione;
4. se nel frattempo la prenotazione è stata rifiutata o è scaduta (raro: servirebbe un commit fallito **e** una risposta nel giro di pochi secondi), i soldi sono incassati per una seduta che non c'è. Non si rimborsa dal sistema (D-015): si scrive un **errore** nel log, "rimborsare a mano dalla dashboard".

E se il professionista, vista la pagina d'errore, ripremesse "approva"? Stessa chiave d'idempotenza (`capture-{pi}`): Stripe restituisce il primo incasso, non ne fa un secondo. Anche questo chiude il caso.

Per l'approvazione automatica non serve niente di nuovo: il commit fallito annulla anche la riga degli eventi visti (D-062), e quando Stripe rimanda `checkout.session.completed` si ricomincia con la stessa chiave d'incasso.

---

## Parte 7 — L'annullamento che fallisce

Rifiuto, ritiro e scadenza devono riuscire **anche se Stripe non risponde** (D-031, D-046): il blocco si scioglie comunque da solo in 7 giorni. Quindi `cancel` non lancia mai: registra un avviso e lascia il pagamento `AUTHORIZED`.

Chi lo porta a `CANCELED` dopo? L'evento **`payment_intent.canceled`**, che Stripe manda quando annulla il blocco da sé dopo 7 giorni (parte 2): confronta-e-cambia `AUTHORIZED` → `CANCELED`. Lo stesso evento arriva anche dopo i nostri annullamenti riusciti: lì il pagamento è già `CANCELED`, e non succede niente.

Un PaymentIntent già non annullabile (`payment_intent_unexpected_state`) va bene se era già annullato; se fosse stato **incassato** (il caso raro della parte 6, punto 4) è un problema da rimborsare a mano. Il registro lo scrive come errore con questa indicazione, senza una chiamata in più per scoprire quale dei due.

---

## Parte 8 — I lucchetti: pagamento e prenotazione, in che ordine

Due flussi toccano le stesse due righe:
- **l'approvazione**: lucchetto sulla prenotazione (D-046), poi sul pagamento, poi Stripe, poi i cambi, commit;
- **il webhook**: lucchetto sul pagamento, poi (se serve) sulla prenotazione.

Ordini opposti possono creare un *deadlock* (due transazioni che si aspettano a vicenda). Qui no, e vale la pena vedere perché:
- l'approvazione prende il lucchetto sul pagamento **prima** di chiamare Stripe; l'evento `payment_intent.succeeded` nasce **dopo** l'incasso, quindi trova il pagamento già bloccato, aspetta il commit, rilegge `SUCCEEDED`, e si ferma senza mai chiedere la prenotazione;
- `checkout.session.completed` arriva quando la prenotazione è ancora `PENDING_PAYMENT`: un'approvazione contemporanea si ferma al controllo del passaggio, prima di toccare il pagamento.

**Regola**: chi chiama Stripe tiene già il lucchetto sulla riga del pagamento.

---

## Parte 9 — Un errore trovato nel pezzo 3: l'oggetto vecchio in memoria

Il pezzo 3 cambiava il pagamento con un `update` JPQL (`@Modifying`): veloce, e con lo stato di partenza nel `where`. Ma un `update` così scrive **nel database e non nell'oggetto** già caricato in memoria (nel *persistence context*). Fino al pezzo 3 nessuno lo rileggeva dopo; con l'approvazione automatica sì: l'incasso, nella stessa transazione, cerca il pagamento `AUTHORIZED`, e JPA gli restituisce **lo stesso oggetto di prima**, con lo stato vecchio e **senza** l'id del PaymentIntent. Incasso di un PaymentIntent `null`.

Proposta: tornare allo schema delle prenotazioni (D-046). Il pagamento si legge **con il lucchetto** (`select ... for update`), lo stato cambia **nell'entità** con metodi dal nome di dominio che dicono se hanno cambiato qualcosa (`authorize`, `capture`, `cancel`, `expire`), e JPA scrive al commit. Il "confronta-e-cambia" resta: una seconda transazione aspetta il lucchetto, poi legge lo stato già cambiato (con `READ COMMITTED` una `select ... for update` restituisce l'ultima versione salvata), e il metodo risponde "niente da fare". I test del pezzo 3 restano gli stessi, e devono restare verdi.

---

## Parte 10 — I test

| Test | Cosa prova |
|---|---|
| `StripeCardHoldsTest` (con il finto Stripe, sessioni vere) | Approvazione → incasso **sul conto del pagamento**, pagamento `SUCCEEDED`, prenotazione `CONFIRMED`; **conto riassegnato** tra blocco e approvazione → incasso ancora sul conto vecchio; rifiuto, ritiro, scadenza → annullamento sul conto vecchio; incasso rifiutato → 409 `CARD_CAPTURE_FAILED`, tutto come prima; Stripe giù all'approvazione → 503, tutto come prima; Stripe giù al rifiuto → rifiuto riuscito, pagamento ancora `AUTHORIZED` |
| `StripeWebhookApiTest` (casi nuovi) | `payment_intent.succeeded` normale → niente; `payment_intent.canceled` → `AUTHORIZED` → `CANCELED`; approvazione automatica con incasso rifiutato → `PENDING_APPROVAL`, 200, notifica al professionista |
| `StripeReconciliationTest` (commit veri) | **"Incassato ma commit fallito"**: il finto Stripe incassa e poi fa fallire la transazione; prenotazione ancora in attesa; arriva `payment_intent.succeeded` → `CONFIRMED`, `SUCCEEDED` |
| `StripeClientGatewayTest` (casi nuovi) | L'incasso spedisce `POST /v1/payment_intents/{pi}/capture` con la chiave d'idempotenza; `payment_intent_unexpected_state` → `Refused`; Stripe giù → 503 |
| `BookingTransitionsTest`, `BookingAfterCommitTest` (aggiornati) | L'ordine nuovo: un incasso rifiutato non cambia lo stato |

---

## Parte 11 — Cosa c'è da decidere

| Scelta | Raccomandazione | Dove |
|---|---|---|
| Ordine | Controllo del passaggio, incasso, poi il cambio di stato | parte 3 |
| Incasso rifiutato | `CaptureResult` (`Captured`, `Refused`) come valore; temporaneo → 503 | parte 4 |
| Cosa vede il professionista | 409 `CARD_CAPTURE_FAILED`, la richiesta resta in attesa; in automatica, la richiesta torna al professionista | parti 4–5 |
| Riconciliazione | `payment_intent.succeeded`: `AUTHORIZED` → `SUCCEEDED` e conferma senza richiamare Stripe; prenotazione non più confermabile → errore "rimborso a mano" | parte 6 |
| Annullamento fallito | Mai un'eccezione; il pagamento resta `AUTHORIZED` finché arriva `payment_intent.canceled` | parte 7 |
| Lucchetti | Chi chiama Stripe tiene il lucchetto del pagamento; il webhook blocca il pagamento prima della prenotazione | parte 8 |
| Stato del pagamento | Entità con lucchetto e metodi di dominio al posto degli `update` JPQL | parte 9 |
| `LoggingCardHolds` | Rimossa: `payment` implementa `CardHolds` (`StripeCardHolds`) | parte 1 |

Nessuna migrazione. Una scheda nuova: D-063, che precisa D-046 (ordine e firma di `CardHolds`).

---

## Parte 12 — Com'è andata (implementazione)

Costruito come raccomandato nella parte 11 (D-063), su richiesta di Nicholas del 2026-11-10.

**I file:**

| File | Cosa fa |
|---|---|
| `booking/CardHolds` | `capture` restituisce `sealed CaptureResult`; `cancel` non lancia |
| `booking/Booking` | `checkMove`: la domanda "posso?" senza il cambio |
| `booking/BookingService` | `captureAndConfirm` (controllo, incasso, poi conferma); 409 `CARD_CAPTURE_FAILED`; in automatica la richiesta torna al professionista; `paymentCaptured` per la riconciliazione |
| `payment/StripeCardHolds` | L'implementazione vera, sul conto del pagamento, con il lucchetto |
| `payment/Payment` | `authorize`, `expire`, `capture`, `cancel`: dicono se hanno cambiato qualcosa |
| `payment/PaymentRepository` | `findLocked...` al posto degli `update` JPQL |
| `payment/StripeGateway`, `StripeClientGateway` | `capturePaymentIntent`; `cancelPaymentIntent` dice se ha annullato |
| `payment/StripeWebhookService`, `StripeNotice(s)` | `payment_intent.succeeded` e `.canceled` |

**Rimossa**: `LoggingCardHolds`.

**Due cose trovate strada facendo:**
- **L'oggetto vecchio in memoria** (parte 9), trovato ragionando sull'approvazione automatica prima di scrivere il codice: il pezzo 3 non aveva un test che lo mostrasse, perché nessuno rileggeva il pagamento dopo l'`update`.
- **"Non più annullabile" non vuol dire "annullato".** La prima versione del gateway, al pezzo 3, trattava `payment_intent_unexpected_state` come un annullamento riuscito. Scrivendo il test del caso raro (incassato, commit fallito, poi rifiutato) il pagamento sarebbe finito `CANCELED` con i soldi incassati. Ora `cancelPaymentIntent` restituisce `false`, il pagamento resta com'è, e l'evento di Stripe porta la verità (`SUCCEEDED`). Il finto Stripe si comporta come quello vero: un PaymentIntent incassato non si annulla più.

**Maven e la compilazione incrementale.** Dopo aver cambiato la firma di `CardHolds`, `mvnw test-compile` ha detto "tutto aggiornato" e non ha segnalato i test rotti. Con `mvnw clean test-compile` gli errori sono comparsi. La suite completa si lancia sempre con `clean`, proprio per questo.

**I test sanno fallire.** Otto difese tolte una alla volta:

| Tolto | Test che se ne accorge |
|---|---|
| Incassare **prima** di confermare | `StripeWebhookApiTest`, `StripeCardHoldsTest` |
| L'incasso sul conto del pagamento (al suo posto quello attuale) | `StripeCardHoldsTest` |
| L'annullamento sul conto del pagamento | `StripeCardHoldsTest` |
| La tolleranza agli annullamenti falliti | `StripeCardHoldsTest` |
| La riconciliazione | `StripeReconciliationTest` |
| L'esito dell'annullamento ("non più annullabile") | `StripeReconciliationTest` |
| Il 409 dell'incasso rifiutato | `StripeCardHoldsTest` |
| La chiave d'incasso fissa | `StripeReconciliationTest` |

---

## Parte 13 — Dopo la revisione di Nicholas (D-064)

Due richieste dopo il pezzo 4.

**1. Gli annullamenti falliti si riprovano.** "Il blocco si scioglie da solo in 7 giorni" è vero, ma per un problema nostro il cliente si vedrebbe l'importo bloccato per una settimana. Ora un annullamento fallito registra nel pagamento quanti tentativi ha fatto e quando riprovare (migrazione **V13**: `release_attempts`, `next_release_at`). Un lavoro programmato (`PaymentJobs`, ogni minuto) riprova dopo 1 minuto, 5, 30 e 2 ore; al quinto fallimento si arrende e scrive un errore con l'indicazione di annullare a mano dalla dashboard.

La scoperta della revisione: **ogni tentativo usa una chiave d'idempotenza nuova** (`cancel-{pi}-1`, `-2`, ...). Verificato sulla documentazione: Stripe salva la risposta della prima richiesta con una chiave, **anche un 500**, e la restituisce a chi riusa la chiave per 24 ore. Con la stessa chiave, dopo un 500 i tentativi riceverebbero tutti lo stesso 500. Per l'annullamento una chiave nuova è sicura: annullare due volte non fa danni (la seconda risposta è "non più annullabile").

Per l'**incasso** la chiave resta fissa (`capture-{pi}`), di proposito. Con una chiave nuova, un incasso riuscito la cui risposta si è persa diventerebbe al tentativo successivo un "non più incassabile", cioè un 409 sbagliato mentre i soldi ci sono. Il prezzo della chiave fissa è che, dopo un 500 vero di Stripe, l'approvazione non riesce per 24 ore: raro, accettato, annotato in D-064.

È lo schema di D-047: prima gli id, poi una transazione per pagamento con il lucchetto e la condizione riletta dopo il lucchetto (`releaseDue`). Sicuro con due istanze, e con un `payment_intent.canceled` arrivato nel frattempo.

**2. Approvazione automatica con incasso rifiutato: chi lo viene a sapere?** Verificato e provato a tre livelli: il servizio pubblica `ApprovalRequested` (`BookingTransitionsTest`); il webhook lascia la richiesta in attesa con `approval_deadline` fissata (`StripeWebhookApiTest`); partono davvero l'email "da approvare" al professionista e "richiesta inviata" al cliente, con la scadenza (`BookingNotificationsTest`). La scadenza c'è perché `paymentAuthorized` la fissa **prima** di tentare l'incasso.

**I test sanno fallire**: tolti a turno la registrazione del fallimento, la chiave nuova, la resa dopo cinque tentativi e l'`ApprovalRequested`, fallisce ogni volta il test che li protegge.

---

## Domande da colloquio

### Domanda 1: Separi autorizzazione e incasso. Cosa può andare storto tra i due?

**Risposta modello.** Il blocco può sparire: scade dopo qualche giorno, o qualcuno lo annulla. Allora l'incasso viene rifiutato per sempre. Lo distinguo da un errore temporaneo (Stripe che non risponde): il rifiuto definitivo è un esito, lo restituisco come valore e la prenotazione resta com'era; l'errore temporaneo annulla la transazione e si riprova. E il blocco va incassato entro la sua durata: per questo il tempo di risposta del professionista è molto più corto dei 7 giorni del blocco.

### Domanda 2: Perché un esito atteso come valore e non come eccezione, in Spring?

**Risposta modello.** Perché un'eccezione che esce da un metodo `@Transactional` di un altro bean segna la transazione come "solo rollback", anche se poi la catturo: al commit ricevo una `UnexpectedRollbackException` e perdo il lavoro. Un valore di ritorno non segna niente, e con un tipo sealed il compilatore mi obbliga a gestire tutti gli esiti. Le eccezioni le tengo per l'inatteso e per i casi in cui voglio davvero annullare tutto.

### Domanda 3: Stripe ha incassato, ma il tuo commit è fallito. Come te ne accorgi e come lo ripari?

**Risposta modello.** Con il webhook `payment_intent.succeeded`, che Stripe manda dopo ogni incasso. Se trovo il pagamento ancora "autorizzato", vuol dire che il mio commit era fallito: lo segno incassato e confermo la prenotazione senza richiamare Stripe. Se il professionista nel frattempo ha riprovato, la chiave d'idempotenza dell'incasso fa restituire a Stripe il primo incasso invece di farne un secondo. È riconciliazione: la fonte di verità sui soldi è Stripe, e il mio sistema converge verso di lei.

### Domanda 4: Due flussi bloccano le stesse due righe in ordine opposto. Rischi un deadlock?

**Risposta modello.** In generale sì, e la regola di base è prendere i lucchetti sempre nello stesso ordine. Qui gli ordini sono diversi, ma ho verificato che non possono incrociarsi: chi chiama Stripe tiene già il lucchetto sul pagamento, e l'evento che segue l'incasso nasce dopo, quindi aspetta quel lucchetto senza averne presi altri. L'altro evento arriva quando la prenotazione non è ancora approvabile, e l'approvazione si ferma prima di toccare il pagamento.

### Domanda 5: Un `update` JPQL o una modifica dell'entità con il lucchetto? Che differenza c'è?

**Risposta modello.** L'`update` JPQL va diretto al database: è veloce e fa il confronta-e-cambia nel `where`, ma non aggiorna gli oggetti già caricati nel persistence context, che restano vecchi. Se nella stessa transazione qualcuno rilegge quell'entità, JPA gli restituisce l'oggetto vecchio. Con una `select ... for update` e un metodo dell'entità ottengo lo stesso confronta-e-cambia (la seconda transazione aspetta e rilegge lo stato salvato), e la memoria e il database restano d'accordo. Uso l'`update` in blocco solo dove nessuno rilegge, come il lavoro dei blocchi scaduti.

### Domanda 6: L'annullamento del blocco fallisce. Blocchi il rifiuto del professionista?

**Risposta modello.** No: il rifiuto è una decisione del mio dominio, e il blocco sulla carta si scioglie comunque da solo in pochi giorni. Registro un avviso e lascio il pagamento "autorizzato"; quando Stripe annulla il blocco da sé, l'evento `payment_intent.canceled` lo porta allo stato giusto. Un effetto esterno che non è necessario per la correttezza non deve tenere in ostaggio la transazione.

---

## Documentazione ufficiale

- Stripe, incassare un PaymentIntent: https://docs.stripe.com/api/payment_intents/capture
- Stripe, annullare un PaymentIntent: https://docs.stripe.com/api/payment_intents/cancel
- Stripe, bloccare un metodo di pagamento: https://docs.stripe.com/payments/place-a-hold-on-a-payment-method
- Stripe, rimborsi e annullamenti: https://docs.stripe.com/refunds
- Spring, rollback e eccezioni nelle transazioni: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html
