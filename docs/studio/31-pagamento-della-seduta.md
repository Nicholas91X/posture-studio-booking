# Pagamento della seduta con Stripe Checkout — guida di studio

Questa guida accompagna il **pezzo 2 del passo 9**. Dopo la richiesta (`PENDING_PAYMENT`, D-045) il cliente deve ricevere l'indirizzo di una **pagina di pagamento di Stripe**, su cui l'importo viene **bloccato** sulla carta senza essere incassato (D-031), sul conto del professionista (D-057, D-060).

```
POST /api/customer/bookings      {"practitionerId", "serviceId", "startsAt"}
     → 201 {..., "status": "PENDING_PAYMENT", "holdExpiresAt": "...", "paymentUrl": "https://checkout.stripe.com/c/pay/cs_..."}
     → 409 CARD_PAYMENTS_UNAVAILABLE   il professionista non ha un conto di incasso
     → 503 PAYMENT_PROVIDER_UNAVAILABLE  Stripe non ha risposto: niente è stato creato, si riprova
GET  /api/customer/bookings/{id}  → anche qui "paymentUrl", finché il blocco dell'orario è valido
```

Il webhook (Stripe che ci avvisa "pagato") è il pezzo 3: qui il cliente arriva fino alla pagina di Stripe, e la riga `payment` resta `PENDING`.

Teoria di riferimento: D-007 e D-031 (lo dice il webhook, non il browser; blocco e incasso separati), D-008 (blocco dell'orario e scadenza della sessione), D-011 (prezzo fotografato), D-044 e D-046 (interfacce dichiarate da `booking`: `OccupiedTime`, `CardHolds`), D-045 (la richiesta in una transazione), D-057 e D-060 (conti di incasso), guida 30.

---

## Indice

- Parte 1 — Cosa c'è già, e cosa manca
- Parte 2 — Stripe Checkout in breve
- Parte 3 — Prima scelta: chi crea la sessione, e quando
- Parte 4 — La doppia scrittura: il nostro database e Stripe
- Parte 5 — Seconda scelta: la scadenza (30 minuti non bastano)
- Parte 6 — I parametri della sessione
- Parte 7 — L'idempotenza
- Parte 8 — Errori di Stripe e tempi di attesa
- Parte 9 — Dove sta l'indirizzo della pagina
- Parte 10 — Come si prova senza chiamare Stripe
- Parte 11 — Cosa c'è da decidere
- Parte 12 — Com'è andata (implementazione)
- Domande da colloquio

---

## Parte 1 — Cosa c'è già, e cosa manca

**C'è:** la richiesta del cliente (`BookingService.request`, D-045) con il blocco dell'orario (`hold_expires_at`); il prezzo fotografato; i conti di incasso e `PaymentAccountService.forNewPayment` (pezzo 1); la tabella `payment` con il conto; `stripe-java` nel `pom.xml`.

**Manca:** chi crea la sessione Checkout e quando; la riga `payment` `PENDING`; l'indirizzo della pagina nella risposta al cliente; il 409 per il professionista senza conto (rimandato dal pezzo 1); cosa succede se Stripe non risponde.

---

## Parte 2 — Stripe Checkout in breve

Checkout è una **pagina di pagamento ospitata da Stripe**. Il nostro server chiede a Stripe "crea una sessione per 30 € per questa seduta" e riceve un **indirizzo** (`url`). Il browser del cliente va lì, il cliente scrive i dati della carta **sulla pagina di Stripe**: il nostro sistema non li vede mai (requisiti). Finito, Stripe rimanda il browser a un nostro indirizzo di ritorno (*success URL*) e, separatamente, ci avvisa con il webhook (pezzo 3).

Tre oggetti di Stripe da distinguere:
- **Checkout Session** (`cs_...`): la pagina, con una scadenza;
- **PaymentIntent** (`pi_...`): il pagamento vero, creato da Checkout quando il cliente paga. Con `capture_method = manual` resta nello stato `requires_capture`: l'importo è **bloccato**. Incasso e annullamento (pezzo 4) si fanno sul PaymentIntent;
- **Event** (`evt_...`): l'avviso del webhook (pezzo 3).

---

## Parte 3 — Prima scelta: chi crea la sessione, e quando

### 3.1 Il ciclo, di nuovo

La richiesta nasce in `booking`; la sessione la sa creare `payment`, che dipende già da `booking`. Come per `CardHolds` (D-046): **`booking` dichiara un'interfaccia con le sue parole, `payment` la implementa**.

```java
public interface CardCheckout {
    boolean available(long practitionerId);          // ha un conto di incasso?
    URI start(CheckoutRequest request);              // crea la pagina, restituisce l'indirizzo
    Optional<URI> pageOf(long bookingId);            // la pagina di una prenotazione in attesa
}
```

`booking` non sa niente di Stripe, di conti o di chiavi: sa solo che una prenotazione si paga "su una pagina".

### 3.2 Tre momenti possibili

- **(a) Nella stessa transazione della richiesta**, dopo l'`insert` della prenotazione: un'unica chiamata del cliente, una risposta con l'indirizzo pronto.
- **(b) Dopo il commit**, in una seconda transazione: la prenotazione è salvata prima di parlare con Stripe. Se Stripe non risponde, resta un orario bloccato 32 minuti senza pagina (il caso che la guida 30, parte 6, voleva evitare), e serve un modo per ricrearla.
- **(c) Una seconda chiamata del frontend** (`POST .../checkout`): due richieste dove ne basta una, e lo stesso orario bloccato se la seconda non arriva mai.

**Raccomandazione: (a).** Se Stripe fallisce, la transazione si annulla e la prenotazione non esiste: niente resta a metà dal nostro lato. Il prezzo è che la transazione resta aperta durante la chiamata (parte 8).

### 3.3 L'ordine dentro la richiesta

1. il servizio è in vendita (404);
2. **il professionista ha un conto?** Se no, 409 `CARD_PAYMENTS_UNAVAILABLE` (guida 30, parte 6), prima di qualunque lucchetto o scrittura;
3. i passi di oggi: lucchetto sul cliente, limite, orario libero, blocchi scaduti, `insert` con `flush`;
4. **la sessione Stripe**, con l'id della prenotazione appena scritta;
5. **la riga `payment` `PENDING`**, con l'id e l'indirizzo della sessione;
6. commit.

---

## Parte 4 — La doppia scrittura: il nostro database e Stripe

Due sistemi da tenere d'accordo, senza una transazione che li copra entrambi. È il problema della **doppia scrittura** (*dual write*), e va ragionato caso per caso.

| Cosa va storto | Cosa resta | Va bene? |
|---|---|---|
| Stripe non risponde o rifiuta (passo 4) | Niente: la transazione si annulla, il cliente riceve 503 e riprova | Sì |
| Stripe ha creato la sessione, poi il commit fallisce (passi 5–6) | Una sessione su Stripe che nessuno conosce: l'indirizzo non è mai arrivato al cliente | Sì: scade da sola alla sua scadenza, e nessuno può pagarla senza indirizzo |
| Commit riuscito, la risposta si perde (rete del telefono) | Prenotazione e pagamento salvati | Sì: il cliente ritrova l'indirizzo in "le mie sedute" (parte 9) |

Il caso pericoloso, "Stripe ha **incassato** e il nostro commit è fallito", qui non esiste ancora: creare una sessione non muove soldi. Arriva al pezzo 4, con l'incasso.

La regola generale: **prima l'operazione esterna che si può abbandonare, poi la scrittura nostra.** Una sessione orfana è innocua; un pagamento registrato senza sessione no.

---

## Parte 5 — Seconda scelta: la scadenza (30 minuti non bastano)

Verificato sulla documentazione di Stripe (2026-11-10): `expires_at` può essere **da 30 minuti a 24 ore dopo la creazione della sessione**.

D-008 vuole il blocco dell'orario **lungo quanto la sessione**: 30 minuti. Ma il blocco parte dall'istante della richiesta (`now`), la sessione si crea un attimo dopo. Se chiediamo `expires_at = now + 30 min`, per Stripe la sessione dura 29 minuti e 59 secondi e qualcosa: **rifiutata**. In più, l'orologio del nostro server e quello di Stripe non sono identici, e un nuovo tentativo dopo un errore di rete (parte 8) parte qualche secondo più tardi.

Tre possibilità:
- **(a) Sessione più lunga del blocco** (`now + 31 min`): il cliente può pagare un orario già liberato. È proprio l'alternativa che D-008 aveva scartato.
- **(b) Blocco di 30 minuti, sessione allineata, e sperare**: fallirebbe sempre.
- **(c) Blocco di 32 minuti, sessione che scade nello stesso istante del blocco**: per Stripe la sessione dura poco meno di 32 minuti, più di 30 anche con due minuti di ritardi e orologi sfasati.

**Raccomandazione: (c).** Il cliente ha due minuti in più (irrilevante), la pagina non sopravvive mai al blocco. `expires_at` è in secondi: si tronca **per difetto** (`getEpochSecond()`), così la sessione scade al più un istante **prima** del blocco, mai dopo. È una modifica di D-008 (da 30 a 32 minuti): serve una scheda nuova.

E se il cliente paga nell'ultimo secondo e l'avviso arriva dopo la scadenza del blocco? È già coperto (D-046): una `PENDING_PAYMENT` con blocco scaduto ma ancora non chiusa si accetta; una già `EXPIRED` riceve l'annullamento del blocco. Lo vedremo al pezzo 3.

---

## Parte 6 — I parametri della sessione

| Parametro | Valore | Perché |
|---|---|---|
| `mode` | `payment` | un pagamento singolo (non un abbonamento) |
| `line_items[0]` | `price_data`: `currency=eur`, `unit_amount` = `booking.price_cents`, `product_data.name` = nome del servizio; `quantity=1` | l'importo viene dal **prezzo fotografato** (D-011), non dal catalogo di oggi; niente "prodotti" da creare prima su Stripe |
| `payment_intent_data.capture_method` | `manual` | **blocco senza incasso** (D-031) |
| `payment_intent_data.metadata.booking_id` | l'id | anche gli avvisi sul PaymentIntent (pezzo 4) dicono di quale prenotazione si parla |
| `metadata.booking_id`, `client_reference_id` | l'id | per ritrovare la prenotazione dall'avviso della sessione (pezzo 3) |
| `allowed_payment_method_types` | `card` | solo carte (comprese Apple Pay e Google Pay, che per Stripe sono carte). Con l'incasso manuale altri metodi non avrebbero senso. Verificato: in questa versione dell'API il parametro si chiama così, non più `payment_method_types` |
| `expires_at` | `hold_expires_at` in secondi, per difetto | parte 5 |
| `customer_email` | l'email del cliente | precompilata: il cliente non la riscrive, e Stripe vi manda la ricevuta |
| `locale`, `submit_type` | `it`, `book` | pagina in italiano, pulsante "Prenota" |
| `success_url`, `cancel_url` | `APP_URL` + `/prenotazioni/{id}?pagamento=completato` o `annullato` | **segnaposto**: le pagine arrivano al passo 12 |

Il nome del cliente **non** va a Stripe: non serve, e meno dati personali escono, meglio è. L'email sì, perché serve alla ricevuta.

Indirizzo di ritorno: anche se il cliente ci torna, **non conferma niente** (D-007). La pagina dirà "pagamento in verifica" e chiederà lo stato al backend.

---

## Parte 7 — L'idempotenza

**Idempotente** vuol dire: farlo due volte ha lo stesso effetto che farlo una. Una `GET` lo è per natura; una `POST` che crea qualcosa no: due `POST` creano due cose.

Il problema concreto: chiediamo a Stripe di creare la sessione, Stripe la crea, ma la risposta si perde per un errore di rete. La libreria riprova. Senza precauzioni, Stripe crea una **seconda** sessione.

La soluzione di Stripe: l'header **`Idempotency-Key`**. Stripe ricorda per 24 ore la chiave e la risposta: una seconda richiesta con la stessa chiave riceve **la stessa risposta**, senza creare niente. Se la stessa chiave arriva con parametri diversi, Stripe risponde con un errore: è il segnale di un errore nostro, non un caso da gestire.

La chiave la scegliamo noi, e deve identificare **l'intenzione**, non il tentativo: `booking-{id}-checkout`. Ogni prenotazione ha al massimo una sessione da richiesta del cliente, e l'id non si ripete mai (le sequenze di PostgreSQL non tornano indietro neanche con un *rollback*). Il link del pezzo 5, che crea più sessioni per la stessa prenotazione, userà chiavi sue.

Due livelli di idempotenza da non confondere, che ritroveremo al pezzo 3:
- **verso Stripe**: le nostre richieste che creano qualcosa portano una chiave;
- **da Stripe verso di noi**: gli avvisi possono arrivare due volte, e siamo noi a dover riconoscere quelli già visti.

---

## Parte 8 — Errori di Stripe e tempi di attesa

### 8.1 Una chiamata di rete dentro una transazione

Durante la chiamata a Stripe la transazione è aperta: tiene il lucchetto sul cliente e, soprattutto, la riga appena inserita nell'indice del vincolo di non sovrapposizione. **Un'altra richiesta per lo stesso orario aspetta** finché questa non finisce. Per mezzo secondo va benissimo; per 80 secondi no.

I valori predefiniti di `stripe-java` 34.1.0 sono pensati per non arrendersi: 30 secondi per collegarsi, 80 per leggere la risposta, e **nessun** nuovo tentativo (verificato nel bytecode del jar). Proposta iniziale: 5 secondi, 15 e un nuovo tentativo, cioè circa 40 secondi nel caso peggiore. **Corretta su richiesta di Nicholas** (2026-11-10): **2 secondi** per collegarsi, **5** per leggere, **1** nuovo tentativo (con la stessa chiave di idempotenza, quindi sicuro: un `POST` andato in *timeout* che Stripe aveva eseguito restituisce la stessa sessione). Tra i due tentativi la libreria aspetta almeno mezzo secondo. Caso peggiore circa **15 secondi**, poi il cliente riceve "riprova".

Chi aspetta davvero? Solo chi vuole **lo stesso orario**, o lo stesso cliente da un'altra scheda: il lucchetto è sul cliente e l'indice del vincolo è per orario. Gli altri clienti e gli altri orari passano.

### 8.1b L'alternativa, se il traffico crescesse

Separare in tre tempi con una **compensazione**: (1) salvare prenotazione e pagamento "pagina da creare" e fare commit, così i lucchetti si liberano subito; (2) chiamare Stripe fuori da ogni transazione; (3) registrare la sessione, oppure, se Stripe fallisce, **annullare** la prenotazione con una transazione di compensazione. Serve un lavoro programmato che ripari i casi rimasti a metà (un riavvio tra (1) e (3)). È lo schema delle **saga**: ogni passo ha il suo "contrario". Qui non conviene: più stati e più codice, per un'attesa rara a questa scala.

### 8.2 Cosa vede il cliente

Qualunque `StripeException` diventa un **503** `PAYMENT_PROVIDER_UNAVAILABLE`: "il pagamento non è disponibile in questo momento, riprova tra poco". La transazione si annulla: nessuna prenotazione, nessun orario bloccato. Nel log: il tipo d'errore, il codice di Stripe e il suo *request id* (che il supporto di Stripe sa ritrovare), mai le chiavi.

Un errore di tipo `InvalidRequestException` (parametri sbagliati) è un errore **nostro**, non di Stripe: per il cliente cambia poco, ma nel log va come errore, non come avviso.

### 8.3 Il conto giusto

La chiamata usa il **`StripeClient` del conto** restituito da `forNewPayment` (guida 30, parte 8), creato una volta all'avvio per ogni conto. Il conto si copia nella riga `payment` nello stesso momento.

---

## Parte 9 — Dove sta l'indirizzo della pagina

Il cliente deve poter ritrovare la pagina: risposta persa, pagina chiusa per sbaglio, "completa il pagamento" dalla lista delle sue sedute. Due possibilità:
- chiedere ogni volta a Stripe la sessione (`GET /v1/checkout/sessions/{id}`): una chiamata esterna per ogni lettura, anche della lista;
- **salvare l'indirizzo** nella riga `payment`, alla creazione.

**Raccomandazione: salvarlo**, in una colonna nuova `checkout_url`. Una migrazione nuova, **V11**: la V10 è già stata eseguita (nei test) e una migrazione eseguita non si modifica mai (CLAUDE.md), anche se non è ancora nel repository. È proprio il caso in cui si impara la regola.

`CustomerBookingView` riceve `paymentUrl`: valorizzato solo per una `PENDING_PAYMENT` con blocco ancora valido, `null` altrimenti. Lo restituiscono la richiesta, la lettura di una prenotazione e la lista.

---

## Parte 10 — Come si prova senza chiamare Stripe

Tre livelli, dal più veloce al più vero:

1. **Un'interfaccia e un finto.** Dentro `payment`, un'interfaccia `StripeGateway` ("crea una sessione su questo conto") con due implementazioni: quella vera, che usa `StripeClient`, e un finto nei test che ricorda le chiamate, restituisce sessioni inventate e sa fallire a comando. I test delle prenotazioni non parlano mai con Stripe, e il finto è registrato in **tutti** i contesti dei test: nessun test può chiamare Stripe per sbaglio.
2. **Il gateway vero contro un finto server HTTP.** `StripeClient` permette di cambiare l'indirizzo di Stripe (`setApiBase`). Un piccolo server HTTP del JDK, sul computer, risponde come Stripe e ci mostra **cosa la libreria spedisce davvero**: `payment_intent_data[capture_method]=manual`, l'importo, la scadenza, l'header `Idempotency-Key`, la chiave del conto giusto. Stessa idea del finto servizio push della guida 28.
3. **Stripe in modalità di prova**, a mano, con la Stripe CLI: alla fine del passo, documentata nel documento del passo.

---

## Parte 11 — Cosa c'è da decidere

| Scelta | Raccomandazione | Dove |
|---|---|---|
| Chi crea la sessione | `booking` dichiara `CardCheckout`, `payment` la implementa | parte 3.1 |
| Quando | Nella transazione della richiesta, dopo l'`insert`; il conto controllato prima di tutto | parte 3.2–3.3 |
| Doppia scrittura | Prima la sessione (abbandonabile), poi la riga `payment`; nessuna riconciliazione serve qui | parte 4 |
| Scadenza | Blocco di **32** minuti (modifica D-008), sessione che scade nello stesso istante, per difetto al secondo | parte 5 |
| Parametri | Prezzo fotografato, `manual`, solo carte, `booking_id` nei metadati, email sì, nome no, ritorni segnaposto | parte 6 |
| Idempotenza | `Idempotency-Key: booking-{id}-checkout` | parte 7 |
| Tempi ed errori | 2 s / 5 s / 1 tentativo (corretto da 5 s / 15 s su richiesta di Nicholas); ogni errore di Stripe → 503 `PAYMENT_PROVIDER_UNAVAILABLE`, niente creato | parte 8 |
| Indirizzo | Colonna `checkout_url` (V11); `paymentUrl` nella vista del cliente finché il blocco vale | parte 9 |
| Prove | `StripeGateway` con un finto in ogni contesto di test; gateway vero contro un server HTTP locale | parte 10 |

Una migrazione nuova (**V11**). Una scheda nuova (D-061) che modifica D-008.

---

## Parte 12 — Com'è andata (implementazione)

Costruito come raccomandato nella parte 11 (D-061), su richiesta di Nicholas del 2026-11-10. 13 test nuovi: 6 in `BookingCheckoutApiTest`, 1 in `BookingCheckoutRollbackTest`, 6 in `StripeClientGatewayTest` (il sesto aggiunto dopo la revisione di Nicholas: i tempi d'attesa veri).

**I file:**

| File | Cosa fa |
|---|---|
| `booking/CardCheckout` | L'interfaccia di `booking`: `available`, `start`, `pageOf`, e il `record CheckoutRequest` |
| `booking/BookingService` | Il 409 prima di tutto, la sessione dopo l'`insert`, `paymentUrl` nelle viste del cliente |
| `booking/Booking` | `HOLD` da 30 a 32 minuti |
| `payment/StripeGateway` | L'interfaccia verso Stripe, con il conto come parametro obbligatorio |
| `payment/StripeClientGateway` | Quella vera: un `StripeClient` per conto, tempi brevi, un tentativo in più, errori in 503 |
| `payment/StripeCardCheckout` | L'implementazione di `CardCheckout`: conto, sessione, riga `PENDING` |
| `shared/ServiceUnavailableException` | Il 503 con un codice, gestito da `ApiExceptionHandler` |
| `V11__payment_checkout_url.sql` | La colonna dell'indirizzo |

Nei test: `FakeStripeGateway` (importato da `TestcontainersConfiguration`, quindi in ogni contesto), `RecordingCardCheckout` in `BookingTestDoubles` per i test di `booking` senza Spring completo, l'assegnazione del conto `alpha` in `BookingTestData.practitioner`.

**Verificato sulla libreria** (con `javap` sul jar 34.1.0, non a memoria): `StripeClient.builder()` con `setApiBase`, `setConnectTimeout`, `setReadTimeout`, `setMaxNetworkRetries`; i valori predefiniti 30 000 e 80 000 millisecondi; `client.v1().checkout().sessions().create(params, options)`; e **`payment_method_types` non esiste più** nei parametri di creazione: c'è `allowed_payment_method_types`, un filtro sui metodi che Stripe calcola da solo.

**Errori fatti e corretti:**
- **I test che fanno commit veri** (`BookingAfterCommitTest`, `BookingConcurrencyTest`, `BookingNotificationsTest`) puliscono a mano le loro righe alla fine. Le nuove tabelle puntano a `booking` e `practitioner`, quindi la pulizia si fermava sulle *foreign key*: aggiunte le cancellazioni di `payment` e `practitioner_payment_account`, nell'ordine giusto (prima chi punta, poi chi è puntato).
- **Un `record` dentro un'interfaccia è `public` da solo**, e il suo costruttore compatto non può essere meno visibile: il compilatore lo rifiuta.
- **"Stripe giù" in un test transazionale non si vede.** In una classe `@Transactional` la richiesta si unisce alla transazione del test: l'eccezione la segna "da annullare", ma l'annullamento vero avviene solo a fine test, e la prenotazione inserita è ancora lì quando la conto. Il caso sta in una classe a parte, senza transazione del test, con commit veri e pulizia finale.
- **La cache di JPA nel test.** Una prenotazione creata dalla richiesta resta nel *persistence context* della transazione del test; un `update` SQL successivo non si vede quando la si rilegge, perché JPA restituisce l'oggetto che ha già. Il test della scadenza crea le righe direttamente con SQL.
- **Un test che non sapeva fallire.** Il test del nuovo tentativo confrontava il numero di richieste con `1 + MAX_NETWORK_RETRIES`: cambiando la costante, il test la seguiva. Scritto il numero, 2.

**I test sanno fallire.** Sette difese tolte una alla volta:

| Tolto | Test che se ne accorge |
|---|---|
| `capture_method=manual` | `StripeClientGatewayTest` |
| Scadenza arrotondata per difetto | `StripeClientGatewayTest` |
| Il nuovo tentativo | `StripeClientGatewayTest` (dopo la correzione) |
| Il 409 per il professionista senza conto | `BookingCheckoutApiTest` |
| `paymentUrl` solo finché il blocco vale | `BookingCheckoutApiTest` |
| Il blocco di 32 minuti | `BookingTest`, `BookingCheckoutApiTest` |
| La chiave d'idempotenza fissa | `BookingCheckoutApiTest` |
| Lettura di 15 s, o di 1 s | `StripeClientGatewayTest` (il gateway deve arrendersi tra 10 e 15 s) |

**Il test dei tempi d'attesa** usa i valori veri, non valori minuscoli scelti dal test: un test con un'attesa di 50 millisecondi proverebbe solo che la libreria sa aspettare, non che *noi* l'abbiamo configurata bene. Costa circa 10 secondi di suite. Il finto Stripe accetta la richiesta e non risponde (un `CountDownLatch` che il test libera alla fine), su thread *daemon* uno per richiesta, così il nuovo tentativo non resta in coda dietro al primo.

**Non ancora provato con Stripe vero**: la prova manuale con la Stripe CLI arriva quando c'è il webhook (pezzo 3), perché senza non si vede il giro completo.

---

## Domande da colloquio

### Domanda 1: Come integri un pagamento esterno senza che i dati della carta passino dal tuo server?

**Risposta modello.** Con una pagina ospitata dal fornitore, come Stripe Checkout: il server crea una sessione con importo e metadati e riceve un indirizzo; il cliente inserisce la carta sulla pagina del fornitore. Il server non vede mai la carta, e la conferma non arriva dal browser che torna (falsificabile) ma dal webhook firmato del fornitore.

### Domanda 2: Cos'è la doppia scrittura, e come l'hai gestita qui?

**Risposta modello.** È scrivere in due sistemi (il mio database e Stripe) senza una transazione comune: uno può riuscire e l'altro no. Ho messo prima l'operazione esterna che si può abbandonare, la creazione della sessione, e poi la mia scrittura nella stessa transazione della prenotazione. Se Stripe fallisce, annullo tutto; se fallisce il mio commit, resta una sessione che nessuno conosce e che scade da sola. Il caso difficile, soldi mossi e commit fallito, arriva con l'incasso e lo chiudo riconciliando con il webhook.

### Domanda 3: Cos'è una chiave di idempotenza e come la scegli?

**Risposta modello.** È un identificativo che mando con una richiesta che crea qualcosa: se la stessa chiave arriva due volte, il server restituisce la prima risposta invece di creare un doppione. Serve per i nuovi tentativi dopo un errore di rete. La scelgo legata all'intenzione, non al tentativo: qui `booking-{id}-checkout`, perché ogni prenotazione ha una sola sessione e l'id non si ripete mai.

### Domanda 4: Fai una chiamata HTTP dentro una transazione del database. È un problema?

**Risposta modello.** Può esserlo: la transazione tiene lucchetti per tutta la durata della chiamata, e chi vuole le stesse righe aspetta. Qui l'ho accettato perché in cambio, se Stripe fallisce, non resta niente a metà; ma ho abbassato i tempi di attesa della libreria (2 secondi per collegarsi, 5 per leggere, un solo nuovo tentativo, circa 15 secondi nel caso peggiore) perché i valori predefiniti arrivano a più di un minuto, e l'ho provato con un finto server che non risponde. Con più traffico separerei: prima il commit, poi la chiamata, e se fallisce una transazione di compensazione che annulla la prenotazione, più un lavoro che ripara i casi rimasti a metà.

### Domanda 5: Come testi codice che chiama un servizio esterno?

**Risposta modello.** A strati. Un'interfaccia mia davanti al servizio, con un finto nei test della logica: veloce, e posso farlo fallire a comando. Poi il client vero contro un server HTTP locale che finge il servizio, per vedere cosa la libreria spedisce davvero (parametri, header, chiavi). E infine una prova manuale nell'ambiente di prova del fornitore. Il finto è registrato in ogni contesto di test, così nessun test chiama il servizio vero per sbaglio.

### Domanda 6: Il fornitore impone una durata minima della sessione, e la tua regola di business vuole che la pagina scada con il blocco dell'orario. Come le metti d'accordo?

**Risposta modello.** Non posso accorciare la sessione sotto il minimo, e non voglio che sopravviva al blocco, altrimenti il cliente pagherebbe un orario già liberato. Allungo il blocco di poco (qui da 30 a 32 minuti), così la sessione può scadere esattamente con lui restando sopra il minimo anche con ritardi e orologi sfasati, e arrotondo la scadenza per difetto. Il caso del pagamento all'ultimo secondo lo gestisce comunque il webhook.

---

## Documentazione ufficiale

- Stripe, creare una sessione Checkout: https://docs.stripe.com/api/checkout/sessions/create
- Stripe, incasso separato dall'autorizzazione: https://docs.stripe.com/payments/place-a-hold-on-a-payment-method
- Stripe, richieste idempotenti: https://docs.stripe.com/api/idempotent_requests
- Stripe, gestione degli errori: https://docs.stripe.com/error-handling
- stripe-java, `StripeClient`: https://github.com/stripe/stripe-java
