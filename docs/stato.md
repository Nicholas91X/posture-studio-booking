# Stato del progetto

Ultimo aggiornamento: 2026-10-13

## Dove siamo

Progettazione chiusa (passi 1–3). **In corso: passo 4**, creazione del progetto e prime tabelle (dettagli in `passi/04-fondamenta.md`).

Fatto finora: struttura del repository, `.gitignore`, `.env.example`, progetto Spring Boot 4.1.1 generato da Initializr, package vuoti `auth`, `practitioner`, `customer`, PostgreSQL 17 in locale con `infra/docker-compose.dev.yml` (verificata la persistenza sul named volume), configurazione del backend da variabili d'ambiente (D-018, D-019): l'applicazione si avvia da `mvnw` e da VS Code, Flyway si collega (nessuna migrazione ancora).
Da fare (Nicholas): migrazione V1 (prima: scelta sull'email in minuscolo), entità `AppUser` e repository, test con Testcontainers.

Metodo di studio attivo: `docs/metodo-di-studio.md`, comando `/verifica`. Verifiche fatte: 01 (attori del passo 4), 02 (JDBC).

Avvio del database in locale, dalla radice: `docker compose -f infra/docker-compose.dev.yml --env-file .env up -d`

## Percorso

| # | Passo | Chi scrive | Stato |
|---|---|---|---|
| 1 | Regole e requisiti | — | ✅ fatto → `requisiti.md` |
| 2 | Architettura e scelte tecniche | — | ✅ fatto → `architettura.md` |
| 3 | Modello dei dati | — | ✅ fatto → `modello-dati.md` |
| 4 | Fondamenta: repository, Spring Boot, PostgreSQL in Docker, Flyway, prime tabelle | Nicholas | ⏳ in corso |
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
- Uno o due conti Stripe → prima del passo 9
- Scatto di inizio delle sedute → prima del passo 6
- Cancellazione da parte dello studio → prima del passo 11
- Aliquota IVA → prima del passo 5
- Servizio email (orientamento: Brevo) → prima del passo 8
- Codice di accesso anche via SMS → al passo 8 o dopo la prima versione
- Concessioni discrezionali del professionista (crediti, omaggi) → prima del passo 10
- Cancellazione dei dati di un cliente (GDPR, anonimizzazione) → prima del passo 14

## Note per i passi futuri

- **Passo 8 (accesso)**: sessione lunga che si rinnova a ogni visita (ordine di grandezza 30–90 giorni), per ridurre i codici via email senza introdurre password (D-014). Valutare il codice via SMS (requisiti, punto aperto 7): costo per messaggio, verifica del numero, limiti contro gli abusi (invio di SMS a raffica verso numeri a pagamento), consenso. Se il telefono diventa un modo per accedere, deve stare in `app_user`, unico e in un formato standard (internazionale, es. `+39...`), con la data di verifica.
- **Passi 10–11 (crediti, cancellazioni)**: concessioni discrezionali del professionista (requisiti, punto aperto 6). Il modello a lotti e movimenti (D-009, D-010) le regge senza stravolgimenti: un omaggio o un credito concesso su una cancellazione tardiva è un **lotto** con una nuova origine (es. `GRANT`), una correzione è un **movimento** con un nuovo motivo (es. `ADJUSTMENT`). Serve la traccia: chi l'ha concesso, quando, con una nota. Una concessione non cambia lo stato della prenotazione (resta `CANCELLED_LATE`): cambia solo il credito. Perimetro deciso: credito su cancellazione tardiva, sedute omaggio, proroga di una scadenza, ognuno per i propri clienti. Attenzione alla **proroga**: è l'unica che *modifica* qualcosa (la scadenza di un lotto) invece di aggiungere; per non perdere la storia va registrato anche l'evento (vecchia e nuova scadenza, chi, quando, perché).
- **Passo 8 (accesso), riattivazione**: con `unique` sull'email, un cliente disattivato che torna con la stessa email va **riattivato**, non registrato di nuovo.
- **Passo 13 (pannello)**: gestione dei professionisti da parte dell'admin (D-021), compresa la disattivazione.

## Problemi aperti

Nessuno.
