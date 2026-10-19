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
**Stato:** accettata · 2026-10-13

**Decisione.** Prenotazioni, lotti da pacchetto e pagamenti salvano prezzo netto, aliquota e prezzo lordo del momento.

**Conseguenze.** Cambiare un prezzo non altera lo storico. Un po' di dati duplicati, voluti.

---

## D-012 — Soldi in centesimi interi, IVA in punti base
**Stato:** accettata · 2026-10-13

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

**Conseguenze.** Lo stesso `application.properties` funziona in locale e in produzione senza modifiche. Il `.env` è letto da due programmi con regole diverse: niente virgolette, niente `\`, niente spazi in fondo alle righe (errore reale del passo 4). Un percorso sbagliato non dà errori grazie a `optional:`: lo rivela la password senza default (`Could not resolve placeholder`).

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

**Conseguenze.** Il database garantisce entrambi i requisiti senza dipendere da Java. Il vincolo non pesa sugli utenti, perché Java normalizza sempre: scatta solo se il codice ha un bug, con un errore immediato alla scrittura (*fail fast*, come la password senza default in D-018). La ricerca per email è una semplice uguaglianza, servita dall'indice creato da `unique`. Confronto completo: `docs/studio/04-flyway-e-migrazione-v1.md`, parte 3; verifica 03, D2.

---

## D-021 — Ruolo `ADMIN` per chi gestisce la piattaforma
**Stato:** accettata · 2026-10-16

**Contesto.** I requisiti dicono che i professionisti gestiscono tutto dal pannello, ma non chi crea i professionisti stessi. Nicholas, durante la scrittura di V1, ha individuato il buco.

**Decisione.** Un terzo ruolo, `ADMIN`, accanto a `CUSTOMER` e `PRACTITIONER`. L'admin è Nicholas, come gestore della piattaforma, non una persona dello studio. Può creare, modificare e disattivare i professionisti. Ogni utente ha **un solo ruolo**: regge perché l'admin non è anche professionista.

**Alternative scartate.** *Nessun ruolo, professionisti inseriti con uno script SQL*: più semplice per due professionisti, ma ogni modifica richiederebbe un intervento sul database. *Ruoli multipli per utente* (es. una titolare che è anche admin): non serve finché l'admin è Nicholas.

**Conseguenze.** Il `check` sul ruolo ammette tre valori. Un professionista non si cancella mai: si disattiva, per conservare la sua storia (prenotazioni, pagamenti, crediti). Le chiavi esterne verso `app_user` non usano `on delete cascade`. Se in futuro la piattaforma servirà più studi, o un admin dovrà essere anche professionista, il modello dei ruoli andrà rivisto con una nuova decisione.
