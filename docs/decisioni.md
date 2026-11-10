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
**Stato:** accettata · 2026-10-13 · conseguenza sul pagamento tardivo **modificata da D-031** (2026-11-06): si annulla il blocco sulla carta invece di creare un credito · durata **modificata da D-061** (2026-11-10): 32 minuti, con la sessione che scade nello stesso istante

**Contesto.** Durante la progettazione si era ipotizzato un blocco di 10 minuti. Stripe però non accetta sessioni Checkout che scadono prima di 30 minuti.

**Decisione.** Mentre il cliente paga, l'orario resta bloccato 30 minuti, la stessa durata della sessione Stripe. Alla scadenza (evento `checkout.session.expired` oppure lavoro programmato) la prenotazione passa a `EXPIRED`.

**Alternative scartate.** Blocco più breve con sessione Stripe più lunga: il cliente potrebbe pagare un orario già liberato e riassegnato.

**Conseguenze.** Un orario può restare bloccato fino a 30 minuti da chi poi non paga. Accettabile con due professionisti. Se un pagamento arriva comunque tardi, il cliente riceve un credito (lotto `LATE_PAYMENT`). *(Superato: D-031 annulla il blocco, D-068 toglie l'origine `LATE_PAYMENT`.)*

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
**Stato:** accettata · 2026-10-13 · **precisata da D-050** (2026-11-09): l'account nasce alla prima verifica giusta del codice, non alla prima prenotazione; il codice si salva come HMAC con una chiave segreta

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
**Stato:** accettata · 2026-10-16 · **completata da D-052** (2026-11-09): l'API dell'admin per i professionisti, e il primo admin da `ADMIN_EMAIL`

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
**Stato:** accettata · 2026-10-29 · completata da D-030 (2026-11-06): nel limite contano anche le `PENDING_APPROVAL` · la colonna dell'orizzonte nasce al passo 6 (V3), non al passo 7 (D-035) · valori iniziali fissati da D-041 (2026-11-06): 21 giorni e **4** prenotazioni

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
**Stato:** accettata · 2026-10-29 · pagamento in studio **confermato da D-040** (2026-11-06); chi ha creato la prenotazione: D-042 · "cosa rende un cliente suo" **completato da D-066** (2026-11-10)

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
- Il lotto `LATE_PAYMENT` probabilmente non serve più per le sedute: da confermare al passo 10. **Confermato da D-068** (2026-11-10): l'origine non esiste.
- Da verificare al passo 9 sulla documentazione di Stripe: eventuali commissioni sui blocchi annullati, e la durata esatta del blocco per le carte europee. **Verificato il 2026-11-10**: il blocco dura in genere 7 giorni per le carte online (Visa per le transazioni avviate dall'esercente 5), e Stripe annulla da sé un PaymentIntent non incassato dopo 7 giorni (documentazione). La documentazione **non** dice se un blocco annullato costa una commissione; nella **prova manuale in modalità di prova** il blocco annullato non ha lasciato niente nel saldo (né importo né commissione), mentre il pagamento incassato da 30 € ha portato 28,80 € (1,20 € di commissione, con la carta di prova `4242`, statunitense). Da riconfermare in produzione con il primo rifiuto vero (passo 14).

---

## D-032 — Notifiche push con Web Push, dal pannello installabile come app
**Stato:** accettata · 2026-11-06 · modifica l'architettura (modulo `notification`, prima solo email) · **modificata da D-039** (2026-11-06): lato backend al passo 8, non al passo 7 · **completata da D-054** (2026-11-09): lato backend fatto

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
**Stato:** accettata · 2026-11-06 · chiude il punto aperto 13 dei requisiti; modifica la tabella `working_hours` progettata in `modello-dati.md` · **precisata** lo stesso giorno (guida 15, parte 2): "già iniziata" vuol dire con `valid_from` **prima** di oggi; una versione che parte oggi si può ancora modificare o cancellare

**Contesto.** Lo studio vuole cambiare gli orari "quando e come vuole", anche **da una data futura** (es. orario estivo dal 1° giugno). Con uno schema settimanale unico, una modifica vale subito. Confronto completo: guida 13, parte 4.

**Decisione.**
- Una **versione** è una settimana-tipo completa (`working_schedule`), con una `valid_from` (data locale). Le sue fasce stanno in `working_hours`, che punta alla versione invece che al professionista.
- **Lettura**: per un giorno D vale la versione con la `valid_from` più recente tra quelle non successive a D. Nessuna versione → nessun orario libero.
- **Scrittura**: una versione si salva **intera**, con tutte le fasce della settimana, e il servizio rifiuta fasce sovrapposte nello stesso giorno. Una versione **già iniziata** (`valid_from` prima di oggi, a Roma) **non si modifica**: per cambiare "da subito" se ne crea una con `valid_from` di oggi, oppure si modifica quella di oggi se esiste già. Una versione da oggi in poi si sostituisce o si cancella. Non si creano versioni retrodatate.
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

**Conseguenze.** Il calcolo ha molti test unitari, compresi i cambi dell'ora di marzo e ottobre (guida 13, parte 7). Le dipendenze tra moduli diventano `schedule` → `catalog` → `practitioner` → `auth`, con `schedule` che usa anche `practitioner` direttamente. Al passo 7 `booking` fornisce gli intervalli occupati e chiede a `schedule` se un orario è libero; il calcolo deve considerare libere le `PENDING_PAYMENT` con blocco scaduto (`modello-dati.md`, `booking`). Attenzione al ciclo tra moduli che ne nasce (guida 16, 6.1): da risolvere al passo 7, con un'interfaccia dichiarata da `schedule` e implementata da `booking`, oppure spostando l'API pubblica in `booking`.

Implementazione (pezzo 3, guida 16): `FreeSlots.compute(SlotQuery)` nel package `schedule`; un `record` `SlotQuery` con i nove ingressi; dentro una fascia si avanza in istanti (niente aritmetica di `LocalTime`, che gira a mezzanotte); la versione di un giorno con `TreeMap.floorEntry`; `TimeRange` (intervallo semiaperto) pubblico in `shared`.

---

## D-036 — Fasce di lavoro non sovrapposte anche nel database: *exclusion constraint*
**Stato:** accettata · 2026-11-06

**Contesto.** Nella stessa versione dello schema (D-034), due fasce dello stesso giorno non devono sovrapporsi: il calcolo offrirebbe gli stessi orari due volte. Un `check` vede una riga sola, e un `unique` non conosce le sovrapposizioni. Confronto completo: `docs/studio/14-migrazione-v3-calendario.md`, parte 2.

**Decisione.**
- In V3: estensione `btree_gist`, tipo `timerange` (`create type timerange as range (subtype = time)`) e il vincolo `working_hours_no_overlap_ex`: `exclude using gist (schedule_id with =, day_of_week with =, timerange(start_time, end_time) with &&)`.
- Gli intervalli sono semiaperti `[inizio, fine)`, come vuole D-035: fasce che si toccano (9–13 e 13–14) sono accettate.
- Java controlla comunque prima (pezzo 2), per dare al professionista un errore chiaro: il vincolo è l'ultima difesa, come per D-025.

**Alternative scartate.** *Solo in Java*: un bug o un `insert` a mano passerebbero; e si rinuncerebbe a provare su un caso semplice lo strumento che al passo 7 sarà indispensabile (D-013).

**Conseguenze.** `btree_gist` è già installata per il passo 7. Una violazione arriva a Java come `DataIntegrityViolationException`, come gli altri vincoli (provato da `ScheduleSchemaTest`). Nel pezzo 2, sostituendo una versione futura, attenzione all'ordine delle istruzioni di Hibernate al *flush*: le cancellazioni esplicite vanno dopo gli `insert` (guida 14, 2.4). Vale anche per `unique (practitioner_id, valid_from)`. **Verificato** (pezzo 2): anche con l'*orphan removal* Hibernate 7 manda prima gli `insert` delle fasce nuove, e il vincolo rifiuta. Per questo `ScheduleService.replaceVersion` svuota le fasce, chiama `flush()` e poi aggiunge le nuove; un test sostituisce una versione con fasce identiche.

---

## D-037 — "Uno tra questi valori" con un vincolo Bean Validation nostro: `@OneOf`
**Stato:** accettata · 2026-11-06

**Contesto.** Lo scatto vale solo 30 o 60 (D-033). Il `check` del database rifiuta gli altri valori, ma con un 409 generico. Il pannello deve ricevere un 400 sul campo, come per le altre impostazioni. Bean Validation non ha un vincolo "uno tra questi numeri". Confronto completo: guida 14, parte 3.

**Decisione.**
- `@OneOf(int[] value)` con `OneOfValidator`, nel package `shared`. L'errore esce sul suo campo con il codice `OneOf`. `null` è valido: lo ferma `@NotNull`.
- L'entità resta la seconda difesa, con `Checks.requireOneOf`.

**Alternative scartate.** *`@Min(30) @Max(60)` più il controllo nell'entità*: 45 passerebbe la validazione e diventerebbe un 500. *Un metodo `@AssertTrue`*: l'errore uscirebbe su un campo che non esiste. *Un `enum`*: un valore sbagliato fa fallire la lettura del JSON, con un 400 generico senza il nome del campo.

**Conseguenze.** Il valore ammesso compare in tre punti (vincolo del database, annotazione, `Practitioner.SLOT_STEPS`), ognuno con un commento che rimanda agli altri. Al passo 7 la stessa tecnica, in versione "di classe", controllerà `approval_window_hours` ≤ `min_booking_notice_hours` (D-030).

---

## D-038 — API pubblica degli orari liberi: una domanda al catalogo, parametri come un `record`, finestra decisa dal server
**Stato:** accettata · 2026-11-06

**Contesto.** `GET /api/public/practitioners/{id}/services/{serviceId}/slots?from=&to=` (D-035) deve sapere se il servizio è in vendita e quanto dura (dati di `catalog`), validare due date che arrivano nella query string, e caricare le assenze senza che il lavoro dipenda da cosa chiede il client. Confronto completo: `docs/studio/17-api-orari-liberi.md`.

**Decisione.**
- `CatalogService.serviceOnSale(practitionerId, serviceId)` restituisce `ServiceOnSale(id, durationMinutes)`; "in vendita" vuol dire servizio attivo di un professionista attivo. In tutti gli altri casi la stessa `NotFoundException` (404).
- `from` e `to` in un `record` `SlotRange`, costruito dalla query string, entrambi obbligatori, in ISO, con il vincolo di classe `FromNotAfterTo` (400 sul campo `to`). Nessun limite di distanza: lo taglia l'orizzonte.
- Le assenze caricate sono quelle che si sovrappongono alla finestra **da adesso alla fine dell'ultimo giorno dell'orizzonte**, qualunque siano `from` e `to`.
- Esce solo `PublicSlot(start, end)` in ora di Roma.

**Alternative scartate.** *Riusare il catalogo pubblico e cercare il servizio nella lista*: carica tutto per usarne uno, e la regola "in vendita" finirebbe fuori da `catalog`. *Controllare `from` e `to` nel servizio*: un 409 per una richiesta sbagliata in sé (D-027). *Lasciarlo a `SlotQuery`*: un 500. *Caricare le assenze dei giorni richiesti*: con `to` nel 2099 la finestra la deciderebbe il client.

**Conseguenze.** Le dipendenze restano in un verso solo: `schedule` → `catalog` → `practitioner` → `auth`, e `schedule` → `practitioner`. Al passo 7 `booking` userà `serviceOnSale` per la durata e il prezzo da fotografare; e il ciclo tra `schedule` e `booking` per gli intervalli occupati va risolto per primo (guida 16, 6.1).

---

## D-039 — Notifiche push lato backend al passo 8, insieme all'email
**Stato:** accettata · 2026-11-06 · modifica D-032 (conseguenze: "lato backend al passo 7") · **completata da D-053** (2026-11-09): le email ascoltano gli eventi; `serviceId` aggiunto agli eventi

**Contesto.** D-032 metteva le notifiche push lato backend al passo 7, insieme all'approvazione. Ma la stessa D-032 vuole un'**email di riserva** quando il professionista non ha dispositivi registrati o l'invio fallisce, e il servizio email arriva al passo 8 (requisiti, punto aperto 5). Al passo 7 le push nascerebbero senza la loro riserva, cioè con richieste da approvare che possono andare perse.

**Decisione.**
- Le notifiche push lato backend (tabella `push_subscription`, chiavi VAPID, invio) passano al **passo 8**, insieme all'email.
- Il passo 7 **pubblica eventi di dominio** (es. "richiesta in attesa", "prenotazione confermata") senza ascoltatori. Al passo 8 email e push si agganciano a quegli eventi, senza toccare il modulo delle prenotazioni.
- Il lato frontend resta al passo 13.

**Alternative scartate.** *Push al passo 7 senza riserva*: un professionista senza dispositivi registrati non saprebbe delle richieste fino alla loro scadenza. *Email anticipata al passo 7*: anticipa a metà il passo 8 (scelta del servizio email, punto aperto 5).

**Conseguenze.** Il passo 7 si concentra su prenotazione, stati e vincoli. Finché il passo 8 non è fatto, nessuno viene avvisato delle richieste: non è un problema, perché il sistema non va online prima del passo 14.

---

## D-040 — Prenotazioni dal pannello pagate in studio; il link di pagamento si decide prima del passo 9
**Stato:** accettata · 2026-11-06 · conferma la proposta di D-024 · chiude il punto aperto 10 dei requisiti

**Contesto.** D-024 proponeva, per le prenotazioni fatte dal pannello senza crediti, il pagamento in studio. Nicholas lo conferma, e chiede in più un pulsante per inviare al cliente un link di pagamento, se ha un numero di telefono.

**Decisione.**
- Una prenotazione dal pannello senza crediti è **pagata in studio** (`paid_with = IN_STUDIO`): confermata subito, senza approvazione, anticipo, orizzonte né limite; se cancellata in tempo **non genera credito**.
- Il **pezzo 5 resta nel passo 7**, senza crediti (passo 10).
- Il **link di pagamento** non entra nel passo 7: richiede Stripe (passo 9) e apre domande da decidere prima (canale, numero non verificato, effetto sulle regole di cancellazione, link vecchi). Diventa il punto aperto 16 dei requisiti.

**Alternative scartate.** *Link di pagamento già al passo 7*: senza Stripe sarebbe un pulsante finto, e le sue conseguenze sulle cancellazioni non sono decise.

**Conseguenze.** `paid_with` esiste dalla V4 con `CARD` e `IN_STUDIO` (D-042); `CREDIT` si aggiunge al passo 10. Il pagamento in studio resta una scelta del professionista, mai un'opzione per chi prenota da solo (D-024).

---

## D-041 — Valori iniziali: orizzonte 21 giorni, limite 4 prenotazioni future
**Stato:** accettata · 2026-11-06 · completa D-023 · chiude il punto aperto 11 dei requisiti

**Contesto.** D-023 proponeva 21 giorni e 2 prenotazioni. Nicholas: un cliente fa in genere 2 sedute a settimana, e con un limite di 2 coprirebbe solo la settimana in corso. Contro: con il calendario quasi pieno (stima: circa 45 sedute a settimana per professionista, occupate per circa il 90%), un limite alto lascia agli abituali tutto l'orizzonte e poco spazio ai clienti nuovi.

**Decisione.**
- Valori iniziali: `booking_horizon_days = 21` (c'è già dalla V3), `max_future_bookings = 4` (V4). Restano modificabili dal pannello, per ogni professionista.
- Nel limite contano le prenotazioni `PENDING_PAYMENT`, `PENDING_APPROVAL` e `CONFIRMED` con **inizio nel futuro**: quando una seduta inizia, smette di contare e il cliente può prenotarne un'altra. L'orizzonte si sposta da solo (oggi + 21). Nessuna logica in più: è la regola di D-023.
- Dal pannello il professionista assegna tutte le sedute che vuole, senza limite (D-024); quelle sedute contano quando il cliente prenota da solo.

**Alternative scartate.** *Limite 2*: costringe gli abituali a prenotare una settimana alla volta. *Limite 6* (2 a settimana per 3 settimane): equivale a nessun limite per un cliente abituale. *Limite per settimana* ("al massimo 2 a settimana"): rende meglio "2 a settimana", ma cambia D-023 e complica il conteggio, mentre l'accumulo nel tempo lo ferma già l'orizzonte.

**Conseguenze.** Il default della colonna è 4. Una seduta in una `PENDING_PAYMENT` con blocco scaduto non deve contare (pezzo 2 del passo 7).

---

## D-042 — Schema delle prenotazioni (V4): vincolo parziale, `booked_by`, `check` con i soli valori prodotti
**Stato:** accettata · 2026-11-06 · completa D-013, D-024 e D-030 · confronto completo: `docs/studio/18-migrazione-v4-prenotazioni.md`

**Contesto.** La V4 crea `booking` e aggiunge a `practitioner` limite, approvazione e tempo di risposta. Da decidere: come si registra chi ha creato la prenotazione (D-024), quali valori ammettono i `check`, come si fa rispettare il legame tra tempo di risposta e anticipo minimo (D-030).

**Decisione.**
- **Non sovrapposizione** (D-013): `booking_no_overlap_ex`, `exclude using gist (practitioner_id with =, tstzrange(starts_at, ends_at) with &&) where (status in ('PENDING_PAYMENT', 'PENDING_APPROVAL', 'CONFIRMED'))`. In Java gli stessi tre stati in `BookingStatus.HOLDS_TIME`, con un commento che rimanda al vincolo.
- Il servizio deve essere del professionista della prenotazione: *foreign key* composta `(service_id, practitioner_id)`, come D-025.
- **Chi ha creato la prenotazione**: `booked_by`, `CUSTOMER` o `PRACTITIONER`. Dal pannello prenota solo il professionista della prenotazione, quindi la persona si ricava da `customer_id` o da `practitioner_id`.
- **Valori dei `check`**: solo quelli che il codice del passo produce. `status` con i sette stati del passo 7; `paid_with` con `CARD` e `IN_STUDIO` (D-040). I passi 10 e 11 allargano i `check` con una migrazione nuova (`drop constraint` + `add constraint`).
- `check` che legano lo stato a una colonna: `PENDING_PAYMENT` ha sempre `hold_expires_at`, `PENDING_APPROVAL` sempre `approval_deadline`. Le colonne non si svuotano quando lo stato cambia.
- `booking_in_studio_ck`: `IN_STUDIO` solo con `booked_by = PRACTITIONER`. Aggiunto durante l'implementazione: è la regola di D-024 ("mai un'opzione per chi prenota da solo") detta dal database.
- Colonne di momento `decided_at` (approvazione o rifiuto) e `cancelled_at` (ritiro, e dal passo 11 cancellazione); nessuna tabella della storia. `version` per `@Version`.
- Indici `(practitioner_id, starts_at)` e `(customer_id, starts_at)`.
- In `practitioner`: `max_future_bookings` (> 0, default 4, D-041), `booking_approval` (`AUTOMATIC`/`MANUAL`, default `MANUAL`), `approval_window_hours` (default 12, da 1 a `min_booking_notice_hours` e al massimo **48**). La migrazione aggiunge la colonna, corregge con un `update` le righe in cui il default supererebbe l'anticipo, e solo dopo aggiunge il `check`.
- In Java la regola tra i due campi è un vincolo di classe su `PractitionerSettings` (400 sul campo `approvalWindowHours`), come aveva previsto D-037, più il controllo nell'entità. `BookingApproval` è un `enum` pubblico di `practitioner`: un valore sbagliato nel JSON dà un 400 generico, accettato.
- Il test di concorrenza sul vincolo: due thread partono insieme, ognuno nella propria transazione confermata; ne passa uno solo.

**Alternative scartate.**
- *`created_by_user_id` verso `app_user`*: dice quale persona, ma oggi è ridondante, e per sapere "l'ha creata il cliente?" bisogna chiedere a un altro modulo.
- *Tutti gli stati del modello subito*: stati che nessun codice raggiunge nella macchina a stati.
- *La scadenza del blocco nel `where` del vincolo*: impossibile, `now()` non è `IMMUTABLE` (guida 18, 3.5).
- *Una tabella della storia delle transizioni*: più completa, ma nessun requisito la chiede; si può aggiungere senza toccare le colonne di momento.

**Conseguenze.** Una `PENDING_PAYMENT` con blocco scaduto occupa ancora l'orario per il vincolo finché qualcuno non ne cambia lo stato: va gestita nel pezzo 2. `modello-dati.md` descrive la V4 com'è. Una violazione del vincolo arriva come `DataIntegrityViolationException`, e il pezzo 2 la traduce in un 409 con un codice leggibile.

---

## D-043 — Il servizio di valutazione è una proprietà del servizio
**Stato:** accettata · 2026-11-06 · chiude il punto aperto 12 dei requisiti

**Contesto.** Stanza unica: la sovrapposizione tra una visita di valutazione di un professionista e una seduta dell'altro si segnala nel pannello, non si impedisce. Serve sapere quali servizi sono valutazioni.

**Decisione.**
- Colonna `service.evaluation boolean not null default false`, impostata dal professionista dal pannello (`ServiceOfferDetails`, `ServiceOfferView`). Non compare nel catalogo pubblico.
- **Non si fotografa** nella prenotazione: descrive cos'è il servizio, e una correzione deve valere anche per le sedute già prenotate. Il modulo delle prenotazioni lo chiede a `catalog` quando produce la segnalazione (D-002).
- La segnalazione mostra all'altro professionista solo l'orario e "valutazione", mai il nome del cliente.

**Alternative scartate.** *Un `enum` del tipo di servizio*: non si vede un terzo tipo; da booleano a `enum` si passa con una migrazione, se servirà. *Riconoscere la valutazione dal nome o dalla durata*: fragile.

**Conseguenze.** I servizi esistenti diventano sedute. Calcolo degli orari liberi e vincolo di non sovrapposizione restano per professionista.

---

## D-044 — Il ciclo tra `schedule` e `booking`: `schedule` dichiara `OccupiedTime`, `booking` lo implementa
**Stato:** accettata · 2026-11-06 · risolve il ciclo segnalato in D-035 e D-038

**Contesto.** `booking` chiede a `schedule` se un orario è libero; `schedule` deve sapere quali orari sono prenotati. Confronto: guida 16, 6.1; guida 18, parte 8.

**Decisione.**
- In `schedule`, l'interfaccia pubblica `@FunctionalInterface OccupiedTime` con `List<TimeRange> between(long practitionerId, TimeRange window, Instant now)`: gli intervalli del professionista che si sovrappongono alla finestra e sono occupati all'istante `now`. `now` è esplicito perché `ScheduleService` legge il `Clock` una volta sola.
- In `booking`, l'implementazione `BookingOccupiedTime`: package-private, dipende solo dal repository. Considera occupate `PENDING_APPROVAL`, `CONFIRMED` e le `PENDING_PAYMENT` con blocco non ancora scaduto.
- `ScheduleService.freeSlots` unisce assenze e intervalli occupati; `FreeSlots` non cambia.
- Nei test `@DataJpaTest` di `schedule`, un bean finto scritto come lambda ("niente di occupato"); la prova con le prenotazioni vere sta nel package `booking`.

**Alternative scartate.** *Spostare l'API pubblica degli orari liberi in `booking`*: il calcolo resterebbe in un modulo che non conosce tutto ciò che occupa il tempo. *Far implementare l'interfaccia a `BookingService`*: siccome `BookingService` userà `ScheduleService`, Spring non riuscirebbe ad avviarsi (riferimento circolare tra bean).

**Conseguenze.** Le dipendenze nel codice vanno in un verso solo: `booking` → `schedule` → `catalog` → `practitioner` → `auth`. Gli orari liberi escludono le prenotazioni vere; un blocco scaduto è libero per il calcolo ma non ancora per il vincolo (D-042).

---

## D-045 — Richiesta di prenotazione: un solo calcolo, lucchetto sul cliente, scadenza pigra dei blocchi
**Stato:** accettata · 2026-11-06 · confronto completo: `docs/studio/19-richiesta-di-prenotazione.md` · scelte di Claude secondo le raccomandazioni della guida, su indicazione di Nicholas ("se non ci sono domande, procedi senza chiedere conferma") · **completata da D-046** (2026-11-07): anche il *deadlock* `40P01` all'`insert` diventa `SLOT_NOT_AVAILABLE`

**Contesto.** Il cliente chiede un orario. I controlli sono sei (servizio in vendita, griglia e orari di lavoro, anticipo, orizzonte, limite, orario libero), il limite è un conteggio in Java esposto alla concorrenza (D-023), e un blocco scaduto occupa ancora l'orario per il vincolo del database (D-042).

**Decisione.**
- API del cliente sotto **`/api/customer`**: `POST /api/customer/bookings` (201 + `Location`, `NewBooking(practitionerId, serviceId, startsAt)`) e `GET /api/customer/bookings/{id}` (404 per la prenotazione di un altro). La fine la calcola il backend dalla durata.
- **Ordine**: servizio in vendita (404) → lucchetto sul cliente → limite (409 `BOOKING_LIMIT_REACHED`) → orario libero (409 `SLOT_NOT_AVAILABLE`) → scadenza dei blocchi vecchi sull'orario → `insert`. Il limite prima dell'orario: nessun altro orario risolverebbe il problema del cliente.
- **Orario libero**: `ScheduleService.requireFreeSlot(practitionerId, durationMinutes, start, now)` riusa il calcolo degli orari liberi del giorno. **Un solo codice**, `SLOT_NOT_AVAILABLE`, per griglia, orari, anticipo, orizzonte, assenze e orari occupati: l'azione del cliente è la stessa, e codici diversi rivelerebbero le prenotazioni.
- **Limite sotto concorrenza**: lucchetto pessimistico sulla riga del cliente (`@Lock(PESSIMISTIC_WRITE)`), con `CustomerService.lockForBooking` in `Propagation.MANDATORY`. Isolamento di default, `READ COMMITTED`, necessario perché il conteggio dopo l'attesa veda la prenotazione appena confermata.
- **Blocco scaduto**: scadenza "pigra" nella stessa transazione, con un `update` JPQL in blocco (`PENDING_PAYMENT` → `EXPIRED`) limitato ai blocchi scaduti che si sovrappongono all'orario richiesto; la versione si incrementa a mano. Nel limite un blocco scaduto non conta (stessa condizione del calcolo).
- **Violazione del vincolo**: `saveAndFlush`, poi `SQLSTATE 23P01` e nome `booking_no_overlap_ex` cercati nelle cause dell'eccezione → `SLOT_NOT_AVAILABLE`. La traduzione sta in `BookingService`, non nel gestore globale; ogni altra violazione si rilancia.
- **Cliente corrente provvisorio**: `CurrentCustomer` e `DevHeaderCurrentCustomer` (header `X-Customer-Id`) nel modulo `customer`, da rimuovere al passo 8.
- `BookingStatus` diventa pubblico: è nella risposta, quindi parte del contratto.

**Alternative scartate.** *Controlli dedicati in `booking`*: la stessa regola in due posti. *Lucchetto ottimistico o `SERIALIZABLE` per il limite*: chi perde fallisce e va ripetuto, invece di aspettare. *Lucchetto consultivo di PostgreSQL*: specifico e poco leggibile. *Aspettare il lavoro programmato*: fino a un minuto di "occupato" falso. *Riprovare dopo la violazione*: PostgreSQL annulla tutta la transazione. *Cancellare il blocco scaduto*: si perde la storia e il pagamento tardivo non troverebbe la sua prenotazione. *Tradurre il vincolo nell'`ApiExceptionHandler`*: dovrebbe conoscere i vincoli di tutti i moduli.

**Conseguenze.** Le richieste dello stesso cliente vanno una alla volta; quelle di clienti diversi no. Le prenotazioni dal pannello (pezzo 5) devono prendere lo stesso lucchetto, perché contano nel limite. Al passo 8 Spring Security apre `/api/customer/**` solo ai clienti. Il lavoro programmato del pezzo 4 farà la stessa transizione su tutti i blocchi scaduti, con la stessa guardia nel `where`.

---

## D-046 — Stati e approvazione: macchina nell'`enum`, lucchetto pessimistico sulle transizioni, eventi dopo il commit, pagamento simulato spento per default
**Stato:** accettata · 2026-11-07 · confronto completo: `docs/studio/20-stati-e-approvazione.md` · scelte di Claude secondo le raccomandazioni della guida, su indicazione di Nicholas · completa D-045 (traduzione del *deadlock*)

**Contesto.** Dopo la richiesta (D-045), la prenotazione deve arrivare alla conferma passando dal pagamento (simulato fino al passo 9) e dall'approvazione (D-030), con incasso e annullamento del blocco sulla carta (D-031). Il brief vuole una macchina a stati esplicita, un'interfaccia per incasso e annullamento, eventi di dominio senza ascoltatori (D-039) e un "pagamento autorizzato" che non sia mai attivo in produzione.

**Decisione.**
- **Macchina a stati**: `BookingStatus.canMoveTo`, uno `switch` espressione senza `default` (il compilatore obbliga a coprire ogni stato). Sei frecce: `PENDING_PAYMENT` → `PENDING_APPROVAL`, `EXPIRED`; `PENDING_APPROVAL` → `CONFIRMED`, `REJECTED`, `APPROVAL_EXPIRED`, `WITHDRAWN`. Nell'entità lo stato cambia solo con `moveTo`; transizioni con un nome (`paymentAuthorized`, `approve`, `reject`, `withdraw`). Un passaggio vietato è un 409 `BOOKING_TRANSITION_NOT_ALLOWED`.
- **Un solo percorso** per le due modalità: il pagamento porta sempre a `PENDING_APPROVAL`; in `AUTOMATIC` lo stesso `confirm` del professionista parte subito, nella stessa transazione.
- **Scadenza della risposta** = `created_at` + `approval_window_hours` (D-030: "dal momento della richiesta"). `booking.created_at` lo scrive Java con il `Clock`. Il professionista che risponde dopo la scadenza riceve 409 `APPROVAL_DEADLINE_PASSED`; il cliente può ritirare anche allora.
- **Pagamento tardivo**: su una `PENDING_PAYMENT` (anche con blocco scaduto) si accetta; su una `EXPIRED` si annulla il blocco e non cambia niente, senza errore.
- **`CardHolds`** (`capture`, `cancel`), dichiarata da `booking` e implementata da `payment` al passo 9; ora `LoggingCardHolds`, provvisoria.
- **Lucchetto pessimistico** sulla riga della prenotazione in ogni transizione, e quest'ordine: lucchetto, transizione in memoria, chiamata a `CardHolds`, commit. `@Version` resta come seconda difesa. Sostituisce l'anticipazione della guida 19 (4.3), che prevedeva il lucchetto ottimistico.
- **Eventi di dominio**: `sealed interface BookingEvent` con quattro `record` annidati (`ApprovalRequested`, `BookingConfirmed`, `BookingRejected`, `BookingWithdrawn`), solo id e istanti. Pubblicati con `ApplicationEventPublisher` dentro la transazione; al passo 8 ascoltati con `@TransactionalEventListener` (dopo il commit).
- **Pagamento simulato**: `POST /api/dev/bookings/{id}/payment-authorized`, controller con `@ConditionalOnProperty(booking.simulated-payments=true)`, default `false` (`BOOKING_SIMULATED_PAYMENTS` nel `.env`). Senza la proprietà l'indirizzo non esiste.
- **API**: `POST /api/panel/bookings/{id}/approve` e `/reject` (`PanelBookingView`), `POST /api/customer/bookings/{id}/withdraw`; `CustomerBookingView` riceve `approvalDeadline`.
- **Deadlock all'`insert`** (completa D-045): due `insert` contemporanei sul vincolo di non sovrapposizione possono aspettarsi a vicenda, perché un *exclusion constraint* inserisce nell'indice prima di cercare i conflitti; PostgreSQL ne annulla uno con `40P01`. `BookingService` lo traduce in `SLOT_NOT_AVAILABLE`, come `23P01`.

**Alternative scartate.** *Tabella `EnumMap`*: uno stato dimenticato non è un errore di compilazione. *Pattern State, Spring Statemachine*: troppo per stati senza comportamento proprio. *Lucchetto ottimistico sulle transizioni*: scopre il conflitto dopo l'incasso, che non si annulla. *Ascoltatori `@EventListener`*: un'email partirebbe anche per una transazione annullata. *Outbox*: una tabella e un lavoro in più, rimandabile al passo 8 se servirà. *Profilo `dev` o controller sempre attivo per il pagamento simulato*: sicuri solo finché nessuno sbaglia la configurazione. *Riprovare la transazione dopo il deadlock*: chi perde troverebbe comunque l'orario occupato, con più codice.

**Conseguenze (D-046).** La riga della prenotazione resta bloccata per la durata della chiamata a Stripe: irrilevante a questa scala. Resta il caso "incasso riuscito, commit fallito" (doppia scrittura), da chiudere al passo 9 riconciliando con il webhook (`payment_intent.succeeded`). Al passo 9 un annullamento fallito si registra senza bloccare la transizione. Al passo 8 gli ascoltatori che scrivono nel database hanno bisogno di `REQUIRES_NEW`. Il pezzo 4 aggiunge l'evento `ApprovalExpired` e usa `CardHolds.cancel` per le approvazioni scadute.

---

## D-047 — Lavori programmati: `fixedDelay` di un minuto, una transazione per richiesta, sicuri con più istanze
**Stato:** accettata · 2026-11-07 · confronto completo: `docs/studio/21-lavori-programmati.md` · scelte di Claude secondo le raccomandazioni della guida, su indicazione di Nicholas

**Contesto.** Blocchi di pagamento scaduti e richieste senza risposta vanno chiusi ogni minuto (D-008, D-030), leggendo l'ora dal `Clock`, con test che non aspettano davvero. Il brief chiede anche cosa succederebbe con due istanze dell'applicazione.

**Decisione.**
- `@Scheduled(fixedDelayString = "PT1M")` in `BookingJobs`, una classe separata da `BookingService`: un minuto dalla **fine** dell'esecuzione precedente, mai due esecuzioni sovrapposte; le chiamate passano dal *proxy* del servizio, quindi `@Transactional` vale.
- **Blocchi scaduti**: un solo `update` in blocco (`PENDING_PAYMENT` → `EXPIRED`, versione incrementata), con lo stato di partenza nel `where`. Nessun evento.
- **Richieste scadute**: prima gli id (`approval_deadline <= now`), poi **una transazione per richiesta** con il lucchetto sulla riga e la guardia **riletta dopo il lucchetto**; se non vale più (approvata, ritirata), non succede niente e non è un errore. Poi `APPROVAL_EXPIRED`, `CardHolds.cancel`, evento `ApprovalExpired`. Un errore su una richiesta si registra e non ferma le altre.
- `@EnableScheduling` in `SchedulingConfiguration`, acceso da `booking.jobs.enabled` (default `true`, variabile `BOOKING_JOBS_ENABLED`).
- Nei test: un `MovableClock` che si sposta a comando e i metodi dei lavori chiamati direttamente.

**Alternative scartate.** *`fixedRate`*: esecuzioni lente si accumulano. *`cron`*: aggancio all'orologio inutile qui. *Una transazione per tutto il lavoro delle richieste*: un errore annullerebbe nel database anche le richieste già chiuse, ma non le chiamate esterne già fatte. *ShedLock*: non serve con un'istanza, e il codice è già corretto con due.

**Conseguenze.** Con due istanze i lavori girano due volte: lavoro doppio, ma nessun effetto doppio (la seconda trova le righe già chiuse). Una richiesta scaduta resta aperta al massimo circa un minuto, coperto dal controllo della scadenza nelle risposte (D-046) e dalla scadenza pigra (D-045).

---

## D-048 — Prenotazione dal pannello: fasce e assenze sì, griglia no; cliente trovato o creato per email
**Stato:** accettata · 2026-11-07 · completa D-024 e D-040 · confronto completo: `docs/studio/22-prenotazione-dal-pannello.md` · scelte di Claude secondo le raccomandazioni della guida, su indicazione di Nicholas

**Contesto.** Il professionista prenota per un suo cliente (D-024), pagamento in studio (D-040). I documenti dicevano cosa non vale dal pannello (anticipo, orizzonte, limite, approvazione), non se valgono griglia, fasce e assenze; e non dicevano come si riconosce un cliente esistente.

**Decisione.**
- `POST /api/panel/bookings` con `serviceId`, `startsAt` e `customer` (`email`, `firstName`, `lastName`); 201 + `Location`; `GET /api/panel/bookings/{id}` per la propria.
- **Regole**: dentro una **fascia di lavoro** della versione valida quel giorno e fuori dalle **assenze** (`ScheduleService.requireWorkingTime`); non nel passato; **non** valgono griglia, anticipo, orizzonte, limite, approvazione. Sovrapposizioni: il vincolo del database, tradotto in `SLOT_NOT_AVAILABLE`.
- Nasce `CONFIRMED`, `booked_by = PRACTITIONER`, `paid_with = IN_STUDIO`, `decided_at` = adesso (`Booking.bookedFromPanel`, l'unico modo di creare una prenotazione confermata); evento `BookingConfirmed`. Prende lo stesso lucchetto sul cliente della richiesta (D-045).
- **Cliente per email**: `AccountService.customerAccountFor` trova o crea l'account `CUSTOMER` (email normalizzata, D-020), lo riattiva se disattivato, rifiuta con 409 `EMAIL_NOT_A_CUSTOMER` un'email di un professionista o dell'admin (D-021); `CustomerService.findOrCreate` trova o crea il cliente. Un cliente esistente **tiene il suo nome**. Tutto nella stessa transazione della prenotazione.
- `@ValidEmail` (in `auth`): Bean Validation che delega al `record` `Email`, una regola sola; 400 su `customer.email`.
- Dipendenze: `customer` → `auth`, oltre a `booking` → `customer`.

**Alternative scartate.** *Griglia anche dal pannello*: impedirebbe la seduta alle 9:30 dopo una valutazione 9:00–9:30, il caso per cui lo studio ha chiesto la mezz'ora. *Nessuna fascia dal pannello*: lo studio non lavora mai fuori dal proprio schema; una seduta fuori orario sarebbe un errore di battitura. *Scegliere il cliente da un elenco*: apre la domanda, ancora aperta, su quali clienti può vedere un professionista (passo 13). *`jakarta.validation.constraints.Email`*: accetta indirizzi che `Email` rifiuta, con un 500.

**Conseguenze.** Il professionista può prenotare a distanza ravvicinata o lontana nel tempo, e fuori griglia. La segnalazione della stanza unica "prima di confermare" (punto aperto 12) non blocca e compare nelle liste (pezzo 6); il frontend del passo 13 potrà mostrarla prima dell'invio. Due creazioni simultanee della stessa email: una fallisce sul `unique` di `app_user` (raro; ripetendo, si trova l'account).

---

## D-049 — Liste e segnalazioni: domande su insiemi di id, la stanza unica senza leggere i clienti degli altri
**Stato:** accettata · 2026-11-07 · completa D-034 e D-043 · confronto completo: `docs/studio/23-liste-e-segnalazioni.md` · scelte di Claude secondo le raccomandazioni della guida, su indicazione di Nicholas

**Contesto.** Il professionista deve vedere le proprie prenotazioni e le richieste da approvare, con due segnalazioni: prenotazione fuori dall'orario di lavoro attuale (D-034) e sovrapposizione con una valutazione dell'altro professionista (D-043), senza mai il nome del cliente dell'altro. Il cliente deve vedere le proprie.

**Decisione.**
- `GET /api/panel/bookings?from=&to=` (date locali, `SlotRange` riusato): le prenotazioni proprie che si sovrappongono al periodo, tutti gli stati, per inizio. `GET /api/panel/bookings/requests`: le `PENDING_APPROVAL` con scadenza nel futuro, prima quella che scade prima. `GET /api/customer/bookings`: le proprie, inizio decrescente.
- Riga `PanelBookingRow` con nome del cliente (chiesto a `customer` con `namesOf`, un insieme di id), chi l'ha creata, come si paga, scadenza, `outsideWorkingHours`, `evaluationOverlaps`.
- Segnalazioni **solo sulle righe che occupano l'orario adesso** (`Booking.holdsTimeAt`, la stessa regola delle due query del repository).
- `outsideWorkingHours`: la riga non sta in una fascia della versione valida nel suo giorno (`ScheduleService.outsideWorkingHours`, le versioni caricate una volta).
- `evaluationOverlaps`: gli intervalli condivisi con le prenotazioni degli altri professionisti che occupano l'orario, quando una delle due è una valutazione (`CatalogService.evaluationsAmong`, un insieme di id). La query sugli altri **seleziona solo servizio e orari** (`BookedRange`): il cliente dell'altro non viene nemmeno letto. La segnalazione ha solo `start` ed `end`.
- `BookedBy` e `PaidWith` diventano pubblici: sono nella risposta.

**Alternative scartate.** *Una join SQL tra prenotazioni, clienti e servizi*: attraverserebbe tre moduli (D-026). *Leggere le prenotazioni degli altri come entità e filtrare la risposta*: riservatezza per filtro, non per costruzione; un campo aggiunto per errore uscirebbe. *Fotografare "valutazione" nella prenotazione*: D-043 lo esclude. *Un limite alla lunghezza del periodo*: dati propri e pochi.

**Conseguenze.** Numero di query costante per lista (righe, altri, valutazioni, orari, nomi). Il frontend del passo 13 mostra la segnalazione anche prima di confermare una prenotazione dal pannello (punto aperto 12, "quando compare"), chiedendo la lista del giorno.

---

## D-050 — Codice di accesso via email: HMAC, risultato invece di eccezione, account alla verifica, limiti nel database
**Stato:** accettata · 2026-11-09 · precisa D-014 · confronto completo: `docs/studio/24-codice-di-accesso-email.md` · **completata da D-055** (2026-11-09): canale `SMS`, colonne `purpose`, `requested_by`, `message_sent` · scelte di Claude secondo le raccomandazioni della guida, su indicazione di Nicholas

**Contesto.** Il pezzo 1 del passo 8 costruisce richiesta e verifica del codice di D-014 (6 cifre, 10 minuti, 5 tentativi, solo l'impronta). Da decidere: la tabella, pensando all'SMS del pezzo 6; quale impronta, visto che i codici possibili sono solo un milione; come restano scritti i tentativi sbagliati; quando nasce l'account; cosa succede agli account disattivati; dove si contano i limiti; chi spedisce il codice, visto che `auth` non dipende da nessun modulo.

**Decisione.**
- **Tabella** `login_code` (V5), una per tutti i canali: `channel` (`check` con il solo `EMAIL`, il pezzo 6 aggiunge `SMS`), `destination` (l'email normalizzata), `code_hash`, `requested_from` (`inet`), `created_at` scritto da Java con il `Clock`, `expires_at`, `attempts` (`check` da 0 a 5), `used_at`. Lo scopo del codice (`purpose`) arriverà con il pezzo 6.
- **Impronta**: HMAC-SHA256 di `destinazione:codice` con una chiave segreta fuori dal database (`AUTH_CODE_HMAC_KEY`, Base64 di almeno 32 byte; senza, l'applicazione non parte). Confronto con `MessageDigest.isEqual`, a tempo costante. Codice da `SecureRandom`, con gli zeri davanti. La chiave non sta in un `record`, perché il suo `toString` la stamperebbe.
- **Verifica**: restituisce una `sealed interface CodeCheck` (`Valid(userId, newAccount)`, `Wrong`, `Expired`, `AccountDeactivated`) invece di lanciare. Un'eccezione annullerebbe con il rollback anche il tentativo contato. **Lucchetto pessimistico** sull'ultimo codice della destinazione. Vale solo l'ultimo codice chiesto: il quinto sbagliato risponde già `Expired`.
- **Primo accesso**: alla prima verifica giusta di un'email mai vista nasce l'account `CUSTOMER` (precisa D-014: non alla prima prenotazione). La richiesta non crea niente. Nome e cognome si chiedono dopo la verifica, una volta, nel pezzo 2.
- **Account disattivati**: l'accesso è rifiutato per ogni ruolo e non c'è riattivazione automatica. Alla richiesta la risposta è identica, ma l'email dice "account disattivato" invece di portare il codice; alla verifica, `AccountDeactivated`. La riattivazione avviene solo con un atto esplicito (prenotazione dal pannello, D-048, o l'admin).
- **Nessuna enumerazione**: ogni richiesta accettata fa lo stesso lavoro (una riga, un'email), quindi stessa risposta (202 senza corpo) e stesso tempo, che l'email esista o no.
- **Limiti**, contati sulle righe di `login_code`: 3 in 15 minuti e 10 in 24 ore per destinazione; 30 l'ora per IP. Oltre il limite, 429 con `Retry-After`. I valori stanno in un `record` `@ConfigurationProperties` (`auth.login-code.*`). Per IPv6 si salva e si conta solo la rete `/64`; l'IP arriva da Tomcat con `server.forward-headers-strategy=native`, mai leggendo `X-Forwarded-For` a mano. Un lavoro ogni ora cancella le righe con più di 24 ore: gli IP sono dati personali.
- **Spedizione**: `LoginCodeSender`, dichiarata da `auth` e implementata da `notification` (`EmailLoginCodeSender`, Spring Mail), come D-044. Prima il commit, poi la spedizione, fuori dalla transazione; se fallisce, 503. Mailpit in `infra/docker-compose.dev.yml` già dal pezzo 1. Timeout SMTP di 5 secondi.

**Alternative scartate.** *SHA-256 semplice*: un milione di codici si provano in meno di un secondo. *BCrypt*: lento a ogni verifica, e comunque in pochi minuti su una scheda grafica. *Una tabella per canale*: la stessa logica due volte. *Codice legato a `user_id`*: al primo accesso l'utente non esiste. *`noRollbackFor` o `REQUIRES_NEW` per i tentativi*: corretti, ma fragili o con due transazioni. *Account creato alla richiesta*: account spazzatura per ogni email digitata. *Account alla prima prenotazione*: sessioni senza utente. *Riattivazione al login*: renderebbe inutile la disattivazione. *Contatori in memoria*: azzerati a ogni riavvio, doppi con due istanze. *Codice nel log al posto dell'email*: un altro sostituto provvisorio, e un buco se finisse in produzione. *Un evento per spedire*: il codice in chiaro in un evento, e nessun modo di rispondere 503.

**Conseguenze.** `auth` resta senza dipendenze; nasce il modulo `notification` (→ `auth`). Il lavoro di pulizia si accende e si spegne con gli altri (`booking.jobs.enabled`, D-047). **Provato**: senza il lucchetto, 10 ipotesi sbagliate in parallelo sono state contate tutte come `Wrong`, cioè 10 tentativi invece di 5. L'endpoint della verifica, la sessione e `POST /api/customer/profile` sono del pezzo 2. Al passo 14: controllare che Caddy sostituisca `X-Forwarded-For` invece di accodarlo; il fornitore email vero (punto aperto 5) con il suo indirizzo mittente (`MAIL_FROM`) e le credenziali SMTP o API.

---

## D-051 — Sessione e Spring Security: solo l'id nella sessione, Spring Session JDBC, CSRF ovunque, `denyAll` per il resto
**Stato:** accettata · 2026-11-09 · completa D-014, D-028, D-029, D-045 e D-050 · confronto completo: `docs/studio/25-sessione-e-spring-security.md` · scelte di Claude secondo le raccomandazioni della guida, su indicazione di Nicholas

**Contesto.** Dopo la verifica del codice (D-050) il sistema deve ricordarsi dell'utente alle richieste successive, assegnare ogni area al suo ruolo con una regola sola (D-029), buttare fuori gli account disattivati anche con la sessione aperta, e togliere i due sostituti provvisori `DevHeaderCurrentPractitioner` e `DevHeaderCurrentCustomer` (D-028, D-045).

**Decisione.**
- **Accesso in un controller**: `POST /api/public/auth/session` chiama `LoginCodeService.verify` e decide con uno `switch` sui `record` di `CodeCheck`. Esiti: 200 con `{role}`; 401 `CODE_WRONG` o `CODE_EXPIRED`; 403 `ACCOUNT_DEACTIVATED` (codici in `AuthErrorCode`). All'accesso l'id della sessione cambia (`changeSessionId`), e l'utente si salva nella sessione a mano. `GET` dice chi c'è (401 se nessuno), `DELETE` invalida la sessione (204).
- **Nella sessione solo l'id dell'utente e l'istante dell'accesso.** Un `SecurityContextRepository` nostro (`SessionAccounts`) rilegge l'account a ogni richiesta, per chiave primaria e solo quando qualcuno chiede chi c'è. Se l'account è attivo, costruisce l'`Authentication` con il ruolo **attuale**. Se è disattivato, non esiste più o la sessione ha più di 90 giorni, invalida la sessione e la richiesta diventa anonima.
- **Spring Session JDBC**: le sessioni stanno in PostgreSQL e sopravvivono ai riavvii. Le tabelle vengono dalla migrazione V6, copiata dallo schema di Spring Session 4.1.1 (`initialize-schema=never`).
- **Durate**: 30 giorni di inattività (`spring.session.timeout`), 90 giorni al massimo dall'accesso (`SessionAccounts.MAX_DURATION`).
- **Cookie** `SESSION` dichiarato come bean (`SessionCookieConfiguration`): `HttpOnly`, `Secure`, `SameSite=Lax`, `Max-Age` di 90 giorni, preso dalla stessa costante.
- **CSRF** acceso ovunque, accesso compreso (contro il *login CSRF*), con `csrf.spa()`: token nel cookie `XSRF-TOKEN`, rimandato nell'header `X-XSRF-TOKEN`. Senza token, 403 `CSRF_TOKEN_INVALID`. Unica eccezione `/api/dev/**`, fino al passo 9.
- **Regole**: `/api/public/**` aperto; `/api/panel/**` solo `PRACTITIONER`; `/api/customer/**` solo `CUSTOMER`; `/api/admin/**` solo `ADMIN`; `/api/dev/**` aperto (esiste solo con la proprietà). Tutto il resto `denyAll()`, cioè chiuso anche a chi ha un account. Gli inoltri interni a `/error` sono permessi. *Request cache*, `formLogin`, `httpBasic` e `logout` di Spring sono spenti, e così il `UserDetailsService` automatico.
- **Risposte d'errore dei filtri**: le due funzioni di Spring Security che scrivono 401 e 403 (`AuthenticationEntryPoint`, `AccessDeniedHandler`) passano l'eccezione al `HandlerExceptionResolver` di Spring MVC. Così le scrive `ApiExceptionHandler` come `ProblemDetail`, nell'unico posto dove nascono le risposte d'errore.
- **`CurrentUser`** in `auth` (id e ruolo di chi agisce). `SessionCurrentPractitioner` e `SessionCurrentCustomer` sostituiscono i DevHeader senza cambiare le interfacce. Un cliente entrato senza nome e cognome riceve 409 `PROFILE_INCOMPLETE`.
- **Profilo**: `GET` e `PUT /api/customer/profile`, che leggono `CurrentUser`. `PUT` crea la riga `customer` al primo accesso e poi corregge i nomi.
- **Test**: una sessione vera, creata in Spring Session dall'aiutante `TestSessions`, più `csrf()`. L'accesso completo con il codice in `SessionApiTest`; ruoli e aree in `ConfidentialityTest`.

**Alternative scartate.** *JWT*: non si revoca, e un account disattivato resterebbe dentro fino alla scadenza. *Filtro e `AuthenticationProvider` nostri*: più classi, e gli esiti tornerebbero eccezioni. *One-Time Token Login di Spring*: link al posto del codice, senza tentativi né limiti. *`SecurityContext` intero nella sessione*: ruolo fermo al momento dell'accesso, e oggetti di Spring serializzati per mesi. *Sessioni nella memoria di Tomcat*: tutti fuori a ogni riavvio. *Redis*: un servizio in più. *CSRF spento confidando in `SameSite`*: una difesa sola. *`authenticated()` per il resto*: un'API fuori dai prefissi sarebbe aperta a ogni cliente. *Cookie da `server.servlet.session.cookie.*`*: Boot le applica solo con Tomcat vero, e nei test il cookie usciva senza attributi. *`.with(user(...))` di `spring-security-test`*: salterebbe `SessionAccounts`.

**Conseguenze.** Una query per chiave primaria in più per ogni richiesta autenticata. Il frontend (passi 12 e 13) legge `XSRF-TOKEN` e lo rimanda in ogni richiesta che cambia qualcosa. Dopo l'accesso di un cliente chiede `GET /api/customer/profile`, e con 409 mostra il modulo dei nomi. Al passo 9 il webhook di Stripe si aggiunge alle eccezioni CSRF, con la sua firma, e `/api/dev/**` sparisce. **Provato**: tolto il controllo dell'account attivo, tolto `changeSessionId`, o messo `authenticated()` al posto di `denyAll()`, fallisce ogni volta il test che lo protegge. Il token CSRF non cambia all'accesso: accettato, perché il token sta in un cookie del nostro dominio e non nella sessione.

---

## D-052 — Admin e professionisti: primo admin da `ADMIN_EMAIL`, API nel modulo `practitioner`, impostazioni iniziali dal database
**Stato:** accettata · 2026-11-09 · completa D-021 · confronto completo: `docs/studio/26-admin-e-professionisti.md` · scelte di Claude secondo le raccomandazioni della guida, su indicazione di Nicholas

**Contesto.** Il pezzo 3 del passo 8 deve far creare e disattivare i professionisti all'admin (D-021). Un professionista disattivato non deve entrare né essere prenotabile. E il primo admin deve nascere senza segreti nel repository, anche se solo un admin può creare account speciali.

**Decisione.**
- **Primo admin**: `AdminBootstrap`, un `ApplicationRunner` di `auth`, legge `auth.admin-email` (`ADMIN_EMAIL` nel `.env`, vuota per default) dopo Flyway. Vuota: niente. Email non valida: l'avvio si ferma. Nessun account: crea l'`ADMIN`, con `insert ... on conflict (email) do nothing`, sicuro con due istanze. Già `ADMIN`: niente, anche se disattivato (con un avviso). Un altro ruolo: l'avvio si ferma. **Crea soltanto, non promuove e non riattiva mai.** L'email non è un segreto, perché per entrare serve il codice che arriva in quella casella; è un dato personale, quindi sta solo nel `.env`, e non finisce nel log.
- **API** sotto `/api/admin/practitioners`, nel modulo `practitioner` (`PractitionerAdminController`): elenco, lettura, creazione (201 + `Location`), rinomina (`PUT`, solo nome e cognome), `POST .../deactivate` e `.../activate`, idempotenti. L'area la protegge la regola per prefisso di D-051; il modulo è quello che possiede i dati.
- **Creazione**: account e professionista nella stessa transazione. `AccountService.createPractitionerAccount` entra in quella di `PractitionerService.create`. Un'email che ha già un account, di **qualunque** ruolo, riceve 409 `EMAIL_ALREADY_IN_USE`: nessuna promozione, nessun riuso (D-021). Due creazioni contemporanee: il vincolo `unique` ferma la seconda (409 `DATA_CONFLICT`).
- **Impostazioni iniziali**: i default del database, non ripetuti in Java. Le otto colonne hanno `@Generated` di Hibernate: escluse dall'`insert`, rilette con `insert ... returning`, ancora scritte negli `update` (verificato). `Practitioner` ha ora un costruttore *package-private* (account, nome, cognome) e `rename`.
- **Disattivazione**: solo l'account (`AccountService.deactivate` e `activate`). Accesso, sessioni aperte, elenco pubblico, catalogo, orari liberi e nuove prenotazioni seguono da soli (D-029, D-050, D-051). Le prenotazioni già fatte **non si toccano**: le richieste in attesa scadono da sole (D-047), le confermate future sono una cancellazione da parte dello studio (punto aperto, prima del passo 11).
- **Elenco**: `AccountService.summariesOf(Collection<Long>)` restituisce una `Map` di `AccountSummary(email, active)`, un `record` e mai l'entità; una query sola, come `activeAmong`.

**Alternative scartate.** *Admin in una migrazione*: l'email personale nel repository per sempre, lo stesso admin in ogni ambiente, una riga in più in ogni database di test. *Script SQL a mano*: un passo che si dimentica o si sbaglia, e che nessun test prova. *Il primo che entra diventa admin*: una gara alla messa online. *Promuovere l'account esistente*: un errore di battitura nel `.env` diventerebbe un'escalation di privilegi. *Un modulo `admin`*: un inoltro senza dati propri. *Valori iniziali in Java*: due verità sullo stesso fatto. *`insert` nativo*: scavalca l'entità, non serve dopo la verifica di `@Generated`. *Rifiutare la disattivazione con prenotazioni future*: la disattivazione deve funzionare anche in urgenza, e servirebbe una dipendenza rovesciata verso `booking` per una regola non ancora decisa. *Cambiare l'email dall'admin*: cambia il modo di entrare, scelta a parte se servirà.

**Conseguenze.** Nessuna migrazione nuova. `.env.example` ha `ADMIN_EMAIL=`; ognuno mette la sua nel proprio `.env`, e al passo 14 va impostata in produzione. **Provato**: tolto `@Generated` da una colonna, tolto il controllo dell'email già usata, tolto il controllo del ruolo all'avvio, tolta la disattivazione dell'account, fallisce ogni volta il test che lo protegge. Per il passo 13: la pagina dell'admin, che mostra le prenotazioni future di un professionista prima di disattivarlo. Per il passo 11: cosa succede alle prenotazioni confermate di un professionista disattivato.

---

## D-053 — Notifiche email: ascoltatore `@Async` dopo il commit su virtual thread, testi in una funzione pura, nessuna *outbox*
**Stato:** accettata · 2026-11-09 · completa D-039 e D-046 · confronto completo: `docs/studio/27-notifiche-email.md` · scelte di Claude secondo le raccomandazioni della guida, su indicazione di Nicholas

**Contesto.** Il pezzo 4 del passo 8 aggancia le email ai cinque eventi di `booking` (D-039, D-046). Il brief chiede un'email in Mailpit per ogni evento, e che un invio fallito non annulli la prenotazione. Gli eventi portano solo id e istanti, le email hanno bisogno di indirizzi, nomi e servizio.

**Decisione.**
- **Sei email.** `ApprovalRequested` → professionista ("nuova richiesta da approvare", con la scadenza) e cliente ("richiesta inviata": importo solo bloccato, risposta entro la scadenza, ritiro gratuito). `BookingConfirmed` (anche dal pannello), `BookingRejected`, `ApprovalExpired`, `BookingWithdrawn` → cliente. Al professionista niente per conferme, ritiri e scadenze: se servirà, una push (pezzo 5).
- **`@Async` + `@TransactionalEventListener`** (`notification/BookingNotifications`): l'email parte dopo il commit, in un altro thread. La risposta e i lavori programmati non aspettano mai il server di posta, e un errore di posta non può raggiungerli: il criterio del brief vale per costruzione, non per un `catch` da non dimenticare.
- **Un executor dedicato con virtual thread** (`NotificationConfiguration`, `SimpleAsyncTaskExecutor` con `setVirtualThreads(true)`, un thread per email, 10 secondi di attesa allo spegnimento). Non `spring.threads.virtual.enabled`, che sposterebbe anche Tomcat e lo scheduler dei lavori.
- **Dati**: `serviceId` entra in `BookingEvent` (un id: resta "solo id e istanti"; è l'unica modifica a `booking`). Il resto lo chiede `notification` ai moduli, dopo il commit: `CustomerService.contactOf` (`CustomerContact`), `PractitionerService.contactOf` (`PractitionerContact`, anche se disattivato), `CatalogService.serviceNameOf` (anche se disattivato).
- **Testi** in `BookingEmails`, una funzione pura con uno `switch` sulla `sealed interface` senza `default`. Testo semplice con *text block*. Negli oggetti la data, mai nomi né trattamenti; nell'email al professionista mai il nome del cliente (D-032). Date a Roma con `Locale.ITALIAN`. Link all'indirizzo base `notification.app-url` (`APP_URL`, default `http://localhost:3000`).
- **Errori**: ogni email ha il suo `try`; il log dice evento, id della prenotazione e ruolo del destinatario, mai l'indirizzo. Nessun nuovo tentativo.
- **`MailSender`** invece di `JavaMailSender` in `BookingNotifications`: basta `send(SimpleMailMessage)`.

**Alternative scartate.** *Ascoltatore nello stesso thread*: la risposta aspetta la posta, e un'eccezione dopo il commit arriva al chiamante come 500 per un'operazione riuscita. *Transactional outbox*: niente si perde, ma una tabella, un lavoro, i tentativi e la pulizia per una trentina di email al giorno; resta la strada se i log mostreranno email perse. *Nuovi tentativi in memoria*: coprono un singhiozzo, non un fornitore fermo. *Nomi e indirizzi nell'evento*: `booking` raccoglierebbe dati non suoi, e il nome del cliente arriverebbe anche alla push. *Query con le `join` da `notification`*: conoscerebbe le tabelle di cinque moduli (D-002). *Email HTML con Thymeleaf*: quando lo studio avrà un'identità grafica. *Email al professionista per ogni prenotazione in automatico*: venti al giorno, che smetterebbe di leggere.

**Conseguenze.** Nessuna migrazione. Un'email si perde se l'applicazione cade tra il commit e la spedizione, o se il server di posta la rifiuta: scritto nel log a livello `ERROR`. Nei test d'API con `@Transactional` gli ascoltatori non partono (rollback), quindi nessun test esistente è cambiato; i test nuovi fanno commit veri e aspettano le email con Awaitility. I test girano con lingua inglese e fuso UTC, come un server (`pom.xml`). **Provato**: tolti a turno `@TransactionalEventListener`, `@Async`, i virtual thread, il `try` per singola email, il fuso di Roma, la regola sul nome del cliente, `Locale.ITALIAN`: fallisce ogni volta il test che lo protegge. Per i passi 12 e 13: i link alle pagine giuste. Per il passo 9: l'email del pagamento arrivato tardi. Per il pezzo 5: la push con la stessa email come riserva.

---

## D-054 — Notifiche push: Web Push con le sole classi del JDK, solo verso i servizi push noti, email al professionista come riserva
**Stato:** accettata · 2026-11-09 · completa D-032 e D-039 · precisa `modello-dati.md` (`push_subscription` riferita a `practitioner`, non ad `app_user`) · confronto completo: `docs/studio/28-notifiche-push.md` · scelte di Claude secondo le raccomandazioni della guida, su indicazione di Nicholas

**Contesto.** Il pezzo 5 del passo 8 fa il lato backend delle notifiche push di D-032: dispositivi registrati, messaggio cifrato e firmato, email di riserva. Le librerie Java per Web Push sono o ferme da anni con dipendenze vulnerabili (`nl.martijndwars:web-push`, Bouncy Castle vecchio), o recenti e con un solo autore.

**Decisione.**
- **Nessuna libreria**: cifratura RFC 8291 (ECDH P-256, HKDF, AES-128-GCM, codifica `aes128gcm` di RFC 8188) e firma VAPID RFC 8292 (JWT ES256 con `SHA256withECDSAinP1363Format`) con le classi del JDK 21 (`PushEncryption`, `Vapid`, `P256`). La cifratura è verificata **byte per byte** contro l'esempio dell'appendice A dell'RFC 8291; un finto browser scritto nei test decifra il giro completo.
- **Chiavi**: `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` (segreto), `VAPID_SUBJECT` nel `.env`, Base64url come le altre librerie, generate con `java tools/GenerateVapidKeys.java` (JEP 330). Senza la coppia, o con due chiavi che non sono una coppia, **l'avvio si ferma**. I test usano le chiavi pubbliche dell'RFC.
- **Tabella `push_subscription` (V7)** in `notification`, riferita a **`practitioner`** (solo i professionisti ricevono push, D-032; la *foreign key* lo garantisce). `endpoint` unico; registrazione idempotente con `insert ... on conflict (endpoint) do update` (lo stesso browser torna, o passa a un altro professionista); al massimo 10 dispositivi per professionista, i più vecchi escono.
- **API** sotto `/api/panel/push-subscriptions`: `GET /vapid-key`, `POST` (il JSON di `PushSubscription.toJSON()`, 204), `POST /unsubscribe` (solo i propri, 204 sempre).
- **SSRF**: `@PushEndpoint` accetta solo `https` sulla porta standard verso `fcm.googleapis.com`, `push.services.mozilla.com`, `push.apple.com`, `notify.windows.com` e i loro sottodomini; `@BrowserPublicKey` rifiuta una chiave fuori dalla curva. Il controllo è all'ingresso; chi spedisce si fida delle righe.
- **Quando**: solo `ApprovalRequested`. Push a tutti i dispositivi; se **almeno uno** la accetta (2xx), l'email al professionista non parte; altrimenti parte (nessun dispositivo, errori, servizio irraggiungibile, un bug). `TTL` fino alla scadenza della risposta, `Urgency: high`. Nello stesso ascoltatore del pezzo 4, dopo il commit, nel virtual thread dell'email.
- **Risposte**: 404/410 cancellano la riga; 429 e 5xx `WARN`, 400/403/413 `ERROR`; mai l'`endpoint` intero nei log, solo l'host.
- **Testo**: `{"title", "body", "url", "tag"}` da un `record` con Jackson; data, ora e scadenza; né nomi né trattamento; `tag` = `booking-<id>`.

**Alternative scartate.** *`nl.martijndwars:web-push`*: ferma da anni, Bouncy Castle con vulnerabilità note. *Librerie recenti di un solo autore*: fiducia da costruire, per un codice che il JDK fa in un centinaio di righe verificabili. *Firebase Cloud Messaging*: già scartato da D-032. *Qualunque `https`*: l'SSRF verso host interni resta. *Riferimento ad `app_user`*: permetterebbe dispositivi di clienti e admin, che non ricevono push. *Email sempre insieme alla push*: due avvisi per ogni richiesta, contro D-032. *Push anche per ritiri e scadenze*: nessuna azione da fare.

**Conseguenze.** Migrazione V7. Tre righe nuove nel `.env` (le chiavi sono **obbligatorie** per avviare il backend). Cambiare la coppia VAPID invalida tutte le iscrizioni. Non provato con un servizio push vero: lo si vede al passo 13, con il primo telefono iscritto. **Provato**: tolti a turno lo zero di `key_info`, il formato P1363, il controllo della curva, il controllo `https`, la regola della riserva, la cancellazione dei 404/410, il 410 come "scaduto", il limite di 10 dispositivi, la proprietà nella cancellazione e il `TTL` fino alla scadenza, fallisce ogni volta il test che lo protegge. Per il passo 13: iscrizione con la chiave pubblica, invio a ogni visita, cancellazione all'uscita, il *service worker* che legge `title`, `body`, `url`, `tag`. Per il passo 14: chiavi di produzione e `VAPID_SUBJECT` dello studio.

---

## D-055 — Codice di accesso via SMS: solo numeri verificati, solo cellulari italiani, mai un account da un numero, invio asincrono, tetto di spesa
**Stato:** accettata · 2026-11-09 · chiude il punto aperto 7 dei requisiti · completa D-050 (canale `SMS`, scopo del codice) e D-051 (area `/api/account/**`) · confronto completo: `docs/studio/29-codice-via-sms.md` · scelte di Claude secondo le raccomandazioni della guida, su indicazione di Nicholas · **modificata da D-056** (2026-11-10): tetto a 50 SMS al giorno, l'admin accede solo con l'email

**Contesto.** Proposta di Nicholas (requisiti, punto 7): il codice di accesso via SMS, se l'utente ha un numero verificato. Un SMS costa e non si può spedire a numeri sconosciuti, quindi le difese del pezzo 1 contro l'enumerazione vanno ripensate, e il modulo che spedisce SMS va difeso dalla frode (*SMS pumping*).

**Decisione.**
- **Numero**: `app_user.phone` in E.164, unico, scritto **solo** dalla verifica, con `phone_verified_at` (`check` sulla coppia). Un `record` `PhoneNumber` come `Email` (D-020): normalizza e accetta **solo cellulari italiani** (`+39 3`, 8 o 9 cifre dopo il 3); il database controlla solo il formato E.164. Niente libphonenumber.
- **Verifica**: da entrati, in `/api/account/phone` (area nuova `/api/account/**`, `authenticated()`, per ogni ruolo; **dal 2026-11-10 non per l'admin**, D-056). Codice con scopo `VERIFY_PHONE`, legato all'account che lo chiede (`login_code.requested_by`). Codice giusto: il numero è dell'account, e lo perde l'account che lo aveva prima (i numeri si riciclano). Codice sbagliato: **409** `CODE_WRONG`/`CODE_EXPIRED` (la persona è entrata: un 401 la farebbe credere fuori), con un risultato `PhoneCheck` invece di un'eccezione.
- **Scopo** (`login_code.purpose`): `SIGN_IN` o `VERIFY_PHONE`; l'ultimo codice si cerca per canale, destinazione e scopo. Un codice di verifica non apre una sessione, uno di accesso non verifica.
- **Accesso**: `POST /api/public/auth/login-code/sms` (202 sempre) e `POST /api/public/auth/session/sms` (le risposte dell'email). Un numero **non crea mai un account**. La riga si scrive per ogni numero, anche di nessuno (`message_sent = false`), così i limiti si comportano uguali; l'SMS parte solo al numero verificato di un account attivo, e **in un altro thread**, così il tempo di risposta non rivela chi è cliente (si perde il 503 per un fornitore che non risponde: resta il log, e l'email).
- **Limiti**: quelli di D-050 sul canale `SMS` (per destinazione, per IP insieme all'email), più **5 verifiche al giorno per account** e un **tetto di 50 SMS spediti al giorno** in tutto (D-056; era 100) (contati con `message_sent`, controllato per ogni numero). Oltre: 429.
- **Spedizione**: `SmsCodeSender` dichiarata da `auth`, **facoltativa** (`ObjectProvider`): senza, le API SMS rispondono subito 503 `SMS_UNAVAILABLE`, uguale per ogni numero. In sviluppo `MailpitSmsCodeSender` (`notification.sms.mailpit=true`, `SMS_TO_MAILPIT`, spento per default) spedisce il testo come email a `sms-<numero>@sms.local`, in un virtual thread. Il fornitore vero è un punto aperto nuovo (requisiti, punto 17).
- **Testo** (`SmsTexts`, funzione pura): al massimo 160 caratteri dell'alfabeto GSM-7 di base, "Non darlo a nessuno, nemmeno allo studio", ultima riga `@host #codice` (*origin-bound one-time codes*, compilazione automatica solo sul nostro sito).

**Alternative scartate.** *Account creato dal numero*: un numero inventato o riciclato aprirebbe un account. *Numero non verificato in `app_user`*: chiunque potrebbe occupare il numero di un altro. *libphonenumber*: giusta per numeri di tutto il mondo, troppo per una regola di due righe. *Rifiutare un numero già di un altro account*: lascerebbe senza SMS chi ha il telefono adesso, e rivelerebbe che quel numero è di un cliente. *Nessuna riga per i numeri sconosciuti*: i limiti direbbero chi è cliente. *Invio sincrono*: i tempi direbbero chi è cliente. *Codici SMS nel log in sviluppo*: escluso dalla guida 24. *Un campo `phone` nelle API dell'email*: una validazione "uno dei due" invece di due indirizzi semplici.

**Conseguenze.** Migrazione V8 (`app_user.phone` azzerato: nessun codice l'aveva mai scritto). In produzione l'SMS resta spento finché il fornitore non è scelto e implementato come un altro `SmsCodeSender`. Il punto aperto 16 (link di pagamento) può contare su un numero verificato. **Provato**: tolti a turno la regola dei cellulari italiani, il "solo numeri verificati", la riga per i numeri di nessuno, il tetto, il conteggio dei soli SMS spediti, il legame del codice di verifica all'account, il `flush` prima di spostare il numero, il limite per account, l'alfabeto GSM-7, il 409, il 503 senza fornitore, fallisce ogni volta il test che lo protegge.

---

## D-056 — L'admin accede solo con l'email; tetto degli SMS a 50 al giorno
**Stato:** accettata · 2026-11-10 · **modifica D-055** · richiesta di Nicholas dopo la chiusura del passo 8 · scelte di realizzazione di Claude

**Contesto.** D-055 apriva la verifica del numero, e quindi l'accesso via SMS, a ogni ruolo, admin compreso, con un tetto di 100 SMS spediti al giorno. Due correzioni prima del passo 9. La prima: l'account dell'admin è il più prezioso (crea e disattiva i professionisti, D-052). Con lo **scambio di SIM** (*SIM swap*: qualcuno si fa intestare il numero dall'operatore, spesso con un documento falso o una telefonata), chi ha la nuova SIM riceve i codici, e non gli serve altro. La seconda: il tetto può essere più basso, perché il volume atteso è di poche decine di codici al giorno (guida 29, 5.2).

**Decisione.**
- **Tetto**: 50 SMS spediti al giorno (`auth.login-code.sms.sent.max=50`, in `application.properties`; non è una variabile d'ambiente, quindi `.env.example` non cambia). Con i prezzi indicativi di 5–9 centesimi a SMS, il caso peggiore costa meno di 5 euro al giorno.
- **Admin solo con l'email**: l'admin non verifica un numero, quindi non ha un numero, quindi non riceve SMS di accesso. La regola sta in tre punti, dal più esterno al più interno:
  - `SecurityConfiguration`: `/api/account/phone/**` solo a `CUSTOMER` e `PRACTITIONER`, prima della regola di `/api/account/**`; l'admin riceve **403** su ogni metodo (lettura, richiesta del codice, conferma, cancellazione);
  - `AppUser.verifyPhone` rifiuta l'admin con un'`IllegalStateException`, se un giorno un'altra strada chiamasse il servizio;
  - migrazione **V9**: `app_user_admin_no_phone_ck` (`role <> 'ADMIN' or phone is null`), dopo aver tolto il numero a un eventuale admin scritto a mano.
- **Accesso via SMS**: nessun controllo in più. Un admin non può avere un numero, quindi una richiesta a qualunque numero non trova mai un admin: è la richiesta per un numero di nessuno (202, nessun SMS), come vuole D-055.

**Alternative scartate.** *Solo la regola di Spring Security*: basterebbe una porta dimenticata aperta in futuro (un'altra API che chiama il servizio). *Solo il vincolo nel database*: l'errore arriverebbe al commit come una violazione generica, e l'API risponderebbe 500 invece di 403. *Un controllo sul ruolo anche in `requestSms` e `verifySms`*: codice che nessun test può raggiungere, perché il database impedisce lo stato che controlla. *Lasciare 100*: il tetto è il massimo della spesa, e il volume atteso sta comodo sotto 50.

**Conseguenze.** La sicurezza dell'account admin dipende ora solo dalla sua casella email, che va protetta con la verifica in due passaggi del suo fornitore (da ricordare al passo 14). Il frontend del pannello admin (passo 13) non mostra la sezione del telefono. Per i professionisti vale lo stesso ragionamento in misura minore (il loro account vede clienti e prenotazioni): oggi restano con l'SMS, da rivalutare se lo studio lo chiede. **Provato**: tolti a turno la regola di sicurezza, il controllo in `AppUser`, il vincolo della V9 e il valore 50, fallisce ogni volta il test che li protegge (`SmsAccessApiTest`, `SmsCodeServiceTest`, `LoginCodeSchemaTest`).

---

## D-057 — Conti di incasso configurabili: uno per professionista o uno comune, senza Stripe Connect
**Stato:** accettata · 2026-11-10 · chiude il punto aperto 1 dei requisiti · brief del passo 9

**Contesto.** I due professionisti sono forfettari, quindi due partite IVA distinte (D-022): di regola ognuno incassa sul proprio conto. Lo studio però può preferire, oggi o in futuro, un conto solo; e la scelta può cambiare dopo la messa online. Stripe offre per i casi "più venditori su una piattaforma" il prodotto **Connect**.

**Decisione.**
- Il sistema conosce uno o più **conti di incasso**, ognuno un conto Stripe a sé, con un **nome** (es. `rossi`, `studio`). Sono dichiarati nella configurazione; la chiave segreta e il segreto del webhook di ogni conto vengono da variabili d'ambiente, mai dal repository (`.env.example` li elenca senza valori).
- Ogni professionista ha **un conto assegnato**, scelto dall'admin. Più professionisti possono avere lo stesso conto (conto comune). **Aggiungere un conto** richiede un riavvio (nuove variabili d'ambiente); **cambiare l'assegnazione** no, si fa in produzione dall'API dell'admin.
- Ogni pagamento registra **il conto su cui è stato fatto**. Incasso, annullamento e qualunque altra operazione successiva avvengono sempre su quel conto, mai su quello assegnato in quel momento al professionista: cambiare conto non tocca i pagamenti in corso.
- **Niente Stripe Connect.**

**Alternative scartate.** *Stripe Connect*: il motivo **non** sono i costi. Con l'opzione "Stripe gestisce le tariffe per i tuoi utenti" la piattaforma non paga commissioni Connect (verificato su stripe.com/connect/pricing il 2026-11-10: nessuna commissione per account attivo né per bonifico; solo con "Gestisci le tariffe in autonomia" si pagano 2 € per account attivo al mese e lo 0,25% + 0,10 € per bonifico). Il motivo è che con Connect **qualcuno diventa la piattaforma** tra lo studio e Stripe: un conto "piattaforma" (di Nicholas, o di uno dei due professionisti) che accetta il contratto di piattaforma con Stripe, ne porta le responsabilità verso i conti collegati, e per cui va costruito un **percorso di registrazione dei conti** (*onboarding*) nel sistema. Tutto questo per due conti che i professionisti possono aprire da soli dalla dashboard di Stripe. *Un solo conto fisso nel codice*: obbligherebbe a una modifica del codice il giorno in cui lo studio passa a due conti, o viceversa. *Due conti fissi, uno per professionista*: non regge il conto comune, né un terzo professionista.

**Conseguenze.**
- Ogni professionista apre il proprio conto Stripe e ne consegna le chiavi a chi gestisce il server (passo 14); nessun dato della carta passa mai da noi (requisiti).
- Un webhook per conto, ognuno con il proprio segreto: Stripe firma gli avvisi di un conto con il segreto dell'endpoint configurato su quel conto.
- **Con un conto comune, i soldi di una partita IVA finiscono sul conto dell'altra**: un professionista incassa prestazioni fatturate dall'altro. **Va verificato con il commercialista** prima di usarlo in produzione.
- Un professionista senza conto assegnato non può incassare: cosa succede alle richieste dei suoi clienti si decide nella guida del pezzo 1 del passo 9 (risposta chiara, mai un 500).
- I pacchetti del passo 10 useranno lo stesso meccanismo.

---

## D-058 — Marca da bollo: nessuna gestione nel sistema
**Stato:** accettata · 2026-11-10 · chiude il punto aperto 9 dei requisiti · brief del passo 9

**Contesto.** Sulle ricevute e fatture non soggette a IVA di importo superiore a 77,47 € va applicata una marca da bollo da 2 €. Riguarda soprattutto i pacchetti: una seduta singola da 30 € resta sotto la soglia. Il bollo può essere addebitato al cliente oppure restare a carico del professionista.

**Decisione.** Il sistema **non gestisce** la marca da bollo: nessuna colonna, nessun calcolo, nessuna riga aggiuntiva nella pagina di pagamento. **L'importo incassato è il prezzo** del servizio o del pacchetto, come vuole D-022.

**Alternative scartate.** *Aggiungere 2 € in automatico sopra la soglia*: una regola fiscale nel codice, da tenere aggiornata, e un prezzo pagato diverso da quello mostrato. *Una colonna per il bollo nei pagamenti*: dati che nessuno leggerebbe, perché la fattura elettronica resta fuori dal sistema (requisiti, "Fuori dalla prima versione").

**Conseguenze.** Il bollo, se dovuto, si applica sulla fattura elettronica che lo studio emette come oggi, fuori dal sistema. Se un professionista vuole farlo pagare al cliente, lo comprende nel **prezzo** del pacchetto, che decide lui dal pannello. Se un giorno il bollo dovrà comparire nel pagamento, servirà una nuova decisione.

---

## D-059 — Link di pagamento per le sedute "in studio": condivisione con WhatsApp dal telefono del professionista
**Stato:** accettata · 2026-11-10 · chiude il punto aperto 16 dei requisiti · completa D-040 · brief del passo 9 · **realizzata da D-065**

**Contesto.** Le prenotazioni fatte dal pannello senza crediti sono pagate in studio (D-040). Nicholas ha chiesto un pulsante per mandare al cliente un link con cui pagarle prima, con carta. Il punto aperto 16 chiedeva di decidere il canale, il problema del numero non verificato, l'effetto sulla prenotazione e i link vecchi.

**Decisione.**
- **Canale**: il pannello apre la **condivisione di WhatsApp** con il messaggio pronto (link e testo), dal telefono del professionista. Nessun costo, nessun fornitore, e il professionista vede a chi lo manda prima di premere "invia". Se il cliente ha un numero (verificato, D-055) il messaggio è già indirizzato a lui; altrimenti il professionista sceglie il contatto.
- **Numero non verificato**: il problema non c'è più. Da D-055 `app_user.phone` è sempre verificato; e comunque è il professionista a scegliere il destinatario.
- **Quando**: solo su una seduta **sua**, `CONFIRMED` e `IN_STUDIO`.
- **Un solo link valido per seduta**: chiederne uno nuovo invalida il vecchio.
- **Blocco, non incasso** (precisato da Nicholas il 2026-11-10): anche il link **blocca** l'importo sulla carta (`capture_method = manual`), come le richieste dei clienti (D-031). All'arrivo del pagamento (webhook): se la seduta è ancora `CONFIRMED` e `IN_STUDIO`, si **incassa subito**; altrimenti si **annulla il blocco**. Stesso meccanismo del pagamento tardivo (D-031). **D-015 resta valida**: il sistema non fa mai rimborsi.
- **Più link, più pagamenti**: si incassa il **primo** pagamento che arriva mentre la seduta è ancora `IN_STUDIO`; ogni pagamento successivo per la stessa seduta (un link invalidato ma pagato lo stesso, o due link pagati entrambi) si annulla.
- **Effetto del pagamento incassato**: la seduta resta `CONFIRMED`, `paid_with` passa da `IN_STUDIO` a `CARD`, si registra il pagamento. Niente da approvare. Da quel momento valgono le regole di cancellazione di una seduta pagata con carta (passo 11).
- La regola "seduta non più valida → annulla il blocco" si scrive al passo 9, nel codice del webhook, provata con un test che scrive lo stato direttamente nel database (al passo 9 una seduta confermata non si può ancora cancellare). La prova con il percorso vero è un criterio di "fatto" del passo 11.
- Gli altri dettagli (scadenza del link, invalidazione del vecchio) si decidono nella guida del pezzo 5 del passo 9.

**Alternative scartate.** *SMS dal sistema*: a pagamento, con un fornitore ancora da scegliere (punto aperto 17). *Email dal sistema*: possibile, ma meno immediata dal telefono, dove il professionista lavora; si può aggiungere accanto in futuro. *Payment Links di Stripe*: sono pensati per vendere lo stesso prodotto più volte con un link che dura; per una seduta precisa basta una sessione Checkout creata al momento, che scade da sola e porta con sé l'id della prenotazione.

*Incasso immediato con rimborso automatico se la seduta non c'è più* (prima proposta del brief): contro D-015, con le commissioni di Stripe perse sul rimborso; con il blocco non c'è niente da rimborsare.

**Conseguenze.** Il backend restituisce l'indirizzo del pagamento e l'indirizzo di condivisione WhatsApp; il pannello (passo 13) li apre. L'incasso dentro il webhook è una chiamata a Stripe in più, con la stessa doppia scrittura dell'approvazione (riconciliazione con `payment_intent.succeeded`, pezzo 4).

---

## D-060 — Conti di incasso: elenco di nomi più chiavi per convenzione, controlli all'avvio, assegnazione e pagamenti nel modulo `payment`
**Stato:** accettata · 2026-11-10 · realizza D-057 · confronto completo: `docs/studio/30-conti-di-incasso.md` · scelte di Claude secondo le raccomandazioni della guida, su indicazione di Nicholas

**Contesto.** D-057 vuole conti di incasso dichiarati nella configurazione, aggiungibili con un riavvio, assegnati dall'admin in produzione, e pagamenti che restano sul loro conto. Il brief chiede anche cosa succede a un professionista senza conto, e la tabella `payment` con il conto.

**Decisione.**
- **Libreria**: `com.stripe:stripe-java` **34.1.0** (29 ottobre 2026, API `2026-10-28.endive`). Dipende solo da Gson, la cui versione la decide Spring Boot (2.13.2 invece di 2.10.1, verificato con `mvnw dependency:tree`). Dal pezzo 2 un `StripeClient` per conto, mai `Stripe.apiKey` statico.
- **Dichiarazione**: `PAYMENT_ACCOUNTS=nome,nome` (proprietà `payment.accounts`) e, per ogni nome, `STRIPE_<NOME>_SECRET_KEY` e `STRIPE_<NOME>_WEBHOOK_SECRET`, chiesti all'`Environment` di Spring: si trovano uguali nel `.env` letto come file di proprietà (sviluppo) e nelle variabili d'ambiente (produzione). Nomi `^[a-z][a-z0-9]{0,29}$`, mai rinominati. Chiave segreta (`sk_`) o limitata (`rk_`, consigliata).
- **Controlli all'avvio** (l'applicazione non parte): nome valido e non ripetuto; variabili presenti; prefissi `sk_`/`rk_` e `whsec_`; `payment.live` (`PAYMENT_LIVE`, default `false`) uguale al modo di **ogni** chiave (`_test_` o `_live_`); ogni conto **assegnato** e ogni conto di un pagamento **aperto** (`PENDING`, `AUTHORIZED`) configurato (`PaymentStartupCheck`). Zero conti ammesso. I messaggi nominano la variabile, mai il valore; `PaymentAccount.toString()` mostra solo il nome.
- **Assegnazione**: tabella `practitioner_payment_account` del modulo `payment` (V10), una riga per professionista; riassegnare lo stesso conto non cambia niente; anche a un professionista disattivato.
- **Senza conto**: nessun pagamento con carta. Al pezzo 2 la richiesta del cliente riceverà un 409 `CARD_PAYMENTS_UNAVAILABLE` **prima** di creare la prenotazione, tramite un'interfaccia dichiarata da `booking` e implementata da `payment`; le prenotazioni dal pannello pagate in studio non cambiano.
- **`payment`** (V10): solo le colonne delle sedute più `account`; cinque stati (`PENDING`, `AUTHORIZED`, `SUCCEEDED`, `CANCELED`, `EXPIRED`); `stripe_checkout_session_id` obbligatorio e unico; `stripe_payment_intent_id` unico; al massimo un `PENDING` per prenotazione (indice unico parziale); `version`; `created_at` da Java. Le colonne dei pacchetti al passo 10.
- **Regola del conto**: il conto si legge una volta, alla creazione (`PaymentAccountService.forNewPayment`), e si copia nella riga (`Payment.pending` riceve un `PaymentAccount`, non un nome); dopo, sempre `PaymentAccounts.of(payment)`. La colonna non è aggiornabile da JPA.
- **API dell'admin** nel modulo `payment`: `GET /api/admin/payment-accounts` (nomi e professionisti, mai le chiavi), `GET` e `PUT /api/admin/practitioners/{id}/payment-account`; 404 (professionista inesistente o senza conto), 400 (nome malformato), 409 `PAYMENT_ACCOUNT_UNKNOWN` (nome non configurato). Niente `DELETE`. `PractitionerService.requireExists` per il 404.

**Alternative scartate.** *Righe per conto in `application.properties`*: i nomi nel repository, e un conto nuovo diventa una modifica del codice. *Una `Map` legata alle variabili d'ambiente*: la traduzione dei nomi la fa Spring solo per le vere variabili d'ambiente, non per il `.env` letto come file, quindi funzionerebbe in un ambiente e non nell'altro. *Colonna in `practitioner`*: un ciclo tra moduli. *Nascondere il professionista senza conto*: dipendenze in più da `practitioner` e `schedule`, e un cliente confuso. *Creare la prenotazione e fallire dopo*: un orario bloccato 30 minuti per niente. *La tabella `payment` completa del modello dei dati*: colonne e valori che nessun codice scrive fino al passo 10 (D-042).

**Conseguenze.** Togliere un conto dal `.env` mentre ha pagamenti aperti ferma l'avvio: si rimette, o si aspetta che i pagamenti si chiudano. Il controllo all'avvio gira dopo che Tomcat è partito (come `AdminBootstrap`): per un istante l'applicazione può ricevere richieste prima di fermarsi; accettabile. Il test completo "cambiare conto non sposta incasso e annullamento" arriva al pezzo 4; il pezzo 1 prova la metà che c'è (la riga resta sul suo conto). **Provato**: tolti a turno `toString()` senza segreti, il controllo prova/produzione, il controllo dei pagamenti aperti, l'idempotenza dell'assegnazione e il controllo "conto configurato", fallisce ogni volta il test che li protegge. Pagare con il link **cambia le regole di cancellazione** della seduta: da "nessun credito" a "credito se cancellata in tempo" (requisiti, "Cancellazioni").

---

## D-061 — Pagamento della seduta: Checkout nella transazione della richiesta, blocco di 32 minuti, indirizzo salvato, 503 se Stripe non risponde
**Stato:** accettata · 2026-11-10 · **modifica D-008** (durata del blocco) · **tempi d'attesa e tentativi verso Stripe** (2 s / 5 s, un nuovo tentativo) precisati da Nicholas lo stesso giorno: punto "Client" e paragrafo "La chiamata dentro la transazione è una scelta consapevole" · confronto completo: `docs/studio/31-pagamento-della-seduta.md` · scelte di Claude secondo le raccomandazioni della guida, su indicazione di Nicholas

**Contesto.** Dopo la richiesta (`PENDING_PAYMENT`) il cliente deve ricevere la pagina di pagamento di Stripe, con l'importo bloccato (D-031) sul conto del professionista (D-057). D-008 voleva blocco e sessione di 30 minuti; ma Stripe vuole una sessione di **almeno 30 minuti dalla sua creazione** (verificato il 2026-11-10), e la sessione nasce un attimo dopo la richiesta.

**Decisione.**
- **`booking` dichiara `CardCheckout`** (`available`, `start`, `pageOf`), **`payment` la implementa** (`StripeCardCheckout`), come `CardHolds`.
- **Nella transazione della richiesta**: prima di tutto, se il professionista non ha un conto, 409 `CARD_PAYMENTS_UNAVAILABLE`; poi i passi di D-045 fino all'`insert` con `flush`; poi la **sessione Stripe**; poi la riga `payment` `PENDING`; poi il commit. Se Stripe fallisce, si annulla tutto: nessuna prenotazione, nessun orario bloccato.
- **Blocco di 32 minuti** (era 30): la sessione scade **nello stesso istante** del blocco, arrotondato per difetto al secondo, e dura per Stripe poco meno di 32 minuti, più dei 30 richiesti anche con un nuovo tentativo e orologi sfasati. La pagina non sopravvive mai al blocco.
- **Sessione**: `mode=payment`; una riga con `price_data` in euro dal **prezzo fotografato** e il nome del servizio; `payment_intent_data.capture_method=manual`; `booking_id` nei metadati della sessione e del PaymentIntent e in `client_reference_id`; solo carte (`allowed_payment_method_types=card`: in questa versione dell'API `payment_method_types` non c'è più); email del cliente sì, nome no; `locale=it`, `submit_type=book`; ritorni segnaposto `APP_URL/prenotazioni/{id}?pagamento=completato|annullato` (`payment.return-base-url`), fino al passo 12.
- **Idempotenza**: `Idempotency-Key: booking-{id}-checkout`.
- **Client**: un `StripeClient` per conto (`StripeClientGateway`), **2 secondi per collegarsi, 5 per leggere, un nuovo tentativo** (precisato da Nicholas il 2026-11-10; i valori della libreria `stripe-java` 34.1.0 sono 30 e 80 secondi e **nessun** nuovo tentativo, con una pausa tra 0,5 e 5 secondi quando i tentativi sono accesi). Ogni `StripeException` diventa **503** `PAYMENT_PROVIDER_UNAVAILABLE` (`ServiceUnavailableException` in `shared`), con nel log tipo, stato, codice e *request id* di Stripe; un `InvalidRequestException` come errore.
- **Indirizzo salvato** in `payment.checkout_url` (migrazione **V11**: la V10 era già stata eseguita); `CustomerBookingView.paymentUrl` lo mostra solo per una `PENDING_PAYMENT` con blocco valido (richiesta, lettura, lista).
- **Prove**: interfaccia `StripeGateway`; il finto `FakeStripeGateway` è registrato in ogni contesto di test da `TestcontainersConfiguration`, `@Primary`; il gateway vero è provato contro un server HTTP del JDK su `localhost` (`setApiBase`).

**Alternative scartate.** *Sessione dopo il commit*: con Stripe giù resta un orario bloccato senza pagina. *Seconda chiamata del frontend*: due richieste, e lo stesso orario bloccato se la seconda non arriva. *Sessione più lunga del blocco*: il cliente pagherebbe un orario già liberato (l'alternativa scartata da D-008). *Chiedere ogni volta la sessione a Stripe*: una chiamata esterna per ogni lettura. *Cambiare la V10*: una migrazione eseguita non si tocca.

**La chiamata dentro la transazione è una scelta consapevole.** Durante la chiamata la transazione tiene il lucchetto sul cliente (D-045) e la riga appena inserita nell'indice del vincolo di non sovrapposizione: chi chiede **lo stesso orario** (o lo stesso cliente da un'altra scheda) aspetta; le richieste per altri orari e altri clienti no. **Caso peggiore**: due tentativi di al massimo 2 + 5 secondi e la pausa tra i due, circa **15 secondi**, poi 503 e non resta niente. Con Stripe in salute Checkout risponde in molto meno di un secondo. A questa scala (due professionisti, poche decine di richieste al giorno) due clienti sullo stesso orario nello stesso secondo sono rari, e il prezzo è accettabile in cambio di nessuno stato a metà.

**Alternativa, se il traffico crescesse**: tre passi separati con una **compensazione**. (1) Una transazione salva la prenotazione e il pagamento in uno stato "pagina da creare", e fa commit: lucchetti liberati subito. (2) Fuori da ogni transazione si chiama Stripe. (3) Se Stripe risponde, una seconda transazione registra la sessione; se fallisce, una transazione di **compensazione** annulla la prenotazione (l'orario torna libero) e il cliente riceve il 503. Serve in più un lavoro programmato che ripari i casi rimasti a metà (un riavvio tra il passo 1 e il 3), e la chiave d'idempotenza rende sicuro riprovare il passo 2. Più codice e più stati, per lucchetti tenuti millisecondi invece che secondi.

**Conseguenze.** La transazione della richiesta resta aperta durante la chiamata a Stripe: un'altra richiesta per lo stesso orario aspetta (al peggio circa 15 secondi, poi 503). Una sessione creata con il commit poi fallito resta orfana su Stripe e scade da sola: nessuno ne conosce l'indirizzo. La riga `payment` resta `PENDING` finché il webhook (pezzo 3) non la muove. **Provato**: tolti a turno l'incasso manuale, l'arrotondamento per difetto, il nuovo tentativo, il controllo del conto, il limite "solo finché il blocco vale" per l'indirizzo, i 32 minuti e la chiave d'idempotenza fissa, fallisce ogni volta il test che li protegge. I tempi d'attesa sono provati con i **valori veri** contro un finto Stripe che non risponde (`StripeClientGatewayTest`): il gateway si arrende tra 10 e 15 secondi, dopo due richieste con la stessa chiave; con 15 o con 1 secondo di lettura il test fallisce. L'attesa di collegamento (2 s) non è provata: servirebbe un indirizzo che inghiotte le connessioni.

---

## D-062 — Webhook di Stripe: un indirizzo per conto, eventi visti nella stessa transazione, confronta-e-cambia prima della prenotazione, `paymentAuthorized` che risponde
**Stato:** accettata · 2026-11-10 · completa D-007, D-031, D-046 e D-051 · **precisata da D-063** (passaggi del pagamento nell'entità con il lucchetto invece degli `update` JPQL; il rischio di `LoggingCardHolds` risolto) · confronto completo: `docs/studio/32-webhook.md` · scelte di Claude secondo le raccomandazioni della guida, su indicazione di Nicholas

**Contesto.** Il cliente paga sulla pagina di Stripe (D-061); il sistema lo deve sapere da Stripe, non dal browser (D-007). Stripe consegna ogni evento **almeno una volta**, in ordine non garantito, e riprova per 3 giorni se non riceve un `2xx`. Verificato sulla documentazione il 2026-11-10: con l'incasso manuale la sessione completata ha `payment_status = unpaid`; la firma è un HMAC-SHA256 di timestamp e corpo grezzo, con 5 minuti di tolleranza.

**Decisione.**
- **Indirizzi**: `POST /api/webhooks/stripe/{conto}`, uno per conto di incasso; il nome sceglie il segreto. Conto sconosciuto 404; firma mancante, sbagliata o più vecchia di 5 minuti 400 senza dettagli; `Webhook.constructEvent` con il nostro `Clock`. Corpo ricevuto come `byte[]` e decodificato in UTF-8 a mano.
- **Sicurezza**: prefisso nuovo `/api/webhooks/**`, `permitAll()` e **unica** eccezione al CSRF. Via `/api/dev/**`.
- **Eventi usati**: `checkout.session.completed` (pagamento `PENDING` → `AUTHORIZED` con il PaymentIntent, poi `paymentAuthorized`; si guarda `status = complete` e la presenza del PaymentIntent, **mai** `payment_status`) e `checkout.session.expired` (pagamento → `EXPIRED`; la prenotazione la chiude il lavoro programmato, D-047). Gli altri tipi: 200, non registrati. I campi si leggono dal **JSON grezzo** dell'oggetto (Jackson), non dal modello tipizzato di `stripe-java`, che con una versione dell'API diversa è vuoto. Sull'endpoint: solo questi due eventi, formato completo (*snapshot*), versione `2026-10-28.endive`.
- **"Almeno una volta"**, due livelli: tabella **`stripe_event`** (V12), `insert ... on conflict do nothing` **nella stessa transazione** dell'elaborazione; e ogni passaggio del pagamento come **confronta-e-cambia** (`update ... where status = :from`), fatto **prima** di chiedere qualunque cosa alla prenotazione.
- **Il pagamento deve essere del conto** dell'indirizzo: altrimenti si ignora e si registra come errore.
- **`BookingService.paymentAuthorized` risponde** con `sealed interface PaymentOutcome` (`Accepted`, `NotWanted`): `PENDING_PAYMENT`, anche con il blocco scaduto, è `Accepted`; **ogni altro stato** è `NotWanted`, senza errore (era 409 per una seconda autorizzazione). `booking` non chiama più `CardHolds.cancel` per il pagamento tardivo: lo annulla il webhook, su **quel** PaymentIntent e sul conto del pagamento (`cancel-{pi}`), e il pagamento passa a `CANCELED`. Un PaymentIntent già non annullabile (`payment_intent_unexpected_state`) va bene così.
- **Pagamento tardivo**: evento di dominio nuovo `LatePaymentReleased`, email al cliente dopo il commit ("niente addebitato"); nessuna push.
- **Elaborazione nella richiesta**: un errore annulla tutto, riga di `stripe_event` compresa, e la risposta non è un `2xx` (503 se Stripe non risponde, 500 altrimenti): Stripe rimanda l'evento, che è di nuovo nuovo.
- **Pagamento simulato rimosso** (era il pezzo 6): `SimulatedPaymentController`, `booking.simulated-payments`, la riga di `.env.example`, `/api/dev/**` e il suo test.

**Alternative scartate.** *Un indirizzo solo provando ogni segreto*: non si sa da quale conto arriva l'evento. *Eventi visti scritti in una transazione a parte, prima*: un errore perderebbe l'evento. *Solo la tabella degli eventi*: Stripe a volte manda due eventi diversi per la stessa cosa, e il secondo annullerebbe un blocco valido. *Aspettare `payment_status = paid`*: con l'incasso manuale non arriva mai. *`payment_intent.amount_capturable_updated`*: dice la stessa cosa senza l'id della sessione. *Modello tipizzato di `stripe-java`*: vuoto in silenzio con un'altra versione dell'API. *Casella d'arrivo con un lavoro programmato*: più tabelle e più codice; la correttezza non dipende dalla velocità, perché un nuovo tentativo di Stripe trova l'evento già visto. *`paymentAuthorized` che annulla da sé*: con più pagamenti per seduta (D-059) non saprebbe quale.

**Conseguenze.** **Finché il pezzo 4 non c'è, `LoggingCardHolds` conferma un'approvazione senza incassare**: i pezzi 3 e 4 arrivano in `main` insieme, e fino ad allora si usa solo la modalità di prova. La tabella `stripe_event` cresce di pochi eventi al giorno: nessuna pulizia per ora. **Provato**: tolti a turno il confronta-e-cambia, la tabella degli eventi, il controllo del conto, la verifica della firma, l'eccezione CSRF, l'annullamento del pagamento tardivo e i controlli su `complete` e PaymentIntent, fallisce ogni volta il test che li protegge; due consegne contemporanee, dello stesso evento o di due eventi per la stessa sessione, hanno un solo effetto.

---

## D-063 — Incasso e annullamento veri: prima incassare poi confermare, il rifiuto come valore, riconciliazione con `payment_intent.succeeded`
**Stato:** accettata · 2026-11-10 · **precisa D-046** (ordine delle operazioni e firma di `CardHolds`) e D-062 (passaggi del pagamento) · chiave dell'incasso **precisata da D-064**: lettura del PaymentIntent prima di ogni incasso, chiave nuova a ogni tentativo · confronto completo: `docs/studio/33-incasso-e-annullamento.md` · scelte di Claude secondo le raccomandazioni della guida, su indicazione di Nicholas

**Contesto.** Con il webhook (D-062) le prenotazioni arrivano davvero in attesa di approvazione, e `LoggingCardHolds` confermava senza incassare. Il brief chiede: incasso all'approvazione, annullamento a rifiuto, ritiro e scadenza; un incasso fallito lascia la richiesta `PENDING_APPROVAL`; "Stripe ha incassato ma il nostro commit è fallito" chiuso dalla riconciliazione con `payment_intent.succeeded`; un annullamento fallito si registra e non blocca. Verificato il 2026-11-10: si incassa solo un PaymentIntent `requires_capture`; Stripe annulla da sé un blocco non incassato dopo 7 giorni (evento `payment_intent.canceled`).

**Decisione.**
- **`StripeCardHolds`** in `payment` implementa `CardHolds`; **`LoggingCardHolds` rimossa**. Sempre sul conto del **pagamento** (`PaymentAccounts.of`), sempre con la riga del pagamento **bloccata prima** di chiamare Stripe. Chiavi d'idempotenza `capture-{pi}` e `cancel-{pi}`.
- **Ordine** (precisa D-046): lucchetto sulla prenotazione, **controllo** del passaggio (`Booking.checkMove`), incasso, e **solo dopo** il cambio di stato e l'evento. Un passaggio vietato non arriva mai a Stripe; un incasso rifiutato lascia la prenotazione com'era.
- **`CardHolds.capture` restituisce `sealed CaptureResult`** (`Captured`, `Refused(reason)`): il rifiuto definitivo (`payment_intent_unexpected_state`, `CardException`) è un valore; Stripe che non risponde resta un'eccezione (503, tutto annullato). Motivo: un'eccezione uscita da un bean `@Transactional` segna la transazione "solo rollback" anche se catturata. `StripeCardHolds` non è `@Transactional`.
- **Incasso rifiutato**: all'approvazione del professionista → 409 **`CARD_CAPTURE_FAILED`**, richiesta ancora `PENDING_APPROVAL` (si rifiuta o scade); nell'approvazione **automatica** → la richiesta resta `PENDING_APPROVAL` e parte la notifica "da approvare", come in manuale; il webhook risponde 200.
- **`cancel` non lancia mai**: se Stripe non risponde, avviso nel log e pagamento ancora `AUTHORIZED`. `cancelPaymentIntent` dice se ha annullato: "non più annullabile" (già annullato, o **incassato**) lascia il pagamento com'è, con un errore nel log che indica il rimborso a mano se era incassato.
- **Webhook, due eventi in più** (da abilitare sugli endpoint): `payment_intent.succeeded` → `AUTHORIZED` → `SUCCEEDED` e `BookingService.paymentCaptured` (conferma **senza** incassare di nuovo; prenotazione non più confermabile → errore "rimborso a mano dalla dashboard", D-015); nel caso normale il pagamento è già `SUCCEEDED` e non succede niente. `payment_intent.canceled` → `AUTHORIZED` → `CANCELED`.
- **Passaggi del pagamento nell'entità** (precisa D-062): `authorize`, `expire`, `capture`, `cancel` su un pagamento letto con il lucchetto (`findLocked...`), ognuno dice se ha cambiato qualcosa. Sostituiscono gli `update` JPQL del pezzo 3, che lasciavano l'oggetto vecchio in memoria: nell'approvazione automatica l'incasso avrebbe letto un PaymentIntent `null`.
- **Ordine dei lucchetti**: l'approvazione blocca prenotazione poi pagamento; il webhook pagamento poi prenotazione. Non si incrociano: chi chiama Stripe tiene già il lucchetto del pagamento, e `payment_intent.succeeded` nasce dopo l'incasso.

**Alternative scartate.** *Incasso rifiutato come eccezione*: nel webhook vuol dire tre giorni di nuovi tentativi inutili, e catturata in un altro bean lascia la transazione "solo rollback". *Rifiutare in automatico la richiesta dopo un incasso rifiutato*: il brief vuole che resti in attesa; decide il professionista. *Riconciliazione con un lavoro programmato che interroga Stripe*: Stripe ci avvisa già da sé. *Recuperare il PaymentIntent quando non è più annullabile, per sapere se era incassato*: una chiamata in più per un caso raro, che la riconciliazione risolve comunque. *Tenere gli `update` JPQL e svuotare il persistence context*: staccherebbe anche la prenotazione modificata nella stessa transazione.

**Conseguenze.** I pezzi 3 e 4 possono andare in `main`: nessuna approvazione conferma più senza incassare. Sugli endpoint di Stripe vanno abilitati quattro eventi: `checkout.session.completed`, `checkout.session.expired`, `payment_intent.succeeded`, `payment_intent.canceled`. **Da verificare nella prova manuale**: se un blocco annullato costa una commissione (la documentazione non lo dice). **Provato**: tolti a turno l'ordine incasso-poi-conferma, il conto del pagamento per incasso e annullamento, la tolleranza agli annullamenti falliti, la riconciliazione, l'esito dell'annullamento, il 409 e la chiave d'incasso fissa, fallisce ogni volta il test che li protegge; "incassato ma commit fallito" è provato con commit veri.

---

## D-064 — Annullamenti falliti riprovati dal lavoro programmato; chiave nuova a ogni tentativo
**Stato:** accettata · 2026-11-10 · **completa D-063** · richiesta di Nicholas dopo il pezzo 4, **precisata** lo stesso giorno (incasso dopo un errore) · scelte di realizzazione di Claude

**Contesto.** Con D-063 un annullamento che fallisce perché Stripe non risponde si registra e non blocca il rifiuto: il blocco si scioglie da solo, ma fino a **7 giorni** dopo. Nicholas: per un problema nostro, il cliente non deve tenere l'importo bloccato sulla carta per una settimana. Verificato sulla documentazione il 2026-11-10: per l'API v1 Stripe **salva** la risposta della prima richiesta con una chiave d'idempotenza, **errori 500 compresi**, e la restituisce di nuovo per 24 ore a chi riusa la chiave.

**Decisione.**
- **Migrazione V13**: in `payment` `release_attempts` (quanti annullamenti falliti) e `next_release_at` (quando riprovare; `null` = niente da riprovare). `check`: un annullamento può essere dovuto solo da un pagamento ancora `AUTHORIZED`. Indice parziale su `next_release_at`.
- **Pause tra i tentativi**: 1 minuto, 5, 30, 2 ore (`Payment.RELEASE_RETRY_PAUSES`). Dopo il **quinto** fallimento, circa due ore e mezza dopo il primo, ci si arrende: `next_release_at` torna `null` e il registro scrive un **errore** con l'indicazione di annullare a mano dalla dashboard del conto (il blocco si scioglie comunque da solo).
- **Lavoro programmato** `PaymentJobs.retryReleases`, ogni minuto con gli altri (`booking.jobs.enabled`): prima gli id dovuti, poi **una transazione per pagamento** con il lucchetto e la condizione **riletta dopo il lucchetto** (`releaseDue`), come D-047: sicuro con due istanze e con un webhook arrivato nel frattempo.
- **Un solo punto che annulla**: `PaymentReleases.attempt`, usato da `StripeCardHolds.cancel` (rifiuto, ritiro, scadenza) e dal lavoro. Non lancia mai quando Stripe non risponde; non è `@Transactional`.
- **Chiave nuova a ogni tentativo**: `cancel-{pi}`, poi `cancel-{pi}-1`, `cancel-{pi}-2`, ... Con la stessa chiave, dopo un 500 Stripe restituirebbe lo stesso 500 per 24 ore, e i tentativi non servirebbero a niente. Annullare due volte non fa danni: la seconda volta Stripe risponde "non più annullabile", e niente cambia.
- **Uscire da `AUTHORIZED`** (annullato, incassato, o "non più annullabile") toglie il tentativo dovuto.
- **Incasso: prima si legge il PaymentIntent** (precisato da Nicholas): `StripeGateway.paymentIntentStatus`, una `GET` senza chiave. `succeeded` → già incassato (un tentativo precedente con la risposta persa, o il nostro commit fallito dopo l'incasso): si conferma **senza incassare**; `requires_capture` → si incassa con una **chiave nuova** (`capture-{pi}-{casuale}`); `canceled` o altro → rifiutato (409 `CARD_CAPTURE_FAILED`), senza chiedere l'incasso. Così un 500 di Stripe non blocca l'approvazione per 24 ore (la finestra di risposta è al massimo 48 ore, e spesso più corta). Dentro un tentativo, il nuovo tentativo della libreria riusa la sua chiave. La lettura si fa **prima di ogni incasso**, non solo "dopo un errore": ricordarsi l'errore richiederebbe una scrittura che sopravviva al *rollback* della stessa transazione, su una riga che quella transazione tiene già bloccata (si aspetterebbe da sola).
- **Approvazione automatica con incasso rifiutato** (verifica chiesta da Nicholas): parte `ApprovalRequested` (push, o email di riserva al professionista; email "richiesta inviata" al cliente) e `approval_deadline` è fissata, perché il pagamento la fissa prima dell'incasso. Provato a tre livelli: servizio, webhook, email.

**Alternative scartate.** *Riprovare subito, nella stessa richiesta*: se Stripe non risponde adesso, è probabile che non risponda nemmeno tra un secondo, e il rifiuto del professionista aspetterebbe. *Tentativi senza fine*: un problema permanente (una chiave revocata) riempirebbe il registro per sette giorni; dopo due ore e mezza un intervento a mano è la strada giusta. *La stessa chiave per tutti i tentativi*: dopo un 500 inutile per 24 ore. *Lasciare tutto a `payment_intent.canceled`*: arriva solo dopo 7 giorni.

**Conseguenze.** Una chiamata in più (una lettura, qualche decimo di secondo) per ogni approvazione, dentro la transazione: il caso peggiore dei tempi d'attesa di D-061 vale per la lettura e per l'incasso. La prima versione di questa scheda teneva fissa la chiave dell'incasso (`capture-{pi}`) e accettava il blocco di 24 ore dopo un 500: superata dalla lettura. **Provato**: dopo un 500 l'approvazione successiva incassa con una chiave nuova; dopo una risposta persa conferma senza incassare di nuovo; un blocco già sparito è rifiutato senza chiedere l'incasso; tolti a turno la lettura e la chiave nuova, fallisce il test che li protegge. Inoltre: tolti a turno la registrazione del fallimento, la chiave nuova, la resa dopo cinque tentativi e l'`ApprovalRequested` dell'approvazione automatica, fallisce ogni volta il test che li protegge.

---

## D-065 — Link di pagamento: `payment.source`, la pagina vecchia fatta scadere su Stripe dopo averla letta, incasso di quel PaymentIntent, "il primo vince" con il lucchetto sulla seduta
**Stato:** accettata · 2026-11-10 · **realizza D-059** · confronto completo: `docs/studio/34-link-di-pagamento.md` · scelte di Claude secondo le raccomandazioni della guida, su indicazione di Nicholas

**Contesto.** D-059 ha deciso il link (WhatsApp, un solo link valido per seduta, blocco e non incasso, "il primo pagamento che arriva mentre la seduta è ancora `IN_STUDIO` si incassa, gli altri si annullano"). Restava da decidere come: chi possiede il link, come si distingue il suo pagamento, come si invalida una pagina che vive su Stripe, come si incassa quando i pagamenti della seduta possono essere due. Verificato il 2026-11-10: Stripe fa scadere solo una sessione `open`, e per le altre risponde con un errore.

**Decisione.**
- **API** `POST /api/panel/bookings/{id}/payment-link` nel modulo `payment` (area del professionista, D-051): `{paymentUrl, whatsappUrl, expiresAt}`. `booking.linkTarget` decide se la seduta può riceverlo: 404 se non è del professionista; 409 `PAYMENT_LINK_NOT_ALLOWED` se non è `CONFIRMED` e `IN_STUDIO`, o inizia tra meno di **32 minuti**; 409 `CARD_PAYMENTS_UNAVAILABLE` senza conto; 503 se Stripe non risponde.
- **Migrazione V14**: `payment.source` (`REQUEST`, `LINK`); il webhook decide da lì, non dai metadati di Stripe.
- **Durata**: **24 ore meno 2 minuti** (corretto dopo la prova manuale: Stripe vuole *meno* di 24 ore dalla creazione, e il nostro orologio era avanti di un secondo), o fino all'inizio della seduta se viene prima. Incasso manuale come per la richiesta, sul conto **attuale** del professionista.
- **Un solo link valido**: prima di crearne uno nuovo, il `PENDING` vecchio (con il lucchetto) si **legge su Stripe**: `open` → si fa scadere (`expire-{cs}`), `expired` → si chiude e basta, `complete` → 409 **`PAYMENT_LINK_ALREADY_PAID`**, nessun link nuovo. Se la sessione non è più aperta tra la lettura e il "fai scadere", si rilegge. Poi un `flush` esplicito, perché l'`insert` del nuovo (id dal database) parte subito e l'indice "un solo `PENDING`" lo rifiuterebbe. Chiave della sessione nuova: `booking-{id}-link-{casuale}`.
- **Pagamento di un link** (webhook): pagamento → `AUTHORIZED`; `booking.linkPaymentArrived` blocca la seduta e risponde `Accepted` se è ancora `CONFIRMED` e `IN_STUDIO`; allora si incassa **quel** PaymentIntent (`StripeCardHolds.captureLocked`, con la lettura e la chiave nuova di D-064), e solo dopo `booking.paidByLink` (`paid_with` → `CARD`). `NotWanted` → si annulla quel blocco. Un incasso rifiutato lascia la seduta "in studio".
- **"Il primo vince"**: il lucchetto sulla seduta, tenuto fino al commit del webhook, mette in fila due pagamenti contemporanei; il secondo legge `CARD` e viene annullato.
- **Un link invalidato ma pagato lo stesso**: `EXPIRED` → `AUTHORIZED` permesso. Stripe completa solo sessioni aperte: i soldi sono davvero bloccati, e si giudicano come gli altri.
- **Lucchetti**: la creazione del link blocca solo il pagamento `PENDING`, **mai** la seduta: bloccarla prima potrebbe incrociarsi con il webhook di un link vecchio (pagamento, poi seduta).
- **WhatsApp**: testo composto dal backend (nome del cliente, servizio, giorno e ora a Roma, link, scadenza); `https://wa.me/<numero senza +>?text=...`, oppure `https://wa.me/?text=...` senza numero.
- Nessun evento di dominio nuovo: la ricevuta la manda Stripe all'email del cliente.

**Alternative scartate.** *Distinguere i pagamenti dai metadati di Stripe*: la verità sui nostri pagamenti è nel nostro database. *Far scadere la pagina vecchia senza leggerla*: un link appena pagato verrebbe "sostituito" mentre i soldi arrivano. *`CardHolds.capture(bookingId)` anche per i link*: con due link pagati quasi insieme, "il" pagamento `AUTHORIZED` della seduta potrebbero essere due. *Lucchetto sulla seduta anche alla creazione del link*: un possibile deadlock in cambio di niente (il caso che eviterebbe si risolve da solo con "il primo vince"). *Ignorare un `completed` per un pagamento già `EXPIRED`*: un blocco che nessuno annulla.

**Conseguenze.** **Passo 11**: cancellare una seduta "in studio" deve far scadere il link aperto (stessa lettura di prima), e la prova con il percorso vero ("cancellata con un link attivo, poi pagato → blocco annullato") è un suo criterio di "fatto". **Passo 13**: il pannello apre `whatsappUrl`, e traduce i 409. **Provato**: tolti a turno il "solo se ancora in studio", l'`EXPIRED` → `AUTHORIZED`, il `flush`, il "fai scadere" su Stripe, il rifiuto di un link appena pagato, il cambio di `paid_with` e il lucchetto sulla seduta, fallisce ogni volta il test che li protegge; due link pagati nello stesso istante incassano una volta sola.

---

## D-066 — I clienti di un professionista: chi ha una prenotazione o un lotto di crediti con lui; concessioni e prenotazione dal pannello per email
**Stato:** accettata · 2026-11-10 · brief del passo 10 · **completa D-024** ("cosa rende un cliente suo") e D-048

**Contesto.** D-024 ha lasciato aperto cosa rende un cliente "suo", e quindi chi può comparire nelle liste del pannello; D-048 l'ha aggirato per la prenotazione dal pannello, che trova o crea il cliente per email. Il passo 10 porta due cose nuove: una lista "i miei clienti" con i loro crediti, e le concessioni (D-067), che si fanno a un cliente preciso. La regola di riservatezza resta: il professionista vede solo le **proprie** cose (requisiti, "Riservatezza del calendario").

**Decisione.**
- **Nelle liste del pannello** ("i miei clienti", e da lì i crediti di un cliente) i clienti di un professionista sono quelli con **almeno una prenotazione** con lui, in qualunque stato, **o almeno un lotto di crediti** con lui.
- **Le concessioni** (D-067) **e la prenotazione dal pannello** si fanno **per email**, a **qualunque** cliente, anche nuovo: trovato o creato come in D-048. Dopo, quel cliente è suo, perché ha un lotto o una prenotazione con lui.
- **Mai le cose dell'altro professionista**: di un cliente comune, ognuno vede solo i propri lotti e le proprie prenotazioni. Il lotto di un altro è un 404, come la cosa inesistente (D-026).

**Alternative scartate.** *Tutti i clienti dello studio*: ogni professionista vedrebbe i clienti dell'altro, contro la riservatezza. *Solo chi ha una seduta confermata*: un cliente che ha comprato un pacchetto e non l'ha ancora usato sparirebbe dalla lista proprio quando ha dei crediti. *Concessioni scelte solo da un elenco*: non si potrebbe regalare una seduta a un cliente nuovo, e non serve: l'email trova il cliente giusto (D-048).

**Conseguenze.** La lista nasce da due moduli (`booking` e `credit` sanno chi ha cosa con chi), con i nomi chiesti a `customer` su un insieme di id (D-029, D-049). Si chiude la domanda del passo 13 ("quali clienti può cercare e vedere un professionista"). **Da verificare nella guida delle concessioni**: per email si può raggiungere anche un cliente dell'altro professionista, quindi la risposta non deve rivelare più di quanto il professionista ha scritto (per esempio il nome vero di un cliente già esistente, che D-048 non cambia).

---

## D-067 — Concessioni del professionista: omaggio come lotto nuovo, proroga con la traccia delle due date, correzione come movimento
**Stato:** accettata · 2026-11-10 · brief del passo 10 · **chiude il punto aperto 6** dei requisiti · perimetro deciso da Nicholas il 2026-10-16

**Contesto.** Il punto aperto 6 chiedeva come il professionista possa decidere caso per caso: regalare sedute, prorogare una scadenza, concedere il credito su una cancellazione oltre il preavviso. Il modello a lotti e movimenti (D-009, D-010) le regge senza stravolgimenti; restavano da fissare la forma di ognuna e la traccia.

**Decisione.** Tre concessioni, ognuna solo sui **propri** lotti e per i **propri** servizi, per email del cliente (D-066):
- **Seduta omaggio**: un **lotto nuovo** con un'**origine propria** (nome nella guida del pezzo 1), N crediti, per un servizio del professionista. Scadenza scelta da lui, o per default quella delle sedute singole (`single_credit_validity_days`).
- **Proroga di una scadenza**: cambia la scadenza di un lotto, e registra l'evento con **la vecchia e la nuova data**. È l'unica concessione che modifica qualcosa invece di aggiungere: la sua storia non deve perdersi. Se si possa anche anticipare, si decide nella guida.
- **Correzione**: un **movimento** sul lotto, con un **motivo proprio**, che non porta mai il saldo sotto zero. Mai la modifica di un movimento esistente (D-009).
- **Sempre la traccia**: **chi** (l'account del professionista), **quando** (l'orologio dell'applicazione), **perché** (una nota, obbligatoria).
- Sono sempre **crediti**, mai denaro (D-015), e non cambiano lo stato di nessuna prenotazione.
- **Limiti**: nessuno oltre a quelli sopra (propri lotti, saldo mai negativo). Il controllo è la traccia: ogni concessione dice chi l'ha fatta e perché.
- **Credito concesso su una cancellazione tardiva**: al **passo 11**, con lo stesso meccanismo e la stessa traccia.

**Alternative scartate.** *Omaggio come movimento +N su un lotto esistente*: mescola crediti pagati e regalati, che prenderebbero la scadenza di quel lotto; e un cliente senza lotti non riceverebbe niente. *Proroga come lotto nuovo* (−N sul vecchio, +N sul nuovo): spezza la storia, e un credito che torna da una prenotazione rifiutata tornerebbe al lotto vecchio, già scaduto. *Correzione modificando un movimento*: contro D-009, la storia si perde. *Nota facoltativa*: una concessione senza motivo, un anno dopo, non la sa spiegare nessuno.

**Conseguenze.** Un'origine di lotto e un motivo di movimento nuovi, nomi fissati nella guida del pezzo 1 del passo 10; una traccia della proroga (vecchia e nuova data, chi, quando, perché). Il punto aperto 6 è chiuso.

---

## D-068 — Nessun lotto `LATE_PAYMENT`: un pagamento arrivato tardi si annulla
**Stato:** accettata · 2026-11-10 · brief del passo 10 · **conferma** la conseguenza di D-031 ("il lotto `LATE_PAYMENT` probabilmente non serve più") · la toglie dal modello dei dati

**Contesto.** D-008 voleva che un pagamento arrivato dopo la scadenza del blocco diventasse un credito, in un lotto con origine `LATE_PAYMENT`. D-031 l'ha sostituito con l'annullamento del blocco sulla carta (il cliente non è addebitato), e D-062 l'ha realizzato: evento `LatePaymentReleased` ed email al cliente. Anche il link di pagamento (D-059, D-065), quando la seduta non è più da pagare in studio, annulla il blocco.

**Decisione.** L'origine `LATE_PAYMENT` **non esiste**. Un pagamento arrivato tardi non crea mai crediti: si annulla il blocco, come oggi.

**Alternative scartate.** *Tenerla nello schema "per sicurezza"*: un valore che nessun codice produce, contro D-042.

**Conseguenze.** In `modello-dati.md` le origini dei lotti perdono `LATE_PAYMENT`, e `origin_booking_id` resta solo per le cancellazioni (passo 11).

---

## D-069 — Il registro dei crediti (V15): trigger contro modifiche e saldo negativo, scadenza come giorno di calendario, un lotto per pagamento, proroga solo in avanti
**Stato:** accettata · 2026-11-10 · realizza D-009, D-010, D-067 e D-068 · confronto completo: `docs/studio/36-registro-dei-crediti.md` · scelte di Claude secondo le raccomandazioni della guida, **confermate da Nicholas** una per una lo stesso giorno · **estende la convenzione su date e ore** di CLAUDE.md (giorni di calendario)

**Contesto.** Il pezzo 1 del passo 10 decide la forma del registro dei crediti e quali regole il database fa rispettare da solo, anche con un bug in Java o con un `update` scritto a mano. Tre fatti trovati nel codice pesano sulle scelte: il pacchetto in vendita **si modifica** (sedute, prezzo, validità); l'applicazione si collega a PostgreSQL con `POSTGRES_USER`, che l'immagine ufficiale crea come **superuser** (i permessi non lo fermano); alcuni test fanno commit veri e puliscono con `delete from`.

**Decisione.**
- **Nomi**: origini `PACKAGE`, `GRANT`, `CANCELLATION` (passo 11); motivi `LOT_CREATED` (+N), `BOOKING` (−1), `BOOKING_RELEASE` (+1, rifiuto, risposta scaduta o ritiro; era `APPROVAL_RELEASE`), `ADJUSTMENT` (±N), `CANCELLATION_RESTORE` (+1, passo 11). Nei `check` della V15 solo quelli del passo 10.
- **Lotto** (`credit_lot`): foreign key composta verso `service (id, practitioner_id)` (D-025); niente `initial_credits` (lo dice il movimento `LOT_CREATED`); `price_cents` sì (copia tra moduli, D-011); traccia dell'omaggio nella riga (`granted_by`, `note`, `created_at`), pretesa da un `check`.
- **Un lotto per pagamento**: `credit_lot.payment_id` **unico**, con `check ((source = 'PACKAGE') = (payment_id is not null))` (Nicholas). Sostituisce `payment.credit_lot_id` del modello dei dati: con il collegamento dal pagamento un secondo lotto si creerebbe comunque.
- **Movimento** (`credit_movement`): un `check` per motivo (importo e prenotazione); traccia della correzione (`created_by`, `note`) pretesa da un `check`; tre indici unici parziali: un `LOT_CREATED` per lotto, un `BOOKING` per prenotazione, **una sola restituzione** (movimento positivo) per prenotazione.
- **Registro immutabile**: trigger `before update or delete` per riga e `before truncate` per istruzione su `credit_movement` e `credit_lot_extension`; su `credit_lot` niente `delete` né `truncate`. Valgono anche per il superuser; un superuser li può spegnere, quindi proteggono dagli errori, non da un amministratore. **Al passo 14** l'applicazione si collega con un utente non superuser, senza `update`, `delete` e `truncate` su `credit_movement`.
- **Saldo mai negativo**: la regola sta in **Java** (pezzo 3, per una risposta leggibile) **e** in un **trigger** `before insert` che, per ogni movimento negativo, prende il **lucchetto sulla riga del lotto** e poi somma. `for no key update`, non `for update`: due addebiti si mettono in fila, ma una restituzione non aspetta (la foreign key del suo `insert` prende `for key share`, che non è in conflitto). Ordine dei lucchetti: cliente, poi lotto; nessuno prende il lotto e poi il cliente. **Il trigger regge solo in `READ COMMITTED`** (l'isolamento di default, D-045): lì ogni istruzione di una funzione `volatile` prende una fotografia nuova, e la somma dopo l'attesa vede l'addebito appena confermato dall'altra transazione. In `REPEATABLE READ` la fotografia è una sola, presa prima dell'attesa, e il saldo potrebbe andare sotto zero.
- **Scadenza come giorno di calendario**: `credit_lot.valid_until date`, l'**ultimo giorno valido** a Europe/Rome. Si usa in un istante se il giorno di quell'istante, a Roma, non è dopo. "Valido N giorni" = giorno dell'acquisto a Roma + N giorni di calendario. Una regola sola, il `record ValidUntil`, con lo `StudioTime.ZONE`. Precedente: `working_schedule.valid_from` (D-034); l'eccezione è scritta in CLAUDE.md.
- **Proroga**: `valid_until` modificabile, la storia in `credit_lot_extension` (vecchia e nuova data, `extended_by`, `note`, `created_at`); **solo in avanti** (`check`). Un trigger su `credit_lot` permette di cambiare **solo** `valid_until`, e **solo** se esiste la riga della proroga con le stesse due date.
- **Pagamenti dei pacchetti** (`payment`): `purpose` (`BOOKING`, `PACKAGE`, senza default dopo la migrazione), `package_offer_id`, `customer_id` (solo per i pacchetti), e la **fotografia presa alla creazione della sessione Checkout**: `package_credits`, `package_validity_days` (il prezzo è `amount_cents`). Il lotto nasce dalla fotografia, mai dal pacchetto in vendita. Un `check` tiene coerenti scopo e colonne, con un `is not null` esplicito per ogni colonna obbligatoria (un `check` che vale `NULL` accetta la riga); un pacchetto è sempre `REQUEST` e mai `AUTHORIZED` né `CANCELED`.
- **`booking.paid_with`** riceve `CREDIT`, per il cliente e per il professionista; una prenotazione `CREDIT` non è mai `PENDING_PAYMENT`.
- **Pulizia dei test**: `LedgerCleanup`, **solo nel codice di test**, cancella con `session_replication_role = replica` in una transazione (Nicholas).

**Alternative scartate.** *Permessi invece dei trigger, ora*: non fermano un superuser né il proprietario delle tabelle; servono due utenti, al passo 14. *Saldo solo in Java*: un `insert` a mano o un metodo che dimentica il lucchetto lo scavalcano. *Lucchetto sul cliente*: la correzione del professionista non passa da lì. *Scadenza come istante* (`expires_at`, la mezzanotte di Roma del giorno dopo): ogni lettura e ogni proroga richiede una conversione, e ogni conversione è un'occasione di errore nei giorni del cambio dell'ora. *Proroga tutta a sola aggiunta* (scadenza = ultima proroga): ogni query sui lotti utilizzabili avrebbe bisogno di una sotto-query. *Proroga anche all'indietro*: toglierebbe giorni al cliente in silenzio; per togliere c'è la correzione. *Fotografia del pacchetto all'arrivo dell'avviso*: il cliente riceverebbe quello che il pacchetto è diventato, non quello che ha pagato. *`purpose` e `source` in una colonna sola*: da riscrivere il codice del passo 9. *Copiare cliente, professionista e servizio nel movimento con due foreign key composte*: tre colonne in più in ogni riga per una regola che vive in un solo metodo Java (pezzo 3).

**Conseguenze.** La V15 non scrive nessun dato: le righe esistenti di `payment` diventano `BOOKING`, e i test che scrivono pagamenti con SQL dicono lo scopo. Il modulo `credit` ha le entità (`CreditLot`, `CreditMovement` e `CreditLotExtension`, le ultime due `@Immutable`) e repository senza `delete`; i servizi arrivano con i pezzi 2–5. Che una prenotazione `CREDIT` abbia il suo movimento `BOOKING`, e che il lotto sia dello stesso cliente, professionista e servizio, lo garantisce Java (pezzo 3). **Provato**: tolti a turno il lucchetto del trigger del saldo (due transazioni prendono entrambe l'ultimo credito), il trigger di `truncate`, l'`is not null` di `package_credits`, l'indice "una sola restituzione", `unique (payment_id)` e il controllo della traccia nel trigger del lotto, fallisce ogni volta il test che li protegge.
