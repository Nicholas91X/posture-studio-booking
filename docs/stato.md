# Stato del progetto

Ultimo aggiornamento: 2026-10-13

## Dove siamo

Progettazione chiusa (passi 1–3). **In corso: passo 4**, creazione del progetto e prime tabelle (dettagli in `passi/04-fondamenta.md`).

Fatto finora: struttura del repository, `.gitignore`, `.env.example`, progetto Spring Boot 4.1.1 generato da Initializr, package vuoti `auth`, `practitioner`, `customer`, PostgreSQL 17 in locale con `infra/docker-compose.dev.yml` (verificata la persistenza sul named volume).
Da fare (Nicholas): configurazione del backend, migrazione V1, entità `AppUser` e repository, test con Testcontainers.

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
- Servizio email → prima del passo 8

## Problemi aperti

Nessuno.
