# Registro delle decisioni

Una scheda per ogni scelta importante. Le schede non si cancellano: se una decisione cambia, si aggiunge una scheda nuova che la sostituisce e si segna la vecchia come "Sostituita da D-XXX".

Formato: contesto → decisione → alternative scartate → conseguenze.

---

## D-001 — Sviluppo da zero invece di un software esistente
**Stato:** accettata · 2026-10-13

**Contesto.** Esistono piattaforme pronte (es. SimplyBook.me) che risolvono il problema dello studio. Nicholas però vuole tornare a Java per cercare lavoro come sviluppatore.

**Decisione.** Si sviluppa da zero. Il progetto è insieme un prodotto per lo studio e un caso studio da portare ai colloqui.

**Alternative scartate.** Piattaforma esistente: più veloce, ma non insegna nulla.

**Conseguenze.** Tempi più lunghi. Lo studio deve sapere che il sistema arriverà per gradi.

---

## D-002 — Un'unica applicazione backend divisa in moduli
**Stato:** accettata · 2026-10-13

**Contesto.** Per due professionisti il carico è minimo, e il team di sviluppo è una persona sola.

**Decisione.** Monolite modulare: una sola applicazione Spring Boot, divisa in package per area (vedi `architettura.md`). I moduli si parlano solo tramite i rispettivi servizi pubblici.

**Alternative scartate.** Microservizi: rete tra servizi, transazioni distribuite, più deploy, più monitoraggio. Costi reali senza alcun beneficio a questa scala.

**Conseguenze.** Le transazioni restano semplici, perché tutto sta in un solo database. Se un giorno servisse separare un modulo, i confini sono già tracciati.

---

## D-003 — Java 21 e Spring Boot
**Stato:** accettata · 2026-10-13

**Contesto.** Obiettivo: essere pronti per colloqui Java nelle aziende.

**Decisione.** Java 21 (LTS), Spring Boot nella versione stabile più recente compatibile, Maven.

**Alternative scartate.** Java 25: più nuovo, ma oggi meno diffuso nelle aziende. NestJS: già noto a Nicholas, ma non serve all'obiettivo.

**Conseguenze.** Occasione per imparare le novità dopo Java 17: record, sealed class, pattern matching, virtual thread.

---

## D-004 — PostgreSQL gestito in proprio, non Supabase
**Stato:** accettata · 2026-10-13

**Decisione.** PostgreSQL in un container Docker, con lo schema gestito da migrazioni Flyway scritte a mano.

**Alternative scartate.** Supabase: comodo, ma nasconde proprio le parti da riallenare (schema, migrazioni, vincoli, transazioni).

**Conseguenze.** Backup e aggiornamenti del database sono a carico nostro.

---

## D-005 — Frontend in Next.js scritto da Claude, senza regole di business
**Stato:** accettata · 2026-10-13

**Contesto.** Il frontend non serve all'obiettivo di apprendimento. Nicholas non conosce JavaScript.

**Decisione.** Next.js + TypeScript, scritto da Claude Code. Tutte le regole (prezzi, disponibilità, crediti, cancellazioni) vivono nel backend. I tipi delle API si generano dal contratto OpenAPI.

**Conseguenze.** Il backend è l'unica fonte di verità. Un errore nel frontend può mostrare male un dato, ma non può violare una regola.

---

## D-006 — Pagamenti con Stripe Checkout
**Stato:** accettata · 2026-10-13

**Decisione.** Pagina di pagamento ospitata da Stripe (Checkout). Il sistema non vede né conserva mai i dati della carta.

**Alternative scartate.** Form di pagamento integrato nel sito (Stripe Elements): più controllo grafico, ma più codice e più responsabilità sulla sicurezza.

**Conseguenze.** Il cliente esce per un momento dal sito e poi rientra. Gli obblighi di sicurezza sui dati delle carte restano in capo a Stripe.

---

## D-007 — Il pagamento lo conferma il webhook, non il browser
**Stato:** accettata · 2026-10-13

**Contesto.** Dopo il pagamento il cliente torna sul sito, ma potrebbe chiudere la finestra prima, oppure l'indirizzo di ritorno potrebbe essere aperto da qualcuno che non ha pagato.

**Decisione.** Una prenotazione o un pacchetto diventano validi solo quando arriva il webhook `checkout.session.completed`, con firma verificata. Ogni evento si elabora una sola volta (tabella `stripe_event`).

**Conseguenze.** La pagina di ritorno mostra "pagamento in verifica" e aggiorna lo stato chiedendolo al backend.

---

## D-008 — Blocco temporaneo dell'orario: 30 minuti
**Stato:** accettata · 2026-10-13

**Contesto.** Durante la progettazione si era ipotizzato un blocco di 10 minuti. Stripe però non accetta sessioni Checkout che scadono prima di 30 minuti.

**Decisione.** Mentre il cliente paga, l'orario resta bloccato 30 minuti, la stessa durata della sessione Stripe. Alla scadenza (evento `checkout.session.expired` oppure lavoro programmato) la prenotazione passa a `EXPIRED`.

**Alternative scartate.** Blocco più breve con sessione Stripe più lunga: il cliente potrebbe pagare un orario già liberato e riassegnato.

**Conseguenze.** Un orario può restare bloccato fino a 30 minuti da chi poi non paga. Accettabile con due professionisti. Se un pagamento arriva comunque tardi, il cliente riceve un credito (lotto `LATE_PAYMENT`).

---

## D-009 — I crediti sono un registro di movimenti
**Stato:** accettata · 2026-10-13

**Decisione.** Non si salva un saldo da aggiornare. Si registrano movimenti (+N, −1) che non si modificano mai. Il saldo è la somma.

**Alternative scartate.** Colonna "crediti rimasti": semplice, ma perde la storia ed è fragile con aggiornamenti concorrenti.

**Conseguenze.** La storia è sempre ricostruibile, come in un estratto conto. Il calcolo del saldo richiede una somma, irrilevante a questi volumi.

---

## D-010 — Lotti di crediti con scadenza; il pacchetto acquistato è un lotto
**Stato:** accettata · 2026-10-13

**Contesto.** I crediti nascono da pacchetti (scadenza del pacchetto) e da cancellazioni in tempo di sedute pagate con carta (90 giorni).

**Decisione.** Ogni credito appartiene a un lotto con la propria scadenza. Quando si prenota con credito, si usa il lotto valido che scade prima. Non esiste una tabella separata "pacchetto acquistato": è un lotto con `source = PACKAGE`.

**Conseguenze.** Una sola logica per tutti i crediti. Una cancellazione in tempo di una seduta pagata con credito restituisce il credito allo stesso lotto, che mantiene la sua scadenza.

---

## D-011 — Prezzo fotografato
**Stato:** accettata · 2026-10-13 · **modificata da D-022** (2026-10-29): niente IVA, si fotografa solo `price_cents`

**Decisione.** Prenotazioni, lotti da pacchetto e pagamenti salvano prezzo netto, aliquota e prezzo lordo del momento.

**Conseguenze.** Cambiare un prezzo non altera lo storico. Un po' di dati duplicati, voluti.

---

## D-012 — Soldi in centesimi interi, IVA in punti base
**Stato:** accettata · 2026-10-13 · **modificata da D-022** (2026-10-29): restano i centesimi interi; aliquote in punti base e calcolo del lordo eliminati

**Decisione.** Importi in `long`/`bigint` di centesimi. Aliquote in punti base (22% = 2200). Un solo punto del codice calcola il lordo, con arrotondamento half up.

**Alternative scartate.** `double`: errori di arrotondamento. `BigDecimal`: corretto ma più verboso, e non serve per somme e moltiplicazioni intere.

---

## D-013 — Le sovrapposizioni le impedisce il database
**Stato:** accettata · 2026-10-13

**Contesto.** Due clienti possono provare a prenotare lo stesso orario nello stesso istante. Un controllo solo in Java ("è libero? allora salva") lascia una finestra in cui entrambi passano.

**Decisione.** *Exclusion constraint* di PostgreSQL sull'intervallo orario, per professionista, limitato agli stati che occupano l'orario. Il backend intercetta la violazione e risponde "orario non più disponibile".

**Conseguenze.** La regola vale anche in caso di bug nel codice Java. Richiede l'estensione `btree_gist`.

---

## D-014 — Accesso senza password con codice via email
**Stato:** accettata · 2026-10-13

**Decisione.** L'utente inserisce l'email e riceve un codice di 6 cifre (validità 10 minuti, massimo 5 tentativi, salvato solo come hash). La prima prenotazione crea l'account. Stesso meccanismo per i professionisti, con ruolo diverso. Sessione in cookie `HttpOnly`.

**Alternative scartate.** Password: più attrito per i clienti e più responsabilità (salvataggio, recupero). Accesso con Google: comodo, ma non tutti i clienti lo vorranno usare.

**Conseguenze.** Serve un servizio email affidabile: se le email finiscono in spam, nessuno riesce ad accedere.

---

## D-015 — Cancellazione in tempo = credito, mai rimborso
**Stato:** accettata · 2026-10-13

**Decisione.** Chi cancella entro il preavviso riceve un credito. Il sistema non effettua rimborsi in denaro; casi eccezionali si gestiscono a mano dalla dashboard Stripe.

**Conseguenze.** I soldi restano allo studio e non serve integrare i rimborsi Stripe.

---

## D-016 — Un unico repository
**Stato:** accettata · 2026-10-13

**Decisione.** Backend, frontend, infrastruttura e documentazione nello stesso repository, pubblico su GitHub.

**Conseguenze.** Un solo posto da cui Claude Code legge stato e decisioni. Massima attenzione a non versionare segreti.

---

## D-017 — Caddy davanti a tutto, un solo dominio
**Stato:** accettata · 2026-10-13

**Decisione.** Caddy gestisce HTTPS e indirizza `/api/*` al backend e il resto al frontend. Solo Caddy è esposto su internet.

**Alternative scartate.** Nginx: più diffuso, ma richiede di gestire i certificati a parte. Domini separati per frontend e backend: obbligano a configurare CORS e complicano i cookie.

**Conseguenze.** I cookie di sessione funzionano senza configurazioni particolari.

---

## D-018 — Un unico `.env` nella radice, letto da Compose e da Spring
**Stato:** accettata · 2026-10-14

**Contesto.** In locale il database gira in Docker (Compose) e il backend gira fuori da Docker, lanciato da `mvnw` o dall'IDE. Entrambi hanno bisogno degli stessi valori (nome del database, utente, password, porta), che non possono stare nel repository (D-016).

**Decisione.** Un solo file `.env` nella radice del repository, non versionato, con il modello `.env.example` versionato.
- Compose lo legge con `--env-file .env` e passa al container di PostgreSQL solo le variabili che servono (`environment:` con interpolazione, non `env_file:`).
- Spring lo importa con `spring.config.import=optional:file:../.env[.properties],optional:file:.env[.properties]`: due percorsi perché la cartella di lavoro è `backend/` con `mvnw` e la radice con l'IDE; `optional:` perché in produzione il file non c'è e le variabili arrivano dall'ambiente del container, che ha comunque precedenza.
- In `application.properties` i valori si leggono con placeholder sui nomi `POSTGRES_*`; la password non ha mai un default.

**Alternative scartate.** Variabili impostate a mano nel terminale: da rifare a ogni sessione, e le password finiscono nella cronologia. Variabili nella configurazione dell'IDE: non valgono per `mvnw`. Nomi `SPRING_DATASOURCE_*` con *relaxed binding*: doppi nomi per lo stesso valore, e l'URL va comunque composto. Supporto Docker Compose di Spring Boot: dipendenza in più, rimandata. Un `env_file:` in Compose: passerebbe al container di PostgreSQL anche segreti che non gli servono.

**Conseguenze.** Lo stesso `application.properties` funziona in locale e in produzione senza modifiche. Il `.env` è letto da due programmi con regole diverse: niente virgolette, niente `\`, niente spazi in fondo alle righe (errore reale del passo 4). Un percorso sbagliato non dà errori grazie a `optional:`; senza default, la password usata diventa letteralmente `${POSTGRES_PASSWORD}` e l'avvio si ferma con `password authentication failed`. *(Corretto il 2026-10-19: prima diceva `Could not resolve placeholder`, verificato che non è così.)*

---

## D-019 — Open Session In View disattivato
**Stato:** accettata · 2026-10-14

**Contesto.** Spring Boot attiva di default *Open Session In View* (`spring.jpa.open-in-view=true`): il contesto di Hibernate e una connessione al database restano aperti per tutta la richiesta HTTP, anche dopo la fine del servizio.

**Decisione.** `spring.jpa.open-in-view=false`. Tutto ciò che serve per la risposta si carica nel servizio, dentro la transazione; le API restituiscono record DTO, mai entità (architettura).

**Alternative scartate.** Lasciare il default: evita le `LazyInitializationException`, ma nasconde query fuori dal servizio (anche "N+1" durante la conversione in JSON) e tiene occupata una connessione del pool per tutta la richiesta.

**Conseguenze.** I confini delle transazioni sono chiari e le connessioni tornano subito nel pool (10 di default). Se nel servizio si dimentica di caricare una relazione *lazy*, si ottiene una `LazyInitializationException` esplicita, invece di una query nascosta.

---

## D-020 — Email in minuscolo imposta dal database, normalizzata in Java
**Stato:** accettata · 2026-10-16

**Contesto.** L'accesso avviene con l'email (D-014). `Mario.Rossi@Gmail.com` e `mario.rossi@gmail.com` devono essere lo stesso utente, altrimenti un cliente potrebbe avere due account e i crediti divisi tra i due. Il modello dati chiede due cose: email **unica** senza distinguere maiuscole e minuscole, e **salvata in minuscolo**.

**Decisione.** In `app_user`:
- un vincolo `check` che accetta solo email già in minuscolo (`email = lower(email)`);
- un vincolo `unique` normale sulla colonna: visto che esistono solo email minuscole, basta a garantire l'unicità.

In Java la conversione in minuscolo avviene **in un posto solo**, prima di salvare e prima di cercare: un *value object* `Email` (un `record`) che toglie gli spazi, converte con `toLowerCase(Locale.ROOT)` e rifiuta i valori non validi.

**Alternative scartate.**
- *Indice unico su `lower(email)`*: garantisce l'unicità ma salva l'email com'è arrivata, e la ricerca deve usare `lower(email) = ...` per sfruttare l'indice. Con un bug in Java il problema esplode tardi, al login, lontano dalla causa.
- *Tipo `citext`*: comodo, ma salva l'email com'è arrivata (il database finisce con email scritte in modi diversi), richiede un'estensione specifica di PostgreSQL, e il mapping con Hibernate in `validate` va verificato. Con un bug in Java il problema resta nascosto per sempre.

**Conseguenze.** Il database garantisce entrambi i requisiti senza dipendere da Java. Il vincolo non pesa sugli utenti, perché Java normalizza sempre: scatta solo se il codice ha un bug, con un errore immediato alla scrittura (*fail fast*). La ricerca per email è una semplice uguaglianza, servita dall'indice creato da `unique`. Confronto completo: `docs/studio/04-flyway-e-migrazione-v1.md`, parte 3; verifica 03, D2.

---

## D-021 — Ruolo `ADMIN` per chi gestisce la piattaforma
**Stato:** accettata · 2026-10-16

**Contesto.** I requisiti dicono che i professionisti gestiscono tutto dal pannello, ma non chi crea i professionisti stessi. Nicholas, durante la scrittura di V1, ha individuato il buco.

**Decisione.** Un terzo ruolo, `ADMIN`, accanto a `CUSTOMER` e `PRACTITIONER`. L'admin è Nicholas, come gestore della piattaforma, non una persona dello studio. Può creare, modificare e disattivare i professionisti. Ogni utente ha **un solo ruolo**: regge perché l'admin non è anche professionista.

**Alternative scartate.** *Nessun ruolo, professionisti inseriti con uno script SQL*: più semplice per due professionisti, ma ogni modifica richiederebbe un intervento sul database. *Ruoli multipli per utente* (es. una titolare che è anche admin): non serve finché l'admin è Nicholas.

**Conseguenze.** Il `check` sul ruolo ammette tre valori. Un professionista non si cancella mai: si disattiva, per conservare la sua storia (prenotazioni, pagamenti, crediti). Le chiavi esterne verso `app_user` non usano `on delete cascade`. Se in futuro la piattaforma servirà più studi, o un admin dovrà essere anche professionista, il modello dei ruoli andrà rivisto con una nuova decisione.

---

## D-022 — Niente IVA: un solo prezzo, in centesimi
**Stato:** accettata · 2026-10-29 · modifica D-011 e D-012

**Contesto.** Entrambi i professionisti sono in regime forfettario: sulle loro prestazioni l'IVA non si applica. Il modello dati prevedeva prezzo netto, aliquota in punti base e prezzo lordo calcolato (D-011, D-012).

**Decisione.** Ogni servizio e ogni pacchetto hanno **un solo prezzo**, `price_cents`, che è insieme quello mostrato al cliente e quello incassato. Restano valide le parti di D-011 e D-012 che non riguardano l'IVA:
- il prezzo si **fotografa** su prenotazioni, lotti da pacchetto e pagamenti (cambiare un prezzo non altera lo storico);
- i soldi sono **centesimi interi** (`long`/`bigint`), mai `double`.

Spariscono aliquote, punti base e il calcolo del lordo con arrotondamento.

**Alternative scartate.** Tenere `vat_rate_bp` fisso a 0: una colonna che non corrisponde a niente di reale, più un calcolo del lordo che restituisce sempre il netto. Codice da scrivere, testare e mantenere senza alcun beneficio.

**Conseguenze.** Schema e codice più semplici: nessun arrotondamento, nessuna differenza tra prezzo mostrato e prezzo salvato. Se un professionista uscirà dal forfettario, l'IVA si aggiunge con una **migrazione nuova** e una nuova decisione (le migrazioni già eseguite non si toccano). Resta aperta la questione della marca da bollo (requisiti, punto aperto 9).

---

## D-023 — Orizzonte di prenotazione e limite di sedute future
**Stato:** accettata · 2026-10-29

**Contesto.** Un cliente, soprattutto con un pacchetto da molte sedute, potrebbe prenotarle tutte in una volta e occupare il calendario del professionista per settimane, togliendo spazio agli altri clienti.

**Decisione.** Due impostazioni **per professionista**, modificabili dal pannello:
- **orizzonte di prenotazione**: quanti giorni avanti si può prenotare;
- **limite di prenotazioni future**: quante prenotazioni future può avere aperte un cliente con quel professionista. Contano quelle in `PENDING_PAYMENT` e `CONFIRMED` con inizio nel futuro.

Valori iniziali proposti: **21 giorni** e **2 sedute**, da confermare con lo studio (requisiti, punto aperto 11).

Le due regole valgono solo quando **prenota il cliente**. Le prenotazioni fatte dal professionista dal pannello (D-024) non le rispettano, ma **contano** nel limite quando poi il cliente prenota da solo.

**Alternative scartate.** Nessun limite: il problema resta. Limite unico per tutto lo studio: ogni professionista ha il proprio calendario e le proprie impostazioni (requisiti, "Chi usa il sistema").

**Conseguenze.** Due colonne in `practitioner`, introdotte dalla migrazione del passo 7, dove le regole vengono fatte rispettare dal servizio di prenotazione. Il limite si controlla in Java contando le prenotazioni: non è un vincolo del database come la non sovrapposizione (D-013), quindi due prenotazioni simultanee dello stesso cliente vanno considerate al passo 7.

---

## D-024 — Il professionista può prenotare per un cliente dal pannello
**Stato:** accettata · 2026-10-29 · modo di pagamento da confermare (requisiti, punto aperto 10)

**Contesto.** Non tutti i clienti prenoteranno da soli: alcuni chiedono la seduta di persona o al telefono. Il professionista deve poterla inserire lui.

**Decisione.** Dal pannello il professionista prenota **a nome di un suo cliente**, senza orizzonte né limite di sedute future (D-023).
- Se il cliente ha **crediti validi** per quel servizio, si usano (lotto che scade prima, D-010).
- Altrimenti, proposta: **pagamento in studio**. La seduta è confermata subito; se cancellata in tempo **non genera credito**, perché non è stato pagato niente (coerente con D-015: il credito restituisce un valore già versato).
- Se il cliente non esiste ancora, il professionista lo **crea dal pannello** con nome, cognome ed email.

**Alternative scartate.** Obbligare il cliente a prenotare da solo: scarica sul cliente un passaggio che oggi lo studio fa per lui. Inviare al cliente un link di pagamento Stripe: possibile, ma va deciso insieme al modo di pagamento (punto aperto 10).

**Conseguenze.**
- `booking.paid_with` potrebbe avere un nuovo valore (es. `IN_STUDIO`), e serve sapere **chi ha creato** la prenotazione (cliente o professionista).
- La regola di D-007 (validità solo con il webhook) riguarda i pagamenti con carta: una prenotazione con credito o in studio non passa da Stripe.
- Il pagamento in studio reintroduce il rischio da cui nasce il progetto (seduta non pagata in anticipo): è una scelta del professionista per i propri clienti, non un'opzione per chi prenota da solo.
- Creare un cliente dal pannello segue le regole già decise: email normalizzata (D-020), un solo ruolo per utente (D-021), riattivazione se l'account esiste ma è disattivato.
- Da chiarire al passo 7/13: cosa rende un cliente "suo" (cosa può cercare e vedere un professionista nell'elenco dei clienti), coerentemente con la regola "il professionista vede solo le proprie prenotazioni".

---

## D-025 — Il servizio di un pacchetto è dello stesso professionista: foreign key composta
**Stato:** accettata · 2026-10-29 · **modificata da D-026** (2026-10-29): in Java il professionista è un `Long practitionerId`, non un `@ManyToOne`; il vincolo nel database non cambia

**Contesto.** `package_offer` ha sia `practitioner_id` sia `service_id`. Ognuna delle due foreign key, presa da sola, accetta un pacchetto di un professionista costruito sul servizio dell'altro: i dati sarebbero incoerenti. Un `check` non può impedirlo, perché vede solo la propria riga. Confronto completo: `docs/studio/08-migrazione-v2-catalogo.md`, parte 2.

**Decisione.** Foreign key **composta** in V2:
- in `service`, `unique (id, practitioner_id)` (`service_id_practitioner_id_uk`), logicamente ridondante con la chiave primaria ma richiesto da PostgreSQL come destinazione della foreign key;
- in `package_offer`, `foreign key (service_id, practitioner_id) references service (id, practitioner_id)` (`package_offer_service_practitioner_fk`).

Restano anche le foreign key semplici verso `practitioner` e `service`: implicate dalla composta, ma rendono leggibile ogni relazione da sola. In Java, `CatalogService` controlla comunque proprietà e stato del servizio, per dare un errore chiaro.

**Alternative scartate.**
- *Togliere `practitioner_id` da `package_offer`* (normalizzazione): incoerenza impossibile per costruzione, ma ogni ricerca "pacchetti del professionista" passa per una join, e in JPA per una relazione in più da caricare.
- *Trigger*: logica procedurale nascosta nel database, in un secondo linguaggio, per una regola che una foreign key esprime in modo dichiarativo.
- *Solo in Java*: non protegge da bug, scritture esterne e richieste concorrenti (PD-17).

**Conseguenze.** La regola vale anche con un bug in Java o con un `insert` a mano (provato da `CatalogSchemaTest`). Entrambe le colonne sono `not null`: con un `NULL`, una foreign key composta non verrebbe controllata (`MATCH SIMPLE`). JPA non conosce il vincolo: le entità avranno due `@ManyToOne` separati. Lo stesso schema è candidato per `credit_lot` al passo 10, che ha le stesse due colonne.

---

## D-026 — Tra moduli ci si riferisce per id; una sola `NotFoundException`
**Stato:** accettata · 2026-10-29 · modifica una conseguenza di D-025

**Contesto.** Le tabelle di un modulo hanno chiavi esterne verso tabelle di altri moduli (`practitioner.user_id` → `app_user` di `auth`; `service.practitioner_id` → `practitioner`). In JPA una chiave esterna si può mappare come oggetto (`@ManyToOne`, `@OneToOne`) o come semplice id. Confronto completo: `docs/studio/09-modulo-practitioner.md`, parte 2.

**Decisione.**
- **Tra moduli diversi** l'entità tiene solo l'id (`Long userId`, `Long practitionerId`). Per sapere qualcosa dell'altro modulo si chiama il suo servizio pubblico, che restituisce `record`.
- **Dentro lo stesso modulo** si usano le relazioni JPA (es. `PackageOffer` → `Service`, entrambe di `catalog`).
- Entità e repository restano **non pubblici**: D-002 la fa rispettare il compilatore.
- Per "non esiste" (e, dalle API, "non è tuo") c'è **una sola** `NotFoundException` (`RuntimeException`), nel package `shared`. Diventa un 404 nel `@RestControllerAdvice`.

**Alternative scartate.**
- *Oggetti anche tra moduli*: navigazione comoda, ma entità pubbliche, caricamento *lazy* che attraversa i moduli (con `LazyInitializationException` fuori dalla transazione, D-019) e la possibilità per un modulo di modificare le entità di un altro saltando le sue regole.
- *Un'eccezione per modulo*: più precisa, ma per chi chiama l'API la differenza non esiste (sempre 404), e il gestore degli errori dovrebbe conoscerle tutte.

**Conseguenze.** Niente join JPQL tra entità di moduli diversi: se servono dati di più moduli, li combina un servizio. È lo stesso principio degli aggregati del Domain-Driven Design e di Spring Modulith. La coerenza tra id di moduli diversi la garantiscono le foreign key del database (D-025 resta identica nello schema).

---

## D-027 — Regole violate: una `BusinessRuleException` con codice, risposta 409
**Stato:** accettata · 2026-10-29

**Contesto.** Alcune richieste falliscono non perché una cosa manca (404, D-026) né perché sono scritte male (400), ma perché una regola lo impedisce nello stato attuale: un pacchetto su un servizio disattivato oggi, un orario già preso o un credito scaduto domani. L'architettura chiede errori con un codice leggibile che il frontend traduce in italiano. Confronto: `docs/studio/10-modulo-catalog.md`, 1.3.

**Decisione.**
- Una sola `BusinessRuleException` (`RuntimeException`) nel package `shared`, con un codice preso dall'`enum` `ErrorCode` (primo valore: `SERVICE_NOT_ACTIVE`).
- Il gestore degli errori la trasforma in **409 Conflict**, con il codice nel `ProblemDetail`.

**Alternative scartate.** *Una classe per ogni regola*: diventerebbero decine, e il gestore degli errori dovrebbe conoscerle tutte. *Codice come `String`*: errori di battitura possibili, nessun elenco unico. *422 Unprocessable Content*: adatto quando è il contenuto della richiesta a non avere senso; le nostre regole dipendono quasi sempre dallo stato delle risorse.

**Conseguenze.** Ogni nuova regola aggiunge un valore a `ErrorCode`, e il frontend aggiunge la sua traduzione. Il codice è parte del contratto delle API: una volta pubblicato, non si rinomina.

---

## D-028 — Professionista corrente provvisorio: interfaccia + header, da rimuovere al passo 8
**Stato:** accettata · 2026-10-29 · **provvisoria**: l'implementazione va rimossa al passo 8

**Contesto.** Le API del pannello devono sapere quale professionista agisce, ma l'accesso arriva al passo 8. Serve un sostituto che stia in un punto solo, che nei test permetta di agire come due professionisti diversi, e che sia chiaramente provvisorio. Confronto: `docs/studio/11-api-pannello.md`, parte 2.

**Decisione.**
- Interfaccia pubblica `CurrentPractitioner` (`long id()`) nel modulo **`practitioner`**. I controller dipendono solo da lei.
- Implementazione provvisoria `DevHeaderCurrentPractitioner` (package-private, stesso modulo): legge l'header `X-Practitioner-Id` e controlla che il professionista esista. Header mancante, non numerico o con un id inesistente → `NotAuthenticatedException` → **401**.
- **Al passo 8** l'implementazione si cancella e la sostituisce una che chiede ad `auth` l'utente autenticato e lo traduce nel suo professionista (`practitioner.user_id`). Criterio di "fatto" del passo 8.

Nella guida l'interfaccia era proposta in `auth`; spostata in `practitioner` durante l'implementazione per evitare un ciclo tra moduli (il controller di `practitioner` dipenderebbe da `auth`, e `auth` da `practitioner` per sapere se il professionista esiste). Così le dipendenze vanno in un verso solo: `catalog` → `practitioner` → `auth`.

**Alternative scartate.** *Valore fisso nella configurazione*: un solo professionista per contesto, il test "A non tocca le cose di B" non si può scrivere. *Id nella URL*: la stessa identità dichiarata, sparsa in ogni indirizzo, da riscrivere al passo 8. *Spring Security subito con un utente finto*: anticipa a metà il passo 8.

**Conseguenze.** L'identità è **dichiarata dal client, non verificata**: l'implementazione non deve mai arrivare in produzione (il sistema non va online prima del passo 14). Scelte collegate dello stesso pezzo: URL sotto `/api/panel/...`, creazione con `POST` (201 + `Location`), modifica con `PUT`, disattivazione e riattivazione come azioni `POST .../deactivate` e `.../activate`; annotazioni di Bean Validation sui `record` di ingresso dei moduli.
