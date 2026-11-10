# Il webhook di Stripe — guida di studio

Questa guida accompagna il **pezzo 3 del passo 9**. Il cliente ha pagato sulla pagina di Stripe (pezzo 2): l'importo è **bloccato** sulla carta. Ma il nostro sistema non lo sa ancora. Lo viene a sapere quando **Stripe chiama noi**: è il *webhook*.

```
POST /api/webhooks/stripe/{conto}      header Stripe-Signature: t=...,v1=...      corpo: l'evento in JSON
     → 200  ricevuto (anche se già visto, o se non ci interessa)
     → 400  firma sbagliata, scaduta o mancante
     → 404  conto sconosciuto
     → 500  errore nostro: Stripe riproverà
```

Teoria di riferimento: D-007 (lo dice il webhook, non il browser), D-031 (blocco e incasso), D-046 (pagamento tardivo, lucchetto sulla prenotazione), D-051 (CSRF, regole per area), D-057 e D-060 (un conto, il suo segreto), D-059 (più pagamenti per una seduta: si annullano quelli in più), D-061 (sessione Checkout), guida 27 (eventi ed email dopo il commit).

---

## Indice

- Parte 1 — Cos'è un webhook, e perché ci serve
- Parte 2 — Cosa dice Stripe, e cosa no (verificato)
- Parte 3 — La firma
- Parte 4 — Un indirizzo per conto
- Parte 5 — "Almeno una volta": doppioni e ordine sparso
- Parte 6 — Prima scelta: cosa fa ogni evento
- Parte 7 — Seconda scelta: chi annulla un pagamento che non serve più
- Parte 8 — Terza scelta: rispondere subito, o elaborare dopo
- Parte 9 — Il cliente che ha pagato tardi: l'email
- Parte 10 — Sicurezza: CSRF, area, corpo grezzo
- Parte 11 — I test
- Parte 12 — Cosa c'è da decidere
- Parte 13 — Com'è andata (implementazione)
- Domande da colloquio

---

## Parte 1 — Cos'è un webhook, e perché ci serve

Un **webhook** è una chiamata HTTP che un sistema esterno fa **a noi** quando succede qualcosa da lui. Invece di chiedere a Stripe ogni minuto "ha pagato?" (*polling*), diamo a Stripe un indirizzo, e Stripe ci avvisa.

Perché non basta il ritorno del browser sulla pagina di successo (D-007)? Perché il browser è del cliente: può chiudersi prima di tornare, e l'indirizzo di ritorno lo può aprire chiunque. Il webhook arriva da Stripe, con una **firma** che solo Stripe può produrre.

---

## Parte 2 — Cosa dice Stripe, e cosa no (verificato)

Verificato sulla documentazione il 2026-11-10:

- Con l'incasso manuale, alla fine del pagamento la sessione è `status = complete`, ma `payment_status = unpaid`: per Stripe "i fondi non sono ancora disponibili nel tuo conto", perché sono solo bloccati. **Chi aspetta `paid` non conferma mai niente.** Il PaymentIntent è in `requires_capture`; arriva anche l'evento `payment_intent.amount_capturable_updated`.
- Il blocco dura in genere **7 giorni** per le carte online (Visa per le transazioni avviate dall'esercente 5). Il nostro tempo di risposta è al massimo 48 ore (D-042): ampiamente dentro.
- Stripe **non garantisce l'ordine** degli eventi, e può mandare **lo stesso evento più volte**; a volte persino due eventi diversi per la stessa cosa.
- Se non rispondiamo con un `2xx`, Stripe **riprova** per un massimo di **3 giorni** in produzione (in modalità di prova 3 volte in poche ore).
- La firma va verificata sul **corpo grezzo**, byte per byte: un framework che lo rilegge e lo riscrive rompe la verifica. Le librerie accettano un **timestamp** fino a **5 minuti** di distanza dall'ora del server.
- La forma dell'evento dipende dalla **versione dell'API** dell'endpoint: un endpoint con una versione diversa da quella della libreria manda oggetti con campi diversi.

---

## Parte 3 — La firma

Ogni endpoint ha un **segreto** (`whsec_...`), conosciuto solo da Stripe e da noi. Stripe calcola

```
firma = HMAC-SHA256(segreto, timestamp + "." + corpo)
```

e la manda nell'header `Stripe-Signature: t=<timestamp>,v1=<firma>`. Noi rifacciamo il conto con il nostro segreto: se torna uguale, il messaggio è di Stripe e non è stato toccato. È lo stesso HMAC dei codici di accesso (D-050), usato al contrario: lì per non salvare il codice in chiaro, qui per sapere chi ha scritto.

Tre dettagli:
- il **timestamp** è dentro la firma, quindi non si può cambiare: un messaggio vecchio di più di 5 minuti si rifiuta, contro chi lo **ripete** (*replay attack*);
- il confronto va fatto a **tempo costante**: un confronto che si ferma al primo carattere diverso rivela, misurando i tempi, quanti caratteri sono giusti;
- se la firma è sbagliata rispondiamo **400**, senza dire perché.

`stripe-java` fa tutto questo in `Webhook.constructEvent(corpo, header, segreto, tolleranza, clock)`. Il `Clock` è il nostro (`shared`): nei test l'ora è ferma e le firme si generano con quella.

---

## Parte 4 — Un indirizzo per conto

Ogni conto Stripe ha i suoi endpoint e i suoi segreti (D-057). Due possibilità:
- **un indirizzo solo**, provando la firma con ogni segreto finché uno torna: funziona, ma un evento "vale" per il primo conto il cui segreto combacia, e il codice deve indovinare da dove arriva;
- **un indirizzo per conto**: `/api/webhooks/stripe/rossi`, `/api/webhooks/stripe/studio`. Il nome nel percorso sceglie il segreto; un nome sconosciuto è 404.

**Raccomandazione: un indirizzo per conto.** Sappiamo sempre da quale conto arriva l'evento, e possiamo controllare che il pagamento di cui parla sia **di quel conto**: un evento firmato da `beta` che parla di un pagamento fatto su `alpha` si ignora (e si scrive nel log come errore: vuol dire configurazione sbagliata).

---

## Parte 5 — "Almeno una volta": doppioni e ordine sparso

Stripe promette di consegnare ogni evento **almeno una volta** (*at-least-once*). Non "esattamente una volta": quello, tra due sistemi in rete, nessuno lo può promettere. Le conseguenze si risolvono da noi, su due livelli.

### 5.1 Gli eventi già visti: `stripe_event`

Una tabella con l'id di ogni evento elaborato (`evt_...`, chiave primaria), il conto, il tipo, quando. Prima di fare qualunque cosa:

```sql
insert into stripe_event (event_id, account, type, received_at) values (...) on conflict (event_id) do nothing
```

Se inserisce una riga, l'evento è nuovo; se non inserisce niente, è un doppione: si risponde 200 e basta. Due doppioni **contemporanei**? Il secondo `insert` aspetta il commit del primo (stessa chiave primaria), poi trova la riga: niente doppio effetto.

L'`insert` sta **nella stessa transazione** dell'elaborazione. Se l'elaborazione fallisce, anche la riga sparisce: al nuovo tentativo di Stripe, l'evento è di nuovo "nuovo". Una tabella degli eventi scritta **prima**, in una transazione a parte, perderebbe l'evento a ogni errore.

### 5.2 Ogni operazione idempotente: confronta e cambia

La tabella degli eventi non basta: Stripe a volte manda **due eventi diversi** (due id) per la stessa cosa. Il secondo livello è che ogni operazione, ripetuta, non fa danni. Il passaggio del pagamento si scrive così:

```sql
update payment set status = 'AUTHORIZED', stripe_payment_intent_id = ? where id = ? and status = 'PENDING'
```

Lo **stato di partenza nel `where`** (*compare-and-set*): la prima volta cambia una riga, la seconda zero, e chi trova zero non fa nient'altro. Con due transazioni contemporanee, la seconda aspetta il lucchetto della riga, poi rilegge la condizione: non è più `PENDING`, zero righe. Lo stesso trucco del lavoro dei blocchi scaduti (D-047).

**Attenzione a un errore facile.** Se il secondo evento, invece di fermarsi, chiedesse alla prenotazione "pagamento autorizzato?", la troverebbe già `PENDING_APPROVAL` e risponderebbe "non mi serve" (parte 7): e annulleremmo il blocco di un pagamento valido. Il controllo sul **pagamento** viene **prima** di quello sulla prenotazione.

### 5.3 L'ordine

Gli eventi che usiamo non dipendono dall'ordine: una sessione o finisce pagata (`completed`) o scade (`expired`), mai tutte e due. E il pagamento tardivo (arrivato dopo che il nostro lavoro ha chiuso la prenotazione) è proprio il caso "ordine sparso" tra Stripe e noi: lo gestisce la parte 7.

---

## Parte 6 — Prima scelta: cosa fa ogni evento

| Evento | Effetto |
|---|---|
| `checkout.session.completed` | Pagamento `PENDING` → `AUTHORIZED`, con l'id del PaymentIntent; poi `BookingService.paymentAuthorized`. Si controlla `status = complete` e che ci sia il PaymentIntent; **non** `payment_status` (parte 2) |
| `checkout.session.expired` | Pagamento `PENDING` → `EXPIRED`. La prenotazione **no**: la chiude il lavoro programmato entro un minuto (D-047), come prima. Un percorso in meno |
| Qualunque altro | 200 e niente. Non si registra |

E `payment_intent.amount_capturable_updated`? Dice la stessa cosa di `completed`, ma non porta l'id della sessione. Proposta: **non usarlo** ora. Un evento solo per un fatto solo è più semplice da ragionare; quello del PaymentIntent tornerà utile al pezzo 4 (`payment_intent.succeeded`, la riconciliazione dell'incasso).

Si configurano su Stripe, per ogni endpoint, solo i due eventi della tabella, con la versione dell'API della libreria (`2026-10-28.endive`), formato **completo** (*snapshot*, nella dashboard "saliente"): gli eventi "leggeri" (*thin*) sono un'altra famiglia, con un altro gestore.

Quali campi leggiamo? Pochi e stabili: l'id e il tipo dell'evento, e dell'oggetto `id`, `status`, `payment_intent`. Proposta: leggerli dal **JSON grezzo** dell'oggetto (`getRawJson()` della libreria), non dal modello tipizzato `Session`. Il modello tipizzato, se la versione dell'API dell'evento non coincide con quella della libreria, restituisce **un `Optional` vuoto** (`getObject()`): l'evento sembrerebbe vuoto, e lo ignoreremmo **in silenzio**. Con il JSON grezzo, i quattro campi ci sono in ogni versione.

---

## Parte 7 — Seconda scelta: chi annulla un pagamento che non serve più

Oggi `BookingService.paymentAuthorized`, su una prenotazione `EXPIRED`, chiama `CardHolds.cancel(bookingId)`: "annulla il blocco della prenotazione X". Ma con più pagamenti per la stessa seduta (il link del pezzo 5, D-059), "il blocco della prenotazione X" è ambiguo: quale dei due? Chi sa **esattamente quale** PaymentIntent è appena arrivato è il webhook.

Proposta: `paymentAuthorized` **risponde** invece di agire, con un tipo `sealed` (Java 21):

```java
public sealed interface PaymentOutcome {
    record Accepted(BookingStatus status) implements PaymentOutcome {}   // PENDING_APPROVAL o CONFIRMED
    record NotWanted(BookingStatus status) implements PaymentOutcome {}  // EXPIRED, già pagata, ...
}
```

e il webhook decide con uno `switch` senza `default`:

```java
switch (bookings.paymentAuthorized(bookingId)) {
    case Accepted accepted -> { }                                       // la prenotazione ha fatto la sua parte
    case NotWanted notWanted -> cancel(account, paymentIntentId);        // si annulla QUESTO blocco
}
```

Cosa cambia per `booking`:
- `PENDING_PAYMENT` (anche con il blocco scaduto, D-046) → come oggi, `Accepted`;
- **qualunque altro stato** → `NotWanted`, senza errore. Oggi un secondo pagamento su una `PENDING_APPROVAL` dà 409: ma per Stripe un errore vuol dire "riprova" per tre giorni, e la risposta giusta è "questo pagamento non serve, annullalo". È la regola di D-059 ("ogni pagamento successivo si annulla"), applicata anche qui;
- `booking` non chiama più `CardHolds.cancel` nel pagamento tardivo: lo fa `payment`, sul PaymentIntent giusto e sul conto del pagamento (D-057).

L'annullamento: `PaymentIntent.cancel` sul conto, con chiave d'idempotenza `cancel-{pi}`, poi pagamento `AUTHORIZED` → `CANCELED`. Se Stripe risponde che il PaymentIntent è già annullato (qualcuno l'ha fatto dalla dashboard, o il blocco è scaduto), va bene così: si registra e si va avanti. Se Stripe non risponde, l'errore annulla tutto e Stripe ci rimanderà l'evento (parte 8).

---

## Parte 8 — Terza scelta: rispondere subito, o elaborare dopo

Stripe vuole un `2xx` **in fretta**, "prima di qualunque logica complessa". E un errore nostro non deve far perdere l'evento. Due modi.

- **(a) Elaborare subito, nella richiesta.** Verifica, transazione (evento visto + effetti), commit, 200. Se qualcosa fallisce, *rollback* e 500: Stripe riprova, e l'evento è ancora "nuovo". Tempo di risposta: millisecondi; quando c'è una chiamata a Stripe (annullamento, incasso in approvazione automatica, pezzo 4) circa mezzo secondo, al peggio i 15 secondi di D-061.
- **(b) Una casella di arrivo** (*inbox*): verifica, si salva l'evento in una tabella "da elaborare", 200 subito; un lavoro programmato elabora, con i suoi tentativi. Sempre velocissimo, e niente si perde perché l'evento è salvato prima del 200. Ma una tabella in più, un lavoro in più, e la conferma arriva con qualche secondo di ritardo.

**Raccomandazione: (a).** Il punto chiave è che **la correttezza non dipende dalla velocità**: se una risposta arrivasse troppo tardi per Stripe, Stripe riproverebbe, troverebbe l'evento già visto, e riceverebbe 200. Il peggio è un tentativo in più. Con (a) gli errori si vedono subito come 500 nella dashboard di Stripe, che li riprova da sola. (b) è la strada se l'elaborazione diventasse pesante.

---

## Parte 9 — Il cliente che ha pagato tardi: l'email

`stato.md` lo aveva annotato (D-053): oggi un pagamento arrivato per una prenotazione già scaduta si annulla in silenzio, e il cliente non sa niente. Ha visto "pagamento riuscito" sulla pagina di Stripe, e sul suo conto in banca compare un blocco.

Proposta: un evento di dominio nuovo, `LatePaymentReleased`, pubblicato da `paymentAuthorized` quando risponde `NotWanted` per una `EXPIRED`. Come gli altri, parte solo **dopo il commit**: se l'annullamento su Stripe fallisce, niente email (e Stripe rimanda l'evento). Lo `switch` di `BookingEmails` smette di compilare finché non c'è il testo: è proprio quello che `sealed` doveva fare (guida 27).

Testo: "il pagamento è arrivato dopo la scadenza del tempo per pagare, e l'orario non era più riservato a te. L'importo bloccato sulla carta è stato sbloccato: non ti è stato addebitato niente."

---

## Parte 10 — Sicurezza: CSRF, area, corpo grezzo

- **CSRF** (D-051) protegge le richieste fatte **da un browser con il nostro cookie**. Stripe non ha cookie né token: il webhook va **escluso** dal CSRF, e lo protegge la firma. L'eccezione deve essere stretta: solo `/api/webhooks/**`.
- **Area**: un prefisso nuovo, `/api/webhooks/**`, `permitAll()`. Non `/api/public/**`: quelle sono API per i browser, con il CSRF; tenerle separate rende l'eccezione precisa ed evidente.
- **Corpo grezzo**: il controller riceve `byte[]` e lo decodifica in UTF-8 da sé. Un `String` lo farebbe convertire a Spring con un *charset* che dipende dall'header; un oggetto Java lo farebbe rileggere e riscrivere: in entrambi i casi la firma potrebbe non tornare più.
- **Il pagamento simulato sparisce** già qui (era il pezzo 6): con il webhook, `POST /api/dev/bookings/{id}/payment-authorized` non serve più, e la sua eccezione CSRF e la sua regola vanno via insieme. Resta `LoggingCardHolds`, che il pezzo 4 sostituisce con l'incasso vero.

---

## Parte 11 — I test

| Test | Cosa prova |
|---|---|
| `StripeWebhookApiTest` (sessioni no, firme vere) | Evento valido → pagamento `AUTHORIZED`, prenotazione `PENDING_APPROVAL`, evento registrato; **lo stesso evento due volte → un solo effetto**; due eventi diversi per la stessa sessione → un solo effetto e **nessun annullamento**; firma sbagliata, di un altro conto, troppo vecchia o mancante → 400 e niente cambia; conto sconosciuto → 404; **pagamento tardivo → blocco annullato** sul conto e sul PaymentIntent giusti, prenotazione ancora `EXPIRED`; sessione scaduta → pagamento `EXPIRED`; evento di un conto su un pagamento dell'altro → ignorato; approvazione automatica → `CONFIRMED`; evento di un tipo che non ci interessa → 200 e niente |
| `StripeWebhookRollbackTest` (commit veri) | **Un errore nostro non perde l'evento**: annullamento fallito → 500, niente registrato; Stripe rimanda lo stesso evento → elaborato. **Due doppioni contemporanei** → un solo effetto |
| `BookingTransitionsTest` (aggiornato) | `Accepted` e `NotWanted`; il pagamento tardivo pubblica `LatePaymentReleased` e non chiama più `CardHolds` |
| `BookingEmailsTest` (un caso) | Il testo del pagamento tardivo: niente addebitato, link per un altro orario |

Le firme si generano nei test con `javax.crypto.Mac`, a mano: se usassimo la libreria sia per firmare sia per verificare, un nostro errore nell'usarla (il segreto sbagliato, il corpo cambiato) passerebbe inosservato.

---

## Parte 12 — Cosa c'è da decidere

| Scelta | Raccomandazione | Dove |
|---|---|---|
| Indirizzi | Uno per conto, `/api/webhooks/stripe/{conto}`; il pagamento deve essere di quel conto | parte 4 |
| Firma | `Webhook.constructEvent` con il nostro `Clock`, tolleranza 5 minuti, 400 senza spiegazioni | parte 3 |
| Doppioni | `stripe_event` nella stessa transazione, `on conflict do nothing`; confronta-e-cambia sullo stato del pagamento, **prima** della prenotazione | parte 5 |
| Eventi | `checkout.session.completed` (stato `complete`, non `paid`) e `checkout.session.expired`; campi dal JSON grezzo | parte 6 |
| Chi annulla | `paymentAuthorized` risponde `Accepted` o `NotWanted` (`sealed`); annulla `payment`, sul PaymentIntent e sul conto giusti | parte 7 |
| Velocità | Elaborazione nella richiesta, *rollback* e 500 su errore; la casella d'arrivo se servirà | parte 8 |
| Email | Evento `LatePaymentReleased` dopo il commit, email al cliente | parte 9 |
| Sicurezza | `/api/webhooks/**` `permitAll`, fuori dal CSRF; corpo `byte[]`; via il pagamento simulato | parte 10 |

Una migrazione nuova: **V12** (`stripe_event`). Una scheda nuova: D-062.

---

## Parte 13 — Com'è andata (implementazione)

Costruito come raccomandato nella parte 12 (D-062), su richiesta di Nicholas del 2026-11-10.

**I file:**

| File | Cosa fa |
|---|---|
| `V12__create_stripe_event.sql` | La tabella degli eventi visti |
| `payment/StripeWebhookController` | L'indirizzo per conto, il corpo grezzo, la firma con il nostro `Clock`, 400 / 404 |
| `payment/StripeNotices`, `StripeNotice` | Dall'`Event` di Stripe a un `sealed interface` di tre `record`, leggendo il JSON grezzo con Jackson |
| `payment/StripeWebhookService` | Una transazione per evento: visto?, conto giusto?, confronta-e-cambia, `paymentAuthorized`, annullamento se non serve |
| `payment/SeenStripeEvents` | L'`insert ... on conflict do nothing` |
| `payment/PaymentRepository` | `authorize` e `move`, gli `update` con lo stato di partenza nel `where` |
| `payment/StripeGateway`, `StripeClientGateway` | `cancelPaymentIntent`, con "già non annullabile" che va bene |
| `booking/PaymentOutcome` | `Accepted` e `NotWanted` |
| `booking/BookingService` | `paymentAuthorized` che risponde; `LatePaymentReleased` sul pagamento tardivo |
| `notification/BookingEmails`, `PushMessages` | Il testo dell'email del pagamento tardivo; nessuna push |
| `auth/SecurityConfiguration` | `/api/webhooks/**` aperto e fuori dal CSRF; via `/api/dev/**` |

**Rimossi**: `SimulatedPaymentController`, il suo test, `booking.simulated-payments`, la riga di `.env.example`.

**Tre cose trovate strada facendo:**
- **`sealed` ha fatto il suo lavoro.** Aggiunto `LatePaymentReleased`, non compilavano più due `switch`: quello delle email (previsto) e quello delle **push** (`PushMessages`), che avevo dimenticato. Il compilatore me l'ha ricordato.
- **Gson non si può usare.** `stripe-java` porta Gson solo a *runtime* (`gson:2.13.2:runtime` in `dependency:tree`): il nostro codice non lo vede in compilazione. Il progetto usa già Jackson 3 (`JsonMapper`, pacchetto `tools.jackson`), fornito da Spring Boot: uso quello.
- **`LoggingCardHolds` non è più innocua.** Il suo commento diceva "nessuna prenotazione arriva mai in attesa di approvazione": vero finché c'era solo il pagamento simulato spento. Con il webhook ci arrivano, e un'approvazione conferma senza incassare. Commento aggiornato, e i pezzi 3 e 4 vanno in `main` insieme.

**I test sanno fallire.** Otto difese tolte una alla volta:

| Tolto | Test che se ne accorge |
|---|---|
| Lo stato di partenza nel `where` (confronta-e-cambia) | `StripeWebhookApiTest`, `StripeWebhookRollbackTest` |
| La tabella degli eventi visti | `StripeWebhookApiTest` |
| Il controllo "pagamento di questo conto" | `StripeWebhookApiTest` |
| La verifica della firma | `StripeWebhookApiTest` |
| L'eccezione CSRF del webhook | `StripeWebhookApiTest` |
| L'annullamento del pagamento tardivo | `StripeWebhookApiTest` |
| Il controllo su `status = complete` | `StripeWebhookApiTest`, ma **solo dopo** aver aggiunto il test di un evento "completato" senza blocco: prima nessun test lo mandava |
| Il controllo sulla presenza del PaymentIntent | come sopra |

### 13.1 La prova manuale con Stripe in modalità di prova (da fare dopo il pezzo 4)

La prova completa ha senso dopo il pezzo 4, quando l'approvazione incassa davvero. I passi, per non doverli ricostruire:

1. **Un conto Stripe** in modalità di prova (*test mode*). Dalla dashboard, **Sviluppatori → Chiavi API → Crea chiave limitata**, con scrittura su *Checkout Sessions* e *PaymentIntents*: `rk_test_...`.
2. **La Stripe CLI** installata, poi `stripe login`.
3. Nel `.env`:
   ```
   PAYMENT_ACCOUNTS=prova
   STRIPE_PROVA_SECRET_KEY=rk_test_...
   STRIPE_PROVA_WEBHOOK_SECRET=whsec_...   (lo stampa il comando del punto 4)
   ```
4. In un terminale a parte, l'inoltro degli eventi al backend locale:
   ```
   stripe listen --events checkout.session.completed,checkout.session.expired --forward-to localhost:8080/api/webhooks/stripe/prova
   ```
   Il `whsec_...` che stampa va nel `.env`, poi si avvia il backend.
5. Da admin: `PUT /api/admin/practitioners/{id}/payment-account` con `{"account": "prova"}`.
6. Da cliente (accesso con il codice da Mailpit, poi il token CSRF): `POST /api/customer/bookings`. Si apre `paymentUrl` nel browser e si paga con la carta di prova `4242 4242 4242 4242`, una scadenza futura qualunque, un CVC qualunque.
7. Nel terminale di `stripe listen` compare `checkout.session.completed → 200`. `GET /api/customer/bookings/{id}`: `PENDING_APPROVAL` (manuale) o `CONFIRMED` (automatica). Nella dashboard di Stripe il pagamento è **non acquisito** (*uncaptured*).
8. Le prove in più: rimandare lo stesso evento dalla dashboard (**un solo effetto**); far scadere una sessione (`stripe checkout sessions expire cs_...`); pagare dopo aver portato a mano la prenotazione a `EXPIRED` (il blocco si annulla, arriva l'email in Mailpit).

---

## Domande da colloquio

### Domanda 1: Come verifichi che un webhook arrivi davvero da Stripe?

**Risposta modello.** Con la firma: Stripe calcola un HMAC-SHA256 del timestamp e del corpo con un segreto che conosciamo solo noi due, e lo manda in un header. Ricalcolo l'HMAC sul corpo grezzo, byte per byte, e confronto a tempo costante. Il timestamp è dentro la firma, quindi rifiuto i messaggi più vecchi di qualche minuto contro chi li ripete. Se qualcosa non torna, 400 senza spiegazioni.

### Domanda 2: Stripe consegna gli eventi "almeno una volta". Come eviti di elaborarli due volte?

**Risposta modello.** Su due livelli. Una tabella con l'id di ogni evento elaborato, scritta con `insert ... on conflict do nothing` nella stessa transazione dell'elaborazione: se l'elaborazione fallisce sparisce anche la riga, e il nuovo tentativo funziona. E operazioni idempotenti: ogni cambio di stato ha lo stato di partenza nel `where`, così un secondo evento diverso per la stessa cosa cambia zero righe e si ferma.

### Domanda 3: Perché la tabella degli eventi visti nella stessa transazione, e non prima?

**Risposta modello.** Perché "visto" deve voler dire "elaborato". Se la scrivessi prima, in una transazione a parte, e poi l'elaborazione fallisse, il nuovo tentativo di Stripe troverebbe l'evento già visto e lo scarterebbe: evento perso. Nella stessa transazione, o c'è l'effetto e la riga, o nessuno dei due.

### Domanda 4: Stripe vuole una risposta veloce. Elabori nella richiesta o in un secondo momento?

**Risposta modello.** Qui nella richiesta, perché l'elaborazione è breve e, soprattutto, la correttezza non dipende dalla velocità: se Stripe non aspettasse la risposta, riproverebbe e troverebbe l'evento già visto. Su un errore faccio rollback e rispondo 500, e Stripe riprova da solo. Se l'elaborazione diventasse pesante passerei a una casella d'arrivo: salvo l'evento verificato, rispondo subito, e un lavoro lo elabora con i suoi tentativi.

### Domanda 5: Il pagamento arriva per una prenotazione già scaduta. Cosa fai?

**Risposta modello.** Il servizio delle prenotazioni non lancia un errore, risponde "non mi serve" con un tipo sealed. Il modulo dei pagamenti annulla quel preciso PaymentIntent sul conto su cui è stato fatto, con una chiave d'idempotenza, e il cliente riceve un'email dopo il commit: niente addebitato. Un errore qui sarebbe sbagliato, perché per Stripe vorrebbe dire "riprova" per tre giorni.

### Domanda 6: Perché leggi i campi dal JSON grezzo invece di usare le classi della libreria?

**Risposta modello.** Perché la forma dell'evento dipende dalla versione dell'API dell'endpoint, e se non coincide con quella della libreria il modello tipizzato restituisce un oggetto vuoto: l'evento verrebbe ignorato in silenzio. I quattro campi che mi servono, id, stato e PaymentIntent della sessione, sono gli stessi in ogni versione: leggerli dal JSON mi rende indipendente da quell'allineamento.

---

## Documentazione ufficiale

- Stripe, ricevere eventi sul webhook (firma, doppioni, ordine, tentativi): https://docs.stripe.com/webhooks
- Stripe, bloccare un metodo di pagamento (incasso manuale, durata del blocco): https://docs.stripe.com/payments/place-a-hold-on-a-payment-method
- Stripe, l'oggetto Checkout Session (`status`, `payment_status`): https://docs.stripe.com/api/checkout/sessions/object
- Stripe, annullare un PaymentIntent: https://docs.stripe.com/api/payment_intents/cancel
- Stripe CLI, inoltrare i webhook in locale: https://docs.stripe.com/stripe-cli
