# Il link di pagamento per le sedute "in studio" — guida di studio

Questa guida accompagna il **pezzo 5 del passo 9**. Una seduta prenotata dal pannello senza crediti è "da pagare in studio" (D-040). D-059 ha deciso che il professionista può mandare al cliente un **link di pagamento** con WhatsApp; Nicholas ha precisato che anche il link **blocca** l'importo, e si incassa solo se la seduta è ancora da pagare.

```
POST /api/panel/bookings/{id}/payment-link
     → 200 {"paymentUrl": "https://checkout.stripe.com/...", "whatsappUrl": "https://wa.me/393331234567?text=...",
            "expiresAt": "2026-11-07T10:00:00+01:00"}
     → 404 la seduta non esiste o è di un altro professionista
     → 409 PAYMENT_LINK_NOT_ALLOWED      non è CONFIRMED e "in studio", o inizia tra meno di 32 minuti
     → 409 CARD_PAYMENTS_UNAVAILABLE     il professionista non ha un conto di incasso
     → 409 PAYMENT_LINK_ALREADY_PAID     il link precedente è appena stato pagato
     → 503 PAYMENT_PROVIDER_UNAVAILABLE  Stripe non risponde
```

Teoria di riferimento: D-059 (il link, con le precisazioni di Nicholas), D-040 (pagamento in studio), D-057 (conti), D-061 (sessione Checkout), D-062–D-064 (webhook, incasso con lettura, chiavi), guida 31 parte 4 (doppia scrittura), guida 33 parte 8 (ordine dei lucchetti).

---

## Indice

- Parte 1 — Cosa c'è già, e cosa manca
- Parte 2 — Il giro completo
- Parte 3 — Prima scelta: chi possiede il link
- Parte 4 — Seconda scelta: quanto dura un link
- Parte 5 — Terza scelta: "un solo link valido" con Stripe di mezzo
- Parte 6 — Quarta scelta: il pagamento che arriva
- Parte 7 — Due link, due pagamenti: "il primo vince"
- Parte 8 — I lucchetti, di nuovo
- Parte 9 — Il messaggio WhatsApp
- Parte 10 — I test
- Parte 11 — Cosa c'è da decidere
- Parte 12 — Com'è andata (implementazione)
- Domande da colloquio

---

## Parte 1 — Cosa c'è già, e cosa manca

**C'è:** le sedute `CONFIRMED` e `IN_STUDIO` (D-040); `paid_with` aggiornabile (preparato al passo 7 proprio per questo); la sessione Checkout con incasso manuale (D-061); il webhook con "eventi visti" e passaggi del pagamento con il lucchetto (D-062, D-063); l'incasso con lettura prima e chiave nuova (D-064); l'annullamento di un pagamento non voluto (D-062); un solo `PENDING` per prenotazione (indice parziale, V10); il numero verificato del cliente in `app_user.phone` (D-055).

**Manca:** l'API del link; sapere, quando arriva un pagamento, se è di una **richiesta** del cliente o di un **link**; invalidare il link vecchio; cosa fa il pagamento di un link; la regola "il primo vince".

---

## Parte 2 — Il giro completo

1. Il professionista, sulla sua seduta "in studio", preme **"invia link"**.
2. Il backend controlla la seduta, invalida il link precedente (se c'è), crea una sessione Checkout con **incasso manuale** sul conto del professionista, registra il pagamento `PENDING` di tipo **link**, e risponde con l'indirizzo della pagina e quello di WhatsApp.
3. Il pannello (passo 13) apre WhatsApp con il messaggio pronto; il professionista lo manda.
4. Il cliente paga sulla pagina di Stripe: importo **bloccato**.
5. Il webhook `checkout.session.completed` arriva. Se la seduta è **ancora** `CONFIRMED` e `IN_STUDIO`: si **incassa subito**, e `paid_with` diventa `CARD`. Altrimenti (cancellata, già pagata da un altro link): si **annulla** il blocco. Nessun rimborso, mai (D-015).

Da quel momento la seduta conta come pagata con carta: cancellata in tempo, genera un credito (passo 11).

---

## Parte 3 — Prima scelta: chi possiede il link

Il link è fatto di Stripe, conti e pagamenti: è di `payment`. Ma la domanda "questa seduta può ricevere un link?" e la decisione "questo pagamento mi serve?" sono di `booking`. Come nei pezzi precedenti: **`payment` chiede, `booking` risponde**, e le dipendenze restano in un verso (`payment` → `booking`).

- Il **controller** sta in `payment` (`/api/panel/bookings/{id}/payment-link`): l'area `/api/panel/**` è del professionista (D-051), il modulo è di chi possiede i dati, come per l'API dell'admin dei conti (D-060).
- `booking` offre `linkTarget(practitionerId, bookingId)`: 404 se non è sua, 409 se non è "in studio" e confermata; altrimenti i dati che servono (cliente, servizio, prezzo, inizio).
- Al pagamento: `booking` risponde `Accepted` o `NotWanted` (lo stesso `PaymentOutcome` del pezzo 3), e se accetta, dopo l'incasso, cambia `paid_with`.

**Come distinguere un pagamento di un link da uno di una richiesta?** Una colonna nuova in `payment`: **`source`**, `REQUEST` o `LINK` (migrazione V14; le righe esistenti sono tutte `REQUEST`). Il webhook guarda lì, non nei metadati di Stripe: la verità sui nostri pagamenti è nel nostro database.

---

## Parte 4 — Seconda scelta: quanto dura un link

Una sessione Checkout dura da 30 minuti a 24 ore (D-061). Proposta: il link scade **dopo 24 ore, o all'inizio della seduta se viene prima**. Pagare dopo l'inizio non ha senso: a quel punto si paga in studio. Se la seduta inizia tra meno di **32 minuti** (lo stesso margine di D-061 sopra i 30 di Stripe), il link non si può fare: 409 `PAYMENT_LINK_NOT_ALLOWED`.

Il professionista può sempre chiederne uno nuovo: un link scaduto non è un problema, è un pulsante da premere di nuovo.

---

## Parte 5 — Terza scelta: "un solo link valido" con Stripe di mezzo

Chiedere un link nuovo **invalida il vecchio**. Da noi è facile (il pagamento vecchio passa a `EXPIRED`); ma la pagina vecchia è **su Stripe**, e finché è aperta il cliente la può pagare. Stripe permette di far scadere una sessione aperta (`POST /v1/checkout/sessions/{id}/expire`).

C'è una gara: il cliente potrebbe pagare il link vecchio **nello stesso istante** in cui ne chiediamo uno nuovo. Si risolve come l'incasso (D-064): **prima si legge**.

| Stato della sessione vecchia | Cosa si fa |
|---|---|
| `open` | Si fa scadere su Stripe, poi pagamento → `EXPIRED`, poi la sessione nuova |
| `expired` | Pagamento → `EXPIRED` (Stripe l'ha già chiusa), poi la sessione nuova |
| `complete` | Il cliente ha **appena pagato**: niente link nuovo, 409 `PAYMENT_LINK_ALREADY_PAID`. Il webhook in arrivo porterà `paid_with` a `CARD` |

E se il cliente completa il pagamento **tra** la nostra lettura e il nostro "fai scadere"? Stripe rifiuta di far scadere una sessione non più aperta: si rilegge, si trova `complete`, e vale la terza riga. Se invece la nostra transazione fallisse dopo aver fatto scadere la sessione su Stripe, il pagamento resterebbe `PENDING` da noi: arriverà `checkout.session.expired`, che lo porta a `EXPIRED`. Doppia scrittura, ma abbandonabile in entrambi i versi.

Le chiavi: la sessione nuova `booking-{id}-link-{casuale}` (ogni pressione del pulsante è un'intenzione nuova); il "fai scadere" `expire-{cs}` (far scadere due volte la stessa sessione non fa danni).

---

## Parte 6 — Quarta scelta: il pagamento che arriva

Al webhook `checkout.session.completed` di un pagamento `LINK`, nella transazione dell'evento:

1. pagamento (con il lucchetto) → `AUTHORIZED`, con il PaymentIntent;
2. `booking.linkPaymentArrived(id)`: lucchetto sulla seduta; `Accepted` se è ancora `CONFIRMED` e `IN_STUDIO`, altrimenti `NotWanted`;
3. `Accepted` → **incasso subito** di **questo** PaymentIntent (lettura prima, chiave nuova, D-064); riuscito → pagamento `SUCCEEDED`, poi `booking.paidByLink(id)`: `paid_with` → `CARD`. Prima incassare, poi cambiare (D-063);
4. `NotWanted` → si annulla **questo** blocco, come il pagamento tardivo (D-062).

Perché qui non si usa `CardHolds.capture(bookingId)` dell'approvazione? Perché cerca "il" pagamento `AUTHORIZED` della seduta: con due link pagati quasi insieme, potrebbero essere **due**. Il webhook sa esattamente quale PaymentIntent è arrivato, e incassa quello. Lo stesso ragionamento che, al pezzo 3, ha tolto l'annullamento a `booking`.

Un incasso rifiutato (il blocco sparito in pochi secondi, quasi impossibile): la seduta resta "in studio", il pagamento resta com'è, e Stripe chiuderà il PaymentIntent da sé. Stripe che non risponde: *rollback*, errore, Stripe rimanda l'evento (D-062).

**Un pagamento invalidato ma pagato lo stesso.** Se, per una gara che la parte 5 non può escludere del tutto (un evento in ritardo, un riavvio), arriva `completed` per un pagamento che da noi è già `EXPIRED`, i soldi sono **davvero** bloccati sulla carta: Stripe completa solo sessioni che erano aperte. Proposta: `EXPIRED` → `AUTHORIZED` **è permesso**, e il pagamento si giudica come gli altri. Altrimenti resterebbe un blocco che nessuno annulla.

---

## Parte 7 — Due link, due pagamenti: "il primo vince"

La regola di Nicholas: **si incassa il primo pagamento che arriva mentre la seduta è ancora `IN_STUDIO`; ogni pagamento successivo per la stessa seduta si annulla.**

Con la parte 6 viene da sé: il primo pagamento trova `IN_STUDIO`, incassa, e porta `paid_with` a `CARD`; il secondo trova `CARD`, riceve `NotWanted`, e il suo blocco si annulla. Non importa quale link fosse "valido": vince l'ordine di arrivo.

E se arrivano **nello stesso istante**? I due eventi riguardano due pagamenti diversi, quindi i lucchetti dei pagamenti non li fermano. Li ferma il lucchetto della **seduta** (passo 2 della parte 6): il secondo aspetta il commit del primo, poi legge `CARD`. Un test con due eventi in parallelo lo prova.

---

## Parte 8 — I lucchetti, di nuovo

- **Il webhook**: pagamento, poi seduta (come al pezzo 3).
- **La creazione del link**: blocca i pagamenti `PENDING` della seduta (per invalidarli), **senza** bloccare la seduta. Se bloccasse la seduta prima, potrebbe incrociarsi con un webhook di un link vecchio (che ha il pagamento e vuole la seduta): *deadlock*. Senza il lucchetto sulla seduta, il peggio che può succedere è creare un link per una seduta appena pagata da un altro link: se il cliente lo pagasse, il suo blocco verrebbe annullato (parte 7). Innocuo.
- Due richieste di link contemporanee: la seconda aspetta il lucchetto del pagamento `PENDING`; se non ce n'era nessuno, l'indice parziale "un solo `PENDING` per seduta" ne fa fallire una con un 409.

---

## Parte 9 — Il messaggio WhatsApp

Il backend compone il testo, perché il frontend non contiene regole e nemmeno testi che dipendono dai dati (CLAUDE.md):

> Ciao Maria, ecco il link per pagare la seduta "Seduta" di lunedì 9 novembre, ore 9:00: https://checkout.stripe.com/... Il link vale fino a domenica 8 novembre, ore 10:00.

L'indirizzo di condivisione è `https://wa.me/<numero>?text=<testo>`, con il numero in formato internazionale **senza** il `+` (`393331234567`); senza numero, `https://wa.me/?text=<testo>` e il professionista sceglie il contatto. Il numero è sempre verificato (D-055). Il testo nomina il cliente e il servizio: lo vede solo chi lo manda, dal suo telefono.

---

## Parte 10 — I test

| Test | Cosa prova |
|---|---|
| `PaymentLinkApiTest` (sessioni vere) | Il link: indirizzo di Stripe, WhatsApp con e senza numero, scadenza (24 ore o l'inizio), pagamento `PENDING` `LINK` sul conto del professionista; un secondo link fa scadere il primo su Stripe; il primo appena pagato → 409 senza link nuovo; non "in studio", di un altro (404), troppo vicino all'inizio, senza conto → rifiutati |
| `PaymentLinkWebhookTest` | Pagato mentre "in studio" → incassato **quel** PaymentIntent, `paid_with = CARD`; seduta non più valida (stato scritto nel database, criterio del brief) → blocco annullato; **due link**: il primo pagato incassa, il secondo si annulla, in entrambi gli ordini, compreso il link invalidato ma pagato lo stesso |
| `PaymentLinkConcurrencyTest` (commit veri) | Due link pagati nello stesso istante: uno solo incassato, l'altro annullato |

---

## Parte 11 — Cosa c'è da decidere

| Scelta | Raccomandazione | Dove |
|---|---|---|
| Chi possiede il link | Controller in `payment`; `booking` dice se la seduta può riceverlo e se il pagamento serve | parte 3 |
| Tipo di pagamento | Colonna `payment.source` (`REQUEST`, `LINK`), V14 | parte 3 |
| Durata | 24 ore o fino all'inizio; non sotto i 32 minuti | parte 4 |
| Link vecchio | Leggere la sessione: aperta → farla scadere; scaduta → `EXPIRED`; completata → 409 | parte 5 |
| Pagamento | Incasso subito di quel PaymentIntent, poi `CARD`; altrimenti annullamento | parte 6 |
| Pagato dopo l'invalidazione | `EXPIRED` → `AUTHORIZED` permesso, e si giudica come gli altri | parte 6 |
| Due pagamenti | Vince il primo che trova `IN_STUDIO`; lucchetto sulla seduta | parte 7 |
| Lucchetti | Creazione del link: solo i pagamenti `PENDING`; webhook: pagamento poi seduta | parte 8 |
| WhatsApp | Testo composto dal backend; `wa.me` con il numero senza `+`, o senza numero | parte 9 |

Una migrazione nuova (**V14**). Una scheda nuova (D-065), che realizza D-059.

---

## Parte 12 — Com'è andata (implementazione)

Costruito come raccomandato nella parte 11 (D-065), su richiesta di Nicholas del 2026-11-10.

**I file:**

| File | Cosa fa |
|---|---|
| `V14__payment_source.sql` | `payment.source`, `REQUEST` o `LINK` |
| `booking/LinkTarget`, `BookingService` | `linkTarget` (404 / 409), `linkPaymentArrived` (con il lucchetto), `paidByLink` |
| `booking/Booking` | `awaitsPaymentInStudio`, `paidByLink` |
| `payment/PaymentLinks` | Il link: la pagina vecchia letta e fatta scadere, la sessione nuova, il pagamento `PENDING` `LINK` |
| `payment/PaymentLinkMessages` | Il testo e l'indirizzo di WhatsApp |
| `payment/PaymentLinkController`, `PaymentLinkView` | L'API del pannello |
| `payment/StripeWebhookService` | Lo smistamento per `source`, e `linkPaid` |
| `payment/StripeCardHolds` | `captureLocked`: l'incasso di **quel** pagamento |
| `payment/StripeGateway`, `StripeClientGateway` | `checkoutSessionStatus`, `expireCheckoutSession` |
| `payment/Payment` | `source`; `authorize` anche da `EXPIRED` |
| `auth/AccountService`, `customer/CustomerService` | `phoneOf`: il numero verificato del cliente |

**Due cose trovate strada facendo:**
- **L'ordine delle scritture di Hibernate.** Nella stessa transazione il pagamento vecchio passa a `EXPIRED` e il nuovo `PENDING` viene salvato. Hibernate esegue **subito** l'`insert` di una riga con id generato dal database (deve sapere l'id), ma rimanda l'`update` del vecchio al commit: per un attimo il database vedrebbe due `PENDING`, e l'indice parziale "un solo `PENDING`" rifiuterebbe il nuovo. Trovato ragionando prima dei test, provato togliendo il `flush`: il test del secondo link fallisce.
- **Il finto Stripe che ricordava troppo.** Per la riconciliazione (pezzo 4) il finto teneva i PaymentIntent incassati anche dopo `reset()`. Un test di questo pezzo usa gli stessi nomi (`pi_OLD`) di un altro: il finto credeva `pi_OLD` già incassato, e l'annullamento rispondeva "non più annullabile". Il codice era giusto, il finto no. Ora `reset()` dimentica tutto, e `forgetCalls()` dimentica solo le chiamate tenendo lo stato di Stripe: è quello che serve alla riconciliazione.

**I test sanno fallire.** Sette difese tolte una alla volta:

| Tolto | Test che se ne accorge |
|---|---|
| "Solo se la seduta è ancora in studio" | `PaymentLinkWebhookTest` |
| `EXPIRED` → `AUTHORIZED` (il link invalidato ma pagato) | `PaymentLinkWebhookTest` |
| Il `flush` prima del nuovo `PENDING` | `PaymentLinkApiTest` |
| La pagina vecchia fatta scadere su Stripe | `PaymentLinkApiTest` |
| Il rifiuto di un link appena pagato | `PaymentLinkApiTest` |
| `paid_with` → `CARD` | `PaymentLinkWebhookTest`, `PaymentLinkConcurrencyTest` |
| Il lucchetto sulla seduta | `PaymentLinkConcurrencyTest` (due pagamenti nello stesso istante: senza il lucchetto ne incassa due) |

### 12.1 La prova manuale (dopo quella della guida 32, parte 13.1)

Con lo stesso conto di prova e lo stesso `stripe listen` acceso:

1. Da professionista, una prenotazione dal pannello (`POST /api/panel/bookings`): è `CONFIRMED` e `IN_STUDIO`.
2. `POST /api/panel/bookings/{id}/payment-link`. Si apre `whatsappUrl` nel browser: WhatsApp Web mostra il messaggio pronto.
3. Un **secondo** link: nella dashboard di Stripe la prima sessione risulta **scaduta**; aprendo il primo `paymentUrl` la pagina dice che non è più valida.
4. Si paga il secondo link con `4242 4242 4242 4242`: in `stripe listen` `checkout.session.completed → 200`, poi `payment_intent.succeeded → 200`. Nella dashboard il pagamento è **riuscito** (incassato subito); `GET /api/panel/bookings/{id}` mostra la seduta confermata, e nel database `paid_with = CARD`.
5. Un terzo link dopo il pagamento: 409 `PAYMENT_LINK_NOT_ALLOWED` (la seduta non è più da pagare in studio).

---

## Domande da colloquio

### Domanda 1: Come invalidi un link di pagamento che vive su un sistema esterno?

**Risposta modello.** Da me basta cambiare uno stato, ma la pagina vive su Stripe e finché è aperta si può pagare. Quindi la chiudo anche lì (Stripe permette di far scadere una sessione aperta), e prima leggo il suo stato: se il cliente l'ha appena pagata non la invalido, e rispondo che il pagamento è in arrivo. Se la chiusura su Stripe riesce e la mia transazione no, l'evento di scadenza di Stripe rimette d'accordo il mio database.

### Domanda 2: Due pagamenti per la stessa cosa arrivano quasi insieme. Come ne incassi uno solo?

**Risposta modello.** Decido con una regola sullo stato dell'oggetto pagato, non sul pagamento: "si incassa solo se la seduta è ancora da pagare". Il primo pagamento incassa e cambia lo stato; il secondo trova lo stato cambiato e il suo blocco si annulla. Contro la contemporaneità, il lucchetto sulla riga della seduta serializza i due: il secondo aspetta il commit del primo e poi legge lo stato nuovo. Con l'importo solo bloccato, il secondo pagamento non costa niente al cliente.

### Domanda 3: Perché bloccare l'importo anche per un pagamento da incassare subito?

**Risposta modello.** Perché tra il pagamento e il nostro avviso la situazione può cambiare: la seduta può essere stata cancellata, o pagata da un altro link. Con l'incasso immediato dovrei rimborsare, cosa che il sistema non fa per scelta e che costa le commissioni; con il blocco decido io al momento dell'avviso, e se non serve lo annullo. Il costo è una chiamata in più, l'incasso, dentro il webhook.

### Domanda 4: Un evento dice "pagato" per un pagamento che tu avevi già chiuso. Lo ignori?

**Risposta modello.** No: se Stripe dice che la sessione è completata, i soldi sono davvero bloccati sulla carta, qualunque cosa dica il mio database, che qui è in ritardo rispetto a Stripe. Riapro il pagamento e lo giudico come gli altri: se la seduta lo vuole lo incasso, altrimenti lo annullo. Ignorarlo lascerebbe un blocco sulla carta del cliente che nessuno toglie.

### Domanda 5: Perché il backend compone il messaggio WhatsApp, e non il frontend?

**Risposta modello.** Perché il testo dipende da dati e regole del backend: il nome del servizio, l'ora nella zona dello studio, la scadenza del link. Se lo componesse il frontend, la stessa logica (formati, fusi orari) vivrebbe in due posti. Il frontend riceve un indirizzo pronto e lo apre.

### Domanda 6: Hai evitato un lucchetto. Come fai a sapere che non serviva?

**Risposta modello.** Ho guardato cosa succede senza: il peggio è creare un link per una seduta che un altro link ha appena pagato, e se il cliente lo pagasse il suo blocco verrebbe annullato dalla regola "il primo vince". Con il lucchetto, invece, la creazione del link e il webhook prenderebbero gli stessi due lucchetti in ordine opposto, e potrebbero bloccarsi a vicenda. Un lucchetto in meno con un caso innocuo è meglio di un lucchetto in più con un deadlock possibile.

---

## Documentazione ufficiale

- Stripe, far scadere una sessione Checkout: https://docs.stripe.com/api/checkout/sessions/expire
- Stripe, recuperare una sessione Checkout: https://docs.stripe.com/api/checkout/sessions/retrieve
- WhatsApp, link per la chat (`wa.me`): https://faq.whatsapp.com/5913398998672934
