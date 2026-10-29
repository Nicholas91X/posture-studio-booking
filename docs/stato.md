# Stato del progetto

Ultimo aggiornamento: 2026-10-29

## Dove siamo

Progettazione chiusa (passi 1–3). **Passo 4 chiuso** (dettagli e risposte modello alle domande da colloquio in `passi/04-fondamenta.md`). Prossimo: passo 5, catalogo e professionisti.

Fatto nel passo 4: struttura del repository, progetto Spring Boot 4.1.1 su Java 21, PostgreSQL 17 in locale con `infra/docker-compose.dev.yml`, configurazione da variabili d'ambiente (D-018, D-019) con `ddl-auto=validate`, migrazione V1 (`app_user`, `customer`, `practitioner`; D-020, D-021), prima entità `AppUser` con `Email`, `EmailConverter`, `Role` e `AppUserRepository`. Test: `EmailTest` (12 unitari, senza Spring) e `AppUserRepositoryTest` (9 di integrazione con Testcontainers su `postgres:17`), tutti verdi.

**Verifiche di comprensione sospese** dal 2026-10-29 per scelta di Nicholas (`docs/metodo-di-studio.md`); `/verifica` resta disponibile su richiesta. Verifiche fatte: 01 (attori del passo 4), 02 (JDBC), 03 (Flyway e vincoli); 04 e 05 abbandonate. Guide di studio: 01–07 in `docs/studio/` (06 come funziona Spring, 07 costruttori).

Decisioni del 2026-10-29 (solo documentazione): niente IVA perché entrambi i professionisti sono forfettari (D-022, modifica D-011 e D-012); calendario non pubblico (requisiti, "Riservatezza del calendario"); orizzonte di prenotazione e limite di sedute future (D-023); prenotazione dal pannello per conto di un cliente (D-024).

Avvio del database in locale, dalla radice: `docker compose -f infra/docker-compose.dev.yml --env-file .env up -d`

## Percorso

| # | Passo | Chi scrive | Stato |
|---|---|---|---|
| 1 | Regole e requisiti | — | ✅ fatto → `requisiti.md` |
| 2 | Architettura e scelte tecniche | — | ✅ fatto → `architettura.md` |
| 3 | Modello dei dati | — | ✅ fatto → `modello-dati.md` |
| 4 | Fondamenta: repository, Spring Boot, PostgreSQL in Docker, Flyway, prime tabelle | Nicholas | ✅ fatto → `passi/04-fondamenta.md` |
| 5 | Catalogo e professionisti: servizi, prezzi, impostazioni | Nicholas | |
| 6 | Calendario: orari di lavoro, indisponibilità, calcolo degli orari liberi | Nicholas | |
| 7 | Prenotazione: blocco dell'orario, vincolo di non sovrapposizione, lavori programmati | Nicholas | |
| 8 | Accesso: codice via email, sessioni, ruoli (Spring Security) | Nicholas | |
| 9 | Pagamenti: Stripe Checkout e webhook | Nicholas | |
| 10 | Crediti e pacchetti: lotti, movimenti, acquisto, prenotazione con credito | Nicholas | |
| 11 | Cancellazioni | Nicholas | |
| 12 | Frontend cliente: prenotazione e "le mie sedute" | Claude | |
| 13 | Frontend pannello professionisti | Claude | |
| 14 | Messa online: Dockerfile, Compose, Caddy, GitHub Actions, backup su Hetzner | Nicholas | |

Il frontend può partire prima, appena esistono le prime API (dopo il passo 7), se serve mostrare qualcosa allo studio.

## Punti aperti

Elencati in `requisiti.md`, sezione "Punti aperti". Da chiudere prima del passo indicato:
- Uno o due conti Stripe (quasi certamente due: due partite IVA distinte) → prima del passo 9
- Scatto di inizio delle sedute → prima del passo 6
- Cancellazione da parte dello studio → prima del passo 11
- ~~Aliquota IVA~~ → chiuso il 2026-10-29: niente IVA (D-022)
- Modo di pagamento delle prenotazioni dal pannello (proposta: in studio) → prima del passo 7
- Valori iniziali di orizzonte e limite (proposta: 21 giorni, 2 sedute) → prima del passo 7
- Servizio email (orientamento: Brevo) → prima del passo 8
- Codice di accesso anche via SMS → al passo 8 o dopo la prima versione
- Concessioni discrezionali del professionista (crediti, omaggi) → prima del passo 10
- Cancellazione dei dati di un cliente (GDPR, anonimizzazione) → prima del passo 14

## Note per i passi futuri

- **Passo 5 (catalogo)**: servizi e pacchetti hanno un solo `price_cents`, niente aliquota né calcolo del lordo (D-022).
- **Passo 7 (prenotazione)**: la migrazione aggiunge `booking_horizon_days` e `max_future_bookings` a `practitioner` (D-023) e il servizio di prenotazione li fa rispettare solo quando prenota il cliente. Attenzione: una `PENDING_PAYMENT` con blocco già scaduto non dovrebbe contare nel limite, coerentemente con il calcolo degli orari liberi (`modello-dati.md`, `booking`); e due richieste simultanee dello stesso cliente potrebbero superare il limite, perché è un controllo in Java e non un vincolo del database. Prenotazione dal pannello (D-024): traccia di chi l'ha creata, eventuale nuovo valore di `paid_with`, creazione del cliente se non esiste.
- **Passo 8 (accesso), riservatezza del calendario**: ogni regola di "Riservatezza del calendario" (requisiti) diventa un **test automatico**. Almeno: utente anonimo rifiutato su prenotazioni e sedute (vede solo gli orari liberi); professionista A rifiutato sulle prenotazioni di B; cliente rifiutato sulle sedute di altri. **Criterio di "fatto" del passo 8**: questi test esistono e sono verdi.
- **Passo 8 (accesso)**: sessione lunga che si rinnova a ogni visita (ordine di grandezza 30–90 giorni), per ridurre i codici via email senza introdurre password (D-014). Valutare il codice via SMS (requisiti, punto aperto 7): costo per messaggio, verifica del numero, limiti contro gli abusi (invio di SMS a raffica verso numeri a pagamento), consenso. Se il telefono diventa un modo per accedere, deve stare in `app_user`, unico e in un formato standard (internazionale, es. `+39...`), con la data di verifica.
- **Passi 10–11 (crediti, cancellazioni)**: concessioni discrezionali del professionista (requisiti, punto aperto 6). Il modello a lotti e movimenti (D-009, D-010) le regge senza stravolgimenti: un omaggio o un credito concesso su una cancellazione tardiva è un **lotto** con una nuova origine (es. `GRANT`), una correzione è un **movimento** con un nuovo motivo (es. `ADJUSTMENT`). Serve la traccia: chi l'ha concesso, quando, con una nota. Una concessione non cambia lo stato della prenotazione (resta `CANCELLED_LATE`): cambia solo il credito. Perimetro deciso: credito su cancellazione tardiva, sedute omaggio, proroga di una scadenza, ognuno per i propri clienti. Attenzione alla **proroga**: è l'unica che *modifica* qualcosa (la scadenza di un lotto) invece di aggiungere; per non perdere la storia va registrato anche l'evento (vecchia e nuova scadenza, chi, quando, perché).
- **Passo 8 (accesso), riattivazione**: con `unique` sull'email, un cliente disattivato che torna con la stessa email va **riattivato**, non registrato di nuovo.
- **Passo 13 (pannello)**: gestione dei professionisti da parte dell'admin (D-021), compresa la disattivazione. Prenotazione per conto di un cliente e creazione del cliente con nome, cognome ed email (D-024). Da chiarire prima: quali clienti può cercare e vedere un professionista.

