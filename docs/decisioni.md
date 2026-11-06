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
**Stato:** accettata · 2026-10-13 · **modificata da D-031** (2026-11-06): per le sedute il webhook conferma il blocco dell'importo, non la prenotazione

**Contesto.** Dopo il pagamento il cliente torna sul sito, ma potrebbe chiudere la finestra prima, oppure l'indirizzo di ritorno potrebbe essere aperto da qualcuno che non ha pagato.

**Decisione.** Una prenotazione o un pacchetto diventano validi solo quando arriva il webhook `checkout.session.completed`, con firma verificata. Ogni evento si elabora una sola volta (tabella `stripe_event`).

**Conseguenze.** La pagina di ritorno mostra "pagamento in verifica" e aggiorna lo stato chiedendolo al backend.

---

## D-008 — Blocco temporaneo dell'orario: 30 minuti
**Stato:** accettata · 2026-10-13 · conseguenza sul pagamento tardivo **modificata da D-031** (2026-11-06): si annulla il blocco sulla carta invece di creare un credito

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
**Stato:** accettata · 2026-10-13 · completata da D-030 (2026-11-06): anche `PENDING_APPROVAL` occupa l'orario

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
**Stato:** accettata · 2026-10-29 · completata da D-030 (2026-11-06): nel limite contano anche le `PENDING_APPROVAL` · la colonna dell'orizzonte nasce al passo 6 (V3), non al passo 7 (D-035)

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

---

## D-029 — "Professionista attivo" si chiede ad `auth`, con una domanda su un insieme di id
**Stato:** accettata · 2026-10-30

**Contesto.** L'elenco pubblico dei professionisti mostra solo quelli attivi, e il catalogo pubblico di un professionista disattivato non deve esistere. Ma `practitioner` non ha una colonna `active`: la disattivazione è quella dell'account, `app_user.active` (D-021), che appartiene al modulo `auth`. Confronto completo: `docs/studio/12-api-pubbliche.md`, parte 2.

**Decisione.**
- `auth` espone il suo primo servizio pubblico, `AccountService`, con `Set<Long> activeAmong(Collection<Long> userIds)`: riceve un **insieme** di id utente e restituisce quelli attivi (una sola query, che legge solo gli id).
- `PractitionerService.listActive()` legge tutti i professionisti ordinati per cognome e nome, chiede ad `auth` in un colpo solo, e filtra in Java: due query, qualunque sia il numero dei professionisti. `requireActive(id)` lancia la stessa `NotFoundException` per "inesistente" e "disattivato".
- Il controllo sta nel servizio: `CatalogService.publicCatalog` chiama `requireActive`, il controller non ha logica.
- Dipendenze tra moduli in un verso solo: `catalog` → `practitioner` → `auth`. `auth` non dipende da nessuno.
- API pubbliche sotto `/api/public/...`, con `record` dedicati: il professionista espone solo `id`, `firstName`, `lastName` (`PublicPractitioner`); il nome da mostrare lo compone il frontend.

**Alternative scartate.** *Join su `app_user` nel repository di `practitioner`*: una query sola, ma è proprio ciò che D-002 vieta, e richiederebbe SQL nativo (con D-026 non c'è una relazione JPA su cui fare la join); se `auth` cambia la tabella, si rompe solo a runtime. *Colonna `active` anche in `practitioner`*: due verità sullo stesso fatto, che prima o poi divergono. *Un metodo `isActive(userId)` per un id alla volta*: un N+1 tra moduli, che nessun `join fetch` può risolvere.

**Conseguenze.** I test di `practitioner` e `catalog` con `@DataJpaTest` importano anche i servizi da cui dipendono. Un "nascosto dal sito ma attivo nel pannello" (ferie, pausa) sarebbe un concetto diverso da "account attivo", con un nome suo: se servirà, nuova decisione. Al passo 8: `/api/public/**` aperto e tutto il resto chiuso con una regola per prefisso; il login rifiuta un account disattivato (oggi `DevHeaderCurrentPractitioner` controlla solo che il professionista esista).

---

## D-030 — Approvazione delle prenotazioni: automatica o manuale, per professionista
**Stato:** accettata · 2026-11-06 · completa D-023 (conteggio del limite) e D-013 (stati che occupano l'orario); sostituisce "non per il giorno stesso" (requisiti, punto aperto 14)

**Contesto.** I professionisti vogliono decidere quali prenotazioni accettare: evitare mezz'ore vuote ora che una seduta può iniziare alla mezz'ora, gestire la stanza unica quando c'è una visita di valutazione, scegliere i clienti, avere il controllo dell'agenda. Il flusso deciso finora confermava la prenotazione appena pagata (D-007).

**Decisione.**
- Impostazione **per professionista** `booking_approval`, un `enum` con valori `AUTOMATIC` e `MANUAL`. Valore iniziale **`MANUAL`** (scelta di Nicholas): chi non vuole il controllo lo disattiva.
- **`MANUAL`**: una prenotazione fatta dal cliente, appena pagata (importo bloccato sulla carta, D-031) o con il credito impegnato, passa a `PENDING_APPROVAL`. Il professionista la approva o la rifiuta dal pannello, avvisato con una notifica push (D-032).
- **Tempo di risposta**: impostazione `approval_window_hours`, modificabile dal professionista. Si conta dal momento della richiesta e si **fotografa** nella prenotazione come scadenza (`approval_deadline`): cambiare l'impostazione non sposta le richieste già in attesa. Il cliente vede questo tempo **prima** di prenotare.
- **Nessuna risposta entro la scadenza**: la prenotazione si **annulla** da sola, come un rifiuto. Il blocco sulla carta si annulla o il credito torna, e il cliente riceve un'email. È coerente con la scelta del professionista di approvare a mano.
- Finché è in attesa, il cliente può **ritirare** la richiesta: nessun addebito.
- **`AUTOMATIC`**: il sistema approva da solo, subito dopo il pagamento o l'impegno del credito. Un solo percorso nel codice per entrambe le modalità.
- **Anticipo minimo**: il cliente può prenotare solo orari che iniziano **più di 24 ore dopo** il momento della prenotazione. Esempio: alle 10:00 il primo orario prenotabile è alle 10:30 del giorno dopo, o alle 11:00 con la griglia di un'ora. Impostazione per professionista `min_booking_notice_hours`, valore iniziale 24. Vale in entrambe le modalità.
- Validazione: `approval_window_hours` va da 1 a `min_booking_notice_hours`, così la risposta arriva sempre prima dell'inizio della seduta, e resta molto sotto la durata del blocco sulla carta (D-031). Proposta: al massimo 48.
- Le prenotazioni dal pannello (D-024) non passano dall'approvazione e non rispettano l'anticipo minimo.

**Alternative scartate.**
- *Approvazione sempre obbligatoria*: toglie al professionista la conferma immediata quando non gli serve il controllo.
- *Un booleano* (`requires_approval`): con un `enum` si potrà aggiungere un terzo valore (es. "solo se si sovrappone a una valutazione") senza cambiare il tipo della colonna.
- *Approvazione automatica alla scadenza*: contraddice la scelta di approvare a mano.
- *Scadenza a un'ora fissa* (es. "entro le 21 del giorno prima"): più complessa da spiegare e da calcolare di una finestra in ore.

**Conseguenze.**
- Nuovi stati della prenotazione al passo 7. `PENDING_APPROVAL` occupa l'orario: entra nel vincolo di non sovrapposizione (D-013) e nel limite di prenotazioni future (D-023). Per gli stati finali, nomi proposti `REJECTED`, `APPROVAL_EXPIRED` e `WITHDRAWN` (ritirata dal cliente).
- Un lavoro programmato, ogni minuto, annulla le richieste scadute.
- Il calcolo degli orari liberi (passo 6) scarta quelli che iniziano entro l'anticipo minimo.
- Una conferma che arriva quando mancano meno di 24 ore cade già dentro il preavviso di cancellazione. Esempio: richiesta 25 ore prima, approvata 12 ore dopo. **Accettato** (Nicholas): finché la richiesta è in attesa il cliente può ritirarla gratis, e il tempo di risposta lo conosce prima di prenotare. Il preavviso si conta sempre dall'inizio della seduta, non dalla conferma.

---

## D-031 — Pagamento delle sedute con carta: importo bloccato, incasso all'approvazione
**Stato:** accettata · 2026-11-06 · **modifica D-007** (il webhook conferma il blocco dei soldi, non la prenotazione) e una conseguenza di D-008 (pagamento arrivato tardi)

**Contesto.** Con l'approvazione (D-030) il professionista può rifiutare una prenotazione già pagata. Incassare e poi rimborsare contraddice "mai rimborso" (D-015) e costa le commissioni di Stripe. Approvare prima e far pagare dopo riapre il problema da cui nasce il progetto: il cliente che non paga.

**Decisione.**
- Le sessioni Checkout delle **sedute** si creano con *manual capture* (`payment_intent_data.capture_method = manual`). Stripe **blocca** l'importo sulla carta senza incassarlo.
- `checkout.session.completed` → pagamento `AUTHORIZED`, prenotazione `PENDING_APPROVAL` (D-030).
- Approvazione → il backend chiede a Stripe l'**incasso** (*capture*) → pagamento `SUCCEEDED`, prenotazione `CONFIRMED`.
- Rifiuto, scadenza della richiesta o ritiro → il backend **annulla** il blocco (*cancel* del PaymentIntent) → pagamento `CANCELED`. Il cliente non è mai stato addebitato.
- Con `AUTOMATIC` l'incasso parte subito dopo il webhook: stesso percorso.
- I **pacchetti** restano con incasso immediato: si comprano crediti, non orari, e non c'è niente da approvare.
- Pagamento arrivato tardi per una prenotazione già scaduta: si **annulla il blocco** invece di creare un credito.

**Alternative scartate.** *Approvare prima, pagare dopo*: il cliente può non pagare, e l'orario resta occupato per niente. *Pagare e rimborsare al rifiuto*: contro D-015, con commissioni perse.

**Conseguenze.**
- D-007 resta valida nel principio (a dire che i soldi ci sono è Stripe, mai il browser), ma il webhook ora conferma il **blocco**. La conferma della prenotazione arriva con la risposta di Stripe all'incasso.
- Due chiamate in uscita verso Stripe (incasso, annullamento), con errori da gestire al passo 9. Un incasso può fallire, per esempio con un blocco scaduto: il professionista ha approvato, ma la prenotazione non si può confermare.
- Il blocco sulla carta dura in genere circa 7 giorni per i pagamenti online: la finestra di risposta (D-030) deve restare molto più breve. Se un annullamento fallisce, il blocco si scioglie comunque da solo.
- Il lotto `LATE_PAYMENT` probabilmente non serve più per le sedute: da confermare al passo 10.
- Da verificare al passo 9 sulla documentazione di Stripe: eventuali commissioni sui blocchi annullati, e la durata esatta del blocco per le carte europee.

---

## D-032 — Notifiche push con Web Push, dal pannello installabile come app
**Stato:** accettata · 2026-11-06 · modifica l'architettura (modulo `notification`, prima solo email)

**Contesto.** Con l'approvazione manuale (D-030) il professionista deve sapere subito che c'è una richiesta, perché ha un tempo limitato per rispondere. Nicholas: le notifiche push servono in ogni caso. Il pannello è un sito (Next.js), non un'app.

**Decisione.**
- Il pannello diventa installabile sulla schermata del telefono come app (*PWA*, *Progressive Web App*), con un *service worker*.
- Notifiche con lo standard **Web Push**, firmate dal backend con una coppia di chiavi **VAPID**. La chiave privata è un segreto e va nel `.env`.
- Una tabella dei **dispositivi registrati**: un professionista può averne più di uno.
- **Email di riserva**: se il professionista non ha dispositivi registrati, o l'invio fallisce, la richiesta arriva anche via email.
- Il testo della notifica **non contiene il nome del cliente**, perché compare sullo schermo bloccato. Esempio: "Nuova prenotazione da approvare: giovedì 12, 9:30". Toccandola si apre il pannello sulla richiesta.

**Alternative scartate.** *App nativa iOS/Android*: due app, gli store, molto lavoro fuori dagli obiettivi del progetto. *Firebase Cloud Messaging*: account Google e SDK in più, senza un vero vantaggio per notifiche a pochi dispositivi. *Solo email*: meno immediata, e più facile da perdere. *Bot Telegram o WhatsApp*: dati e dipendenza presso terzi.

**Conseguenze.**
- Nessun account presso terzi, ma i messaggi **transitano** dai servizi push dei produttori dei browser (Apple, Google, Mozilla), cifrati: il contenuto non lo leggono.
- Su iPhone le notifiche arrivano solo se il pannello è **installato** sulla schermata principale, da iOS 16.4 in poi: va spiegato ai professionisti.
- Un dispositivo registrato può scadere: quando il servizio push risponde che non esiste più, la riga si cancella.
- Nuova migrazione (tabella dei dispositivi), nuovo segreto in `.env.example`, *service worker* e pagina di attivazione nel frontend. Lato backend al passo 7, insieme all'approvazione; lato frontend al passo 13.

---

## D-033 — Scatto di 30 o 60 minuti; la griglia parte dall'inizio di ogni fascia
**Stato:** accettata · 2026-11-06 · chiude il punto aperto 3 dei requisiti

**Contesto.** Le sedute iniziano ogni ora, ma ogni professionista può permettere l'inizio alla mezz'ora (requisiti, "Calendario"). `practitioner.slot_step_minutes` esiste dal V1 e accetta qualsiasi numero positivo, con default 30. Confronto completo: `docs/studio/13-calendario-panorama-e-scelte.md`, parte 3.

**Decisione.**
- `slot_step_minutes` vale solo **30 o 60**, con default **60**. Il pannello lo mostra come un interruttore "inizio alla mezz'ora sì/no" e invia il numero.
- La griglia parte dall'**inizio di ogni fascia di lavoro**: inizio, inizio + scatto, e così via, finché la seduta finisce entro la fascia.
- Le fasce iniziano e finiscono solo **all'ora piena o alla mezz'ora**.

**Alternative scartate.** *Un booleano `allow_half_hour_starts`*: una colonna da eliminare e una da creare, una traduzione in più nel calcolo, e nessuno spazio per un terzo valore. *Griglia agganciata all'orologio*: con una fascia che inizia alle 9:30 e scatto 60, la prima mezz'ora andrebbe persa.

**Conseguenze.** V3 sostituisce il vincolo dello scatto e il suo default. Con fasce a ore piene o mezz'ore, i due agganci danno lo stesso risultato con lo scatto di 30; differiscono solo con scatto 60 e fasce che iniziano alla mezz'ora, e lì vince l'orario scritto dal professionista.

---

## D-034 — Orari di lavoro in versioni settimanali, ognuna con una data di inizio
**Stato:** accettata · 2026-11-06 · chiude il punto aperto 13 dei requisiti; modifica la tabella `working_hours` progettata in `modello-dati.md`

**Contesto.** Lo studio vuole cambiare gli orari "quando e come vuole", anche **da una data futura** (es. orario estivo dal 1° giugno). Con uno schema settimanale unico, una modifica vale subito. Confronto completo: guida 13, parte 4.

**Decisione.**
- Una **versione** è una settimana-tipo completa (`working_schedule`), con una `valid_from` (data locale). Le sue fasce stanno in `working_hours`, che punta alla versione invece che al professionista.
- **Lettura**: per un giorno D vale la versione con la `valid_from` più recente tra quelle non successive a D. Nessuna versione → nessun orario libero.
- **Scrittura**: una versione si salva **intera**, con tutte le fasce della settimana, e il servizio rifiuta fasce sovrapposte nello stesso giorno. Una versione **già iniziata non si modifica**: per cambiare "da subito" se ne crea una con `valid_from` di oggi. Una versione **futura** si sostituisce o si cancella.
- `unique (practitioner_id, valid_from)`.
- Ferie e assenze restano **indisponibilità** (non versioni).

**Alternative scartate.** *Solo lo schema settimanale*: il caso "da una data futura" non si può fare. *Validità `from/to` su ogni fascia*: righe di periodi diversi possono accavallarsi nello stesso giorno, e impedirlo richiede vincoli complicati.

**Conseguenze.** È *effective dating*: la storia degli orari passati resta. Con l'anticipo minimo di più di 24 ore (D-030), una nuova versione da oggi non tocca orari ancora prenotabili. Al passo 7: una nuova versione può lasciare fuori orario prenotazioni già fatte, che restano valide e si segnalano nel pannello (requisiti, punto aperto 13).

---

## D-035 — Calcolo degli orari liberi: funzione pura, orari locali convertiti giorno per giorno
**Stato:** accettata · 2026-11-06 · anticipa al passo 6 la colonna dell'orizzonte di D-023 (la regola non cambia)

**Contesto.** Il calcolo degli orari liberi è la logica più ricca del sistema, dipende da "adesso" (anticipo minimo, D-030) e attraversa i cambi dell'ora. Confronto completo: guida 13, parti 2, 5 e 6.

**Decisione.**
- Il calcolo è una **funzione pura**: riceve versioni, indisponibilità, intervalli occupati (vuoti fino al passo 7), durata del servizio, impostazioni e `now` (`Instant`), e restituisce gli orari liberi. Non legge il database né l'ora di sistema.
- `ScheduleService` carica i dati, chiede l'ora a un bean `Clock` (`Clock.systemUTC()` in produzione, `Clock.fixed` nei test) e chiama il calcolo.
- Date e orari si generano **in locale** (`Europe/Rome`) e si convertono in `Instant` un giorno alla volta, con `ZonedDateTime.of`. Mai "istante + 24 ore".
- Intervalli **semiaperti** `[inizio, fine)`, come l'*exclusion constraint* del passo 7: si sovrappongono se `a1 < b2 && b1 < a2`.
- API pubblica: `GET /api/public/practitioners/{id}/services/{serviceId}/slots?from=…&to=…`, con date locali. L'intervallo si taglia in automatico su anticipo minimo e orizzonte; fuori dall'orizzonte la risposta è un elenco vuoto, non un errore. `from > to` → 400. Servizio non in vendita o professionista disattivato → 404.
- Orari nelle API in **ISO-8601 con l'offset di Roma** (`2026-11-09T09:00:00+01:00`, `OffsetDateTime`), in ingresso e in uscita. Ogni orario libero espone solo `start` ed `end`.
- **Orizzonte**: l'ultimo giorno prenotabile è oggi (a Roma) + `booking_horizon_days`, compreso. La colonna nasce nella V3 invece che al passo 7.
- Anticipo minimo in `practitioner.min_booking_notice_hours` (default 24, V3): sono liberi solo gli orari che iniziano **più di** N ore dopo `now`.
- Indisponibilità: `ends_at > starts_at`; possono sovrapporsi tra loro; si cancellano davvero; `reason` solo nel pannello, mai nelle API pubbliche.

**Alternative scartate.** *Calcolo dentro il servizio Spring, con query e `Instant.now()`*: ogni test avrebbe bisogno di un container e dipenderebbe dal giorno in cui gira. *Orari in UTC (`Z`) nelle API*: corretti, ma il frontend dovrebbe convertire ogni orario. *Errore per un intervallo oltre l'orizzonte*: il cliente che sfoglia le settimane non sta sbagliando niente.

**Conseguenze.** Il calcolo ha molti test unitari, compresi i cambi dell'ora di marzo e ottobre (guida 13, parte 7). Le dipendenze tra moduli diventano `schedule` → `catalog` → `practitioner` → `auth`, con `schedule` che usa anche `practitioner` direttamente. Al passo 7 `booking` fornisce gli intervalli occupati e chiede a `schedule` se un orario è libero; il calcolo deve considerare libere le `PENDING_PAYMENT` con blocco scaduto (`modello-dati.md`, `booking`).
