# Acquisto di un pacchetto — guida di studio

Questa guida accompagna il **pezzo 2 del passo 10**. Il cliente sceglie un pacchetto in vendita, paga su Stripe, e quando Stripe ci avvisa nasce il suo lotto di crediti, **una volta sola**.

```
POST /api/customer/package-purchases        {"packageOfferId": 12}
     → 201 Location: /api/customer/package-purchases/{id}
       {"id": 31, "status": "PENDING", "paymentUrl": "https://checkout.stripe.com/...", ...}
GET  /api/customer/package-purchases         i miei acquisti, dal più recente
GET  /api/customer/package-purchases/{id}    uno: la pagina di ritorno chiede lo stato qui (D-007)

webhook checkout.session.completed (pagato) → payment SUCCEEDED → lotto + movimento +N → email al cliente
```

Teoria di riferimento: D-007 (lo conferma il webhook), D-010, D-011, D-031 (pacchetti: incasso immediato), D-057 (conto del professionista), D-061 (Checkout nella transazione, 503), D-062 (idempotenza del webhook), D-053 (email dopo il commit), D-069 (registro, fotografia, un lotto per pagamento, `ValidUntil`).

---

## Indice

- Parte 1 — Cosa c'è già, e cosa cambia rispetto alla seduta
- Parte 2 — La richiesta d'acquisto: controlli e ordine
- Parte 3 — Durata della pagina
- Parte 4 — Chiave d'idempotenza
- Parte 5 — La pagina di ritorno, e un dettaglio di `java.net.URI`
- Parte 6 — Due percorsi verso Stripe: una `sealed interface`
- Parte 7 — Il webhook: dal pagamento al lotto, una volta sola
- Parte 8 — Da quale giorno si contano i 90 giorni
- Parte 9 — Chi crea il lotto: i moduli
- Parte 10 — L'email di conferma
- Parte 11 — I test
- Parte 12 — La prova con la Stripe CLI
- Parte 13 — Cosa c'è da decidere
- Domande da colloquio

---

## Parte 1 — Cosa c'è già, e cosa cambia rispetto alla seduta

C'è quasi tutto: i conti di incasso (D-060), il gateway verso Stripe, il webhook con la tabella degli eventi visti e il confronta-e-cambia sul pagamento letto con il lucchetto (D-062, D-063), la V15 con le colonne dei pacchetti e `credit_lot.payment_id` unico (D-069).

Le differenze con la seduta sono tre, e tutte semplificano:

| | Seduta (passo 9) | Pacchetto |
|---|---|---|
| Incasso | bloccato, poi incassato all'approvazione (D-031) | **immediato**: non c'è niente da approvare |
| Cosa si blocca | un orario, 32 minuti | **niente**: si comprano crediti, non orari |
| Stati del pagamento | `PENDING` → `AUTHORIZED` → `SUCCEEDED` / `CANCELED` | `PENDING` → `SUCCEEDED`, oppure `EXPIRED` |

Con l'incasso immediato `checkout.session.completed` arriva con `payment_status = "paid"`: i soldi sono già dello studio.

---

## Parte 2 — La richiesta d'acquisto: controlli e ordine

1. **Il cliente** è quello della sessione (`CurrentCustomer`; con il profilo incompleto, 409 `PROFILE_INCOMPLETE` come per le prenotazioni).
2. **Il pacchetto è in vendita**: attivo, sul suo servizio attivo, di un professionista attivo. Altrimenti **404**, come un servizio non in vendita (D-027, D-029): per il cliente un pacchetto disattivato non esiste.
3. **Il professionista ha un conto**: altrimenti **409 `CARD_PAYMENTS_UNAVAILABLE`**, lo stesso codice della richiesta di una seduta (D-060), **prima** di chiamare Stripe.
4. **Sessione Checkout** sul conto attuale del professionista (D-057), con incasso immediato.
5. **Riga `payment`** `PENDING`, `PACKAGE`, con la **fotografia** (D-069): prezzo (`amount_cents`), sedute, validità. Poi il commit.

Se Stripe non risponde: **503 `PAYMENT_PROVIDER_UNAVAILABLE`**, e non resta niente (D-061). La chiamata è dentro la transazione come per la seduta, ma qui non tiene **nessun lucchetto conteso**: niente orario, niente riga del cliente. Il prezzo di D-061 (chi chiede lo stesso orario aspetta) qui non c'è.

**Non** si cerca di riusare una pagina ancora aperta dello stesso cliente per lo stesso pacchetto. Due clic, due pagine: il cliente ne paga una, l'altra scade da sola e il suo pagamento diventa `EXPIRED`. Innocuo, e niente codice in più.

---

## Parte 3 — Durata della pagina

Per la seduta la pagina dura quanto il blocco dell'orario (32 minuti, D-061). Qui non c'è niente da bloccare: Stripe accetta da 30 minuti a meno di 24 ore.

- **24 ore (o quasi)**: comodo per chi si distrae. Ma per un giorno intero resta aperta una pagina che vende un pacchetto forse già cambiato, disattivato, o di un professionista nel frattempo disattivato. La fotografia (D-069) protegge il cliente (paga quello che ha visto); non protegge il professionista, che il giorno dopo si trova a vendere un pacchetto che ha tolto.
- **30 minuti**: il minimo di Stripe, senza margine per un orologio sfasato (la lezione del passo 9).
- **60 minuti**: il tempo di pagare con calma, perfino di cercare la carta; dopo un'ora, chi torna rifà l'acquisto e vede il pacchetto com'è adesso.

**Raccomandazione: 60 minuti**, arrotondati per difetto al secondo come per la seduta. Lontano da entrambi i limiti di Stripe.

---

## Parte 4 — Chiave d'idempotenza

Per la seduta la chiave era `booking-{id}-checkout`: la prenotazione esisteva già, con il suo id. Qui la riga del pagamento nasce **dopo** la risposta di Stripe, quindi non c'è un id nostro da usare.

- **Una chiave nuova per ogni richiesta** (`package-{cliente}-{pacchetto}-{casuale}`): protegge da quello che serve davvero, il **nuovo tentativo della libreria** dopo un errore di rete (dentro la stessa chiamata riusa la stessa chiave: provato al passo 9). Non protegge dal doppio clic, che però è innocuo (parte 2).
- **Una chiave mandata dal frontend** (header `Idempotency-Key`): proteggerebbe anche dal doppio clic, ma è un contratto in più con il frontend, per un problema che non fa danni.

**Raccomandazione: la prima.**

---

## Parte 5 — La pagina di ritorno, e un dettaglio di `java.net.URI`

Dopo il pagamento Stripe rimanda il cliente sul sito. La pagina mostra "pagamento in verifica" e chiede lo stato al backend (D-007). Ma **di quale acquisto**? L'id del pagamento non lo conosciamo quando creiamo la sessione.

Stripe ha un segnaposto apposta: `success_url=https://.../acquisto?sessione={CHECKOUT_SESSION_ID}`, che sostituisce con l'id della sessione. Il dettaglio: `URI.create("...{CHECKOUT_SESSION_ID}")` **lancia un'eccezione**, perché le graffe non sono caratteri ammessi in un URI (RFC 3986). Codificarle (`%7B...%7D`) non va: Stripe sostituisce solo il segnaposto letterale. Servirebbe una `String` proprio lì, e un'API che cerca per id della sessione Stripe.

Più semplice: il ritorno porta alla pagina dei crediti del cliente (`APP_URL/crediti?acquisto=completato` oppure `=annullato`), e quella pagina chiede `GET /api/customer/package-purchases`, la lista dei suoi acquisti con lo stato di ognuno: "in verifica", "pagato" (con le sedute e l'ultimo giorno valido), "scaduto". La risposta della `POST` contiene comunque l'id, per chi lo vuole tenere.

**Raccomandazione**: niente segnaposto; ritorno alla pagina dei crediti, lista degli acquisti e lettura di uno per id.

Gli stati che il cliente vede sono tre, in un `enum` del contratto:
- `PENDING`: la pagina è ancora valida (con `paymentUrl` e `expiresAt`), oppure è appena stato pagato e l'avviso non è ancora arrivato;
- `PAID`: il pagamento è `SUCCEEDED`, con le sedute e l'ultimo giorno valido del lotto;
- `EXPIRED`: il pagamento è `EXPIRED`, **oppure** è ancora `PENDING` ma la pagina è scaduta (l'avviso di Stripe può tardare): come per `paymentUrl` delle sedute, conta l'orologio, non l'arrivo dell'avviso.

---

## Parte 6 — Due percorsi verso Stripe: una `sealed interface`

Oggi `StripeGateway.NewCheckoutSession` è un `record` per una seduta: `bookingId`, incasso manuale, `submit_type=book`. Per il pacchetto cambiano l'incasso (automatico), i metadati (niente `booking_id`), il pulsante (`pay`).

Due strade:
- **un metodo in più** sul gateway (`createPackageSession`): semplice, ma due metodi quasi uguali che costruiscono la stessa sessione;
- **una `sealed interface NewCheckoutSession`** con due `record`, `BookingCheckout` e `PackageCheckout`. Il gateway costruisce la parte comune una volta e poi fa uno `switch` sul tipo:

```java
switch (session) {
    case BookingCheckout booking -> params
            .setPaymentIntentData(manual capture, metadata booking_id)
            .setClientReferenceId(...)
            .setSubmitType(BOOK);
    case PackageCheckout purchase -> params
            .setPaymentIntentData(automatic capture, metadata package_offer_id)
            .setSubmitType(PAY);
}
```

Senza `default`: un terzo tipo di sessione non compilerebbe finché qualcuno non decide cosa manda a Stripe. È lo stesso uso di `sealed` di `StripeNotice` e `BookingEvent`, ma qui i casi sono **dati diversi**, non solo un'etichetta: un `enum` non saprebbe portare `bookingId` in uno e `packageOfferId` nell'altro. Questa è la regola pratica: `enum` quando i casi differiscono solo per il nome, `sealed` + `record` quando ognuno porta i suoi dati.

**Raccomandazione: la `sealed interface`.** Il prezzo è rinominare il `record` di oggi nei due punti che lo creano.

Nel webhook invece lo scopo è una colonna del database, e lì un `enum` (`PaymentPurpose`) basta: `switch (payment.getPurpose())`. Attenzione a un dettaglio di Java 21: uno `switch` **istruzione** su un `enum` non è obbligato a coprire tutti i casi (solo uno `switch` **espressione**, o uno con i *pattern*, lo è). Con due valori e un `case` per ognuno va bene, ma il compilatore non ci proteggerebbe da un terzo valore: lo scrivo in un commento.

---

## Parte 7 — Il webhook: dal pagamento al lotto, una volta sola

Nel `checkout.session.completed` il percorso si divide per scopo:

```
BOOKING → come oggi (authorize, poi richiesta o link)
PACKAGE → payment_status deve essere "paid"
          payment.paid(pi): PENDING → SUCCEEDED, oppure false se già fatto
          credit.lotFromPackage(fotografia del pagamento) → lotto + LOT_CREATED +N + evento
```

**"Una volta sola" anche con avvisi doppi o contemporanei**, tre livelli, gli stessi del passo 9 più uno:
1. **lo stesso evento due volte** (Stripe lo rimanda): la tabella `stripe_event` lo riconosce. Due consegne **contemporanee**: la seconda `insert ... on conflict do nothing` aspetta il commit della prima sull'indice unico, poi non inserisce niente;
2. **due eventi diversi per la stessa sessione**: il pagamento letto con il lucchetto, e il passaggio `PENDING` → `SUCCEEDED` che dice se ha cambiato qualcosa. Il secondo aspetta il lucchetto, poi legge `SUCCEEDED` e si ferma;
3. **un bug che scavalca i primi due**: `unique (payment_id)` sul lotto (V15). La seconda transazione fallisce, niente lotto doppio.

**`payment_status` diverso da `paid`**: con le sole carte non succede, ma un pagamento "completo e non pagato" non deve creare crediti. Si registra un avviso e non si fa niente.

**Pacchetto disattivato tra il pagamento e l'avviso**: non si rilegge il pacchetto in vendita. Il lotto nasce dalla fotografia del pagamento. Il servizio e il professionista invece si chiedono al catalogo per l'id del pacchetto: quelli non cambiano mai (`changeDetails` non tocca il servizio), e si leggono anche da un pacchetto disattivato.

**Gli altri eventi**: `payment_intent.succeeded` arriva anche per i pacchetti (l'incasso è immediato). Di solito dopo il `completed`, e trova il pagamento già `SUCCEEDED`: niente da fare. Se arriva prima, non conosciamo ancora quel PaymentIntent: avviso nel registro, niente da fare, e il `completed` farà tutto. `checkout.session.expired` porta il pagamento a `EXPIRED`, come per la seduta.

---

## Parte 8 — Da quale giorno si contano i 90 giorni

D-069: ultimo giorno = giorno dell'acquisto a Roma + N. Ma qual è "il momento dell'acquisto"?

- **l'ora del nostro orologio quando elaboriamo l'avviso**: se il server è giù e Stripe riprova il giorno dopo, il cliente guadagna un giorno; con un ritardo a cavallo della mezzanotte, la data cambia;
- **l'istante dell'evento di Stripe** (`created`, quando la sessione è stata completata): il momento in cui il cliente ha pagato davvero, firmato da Stripe, uguale a ogni nuovo invio dello stesso evento.

**Raccomandazione: l'istante dell'evento.** Si legge dall'evento verificato e viaggia nella `StripeNotice`. `created_at` del lotto resta il nostro orologio: è "quando l'abbiamo scritto".

---

## Parte 9 — Chi crea il lotto: i moduli

`payment` sa che il pagamento è riuscito; `credit` sa cos'è un lotto. Le dipendenze vanno **verso** `credit` (D-069: `payment → credit`, più avanti `booking → credit`), mai da `credit` verso gli altri. Quindi:

- `credit` espone `CreditService.lotFromPackage(PackagePurchase)`, con `Propagation.MANDATORY`: si unisce alla transazione del webhook, e se non c'è una transazione è un errore di programmazione (come `CustomerService.lockForBooking`, D-045). Così lotto, movimento, pagamento e evento visto si confermano **insieme**, o nessuno;
- `PackagePurchase` è un `record` di `credit` con tutto quello che serve: cliente, professionista, servizio, pacchetto, pagamento, prezzo, sedute, validità, istante del pagamento. Il professionista e il servizio li chiede `payment` al catalogo (`payment → catalog` esiste già);
- per la lista degli acquisti, `payment` chiede a `credit` i lotti di un **insieme** di pagamenti (D-029: una domanda su un insieme, non una per riga).

---

## Parte 10 — L'email di conferma

Il brief vuole un evento di dominio. `credit` pubblica `CreditEvent.PackagePurchased` (una `sealed interface`, come `BookingEvent`, con solo id, numeri e date) **dentro** la transazione; `notification` lo ascolta **dopo il commit**, in un altro thread (D-053): un avviso il cui commit fallisce non manda email.

- **Al cliente**: "Acquisto confermato", le sedute, il servizio, il professionista, l'ultimo giorno valido scritto in italiano ("fino a lunedì 8 febbraio 2027 compreso"). La ricevuta del pagamento la manda Stripe (se attiva nella dashboard).
- **Al professionista**: niente, per ora. Non deve fare niente (non c'è da approvare), e vedrà i crediti dei suoi clienti nel pannello (pezzo 6). Una notifica si aggiunge facilmente se lo studio la vuole: è una domanda di prodotto, la segnalo.
  - *Aggiornamento del 2026-11-11*: Nicholas la vuole ("comodo e utile"). Stesso schema della richiesta da approvare (D-054): una **push** "Pacchetto venduto" con sedute e ultimo giorno valido (niente cliente, niente trattamento: si legge a schermo bloccato), valida un giorno, e l'**email** al professionista solo come riserva, anch'essa senza il nome del cliente (D-032).

I testi in una funzione pura (`CreditEmails`), come `BookingEmails`: oggetto senza nomi né trattamento, data in italiano a Roma.

---

## Parte 11 — I test

| Test | Cosa prova |
|---|---|
| `PackagePurchaseApiTest` | 201 con `Location` e `paymentUrl`; la sessione chiesta a Stripe (conto, importo, descrizione, email, 60 minuti, chiave); la riga `PENDING` con la fotografia; 404 per un pacchetto disattivato, di un servizio disattivato, di un professionista disattivato, inesistente; 409 senza conto, **senza chiamare Stripe**; 503 con Stripe giù e nessuna riga; 401 e 403; lista e lettura, 404 per l'acquisto di un altro; `EXPIRED` quando la pagina è scaduta anche senza avviso |
| `PackageWebhookTest` | Pagato → `SUCCEEDED`, lotto con sedute, prezzo, ultimo giorno dall'istante dell'evento, movimento +N; lo **stesso evento due volte** → un lotto; **due eventi diversi** → un lotto; "completo ma non pagato" → niente; pacchetto **cambiato** dopo la pagina → il lotto della fotografia; pacchetto **disattivato** → il lotto nasce; scaduto → `EXPIRED`; `payment_intent.succeeded` dopo → niente |
| `PackageWebhookConcurrencyTest` | Commit veri: lo stesso evento consegnato due volte **nello stesso istante**, e due eventi diversi nello stesso istante → un solo lotto, saldo N |
| `CreditEmailsTest` | Il testo, con la data in italiano |
| `CreditNotificationsTest` | Email dopo un commit vero; nessuna email se la transazione fallisce |
| `StripeClientGatewayTest` (aggiunta) | Quello che la libreria manda davvero per un pacchetto: incasso automatico, `submit_type=pay`, metadati senza `booking_id` |

**Le difese tolte a turno**: il confronta-e-cambia del pagamento (deve accorgersene il test dei due eventi diversi, e poi intervenire `unique (payment_id)`), il controllo di `payment_status`, la fotografia (leggere il pacchetto all'avviso), il conto prima di Stripe.

---

## Parte 12 — La prova con la Stripe CLI

Il brief la chiede ("acquisto provato con la Stripe CLI; avviso doppio → un solo lotto"). La fa Nicholas, come al passo 9 (guida 32, parte 13.1):

1. avvio con il conto di prova e `stripe listen --forward-to localhost:8080/api/webhooks/stripe/prova` con i quattro eventi;
2. professionista con il conto, un servizio, un pacchetto da 5 sedute (pannello: `POST /api/panel/packages`);
3. da cliente: `POST /api/customer/package-purchases`, aprire `paymentUrl`, pagare con `4242 4242 4242 4242`;
4. `GET /api/customer/package-purchases`: `PAID`, 5 sedute, ultimo giorno = oggi + 90; nella dashboard il pagamento è **"Riuscito"** (incassato, non "Non acquisito");
5. **avviso doppio**: `stripe events resend <evt_...>` del `checkout.session.completed` → risposta 200, nel database **un solo** lotto (`select count(*) from credit_lot where payment_id = ...`);
6. l'email "Acquisto confermato" in Mailpit.

---

## Parte 13 — Cosa c'è da decidere

| Scelta | Raccomandazione | Dove |
|---|---|---|
| API | `POST`, `GET` lista e `GET /{id}` sotto `/api/customer/package-purchases`, nel modulo `payment` | parti 2, 5 |
| Controlli | pacchetto in vendita (404), conto (409 prima di Stripe), Stripe giù (503, niente resta) | parte 2 |
| Pagine aperte | nessun riuso: due clic, due pagine, una scade | parte 2 |
| Durata della pagina | 60 minuti | parte 3 |
| Chiave d'idempotenza | nuova a ogni richiesta | parte 4 |
| Ritorno | alla pagina dei crediti, senza segnaposto; stati `PENDING`, `PAID`, `EXPIRED` | parte 5 |
| Due percorsi verso Stripe | `sealed interface NewCheckoutSession` con `BookingCheckout` e `PackageCheckout` | parte 6 |
| Webhook | `payment_status = paid`; `PENDING` → `SUCCEEDED` con il lucchetto; lotto dalla fotografia | parte 7 |
| Inizio della validità | l'istante dell'evento di Stripe | parte 8 |
| Moduli | `CreditService.lotFromPackage` in `MANDATORY`; professionista e servizio dal catalogo | parte 9 |
| Email | evento `CreditEvent.PackagePurchased`, email al cliente; niente al professionista (da confermare con lo studio) | parte 10 |

Dopo l'implementazione: una scheda in `decisioni.md` (D-070) e l'architettura ("Acquisto di un pacchetto") aggiornata.

---

## Parte 14 — Com'è andata (implementazione)

Costruito come raccomandato nella parte 13 (D-070), su indicazione di Nicholas del 2026-11-11 ("dopo la guida, se non hai domande, procedi"). Nessuna migrazione: la V15 aveva già tutto. 32 test nuovi, 929 in tutto, `mvnw clean test` verde.

**I file:**

| File | Cosa fa |
|---|---|
| `PackagePurchases`, `PackagePurchaseController` | L'acquisto e la lettura degli acquisti, sotto `/api/customer/package-purchases` |
| `NewPackagePurchase`, `PackagePurchaseView`, `PackagePurchaseStatus` | La richiesta (solo l'id del pacchetto) e quello che il cliente vede |
| `Payment` | `packagePending` con la fotografia, `paid` (`PENDING` → `SUCCEEDED`), `PACKAGE_PAGE_LIFE`; `authorize` ora rifiuta un pacchetto |
| `StripeGateway`, `StripeClientGateway` | `NewCheckoutSession` `sealed` con `BookingCheckout` e `PackageCheckout`; uno `switch` per la parte che cambia |
| `StripeNotice`, `StripeNotices` | `SessionCompleted` porta anche `payment_status` e l'istante dell'evento |
| `StripeWebhookService` | Si divide per scopo; `packagePaid` crea il lotto |
| `CatalogService` | `packageOnSale` (per vendere) e `packageOwnerOf` (per il lotto, anche se disattivato) |
| `CreditService`, `PackagePurchase`, `PackageLot`, `CreditEvent` | Il lotto di un pacchetto, in `MANDATORY`, con il suo evento; i lotti di un insieme di pagamenti |
| `CreditEmails`, `CreditNotifications` | L'email "Acquisto confermato", dopo il commit |

**Test nuovi**: `PackagePurchaseApiTest` (15), `PackageWebhookTest` (11), `PackageWebhookConcurrencyTest` (2, commit veri), `CreditEmailsTest`, `CreditNotificationsTest` (2, commit vero e transazione annullata), un caso in `StripeClientGatewayTest` (quello che la libreria manda per un pacchetto).

**Un dettaglio di Java 21, scritto nel codice.** Nel webhook lo `switch (payment.getPurpose())` è un'**istruzione** su un `enum`: il compilatore non controlla che copra tutti i valori. Nella vista dell'acquisto invece lo `switch` sullo stato del pagamento è un'**espressione** (assegna `status`), e lì il compilatore pretende tutti i valori: anche `AUTHORIZED` e `CANCELED`, che per un pacchetto non possono esistere (il `check` della V15), e che quindi lanciano un errore di programmazione.

**Nessun errore nei test questa volta**, a parte una mia svista di compilazione: per l'intestazione `Location` avevo usato un'asserzione che `MockMvcTester` non ha; i test esistenti usano `hasHeader`.

**Una precauzione dalla lezione del pezzo 1.** Per togliere le difese a turno lo script tiene in memoria il testo di ogni file e lo rimette, e controlla che sia identico; alla fine ho confrontato l'elenco dei file modificati prima e dopo. Nessun `git checkout`: i file non erano ancora in un commit.

**I test sanno fallire.** Cinque difese tolte una alla volta:

| Tolto | Test che se ne accorge |
|---|---|
| Il confronta-e-cambia sul pagamento | `PackageWebhookTest`: il secondo evento risponde 500, perché `credit_lot_payment_uq` rifiuta il secondo lotto (verificato nel registro: è il terzo livello della parte 7 che lavora) |
| Il controllo `payment_status = paid` | `PackageWebhookTest` |
| La validità dall'istante dell'evento (con il nostro orologio) | `PackageWebhookTest`, il pagamento a mezzanotte e mezza a Roma |
| Il filtro "pacchetto attivo" | `PackagePurchaseApiTest` |
| La scadenza della pagina per l'orologio | `PackagePurchaseApiTest` |

**Resta da fare**: la prova manuale con la Stripe CLI (parte 12), che è un criterio di "fatto" del brief.

---

## Domande da colloquio

### Domanda 1: Un webhook crea un oggetto. Come garantisci che lo crei una sola volta, anche con consegne doppie o contemporanee?

**Risposta modello.** Con più livelli, perché ognuno copre un caso diverso. L'id dell'evento in una tabella con un vincolo unico, scritto nella stessa transazione dell'elaborazione: la stessa consegna ripetuta, anche in parallelo, si ferma lì. Il passaggio di stato del pagamento fatto sulla riga bloccata, con un metodo che dice se ha cambiato qualcosa: due eventi diversi per la stessa cosa si fermano lì. E un vincolo unico sull'oggetto creato (un lotto per pagamento) come ultima difesa contro un bug. Lo provo con consegne contemporanee vere, non solo in sequenza.

### Domanda 2: Il cliente paga un prodotto che nel frattempo cambia prezzo o viene ritirato. Cosa riceve?

**Risposta modello.** Quello che ha visto e pagato. Per questo la fotografia si prende quando nasce la pagina di pagamento, non quando arriva la conferma: il pagamento conserva prezzo, quantità e validità, e il prodotto acquistato nasce da lì. Se il prodotto è stato ritirato, il cliente l'ha pagato comunque, e lo riceve.

### Domanda 3: Quando useresti una `sealed interface` con dei `record` invece di un `enum`?

**Risposta modello.** Quando i casi portano dati diversi. Un `enum` va bene se i casi differiscono solo per il nome, come lo scopo di un pagamento salvato in una colonna; se una sessione di pagamento per una seduta ha un id di prenotazione e una per un pacchetto ha un id di pacchetto, una `sealed interface` con un `record` per caso li tiene separati e tipizzati, e uno `switch` senza `default` mi obbliga a gestire ogni caso. Attenzione: uno `switch` istruzione su un `enum` non è obbligato a essere esaustivo, uno `switch` espressione sì.

### Domanda 4: Da quale istante fai partire una validità di 90 giorni dopo un pagamento online?

**Risposta modello.** Dal momento in cui il cliente ha pagato, preso dall'evento firmato del fornitore, non dal momento in cui il mio server l'ha elaborato: se il server è giù e l'evento arriva il giorno dopo, il cliente non deve guadagnare né perdere un giorno. Poi conto giorni di calendario nel fuso dello studio, non multipli di 24 ore.

### Domanda 5: Perché un servizio di un altro modulo chiamato dal webhook usa `Propagation.MANDATORY`?

**Risposta modello.** Perché quello che fa ha senso solo insieme al resto: il lotto, il pagamento che diventa riuscito e l'evento segnato come visto devono confermarsi o annullarsi insieme. Con `MANDATORY` il metodo si unisce alla transazione del chiamante e, se qualcuno lo chiama senza, fallisce subito invece di aprirne una sua e confermare un lotto senza il suo pagamento.

### Domanda 6: Perché la chiamata a Stripe qui sta dentro la transazione, e quanto costa?

**Risposta modello.** Per non lasciare stati a metà: se Stripe non risponde, la transazione si annulla e non resta un pagamento senza pagina. Al passo 9 il prezzo era che chi chiedeva lo stesso orario aspettava, perché la transazione teneva lucchetti contesi; per un pacchetto non ce ne sono, quindi il prezzo è quasi nullo: solo una connessione al database occupata per qualche centinaio di millisecondi, al peggio una quindicina di secondi se Stripe non risponde.

---

## Documentazione ufficiale

- Stripe, Checkout Session (`payment_status`, `expires_at`, `success_url` e `{CHECKOUT_SESSION_ID}`): https://docs.stripe.com/api/checkout/sessions/create
- Stripe, eventi e consegne doppie: https://docs.stripe.com/webhooks#handle-duplicate-events
- Stripe CLI, `events resend`: https://docs.stripe.com/cli/events/resend
- RFC 3986 (caratteri ammessi in un URI): https://www.rfc-editor.org/rfc/rfc3986#section-2
- Java 21, `switch` ed esaustività (JEP 441): https://openjdk.org/jeps/441
- Spring, `Propagation.MANDATORY`: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html
