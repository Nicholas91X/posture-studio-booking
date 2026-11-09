# Notifiche email — guida di studio

Questa guida accompagna il **pezzo 4 del passo 8**. Dal passo 7 la prenotazione annuncia cosa le succede con cinque eventi di dominio (`BookingEvent`), ma nessuno li ascolta: un cliente paga, il professionista non lo sa; il professionista approva, il cliente non lo sa. Questo pezzo aggancia le email a quegli eventi, senza che il modulo `booking` sappia che le email esistono.

```
richiesta pagata (MANUAL)  → professionista: "Nuova richiesta da approvare"   cliente: "Richiesta inviata"
confermata                 → cliente: "Prenotazione confermata"
rifiutata                  → cliente: "Prenotazione non confermata"
scaduta senza risposta     → cliente: "Prenotazione non confermata"
ritirata dal cliente       → cliente: "Richiesta ritirata"
```

Teoria di riferimento: D-039 (eventi senza ascoltatori al passo 7, email e push al passo 8), D-046 (eventi come `record` di una `sealed interface`, pubblicati dentro la transazione, ascoltati dopo il commit; *outbox* rimandata), D-032 (il nome del cliente non compare dove lo vede chiunque), D-002 e D-026 (tra moduli solo id e servizi pubblici), D-050 (Mailpit in sviluppo, guida 24 parte 7). La regola "dopo il commit" è spiegata nella guida 20, parte 4: qui la si usa.

---

## Indice

- Parte 1 — Cosa c'è già, e cosa manca
- Parte 2 — Prima scelta: chi riceve cosa
- Parte 3 — Seconda scelta: spedire subito o in un altro thread
- Parte 4 — I *virtual thread* (Java 21)
- Parte 5 — Terza scelta: dove prende i dati l'email
- Parte 6 — Il testo: cosa c'è dentro, e cosa no
- Parte 7 — Quando una spedizione fallisce
- Parte 8 — Il codice: un ascoltatore, una funzione pura, uno `switch` senza `default`
- Parte 9 — I test
- Parte 10 — Cosa c'è da decidere
- Parte 11 — Com'è andata (implementazione)
- Domande da colloquio

---

## Parte 1 — Cosa c'è già, e cosa manca

**C'è:**
- `BookingEvent`, una `sealed interface` con cinque `record`: `ApprovalRequested`, `BookingConfirmed`, `BookingRejected`, `ApprovalExpired`, `BookingWithdrawn`. Ognuno porta id e istanti: prenotazione, professionista, cliente, inizio; `ApprovalRequested` anche la scadenza della risposta.
- `BookingService` li pubblica con `ApplicationEventPublisher` **dentro** la transazione, nel punto in cui la transizione avviene. Anche il lavoro programmato che chiude le richieste scadute (guida 21) pubblica `ApprovalExpired`.
- Un test (`BookingAfterCommitTest`) dimostra già la regola di `@TransactionalEventListener`: l'evento arriva dopo un commit riuscito, e sparisce con un *rollback*.
- Il modulo `notification` con la prima email (il codice di accesso, `EmailLoginCodeSender`), Spring Mail configurato da variabili d'ambiente, timeout di 5 secondi, Mailpit in `infra/docker-compose.dev.yml`.

**Manca:**
- chi ascolta gli eventi;
- i dati che un'email deve contenere e che l'evento non ha: l'indirizzo del destinatario, i nomi, il nome del servizio;
- i testi;
- cosa fare quando il server di posta non risponde.

---

## Parte 2 — Prima scelta: chi riceve cosa

### 2.1 Le cinque domande

Per ogni evento: chi ha bisogno di saperlo, e cosa deve fare dopo?

| Evento | Chi lo deve sapere | Perché |
|---|---|---|
| `ApprovalRequested` | **professionista** | Deve rispondere entro la scadenza, o la richiesta si annulla (D-030). È l'email più importante del sistema |
| `ApprovalRequested` | **cliente** | Ha pagato e non ha ancora una prenotazione: deve sapere che l'importo è solo bloccato, entro quando avrà risposta, e che può ritirare |
| `BookingConfirmed` | **cliente** | La conferma. Arriva anche quando la prenotazione la fa il professionista dal pannello (D-048): il cliente può non avere mai aperto il sito |
| `BookingRejected` | **cliente** | Non ha la seduta, e non ha pagato niente |
| `ApprovalExpired` | **cliente** | Come il rifiuto, con un altro motivo |
| `BookingWithdrawn` | **cliente** | L'ha fatto lui, ma la ricevuta "nessun addebito" toglie il dubbio. `architettura.md` la prevede già |

### 2.2 Cosa resta fuori, apposta

- **Il professionista non riceve email per le conferme.** In approvazione automatica (D-030) ogni prenotazione arriverebbe anche a lui: una ventina al giorno, che smette di leggere. In manuale l'ha appena approvata lui; dal pannello l'ha fatta lui. L'agenda del pannello è il posto dove guardare. Se servirà un avviso "nuova prenotazione" in automatico, sarà una push (pezzo 5), più leggera di un'email.
- **Il professionista non riceve email per ritiri e scadenze.** Una richiesta in meno da guardare non è un'azione da fare. Anche qui, se servirà, una push al pezzo 5.
- **Il pagamento arrivato tardi** (`architettura.md`, "pagamento arrivato tardi": il blocco si annulla e il cliente riceve un'email). Oggi `paymentAuthorized` su una prenotazione scaduta non pubblica nessun evento, e il pagamento è simulato. Lo si aggiunge al passo 9, quando il pagamento è vero e quel caso può succedere davvero.

**Raccomandazione: le sei email della tabella 2.1, nient'altro.** Ogni email in più è una che il destinatario impara a ignorare.

---

## Parte 3 — Seconda scelta: spedire subito o in un altro thread

### 3.1 Il punto di partenza

Un ascoltatore `@TransactionalEventListener` riceve l'evento dopo il commit. Fin qui è deciso (D-046). La domanda nuova è: **chi** lo esegue? Per default lo esegue lo stesso thread che ha fatto il commit, cioè il thread della richiesta HTTP (o del lavoro programmato), prima che il metodo del controller restituisca la risposta.

### 3.2 Strada A — Nello stesso thread, subito dopo il commit

```java
@TransactionalEventListener
public void on(BookingEvent event) { ... mail.send(...) ... }
```

È la più semplice, e nei test tutto è deterministico: quando `approve` restituisce, l'email è già partita. Ma:

- **la risposta aspetta il server di posta.** In condizioni normali qualche decina di millisecondi. Se il server non risponde, ogni email aspetta il suo timeout (5 secondi): il professionista che approva aspetta 5 secondi una risposta che non dipende da lui, e per una richiesta pagata (due email) il doppio.
- **un'eccezione dell'ascoltatore arriva al chiamante.** Lo dice la documentazione di `TransactionSynchronization.afterCommit`: un errore in quella fase *viene propagato al chiamante*. Il commit è già avvenuto, la prenotazione è confermata, ma il controller risponde **500**. Il frontend dice "errore", l'utente riprova, e trova un 409 perché la prenotazione è già confermata. Si evita catturando tutto nell'ascoltatore, ma basta dimenticarlo una volta.
- **il lavoro programmato aspetta.** I lavori girano uno dopo l'altro su un solo thread (D-047): dieci richieste scadute con il server di posta fermo sono 50 secondi in cui nessun altro lavoro parte.
- **la transazione è chiusa ma le sue risorse no.** Spring avverte che in `AFTER_COMMIT` la connessione può essere ancora legata al thread: un ascoltatore che scrive nel database ha bisogno di `REQUIRES_NEW` (D-046, conseguenze). Qui non si scrive, ma è una trappola in più.

### 3.3 Strada B — In un altro thread: `@Async`

```java
@Async("notificationExecutor")
@TransactionalEventListener
public void on(BookingEvent event) { ... }
```

Dopo il commit Spring non esegue il metodo: lo **consegna** a un *executor* (un oggetto che esegue compiti in altri thread) e torna subito. La combinazione delle due annotazioni è prevista dalla documentazione di Spring.

- La risposta HTTP non aspetta la posta, e un errore di posta non la tocca mai: sta in un altro thread.
- Il lavoro programmato non aspetta.
- L'ascoltatore parte con un thread pulito, senza nessuna transazione legata: per leggere i dati chiama i servizi pubblici degli altri moduli, ognuno con la sua transazione di sola lettura.

**Cosa costa:**
- l'email parte **qualche millisecondo dopo** la risposta. Per una notifica non conta.
- **nei test** bisogna aspettare: l'email non c'è ancora quando `approve` restituisce. Si usa **Awaitility** (già dentro `spring-boot-starter-test`), che riprova un'asserzione finché passa o finché scade un tempo massimo. Mai `Thread.sleep`: o è troppo corto (test instabile) o troppo lungo (test lento).
- l'email vive **solo in memoria** tra il commit e la spedizione. Se l'applicazione si ferma in quell'istante, si perde. Per uno spegnimento ordinato si chiede all'executor di aspettare i compiti in corso (parte 4.3); per un crash no.

### 3.4 Strada C — Una tabella delle email da spedire (*outbox*)

Dentro la stessa transazione della prenotazione si scrive una riga "email da spedire"; un lavoro programmato le legge e le spedisce, e segna quelle andate. Niente si perde: né per un crash, né per il server di posta fermo un'ora (si riprova al giro dopo).

È la soluzione robusta, ed è quella che D-046 ha rimandato: una migrazione, una tabella, un lavoro, la gestione dei tentativi, e la pulizia delle righe vecchie (che contengono indirizzi email: dato personale, come gli IP della guida 24). Per una trentina di email al giorno è molto lavoro per proteggere un caso raro.

### 3.5 Raccomandazione: B

Il criterio del brief è "un invio fallito non annulla la prenotazione". A lo rispetta solo se nessuno dimentica un `catch`; B lo rispetta **per costruzione**, perché l'invio vive in un altro thread. C resta la strada se le email perse diventano un problema reale: lo diranno i log (parte 7) dopo la messa online.

**Nessun nuovo tentativo automatico.** Riprovare tre volte dopo qualche secondo copre un singhiozzo della rete, ma non un fornitore fermo per mezz'ora; il caso vero lo copre solo C. Per la richiesta da approvare, il secondo canale arriva al pezzo 5 (la push).

---

## Parte 4 — I *virtual thread* (Java 21)

### 4.1 Il problema che risolvono

Un thread "classico" di Java è un thread del sistema operativo: costa memoria (circa 1 MB di stack riservato) e il sistema ne gestisce bene qualche migliaio, non milioni. Per questo i server usano un **pool**: un numero fisso di thread riusati.

Ma un thread che spedisce un'email passa quasi tutto il tempo ad **aspettare** la rete. Mentre aspetta, occupa il suo posto nel pool senza fare niente.

Un **virtual thread** (Java 21, JEP 444) è un thread gestito dalla JVM invece che dal sistema operativo. Quando si blocca su un'operazione di rete, la JVM lo "parcheggia" e usa il thread vero sottostante per un altro virtual thread. Costa poche centinaia di byte, se ne possono creare milioni, e il codice resta quello normale e sequenziale: niente callback, niente `CompletableFuture`.

**Quando convengono:** compiti che aspettano molto (rete, database, file) e calcolano poco. **Quando no:** calcoli pesanti sulla CPU, dove il numero di core è il limite vero e i virtual thread non aggiungono niente.

Un'email è il caso da manuale: si apre una connessione, si aspetta il server, si chiude. Con i virtual thread non serve dimensionare un pool: un thread nuovo per ogni compito, buttato alla fine.

### 4.2 Un executor solo per le notifiche, non i virtual thread ovunque

Spring Boot ha un interruttore globale, `spring.threads.virtual.enabled=true`, che passa ai virtual thread **tutto**: Tomcat, l'executor di default, e lo *scheduler* dei lavori programmati. È una scelta che si può fare, ma non in questo pezzo, perché cambia due cose che con le email non c'entrano:

- lo scheduler con i virtual thread esegue ogni lavoro in un thread suo: `BookingJobs` e `LoginCodeJobs` potrebbero girare **insieme**, mentre oggi `SchedulingConfiguration` dice che girano uno dopo l'altro. Sono sicuri anche insieme (D-047, una transazione per richiesta, pensati per più istanze), ma è un'affermazione da riverificare, non da cambiare di nascosto;
- Tomcat con i virtual thread cambia il comportamento sotto carico di tutte le API.

**Proposta:** un executor dedicato, che crea un virtual thread per ogni compito.

```java
@Bean(NOTIFICATION_EXECUTOR)
SimpleAsyncTaskExecutor notificationExecutor() {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("notification-");
    executor.setVirtualThreads(true);                       // Spring 6.1+, su Java 21
    executor.setTaskTerminationTimeout(10_000);             // allo spegnimento aspetta fino a 10 s
    return executor;
}
```

`SimpleAsyncTaskExecutor` non ha un pool: un thread nuovo per ogni compito. Con i thread classici sarebbe uno spreco; con i virtual thread è esattamente il modo d'uso previsto ("non mettere in pool i virtual thread").

### 4.3 Lo spegnimento

Quando l'applicazione si ferma (un nuovo rilascio, al passo 14), Spring chiude i suoi componenti. Con `setTaskTerminationTimeout` l'executor aspetta fino a 10 secondi i compiti in corso: un'email partita un istante prima dello spegnimento arriva. Oltre i 10 secondi, o con un crash, si perde (parte 3.3).

### 4.4 `@EnableAsync`

`@Async` funziona solo se un `@Configuration` dice `@EnableAsync`: come `@Transactional`, è un *proxy* (guida 6) che intercetta la chiamata e la passa all'executor. Conseguenza: il metodo `@Async` deve essere chiamato **da fuori** il bean, mai da un altro metodo della stessa classe (la stessa trappola dell'auto-invocazione della guida 26, parte 2.6). Qui lo chiama Spring, che consegna l'evento: va bene.

Il nome dell'executor in `@Async("notificationExecutor")` evita ambiguità: se un giorno ci saranno altri executor, ognuno sa il suo.

---

## Parte 5 — Terza scelta: dove prende i dati l'email

### 5.1 Cosa serve, e cosa porta l'evento

Un'email di conferma dice: a chi (l'indirizzo del cliente), come salutarlo (il nome), quale seduta (il nome del servizio), quando (data e ora a Roma), con chi (il nome del professionista). L'evento porta: id della prenotazione, del professionista, del cliente, l'istante di inizio. Mancano tutti i nomi, gli indirizzi, e anche l'id del servizio.

### 5.2 Strada A — Mettere tutto nell'evento

`BookingConfirmed(bookingId, ..., customerEmail, customerFirstName, serviceName, practitionerName)`. L'ascoltatore non chiede niente a nessuno.

Ma `booking` dovrebbe raccogliere dati che non sono suoi (D-026) solo per chi ascolta, dentro la transazione della prenotazione, anche quando nessuno li usa. E D-046 ha deciso eventi con soli id e istanti, proprio perché un evento con il nome del cliente arriverebbe anche alla push (D-032).

### 5.3 Strada B — Leggere le tabelle direttamente da `notification`

Una query con le `join` tra `booking`, `customer`, `app_user`, `service`, `practitioner`. Una sola query, ma `notification` conoscerebbe le tabelle di cinque moduli: è esattamente ciò che D-002 vieta.

### 5.4 Strada C — L'id del servizio nell'evento, il resto chiesto ai moduli

- **`serviceId` entra nell'evento.** È un id, quindi resta dentro la regola di D-046, ed è un fatto della prenotazione: lo sa `booking`. È l'unica modifica a `booking` di questo pezzo, e non aggiunge dipendenze. D-039 diceva "senza toccare il modulo delle prenotazioni": si tocca il contenuto di un evento, non il suo comportamento. Lo registro nella scheda.
- Ogni modulo risponde per il suo pezzo, con un `record`:
  - `CustomerService.contactOf(customerId)` → `CustomerContact(email, firstName, lastName)`. `customer` chiede l'email ad `auth`, come fa già per il profilo;
  - `PractitionerService.contactOf(practitionerId)` → `PractitionerContact(email, firstName, lastName)`, con l'email chiesta ad `auth` allo stesso modo;
  - `CatalogService.serviceNameOf(serviceId)` → il nome del servizio, **anche se nel frattempo è stato disattivato**: una seduta già prenotata resta valida (D-027 vale per le nuove prenotazioni).

Sono tre o quattro query per evento. Per una trentina di email al giorno non conta, e in un altro thread non rallenta nessuno.

**Le dipendenze restano in un verso solo:** `notification` → `booking`, `customer`, `practitioner`, `catalog`, `auth`. Nessuno dipende da `notification`.

### 5.5 Raccomandazione: C

### 5.6 Un dato letto dopo è un dato di dopo

L'ascoltatore legge i nomi **dopo** il commit, in un altro momento: se il cliente ha cambiato nome un secondo dopo, l'email ha il nome nuovo. Per una notifica va benissimo: è il nome giusto. Il caso che conta è un altro: lo stato della prenotazione. L'email dice cosa è successo **secondo l'evento** (confermata), non rilegge lo stato attuale, che nel frattempo potrebbe essere cambiato. È corretto: l'evento racconta un fatto avvenuto, e quel fatto non cambia.

---

## Parte 6 — Il testo: cosa c'è dentro, e cosa no

### 6.1 L'oggetto si vede sullo schermo bloccato

Come per il codice di accesso (guida 24), l'oggetto di un'email compare nelle notifiche del telefono. Quindi nell'**oggetto** mai nomi di persone e mai il tipo di trattamento; la data sì, perché è ciò che serve per riconoscere l'email al volo.

- "Prenotazione confermata: giovedì 12 novembre, 9:30" ✅
- "Maria Rossi: Valutazione posturale confermata" ❌

### 6.2 L'email al professionista non contiene il nome del cliente

L'email "nuova richiesta da approvare" è anche la **riserva** della push del pezzo 5 (D-032), e D-032 vuole quel testo senza il nome del cliente. La stessa regola, per coerenza: servizio, data, ora, scadenza, e il link al pannello. Chi è il cliente lo dice il pannello, dopo l'accesso. È anche *minimizzazione* (GDPR): una casella di posta è un posto meno protetto del pannello.

### 6.3 L'email al cliente

A lui si scrive alla sua casella, quindi nel **corpo** ci sono il suo nome, il servizio, il professionista, data e ora. E le cose che deve sapere per agire: entro quando avrà risposta, che l'importo è solo bloccato, che può ritirare la richiesta senza costi, dove vedere le sue prenotazioni.

Una frase onesta sul blocco della carta: annullarlo è immediato per noi, ma la banca può impiegare qualche giorno a mostrarlo nell'estratto conto. Dirlo evita una telefonata allo studio.

### 6.4 Data e ora a Roma, in italiano

Gli istanti sono in UTC (CLAUDE.md, "Date e ore"). Per mostrarli:

```java
DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, 'ore' H:mm", Locale.ITALIAN)
        .format(startsAt.atZone(StudioTime.ZONE));       // giovedì 12 novembre 2026, ore 9:30
```

- `atZone(Europe/Rome)` sceglie da solo +01:00 o +02:00 secondo la data: un test con una data di luglio lo verifica (ora legale).
- `Locale.ITALIAN` dà i nomi dei giorni e dei mesi in italiano, **indipendentemente dalla lingua del server**: su un server Hetzner configurato in inglese, senza `Locale` esplicito, uscirebbe "Thursday".
- `DateTimeFormatter` è immutabile e *thread-safe*: una costante `static final` condivisa da tutti i virtual thread va bene (non era così per il vecchio `SimpleDateFormat`).

### 6.5 Testo semplice, con i *text block*

Come il codice di accesso: testo semplice, scritto con i *text block* di Java 15 e `formatted(...)`. Un'email HTML con il logo dello studio richiede un motore di *template* (Thymeleaf) e una versione testo per chi non vede l'HTML: si potrà aggiungere quando lo studio avrà un'identità grafica, senza toccare chi decide cosa spedire.

### 6.6 Il link

Ogni email ha un link al sito: il cliente alle sue prenotazioni, il professionista al pannello. Gli indirizzi delle pagine li decidono i passi 12 e 13; oggi si conosce solo l'indirizzo base. Una proprietà nuova, `notification.app-url=${APP_URL:http://localhost:3000}` (la porta di default di Next.js), e per ora il link è l'indirizzo base. Una nota in `stato.md` ricorda di metterci la pagina giusta.

---

## Parte 7 — Quando una spedizione fallisce

### 7.1 Ogni email per conto suo

`ApprovalRequested` produce due email. Se quella al professionista fallisce, quella al cliente deve partire lo stesso: ogni spedizione ha il suo `try`/`catch`.

### 7.2 Cosa si scrive nel log

Un errore si registra a livello `ERROR`, con: il tipo di evento, l'id della prenotazione, il ruolo del destinatario ("al professionista"). **Mai l'indirizzo email**: è un dato personale, e i log li leggono altre persone e altri sistemi, li si conserva a lungo. Con l'id della prenotazione si risale a tutto il resto, se serve.

Lo stesso se fallisce la **lettura** dei dati (un cliente che non esiste più, un errore del database): l'email non parte, e il log lo dice.

### 7.3 Cosa non si fa

- Non si rilancia l'eccezione. In un metodo `@Async` che restituisce `void` finirebbe a un gestore di Spring che la scrive nel log: lo stesso risultato, con un messaggio meno chiaro.
- Non si cambia lo stato della prenotazione. Un'email persa non rende falsa una conferma.

---

## Parte 8 — Il codice: un ascoltatore, una funzione pura, uno `switch` senza `default`

### 8.1 Due classi, due mestieri

| Classe | Cosa fa | Come si prova |
|---|---|---|
| `BookingNotifications` | Ascolta gli eventi, raccoglie i dati dai moduli, fa comporre le email, le spedisce, registra gli errori | Con Spring, il database e un `MailSender` finto |
| `BookingEmails` | Dato un evento e i dati, restituisce le email da spedire: destinatario, oggetto, testo. Non legge niente e non spedisce niente | Senza Spring: è una **funzione pura**, come `FreeSlots` (D-035) |

La parte con più casi (sei email, date, ora legale, cosa c'è e cosa non c'è nel testo) è anche la più facile da provare, perché non tocca né database né rete.

### 8.2 Lo `switch` con il *pattern matching*

```java
List<OutgoingEmail> emailsFor(BookingEvent event, BookingFacts facts) {
    return switch (event) {
        case ApprovalRequested requested -> List.of(toPractitioner(requested, facts), toCustomer(requested, facts));
        case BookingConfirmed confirmed -> List.of(confirmed(confirmed, facts));
        case BookingRejected rejected -> List.of(notConfirmed(rejected, facts, "..."));
        case ApprovalExpired expired -> List.of(notConfirmed(expired, facts, "..."));
        case BookingWithdrawn withdrawn -> List.of(withdrawn(withdrawn, facts));
    };
}
```

- `case ApprovalRequested requested` controlla il tipo **e** dà un nome alla variabile già convertita: niente `instanceof` seguito da un *cast* (Java 21, JEP 441).
- **Nessun `default`.** `BookingEvent` è `sealed`: il compilatore conosce tutti i casi, e uno `switch` che li copre tutti è completo. Se domani si aggiunge un evento (es. `BookingCancelled` al passo 11), **questo `switch` non compila più** finché qualcuno non decide quali email manda. Con un `default` compilerebbe, e il nuovo evento passerebbe in silenzio senza email.
- Esiste anche la forma che **scompone** il `record` (*record pattern*): `case ApprovalRequested(long id, long p, long c, long s, Instant start, Instant deadline) ->`. Con sei componenti si legge peggio del nome della variabile, e servono solo due campi. Da Java 22 si possono scartare con `_` quelli che non servono (*unnamed variables*, JEP 456); su Java 21 no. Qui la forma con il nome è la più chiara.

### 8.3 `MailSender` invece di `JavaMailSender`

`BookingNotifications` riceve l'interfaccia `MailSender` di Spring, che ha un solo metodo che ci serve: `send(SimpleMailMessage)`. `JavaMailSender` la estende con i messaggi MIME (allegati, HTML), che qui non servono. Il vantaggio è nei test: un `MailSender` finto si scrive in dieci righe, come il `RecordingLoginCodeSender` della guida 24.

---

## Parte 9 — I test

| # | Test | Cosa prova |
|---|---|---|
| 1 | `BookingEmailsTest` (senza Spring) | Per ogni evento, le email giuste: destinatari, oggetto, cose che il testo deve dire |
| 2 | | La data a Roma in italiano, anche con l'ora legale (una data di luglio) |
| 3 | | Il nome del cliente non è mai nell'email al professionista; nessun nome negli oggetti |
| 4 | `BookingNotificationsTest` (Spring, commit veri, `MailSender` finto) | Richiesta pagata in manuale: due email, una al professionista e una al cliente |
| 5 | | Approvazione, rifiuto, ritiro, scadenza (con il `MovableClock`), prenotazione dal pannello: ognuno la sua email |
| 6 | | **Un invio fallito non annulla niente**: la prenotazione resta confermata e l'altra email dello stesso evento parte |
| 7 | | La risposta non aspetta la posta: l'email parte in un thread `notification-`, che è un virtual thread |
| 8 | | Una transazione annullata non manda email |
| 9 | `MailpitDeliveryTest` (Spring, Mailpit in un container) | Un'email vera via SMTP arriva a Mailpit, con mittente, destinatario e lettere accentate giuste (UTF-8) |
| 10 | test dei servizi | `contactOf` di cliente e professionista, `serviceNameOf` anche di un servizio disattivato |

**I test esistenti non cambiano.** I test d'API con `@Transactional` annullano la transazione alla fine: gli ascoltatori dopo il commit non partono, e nessuna email viene tentata. I test `@DataJpaTest` di `booking` non contengono `notification`. Per questo i test nuovi sono in classi proprie, **senza** `@Transactional`, con i dati cancellati a mano alla fine (come `BookingAfterCommitTest`).

**Il test con Mailpit** usa un container in più (Testcontainers, la stessa immagine `axllent/mailpit:v1.33` di `infra/docker-compose.dev.yml`) e legge le email ricevute con l'API HTTP di Mailpit. È l'unico che prova la spedizione vera: codifica dei caratteri, intestazioni, configurazione di Spring Mail. È anche il criterio del brief "email in Mailpit per ogni evento", provato in automatico invece che a mano.

---

## Parte 10 — Cosa c'è da decidere

| Scelta | Raccomandazione | Dove |
|---|---|---|
| Chi riceve cosa | Sei email: al professionista solo "da approvare", al cliente una per ogni evento | parte 2 |
| Stesso thread o altro thread | `@Async` dopo il commit, senza *outbox* e senza nuovi tentativi | parte 3 |
| Quali thread | Un executor dedicato con virtual thread, non l'interruttore globale | parte 4 |
| Dove prendere i dati | `serviceId` nell'evento; nomi e indirizzi chiesti ai moduli (`contactOf`, `serviceNameOf`) | parte 5 |
| Testi | Testo semplice; oggetti senza nomi; nessun nome del cliente al professionista; date a Roma in italiano; link all'indirizzo base (`APP_URL`) | parte 6 |
| Errori | Ogni email per conto suo; log `ERROR` senza indirizzi | parte 7 |
| Struttura | Ascoltatore + funzione pura; `switch` senza `default`; `MailSender` | parte 8 |

Nessuna di queste scelte cambia lo schema del database: nessuna migrazione.

---

## Parte 11 — Com'è andata (implementazione)

Costruito come raccomandato nella parte 10 (D-053). 559 test verdi con `mvnw clean test`, 25 nuovi: 8 in `BookingEmailsTest`, 10 in `BookingNotificationsTest`, 1 in `MailpitDeliveryTest`, 6 nei test dei servizi. Nessuna migrazione.

**I test sanno fallire.** Ho tolto una difesa alla volta e rilanciato i test:

| Tolto | Test che se ne accorge |
|---|---|
| `@TransactionalEventListener` (al suo posto `@EventListener`) | `aRolledBackApprovalSendsNothing`: l'email di una transazione annullata parte lo stesso |
| `@Async` | `theEmailsLeaveFromVirtualThreadsOfTheirOwn` |
| i virtual thread (`setVirtualThreads(false)`) | lo stesso test |
| il `try` per ogni email (un `return` dopo il primo errore) | `aRefusedEmailUndoesNothingAndDoesNotStopTheOther`: l'email al cliente non parte |
| il fuso di Roma (UTC al suo posto) | 3 test di `BookingEmailsTest` |
| la regola "niente nome del cliente al professionista" | 2 test di `BookingEmailsTest` |
| `Locale.ITALIAN` | 6 test di `BookingEmailsTest`, ma solo dopo la modifica al `pom.xml` (sotto) |

Un dettaglio istruttivo: senza `@Async` **solo** il test del thread fallisce. Tutti gli altri restano verdi, perché il `try`/`catch` per singola email protegge anche nello stesso thread: è la strada A della parte 3.2 fatta bene. Quello che la strada B aggiunge (la risposta non aspetta la posta) lo vede solo il test che guarda il thread.

**Errore 1: un finto `MailSender` che ha spento quello vero.** Il primo `RecordingMailSender` implementava `MailSender`. Il contesto non partiva: "No qualifying bean of type `JavaMailSender`", chiesto da `EmailLoginCodeSender`, che con le email delle prenotazioni non c'entrava. Il motivo: l'autoconfigurazione della posta di Spring Boot crea il suo `JavaMailSender` solo se **non esiste già un `MailSender`** (`@ConditionalOnMissingBean`). Vedendo il finto, si è tirata indietro. Corretto facendo estendere al finto `JavaMailSenderImpl`: è un `JavaMailSender` completo e sostituisce quello vero per tutti. Un bean di test non si aggiunge soltanto: può cambiare quali autoconfigurazioni scattano.

**Errore 2 (mio): un totale credibile da un comando mai partito.** La prima suite completa aveva un percorso del log sbagliato: la shell ha rifiutato il comando prima di lanciare Maven, e il conteggio successivo ha sommato i report rimasti in `target` dalle esecuzioni precedenti, dando un totale plausibile. Me ne sono accorto perché mancava la riga di Maven. Da allora stampo anche il codice di uscita. È lo stesso errore dello script delle mutazioni del pezzo 3: un risultato vale solo insieme alla prova che il comando sia girato.

**Errore 3: un test che sul PC di sviluppo non poteva fallire.** La JVM di questo PC è in italiano (`user.language=it`). Togliere `Locale.ITALIAN` lasciava tutti i test verdi, ma sul server, un'immagine Docker in inglese, le email avrebbero detto "Thursday 12 November". Ora Surefire lancia i test con `-Duser.language=en -Duser.country=US -Duser.timezone=UTC`, come il server, e la stessa mutazione fa fallire 6 test. La suite completa è verde anche così: nessun altro punto del codice dipendeva dalla lingua o dal fuso del PC. Controllato che l'impostazione sia applicata davvero: gli orari del log dei test finiscono con `Z`.

**Una precisazione sul log.** Il messaggio scritto da `BookingNotifications` non contiene l'indirizzo. Lo *stack trace* allegato, però, contiene il messaggio dell'eccezione del server di posta, e un rifiuto SMTP può citare l'indirizzo ("550 ... <maria@...>: recipient rejected"). È lo stesso compromesso del pezzo 1: senza lo *stack trace* un errore di posta non si capisce. Se al passo 14 i log andranno in un sistema esterno, se ne riparla.

**Visto nel log dei test.** L'unico errore di spedizione della suite è quello provocato apposta: `[notification-5] ... Email to the PRACTITIONER for ApprovalRequested of booking 4 not sent`. Il nome del thread conferma l'executor, e la riga non ha indirizzi. Nessun altro test ha provato a spedire: i test d'API con `@Transactional` non arrivano mai al commit.

**Non fatto:** la prova a mano con Tomcat vero e Mailpit. Il test con Mailpit in un container fa la stessa cosa, in automatico: email vera via SMTP, letta dall'API di Mailpit, con mittente, destinatario e lettere accentate giusti.

---

## Domande da colloquio

### Domanda 1: Un'email deve partire quando una prenotazione è confermata. Dove la mandi, e perché non direttamente dal service?

**Risposta modello.** Il service pubblica un evento di dominio dentro la transazione, e un ascoltatore in un altro modulo manda l'email. Così il modulo delle prenotazioni non dipende da email, push e dai loro errori, e un nuovo canale si aggiunge con un nuovo ascoltatore. L'ascoltatore è `@TransactionalEventListener`, quindi l'email parte solo se il commit riesce: con un `@EventListener` normale partirebbe anche per una transazione poi annullata, e il cliente riceverebbe una conferma falsa.

### Domanda 2: Cosa succede se un `@TransactionalEventListener` lancia un'eccezione?

**Risposta modello.** Nella fase `AFTER_COMMIT` il commit è già avvenuto, quindi i dati restano. Ma se l'ascoltatore gira nello stesso thread, l'eccezione arriva al chiamante: l'API risponde 500 per un'operazione riuscita, e il client riprova una cosa già fatta. Per questo o si cattura tutto nell'ascoltatore, o lo si esegue in un altro thread con `@Async`, dove un errore non può raggiungere la risposta. In entrambi i casi l'errore va registrato nel log, perché nessuno lo vedrà altrimenti.

### Domanda 3: Cosa sono i virtual thread e quando li usi?

**Risposta modello.** Sono thread gestiti dalla JVM invece che dal sistema operativo (Java 21). Quando aspettano la rete o il database, la JVM li parcheggia e usa il thread vero per altro: costano poco, se ne creano milioni, e il codice resta sequenziale. Convengono per compiti che aspettano molto e calcolano poco, come spedire un'email o chiamare un'API; non aiutano con i calcoli pesanti, dove il limite sono i core. Non si mettono in un pool: se ne crea uno per compito.

### Domanda 4: Con `@Async` dopo il commit, cosa rischi di perdere, e come lo risolveresti?

**Risposta modello.** L'email vive in memoria tra il commit e la spedizione: un crash in quell'istante la perde, e se il server di posta è fermo l'email fallisce e basta. La soluzione robusta è la *transactional outbox*: nella stessa transazione si scrive una riga "da spedire", e un lavoro programmato la spedisce e la segna, riprovando se fallisce. Costa una tabella, un lavoro e la pulizia; la si introduce quando le email perse diventano un problema misurato, non prima.

### Domanda 5: Perché uno `switch` su una `sealed interface` senza `default`?

**Risposta modello.** Perché il compilatore conosce tutte le implementazioni e verifica che lo `switch` le copra tutte. Se aggiungo un nuovo tipo di evento, ogni `switch` che lo deve gestire smette di compilare finché non decido cosa fare. Con un `default` il codice compilerebbe e il nuovo caso finirebbe nel ramo generico in silenzio, che è il bug più difficile da vedere.

### Domanda 6: Come provi un codice asincrono senza `Thread.sleep`?

**Risposta modello.** Con Awaitility: ripeto un'asserzione a intervalli brevi finché passa, con un tempo massimo dopo il quale il test fallisce. Il test dura quanto serve davvero, e non è instabile come uno `sleep` troppo corto. La parte con più casi la tengo comunque fuori dall'asincrono: i testi sono una funzione pura, provata senza Spring e senza thread.

---

## Documentazione ufficiale

- Spring Framework, eventi legati alle transazioni: https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html
- Spring Framework, `@Async` ed esecuzione asincrona: https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-annotation-support-async
- Spring Framework, `TransactionSynchronization.afterCommit` (Javadoc): https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html#afterCommit()
- Spring Framework, `SimpleAsyncTaskExecutor` (Javadoc): https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/task/SimpleAsyncTaskExecutor.html
- JEP 444, Virtual Threads: https://openjdk.org/jeps/444
- JEP 441, Pattern Matching for switch: https://openjdk.org/jeps/441
- Spring Boot, email: https://docs.spring.io/spring-boot/reference/io/email.html
- Awaitility: https://github.com/awaitility/awaitility/wiki/Usage
- Mailpit, API: https://mailpit.axllent.org/docs/api-v1/
