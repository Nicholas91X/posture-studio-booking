# Conti di incasso e tabella dei pagamenti — guida di studio

Questa guida accompagna il **pezzo 1 del passo 9**. Prima di far pagare qualcuno bisogna sapere **su quale conto Stripe** finiscono i soldi, e avere un posto dove ricordare ogni pagamento. D-057 ha deciso la politica: uno o più conti di incasso, ognuno assegnato a uno o più professionisti, assegnazione modificabile in produzione, niente Stripe Connect. Qui si decide **come** si costruisce.

```
GET  /api/admin/payment-accounts                         → i conti configurati (solo i nomi) e chi li usa
GET  /api/admin/practitioners/{id}/payment-account       → il conto di un professionista (404 se non ne ha)
PUT  /api/admin/practitioners/{id}/payment-account       {"account": "rossi"}  → 200
```

Nessun pagamento parte ancora: la pagina di Stripe arriva al pezzo 2. Questo pezzo costruisce le fondamenta su cui poggiano tutti gli altri.

Teoria di riferimento: D-057 (conti di incasso), D-018 (un unico `.env`), D-002 e D-026 (moduli che si parlano per servizi e per id), D-042 (`check` e colonne solo per i valori che il codice produce), D-011 e D-022 (prezzo fotografato, centesimi), D-027 (regole violate: 409 con un codice), D-052 (API dell'admin, controllo all'avvio di `AdminBootstrap`).

---

## Indice

- Parte 1 — Cosa c'è già, e cosa manca
- Parte 2 — La libreria di Stripe: versione e compatibilità
- Parte 3 — Prima scelta: come si dichiarano i conti
- Parte 4 — Seconda scelta: i controlli all'avvio
- Parte 5 — Terza scelta: dove sta l'assegnazione
- Parte 6 — Quarta scelta: il professionista senza conto
- Parte 7 — Quinta scelta: la tabella `payment`
- Parte 8 — La regola d'oro: si opera sul conto del pagamento
- Parte 9 — Le API dell'admin
- Parte 10 — I test
- Parte 11 — Cosa c'è da decidere
- Domande da colloquio

---

## Parte 1 — Cosa c'è già, e cosa manca

**C'è:**
- `CardHolds` (dichiarata da `booking`, D-046), con `capture` e `cancel`, oggi implementata da `LoggingCardHolds`, che scrive solo nel log;
- `BookingService.paymentAuthorized`, chiamato oggi dal pagamento simulato;
- il prezzo fotografato in `booking.price_cents` (D-011, D-022);
- l'area `/api/admin/**` solo per l'admin (D-051), con l'API dei professionisti nel modulo `practitioner` (D-052);
- un modulo `payment` previsto dall'architettura ("Integrazione Stripe, webhook"), ma **ancora vuoto**.

**Manca:**
- la libreria di Stripe nel `pom.xml`;
- i conti: dove si scrivono le chiavi, come si controllano;
- chi decide quale conto usa un professionista;
- la tabella `payment`.

---

## Parte 2 — La libreria di Stripe: versione e compatibilità

Verificato il 2026-11-10 su Maven Central:
- ultima versione stabile **`com.stripe:stripe-java` 34.1.0**, pubblicata il 29 ottobre 2026 (c'è una 34.2.0-beta.1, ma una *beta* non va in un prodotto vero);
- è fissata sulla versione dell'API di Stripe **`2026-10-28.endive`** (`ApiVersion.CURRENT`). Ogni versione della libreria parla una versione precisa dell'API: la libreria e l'API si aggiornano insieme;
- è compilata per Java 8 e dipende **solo da Gson** (la libreria di Google per il JSON). Non usa Spring, quindi con Boot 4 non c'è niente che si possa scontrare, tranne Gson stesso.

**La sola attenzione: la versione di Gson.** stripe-java chiede Gson 2.10.1, Boot 4.1.2 gestisce Gson 2.13.2. In Maven, quando il progetto eredita da `spring-boot-starter-parent`, la versione decisa dal *dependency management* di Boot **vince** su quella chiesta da una dipendenza transitiva. Quindi useremo Gson 2.13.2. È una versione più nuova della stessa serie 2.x, compatibile all'indietro: va bene, ma lo **proveremo**, perché i test del webhook (pezzo 3) leggono JSON veri con la libreria.

Un concetto da colloquio: *dipendenza transitiva* e *dependency management*. `mvnw dependency:tree` mostra chi ha portato cosa e con quale versione; la riga `(version managed from 2.10.1)` dice proprio che Boot ha deciso al posto di Stripe.

**`StripeClient` invece di `Stripe.apiKey`.** La libreria ha due stili:
- il vecchio: `Stripe.apiKey = "sk_..."`, un campo **statico**, cioè una chiave sola per tutta l'applicazione;
- il nuovo (dalla versione 23): `new StripeClient("sk_...")`, un **oggetto** con la sua chiave.

Con più conti il vecchio stile è sbagliato in partenza: due richieste contemporanee per due conti si contenderebbero lo stesso campo statico. Con `StripeClient` ogni conto ha il suo oggetto, creato all'avvio e riusato. È anche un buon esempio di perché lo stato globale mutabile è un problema: non per estetica, ma perché due thread lo condividono.

---

## Parte 3 — Prima scelta: come si dichiarano i conti

Un conto ha tre cose: un **nome** (che sta nel database, accanto a ogni pagamento), una **chiave segreta** (per chiamare Stripe) e un **segreto del webhook** (per verificare gli avvisi di Stripe, pezzo 3). Le due chiavi sono segreti: solo da variabili d'ambiente, mai nel repository.

Il brief chiede: **aggiungere un conto richiede un riavvio**, non una modifica del codice. Tre modi.

### 3.1 Righe fisse in `application.properties`

```properties
payment.accounts.rossi.secret-key=${STRIPE_ROSSI_SECRET_KEY}
payment.accounts.rossi.webhook-secret=${STRIPE_ROSSI_WEBHOOK_SECRET}
```

Si legge in un `record` con `@ConfigurationProperties`, come `LoginCodeProperties`. Semplice, ma **i nomi dei conti stanno nel repository**: aggiungere un conto vuol dire modificare `application.properties`, fare un commit e una nuova versione. Contraddice il brief.

### 3.2 Una `Map` legata direttamente alle variabili d'ambiente

Spring Boot sa riempire una `Map<String, Account>` da variabili d'ambiente come `PAYMENT_ACCOUNTS_ROSSI_SECRETKEY`: la parte `ROSSI` diventa la chiave della mappa. Elegante, nessuna riga per conto.

**La trappola.** Questa traduzione "nome in maiuscolo con `_` → proprietà con i punti" Spring la fa **solo per le vere variabili d'ambiente** del processo. Il nostro `.env`, in sviluppo, Spring lo legge come **file di proprietà** (`spring.config.import=optional:file:../.env[.properties]`, D-018), e lì `PAYMENT_ACCOUNTS_ROSSI_SECRETKEY` resta un nome qualunque, che non diventa `payment.accounts.rossi.secret-key`. Funzionerebbe in produzione (Docker passa vere variabili d'ambiente) e non in sviluppo, o viceversa. Una configurazione che si comporta in modo diverso a seconda di come arriva è il peggior tipo di errore: lo scopri il giorno della messa online.

### 3.3 Un elenco di nomi, e le chiavi con un nome fisso

```properties
# application.properties: i nomi vengono dall'ambiente, nessun conto scritto qui
payment.accounts=${PAYMENT_ACCOUNTS:}
```

```dotenv
# .env
PAYMENT_ACCOUNTS=rossi,bianchi
STRIPE_ROSSI_SECRET_KEY=rk_test_...
STRIPE_ROSSI_WEBHOOK_SECRET=whsec_...
STRIPE_BIANCHI_SECRET_KEY=rk_test_...
STRIPE_BIANCHI_WEBHOOK_SECRET=whsec_...
```

All'avvio il modulo legge l'elenco dei nomi e, per ognuno, chiede all'`Environment` di Spring `STRIPE_<NOME>_SECRET_KEY` e `STRIPE_<NOME>_WEBHOOK_SECRET`. L'`Environment` è l'unione di tutte le fonti (file importati, variabili d'ambiente, proprietà di sistema): la stessa domanda trova la chiave **sia** nel file `.env` in sviluppo **sia** nelle variabili d'ambiente in produzione. Aggiungere un conto: tre righe nel `.env` e un riavvio.

In `.env.example` vanno le righe di un conto d'esempio **senza valori**, con un commento sulla convenzione dei nomi.

**Raccomandazione: 3.3.** È l'unica che rispetta il brief e si comporta uguale ovunque. Il prezzo è un nome di variabile costruito a mano (`"STRIPE_" + nome.toUpperCase() + "_SECRET_KEY"`), quindi un errore di battitura non lo trova il compilatore: lo trova il controllo all'avvio (parte 4).

### 3.4 Il formato dei nomi

Il nome di un conto finisce in due posti che non perdonano: un nome di variabile d'ambiente e una colonna del database. Proposta: solo **lettere minuscole e cifre, iniziando con una lettera, al massimo 30 caratteri** (`^[a-z][a-z0-9]{0,29}$`). Niente trattini né spazi, così il nome della variabile è sempre valido.

**Un nome non si rinomina mai**, come una migrazione già eseguita: i pagamenti vecchi lo portano scritto. Se un conto cambia, se ne dichiara uno nuovo con un nome nuovo e si riassegnano i professionisti.

### 3.5 Chiave segreta o chiave limitata?

Stripe offre due tipi di chiave per il server:
- **segreta** (`sk_test_...`, `sk_live_...`): può fare **tutto** sul conto, anche leggere tutti i clienti e fare bonifici;
- **limitata** (*restricted key*, `rk_test_...`, `rk_live_...`): può fare solo ciò che le si permette, risorsa per risorsa.

A noi servono poche cose: creare e far scadere sessioni Checkout, incassare e annullare PaymentIntent. **Nessun rimborso**: D-015 resta valida, e anche il link del pezzo 5 blocca l'importo invece di incassarlo (D-059, precisata il 2026-11-10), quindi la chiave non ha bisogno del permesso sui rimborsi. Principio del **privilegio minimo**: se la chiave esce dal server (un backup, un log sbagliato), il danno è limitato a quello. **Raccomandazione**: accettare entrambi i tipi, consigliare la limitata nella documentazione del passo (la prova manuale e la messa online la useranno).

---

## Parte 4 — Seconda scelta: i controlli all'avvio

Una configurazione sbagliata deve fermare l'avvio, con un messaggio chiaro, invece di esplodere alla prima prenotazione di un cliente. È lo stesso principio di `AUTH_CODE_HMAC_KEY` e delle chiavi VAPID: oggi senza quelle l'applicazione non parte, apposta.

### 4.1 Cosa si controlla sulla configurazione

Per ogni nome in `PAYMENT_ACCOUNTS`:
1. il nome rispetta il formato (3.4) e non è ripetuto;
2. le due variabili esistono e non sono vuote;
3. la chiave inizia con `sk_` o `rk_`; il segreto del webhook con `whsec_`.

Questi controlli stanno nel costruttore compatto di un `record`, come in `LoginCodeProperties`: un `PaymentAccount(name, secretKey, webhookSecret)` sbagliato **non può esistere**. Attenzione a un dettaglio: un `record` genera da solo `toString()` con **tutti** i campi. Un `log.info("{}", account)` scriverebbe la chiave nel log. Va riscritto `toString()` perché mostri solo il nome. Un `record` non è sempre un contenitore neutro: i suoi metodi generati vanno guardati quando dentro c'è un segreto.

### 4.2 Prova o produzione: un interruttore

Errore classico e costoso: chiavi **di produzione** sul computer di sviluppo (i test fanno pagamenti veri), o chiavi **di prova** in produzione (i clienti "pagano" con carte finte e lo studio non incassa niente). Le chiavi di Stripe lo dicono nel nome: `_test_` o `_live_`.

Proposta: `payment.live=${PAYMENT_LIVE:false}`. All'avvio, ogni chiave deve corrispondere: con `false` solo `sk_test_`/`rk_test_`, con `true` solo `_live_`. Il default è "prova", quindi una variabile dimenticata non fa mai partire pagamenti veri. È lo stesso ragionamento del pagamento simulato spento per default (D-046): dimenticare qualcosa deve portare al lato sicuro.

### 4.3 Cosa si controlla sul database

Il database ricorda nomi di conti in due posti: l'assegnazione dei professionisti (parte 5) e i pagamenti ancora aperti (parte 7). All'avvio:
- ogni conto **assegnato** a un professionista deve essere configurato;
- ogni conto di un pagamento **ancora aperto** (in attesa o bloccato sulla carta) deve essere configurato, perché quel pagamento va ancora incassato o annullato **su quel conto** (parte 8).

Se manca, l'avvio si ferma dicendo quale nome manca. Esempio concreto: chi gestisce il server toglie il conto `rossi` dal `.env` pensando "Rossi ora usa il conto comune", ma ci sono tre sedute di Rossi con l'importo bloccato su `rossi`. Senza il controllo, all'approvazione l'incasso fallirebbe: con il controllo, l'errore si vede subito, prima che un cliente ne paghi le conseguenze.

I pagamenti chiusi (incassati, annullati, scaduti) non contano: su quelli non si opera più. (Se un giorno servirà agire su un pagamento chiuso, la regola andrà rivista.)

Si fa con un ascoltatore di `ApplicationReadyEvent` o un `ApplicationRunner`, come `AdminBootstrap`: dopo che Flyway ha creato le tabelle, prima di accettare richieste.

### 4.4 Zero conti

In sviluppo, chi non ha ancora un conto Stripe deve poter avviare il backend e lavorare su tutto il resto. Proposta: `PAYMENT_ACCOUNTS` vuoto è **ammesso**; semplicemente nessun professionista può avere un conto, e le richieste dei clienti ricevono la risposta della parte 6. I test usano un conto finto (parte 10).

---

## Parte 5 — Terza scelta: dove sta l'assegnazione

### 5.1 Una colonna in `practitioner`

`practitioner.payment_account text`. Una query in meno. Ma il conto di incasso è un fatto **dei pagamenti**: il modulo `practitioner` dovrebbe conoscere i conti configurati per controllare un'assegnazione, quindi dipendere da `payment`. E `payment` dipende già da `practitioner` (per sapere se un professionista esiste) e da `booking` (implementa `CardHolds`). Si crea un **ciclo** tra moduli, quello che D-044 ha già dovuto risolvere tra `schedule` e `booking`.

### 5.2 Una tabella del modulo `payment`

```sql
create table practitioner_payment_account (
    practitioner_id bigint primary key references practitioner (id),
    account         text not null check (account ~ '^[a-z][a-z0-9]{0,29}$'),
    assigned_at     timestamptz not null
);
```

Una riga per professionista, al massimo (la chiave primaria è `practitioner_id`). Riassegnare è un `update`; l'assegnazione precedente non si conserva, e non serve: **ogni pagamento ricorda il suo conto** (parte 7), che è l'unica storia che conta. Il database controlla il **formato** del nome; che il nome sia **configurato** lo può sapere solo Java.

Le dipendenze restano in un verso: `payment` → `practitioner`, `payment` → `booking`. Nessuno dipende da `payment`.

**Raccomandazione: 5.2.** Stesso ragionamento di D-029: i dati stanno nel modulo a cui appartengono, anche al prezzo di una tabella in più.

---

## Parte 6 — Quarta scelta: il professionista senza conto

Succede davvero: il giorno in cui l'admin crea un professionista (D-052), quello non ha ancora un conto. Cosa vede un cliente che prova a prenotare con lui?

### 6.1 Le possibilità

- **(a) Nasconderlo**: niente nell'elenco pubblico, nessun orario libero. Ma l'elenco sta in `practitioner` e gli orari in `schedule`, che dovrebbero chiedere a `payment` (un'altra dipendenza, e di nuovo un ciclo). E per il cliente un professionista che sparisce e ricompare è più strano di un messaggio chiaro.
- **(b) Lasciar creare la prenotazione e fallire dopo**, quando si crea la pagina di pagamento: l'orario resterebbe bloccato 30 minuti da una prenotazione che nessuno può pagare. Peggio di tutte.
- **(c) Rifiutare la richiesta prima di creare la prenotazione**, con un **409** e un codice leggibile, per esempio `CARD_PAYMENTS_UNAVAILABLE`. Il frontend (passo 12) lo traduce: "Questo professionista non accetta ancora prenotazioni online. Contatta lo studio." Niente si blocca, niente resta a metà.

**Raccomandazione: (c).** È la "risposta chiara, mai un 500" del brief, ed è un caso previsto, non un errore: quindi una `BusinessRuleException` (D-027), non un'eccezione qualunque che diventerebbe 500.

### 6.2 Cosa non tocca

- le **prenotazioni dal pannello** pagate in studio (D-040): non passano da Stripe, funzionano anche senza conto;
- i **pagamenti già fatti**: hanno il loro conto (parte 8).

Il link di pagamento (pezzo 5) senza conto riceverà lo stesso 409.

### 6.3 Chi fa la domanda, senza ciclo

La richiesta del cliente nasce in `booking` (`BookingService.request`), ma la risposta ("ha un conto?") ce l'ha `payment`, che dipende da `booking`. `booking` non può chiamare `payment` direttamente. La soluzione è quella già usata due volte (`OccupiedTime`, `CardHolds`): **`booking` dichiara un'interfaccia con le sue parole, `payment` la implementa**. Al pezzo 2 la stessa interfaccia servirà anche per creare la pagina di pagamento, quindi la si disegna lì; in questo pezzo basta che `payment` sappia rispondere a "conto di questo professionista?" con un `Optional<PaymentAccount>`.

Un ciclo tra moduli risolto con un'interfaccia si chiama **inversione delle dipendenze** (la D di SOLID): chi sta "in alto" (`booking`, le regole delle prenotazioni) dichiara cosa gli serve, chi sta "in basso" (`payment`, i dettagli di Stripe) si adatta.

---

## Parte 7 — Quinta scelta: la tabella `payment`

### 7.1 Cosa dice il modello dei dati, e cosa serve adesso

`modello-dati.md` prevede: `purpose` (`BOOKING` o `PACKAGE`), `booking_id`, `package_offer_id`, `credit_lot_id`, `customer_id`, `amount_cents`, `status`, `stripe_checkout_session_id`, `stripe_payment_intent_id`. Il brief aggiunge **il conto**.

Ma i pacchetti arrivano al passo 10. D-042 ha fissato un principio: lo schema contiene solo i valori che il codice sa produrre. Una colonna `package_offer_id` che nessuno scrive per mesi è una promessa, non un dato; e un `check` su `purpose` che accetta `PACKAGE` permetterebbe righe che nessun codice sa gestire.

**Proposta per la V10:**

```sql
create table payment (
    id                         bigint generated always as identity primary key,
    booking_id                 bigint not null references booking (id),
    account                    text not null check (account ~ '^[a-z][a-z0-9]{0,29}$'),
    amount_cents               bigint not null check (amount_cents > 0),
    status                     text not null check (status in ('PENDING', 'AUTHORIZED', 'SUCCEEDED', 'CANCELED', 'EXPIRED')),
    stripe_checkout_session_id text not null unique,
    stripe_payment_intent_id   text unique,
    created_at                 timestamptz not null,
    version                    bigint not null default 0
);
create index payment_booking_idx on payment (booking_id);
```

Cosa resta fuori, e perché:
- `purpose`, `package_offer_id`, `credit_lot_id`: al passo 10, con una migrazione nuova (e `booking_id` diventerà facoltativo, con un `check` "seduta o pacchetto");
- `customer_id`: per una seduta si ricava dalla prenotazione; al passo 10 servirà per i pacchetti, e arriverà con loro;
- la **valuta**: sempre euro, scritta nel codice che crea la sessione. Una colonna `currency` sempre uguale a `eur` è un dato che non dice niente.

### 7.2 Gli stati

| Stato | Significato | Quando |
|---|---|---|
| `PENDING` | Sessione Checkout creata, il cliente non ha ancora pagato | pezzo 2 |
| `AUTHORIZED` | Importo bloccato sulla carta (D-031) | pezzo 3, al webhook |
| `SUCCEEDED` | Incassato | pezzo 4 (approvazione), pezzo 5 (link) |
| `CANCELED` | Blocco annullato: rifiuto, scadenza della risposta, ritiro, pagamento tardivo, link pagato per una seduta non più "in studio" o già pagata (D-059) | pezzi 3–5 |
| `EXPIRED` | La sessione è scaduta senza pagamento | pezzi 2–3 |

Tutti e cinque saranno prodotti dal codice di questo passo, quindi stanno nel `check` fin dall'inizio, anche se il pezzo 1 non ne scrive nessuno: è una tabella nuova, e il passo è un tutt'uno. Se un pezzo successivo avrà bisogno di uno stato in più (per esempio un incasso fallito, pezzo 4), arriverà con la sua migrazione.

### 7.3 `stripe_checkout_session_id` obbligatorio

La riga nasce **dopo** che Stripe ha risposto con la sessione, quindi l'id c'è sempre: `not null`. L'ordine (prima Stripe, poi la riga, nella stessa transazione della prenotazione) e cosa succede se il commit fallisce dopo la risposta di Stripe sono la materia del pezzo 2 (la "doppia scrittura"). Anticipo solo la conclusione: una sessione creata e mai registrata scade da sola dopo 30 minuti, e nessuno ne conosce l'indirizzo; è un rifiuto innocuo, non un buco.

`stripe_payment_intent_id` invece arriva con il pagamento (il webhook lo porta): facoltativo, ma unico quando c'è.

### 7.4 Un solo pagamento in attesa per prenotazione

Per una richiesta del cliente c'è una sola sessione. Ma al pezzo 5 una seduta "in studio" potrà avere più link nel tempo, uno solo valido. Un vincolo lo garantisce già da ora:

```sql
create unique index payment_one_pending_per_booking on payment (booking_id) where status = 'PENDING';
```

È un **indice unico parziale**: l'unicità vale solo per le righe che soddisfano il `where`. Lo stesso strumento del vincolo di non sovrapposizione delle prenotazioni (D-042), in versione più semplice. Due pagamenti `EXPIRED` per la stessa seduta vanno bene; due `PENDING` no.

### 7.5 Perché `version`

Al pezzo 3 il webhook aggiornerà il pagamento mentre, magari nello stesso istante, il professionista approva. Come per `booking` (D-046), la difesa principale sarà il lucchetto sulla riga **della prenotazione**, sempre preso **prima** di toccare il pagamento (sempre nello stesso ordine, per non creare *deadlock*); `@Version` resta la seconda difesa. Lo vedremo per bene al pezzo 3.

---

## Parte 8 — La regola d'oro: si opera sul conto del pagamento

Il caso che il brief vuole provato con un test: Rossi ha il conto `rossi`. Un cliente paga una seduta: 30 € **bloccati sul conto `rossi`**. Prima dell'approvazione, l'admin sposta Rossi sul conto comune `studio`. Rossi approva.

Se l'incasso usasse "il conto attuale di Rossi", chiederebbe a Stripe di incassare sul conto `studio` un PaymentIntent che sul conto `studio` **non esiste**. Stripe risponderebbe "non trovato", l'incasso fallirebbe, la seduta non si confermerebbe; e il blocco su `rossi` resterebbe lì, finché non si scioglie da solo dopo giorni.

Quindi:
- il conto attuale del professionista si legge **una volta sola**, quando si crea il pagamento, e si **fotografa** nella riga (la stessa idea del prezzo fotografato, D-011);
- da lì in poi ogni operazione (incasso, annullamento, far scadere una sessione) parte dalla riga del pagamento: `accounts.of(payment)`.

**Come renderla difficile da sbagliare.** Le regole scritte nei commenti si dimenticano; le regole scritte nella **forma del codice** no. Proposta:
- il componente che parla con Stripe (pezzo 2) riceve il conto come **parametro obbligatorio** di ogni metodo, `capture(PaymentAccount account, String paymentIntentId, ...)`: non esiste un "conto di default" né un "conto del professionista" dentro di lui;
- il registro dei conti (`PaymentAccounts`) ha due metodi con nomi che dicono quando si usano: `forNewPayment(practitionerId)` e `of(Payment payment)`. Chi scrive l'incasso trova pronto `of(payment)`, e il nome stesso gli ricorda la regola.

Il test completo (cambio di conto tra blocco e incasso, e tra blocco e annullamento) richiede l'incasso vero: arriva al **pezzo 4**. In questo pezzo si prova la metà che c'è già: la riga conserva il suo conto anche dopo la riassegnazione.

---

## Parte 9 — Le API dell'admin

### 9.1 Nel modulo `payment`, sotto `/api/admin`

Come per D-052: l'**area** (`/api/admin/**`, solo admin) è una questione di sicurezza, già risolta da `SecurityConfiguration`; il **modulo** è una questione di chi possiede i dati. L'assegnazione è di `payment`, quindi il controller sta in `payment`, anche se l'indirizzo parla di professionisti.

```
GET  /api/admin/payment-accounts
     → 200 [{"name": "rossi", "practitionerIds": [1]}, {"name": "studio", "practitionerIds": []}]
GET  /api/admin/practitioners/{id}/payment-account
     → 200 {"account": "rossi", "assignedAt": "..."}  ·  404 se il professionista non esiste o non ha un conto
PUT  /api/admin/practitioners/{id}/payment-account   {"account": "rossi"}
     → 200 come sopra
```

**Mai le chiavi** nelle risposte, nemmeno all'admin, nemmeno in parte: la chiave serve al server, non a una persona.

### 9.2 Gli errori

- professionista inesistente → **404** (`NotFoundException`, la stessa per tutto, D-026);
- nome che non rispetta il formato → **400** (Bean Validation sul corpo della richiesta, `@Pattern`);
- nome valido ma **non configurato** → **409** `PAYMENT_ACCOUNT_UNKNOWN`: la richiesta è ben fatta, ma viola una regola che dipende dallo stato del sistema (D-027);
- assegnare lo stesso conto già assegnato → **200**, nessun cambiamento: idempotente, come la disattivazione (D-052). `assignedAt` non cambia.

Un professionista **disattivato** si può comunque assegnare? Proposta: sì. La disattivazione tocca solo l'account (D-052), e un professionista riattivato ritrova il suo conto.

### 9.3 Niente "togli il conto", per ora

Un `DELETE` servirebbe a sospendere i pagamenti online di un professionista. Nessuno l'ha chiesto, e ogni operazione in più è codice da provare: si aggiunge quando serve (lo stesso ragionamento di D-042).

### 9.4 Perché la vista dell'admin non è una sola

Sarebbe comodo vedere il conto dentro `AdminPractitionerView` (D-052). Ma quella vista la costruisce `practitioner`, che dovrebbe chiedere a `payment`: ciclo. Due chiamate dal frontend del pannello admin (passo 13), che le mette insieme, costano meno di un ciclo tra moduli.

---

## Parte 10 — I test

| Test | Cosa prova |
|---|---|
| `PaymentAccountTest` (unitario, senza Spring) | Nome valido e non valido; chiave e segreto con il prefisso giusto; `toString()` senza segreti |
| `PaymentAccountsTest` (unitario) | Elenco vuoto ammesso; nome ripetuto rifiutato; variabile mancante rifiutata con il suo nome nel messaggio; `live=false` con una chiave `_live_` rifiutato, e il contrario |
| `PaymentStartupCheckTest` (con database) | Conto assegnato non configurato → avvio fermo; conto di un pagamento aperto non configurato → avvio fermo; conto solo di pagamenti chiusi → avvio regolare |
| `PaymentSchemaTest` (con database) | I `check` della V10 (formato del nome, importo positivo, stati); unicità della sessione e del PaymentIntent; due `PENDING` per la stessa seduta rifiutati, due `EXPIRED` ammessi |
| `PaymentAccountAdminApiTest` (sessioni vere, `TestSessions`) | Assegnazione, riassegnazione, idempotenza; 404, 400, 409; nessuna chiave nelle risposte; un professionista o un cliente ricevono 403 |
| Un caso in `PaymentSchemaTest` o in un test del servizio | Un pagamento creato sul conto `rossi` resta su `rossi` dopo la riassegnazione a `studio` (la metà del test di D-057; l'altra al pezzo 4) |

**Come si fanno i conti nei test.** Il profilo dei test dichiara un paio di conti finti (`PAYMENT_ACCOUNTS=alpha,beta`, chiavi `rk_test_fake...`): nessuna chiamata parte verso Stripe in questo pezzo, quindi basta che le chiavi rispettino il formato. Dal pezzo 2 il componente che parla con Stripe sarà un'interfaccia, con un finto nei test.

---

## Parte 11 — Cosa c'è da decidere

| Scelta | Raccomandazione | Dove |
|---|---|---|
| Libreria | `stripe-java` 34.1.0, un `StripeClient` per conto; Gson lo decide Boot (2.13.2), da provare | parte 2 |
| Dichiarazione dei conti | `PAYMENT_ACCOUNTS=nome,nome` più `STRIPE_<NOME>_SECRET_KEY` e `STRIPE_<NOME>_WEBHOOK_SECRET`, letti dall'`Environment` | parte 3.3 |
| Nomi | `^[a-z][a-z0-9]{0,29}$`, mai rinominati | parte 3.4 |
| Tipo di chiave | Segreta o limitata accettate; limitata consigliata | parte 3.5 |
| Controlli all'avvio | Formato, prefissi, `payment.live` (default prova); conti assegnati e conti dei pagamenti aperti devono esistere; zero conti ammesso | parte 4 |
| Assegnazione | Tabella `practitioner_payment_account` nel modulo `payment` | parte 5 |
| Senza conto | 409 `CARD_PAYMENTS_UNAVAILABLE` prima di creare la prenotazione; il pannello in studio funziona | parte 6 |
| Tabella `payment` | Solo le colonne delle sedute, con `account`; cinque stati; un solo `PENDING` per seduta; pacchetti al passo 10 | parte 7 |
| Regola del conto | Fotografato alla creazione; ogni operazione da `payment.account()`; il conto come parametro obbligatorio verso Stripe | parte 8 |
| API dell'admin | Nel modulo `payment`; mai le chiavi; 404 / 400 / 409 `PAYMENT_ACCOUNT_UNKNOWN`; idempotente; niente `DELETE` | parte 9 |

Una migrazione nuova: **V10** (`practitioner_payment_account`, `payment`). `modello-dati.md` si aggiorna con le colonne vere. Dopo le tue scelte, una scheda nuova in `decisioni.md` (D-060).

---

## Parte 12 — Com'è andata (implementazione)

Costruito come raccomandato nella parte 11 (D-060), su richiesta di Nicholas del 2026-11-10. 67 test nuovi: 25 in `PaymentAccountTest`, 9 in `PaymentAccountsTest`, 12 in `PaymentSchemaTest`, 5 in `PaymentStartupCheckTest`, 10 in `PaymentAccountServiceTest`, 7 in `PaymentAccountAdminApiTest`.

**I file**, tutti nel nuovo modulo `payment`:

| File | Cosa fa |
|---|---|
| `V10__create_payment_accounts_and_payment.sql` | Le due tabelle, con i `check` sul nome e l'indice unico parziale |
| `PaymentAccount` | Il conto: un `record` che non può esistere sbagliato, `live()`, i nomi delle variabili, `toString()` senza segreti |
| `PaymentAccounts` | Il registro dei conti, costruito una volta da `from(nomi, live, variabili)`; una `SequencedMap` (Java 21) tiene l'ordine di `PAYMENT_ACCOUNTS` |
| `PaymentConfiguration` | Crea il registro dall'`Environment` e scrive nel log i nomi (mai le chiavi) e il modo |
| `PaymentStartupCheck` | Il controllo all'avvio sui conti assegnati e su quelli dei pagamenti aperti |
| `PaymentAccountAssignment` (+ repository) | L'assegnazione, idempotente |
| `Payment`, `PaymentStatus` (+ repository) | Il pagamento; `Payment.pending(...)` riceve un `PaymentAccount`, e `account` non è aggiornabile |
| `PaymentAccountService`, `PaymentAccountAdminController`, viste | `forNewPayment` per il pezzo 2, e l'API dell'admin |

Fuori dal modulo: `PractitionerService.requireExists`, `ErrorCode.PAYMENT_ACCOUNT_UNKNOWN`, `payment.accounts` e `payment.live` in `application.properties`, due conti finti (`alpha`, `beta`) nella configurazione dei test, la sezione in `.env.example`, `stripe-java` nel `pom.xml`.

**Verificato**: `mvnw dependency:tree` mostra `gson:2.13.2`, la versione di Boot, sotto `stripe-java:34.1.0`.

**Due errori nei test, corretti:**
- un valore di prova per "chiave sbagliata" era `sk_test_` (niente dopo il prefisso). Il test voleva che il messaggio non contenesse il valore; ma il messaggio, per aiutare, elenca i prefissi giusti, e `sk_test_` è proprio uno di quelli. Il codice era giusto, il test no: valore di prova cambiato in `sk_test_ abc`;
- un test d'API controllava la riga dell'assegnazione con una query SQL subito dopo la `PUT`. Ma il test gira dentro una transazione, e la riga ha un id assegnato da noi (`practitioner_id`), non generato: JPA rimanda l'`insert` al *flush*, cioè al commit, e la query SQL non la vede ancora. Con un id generato dal database (`identity`) l'`insert` parte subito, ed è per questo che gli altri test d'API non avevano mai incontrato il problema. Verifica fatta con la `GET` dell'API.

**I test sanno fallire.** Cinque difese tolte una alla volta:

| Tolto | Test che se ne accorge |
|---|---|
| `toString()` senza segreti | `PaymentAccountTest` |
| Il controllo prova/produzione | `PaymentAccountsTest` |
| Il controllo dei conti dei pagamenti aperti | `PaymentStartupCheckTest` |
| L'idempotenza dell'assegnazione | `PaymentAccountServiceTest` |
| Il controllo "conto configurato" all'assegnazione | `PaymentAccountAdminApiTest` |

**Rimandato**: il 409 `CARD_PAYMENTS_UNAVAILABLE` arriva al pezzo 2, insieme all'interfaccia di `booking` che crea la pagina di pagamento; la libreria di Stripe per ora è solo nel `pom.xml`.

---

## Domande da colloquio

### Domanda 1: Come gestisci i segreti di più conti di un servizio esterno in un'applicazione Spring Boot?

**Risposta modello.** Fuori dal repository, da variabili d'ambiente, con un controllo all'avvio che ferma l'applicazione se manca qualcosa o è nel formato sbagliato: meglio un avvio fallito che un errore alla prima richiesta di un cliente. Li tengo in oggetti immutabili creati una volta (un client per conto, non una chiave statica condivisa), e mi assicuro che non finiscano nei log: per esempio un `record` genera `toString()` con tutti i campi, quindi va riscritto. Aggiungo un interruttore "prova o produzione" che deve corrispondere al prefisso delle chiavi, con default "prova".

### Domanda 2: Perché hai rinunciato a Stripe Connect?

**Risposta modello.** Non per i costi: con Stripe che fattura direttamente i conti collegati, Connect non costa niente alla piattaforma. Ma con Connect qualcuno diventa la piattaforma, con un contratto e delle responsabilità verso i conti collegati, e va costruita la registrazione dei conti nel sistema. Per due professionisti che possono aprire un conto da soli è complessità senza beneficio; un registro di conti configurati, con un'assegnazione per professionista, copre sia due conti sia un conto comune.

### Domanda 3: L'admin cambia il conto di un professionista mentre ci sono pagamenti in corso. Cosa può andare storto e come lo eviti?

**Risposta modello.** Un pagamento bloccato su un conto esiste solo su quel conto: se l'incasso usasse il conto attuale, chiederebbe a Stripe un PaymentIntent che lì non esiste. Quindi fotografo il conto nella riga del pagamento alla creazione, e ogni operazione successiva parte da lì. Lo rendo difficile da sbagliare passando il conto come parametro obbligatorio al client di Stripe, e lo provo con un test che riassegna il conto tra il blocco e l'incasso. All'avvio controllo anche che i conti dei pagamenti aperti siano ancora configurati.

### Domanda 4: Due moduli hanno bisogno l'uno dell'altro. Come rompi il ciclo?

**Risposta modello.** Con l'inversione delle dipendenze: il modulo che esprime la regola dichiara un'interfaccia con le sue parole, quello dei dettagli tecnici la implementa. Qui `booking` deve sapere se un professionista può incassare, ma `payment` dipende già da `booking`; quindi `booking` dichiara l'interfaccia e `payment` la implementa. Per le viste aggregate, come un elenco dell'admin con professionisti e conti, preferisco due chiamate dal frontend a una dipendenza circolare.

### Domanda 5: Cos'è un indice unico parziale, e quando lo useresti?

**Risposta modello.** È un indice `unique` con un `where`: l'unicità vale solo per le righe che rispettano la condizione. Serve quando una regola vale solo in uno stato: qui "al massimo un pagamento in attesa per prenotazione", mentre i pagamenti scaduti possono essere quanti si vuole. Così la regola la garantisce il database anche con due richieste contemporanee, cosa che un controllo in Java prima dell'`insert` non può fare.

### Domanda 6: Una libreria esterna chiede una versione di una dipendenza e Spring Boot ne gestisce un'altra. Quale vince e come lo verifichi?

**Risposta modello.** Con il parent di Spring Boot vince la versione del *dependency management* di Boot, anche sulle dipendenze transitive: qui Gson 2.13.2 invece del 2.10.1 chiesto da stripe-java. Lo vedo con `mvnw dependency:tree`, che segna la versione gestita. Se sono della stessa serie compatibile va bene, ma lo provo con test che usano davvero la libreria; se non lo fossero, si fissa la versione nel `pom.xml` sapendo che si va contro Boot.

---

## Documentazione ufficiale

- Stripe, prezzi di Connect: https://stripe.com/connect/pricing
- Stripe, creare una sessione Checkout (`expires_at`, `payment_intent_data`): https://docs.stripe.com/api/checkout/sessions/create
- Stripe, chiavi API e chiavi limitate: https://docs.stripe.com/keys
- stripe-java: https://github.com/stripe/stripe-java
- Maven Central, stripe-java: https://central.sonatype.com/artifact/com.stripe/stripe-java
- Spring Boot, binding da variabili d'ambiente (e le mappe): https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding.environment-variables
- PostgreSQL, indici parziali: https://www.postgresql.org/docs/17/indexes-partial.html
