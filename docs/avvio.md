# Avvio in locale

Come si accende il progetto sul proprio computer, dalla prima volta all'uso di ogni giorno. Vale per lo sviluppo; la produzione (Caddy, HTTPS, server) è del passo 14.

## I pezzi e dove rispondono

| Pezzo | Cosa fa | Indirizzo | Da dove parte |
|---|---|---|---|
| PostgreSQL 17 | il database | `localhost:5432` (o `POSTGRES_PORT`) | Docker Compose |
| Mailpit | finto server di posta: riceve email (e SMS in sviluppo) e le mostra | pagina `http://localhost:8025`, SMTP `localhost:1025` | Docker Compose |
| Backend Spring Boot | tutte le regole e le API | `http://localhost:8080` | `backend/`, Maven |
| Frontend Next.js | il sito dei clienti | `http://localhost:3000` | `frontend/`, npm |
| Stripe CLI (facoltativo) | porta i webhook di Stripe in modalità di prova al backend | — | un terminale a parte |

Il browser parla solo con `http://localhost:3000`: Next inoltra `/api/...` al backend sulla 8080 (D-085). Così cookie di sessione e CSRF funzionano come dietro Caddy.

```
browser ──► :3000 Next ──/api──► :8080 Spring Boot ──► :5432 PostgreSQL
                                        │
                                        ├──► :1025 Mailpit (email e SMS finti) ──► pagina :8025
                                        └──◄ Stripe CLI ◄── Stripe (webhook di prova)
```

---

## La prima volta

### 1. Programmi da installare

| Programma | Versione | Per |
|---|---|---|
| Docker Desktop | recente | database e Mailpit |
| JDK | **21** | il backend (Maven non serve: c'è `mvnw.cmd`, che lo scarica) |
| Node.js | **22** (con npm) | il frontend |
| Stripe CLI | recente, facoltativo | solo per provare i pagamenti con carta (`stripe login` una volta) |

### 2. Il file `.env`

Nella cartella principale del progetto: copia `.env.example` in `.env` e riempilo. Il `.env` non va mai nel repository (contiene segreti); lo leggono sia Docker Compose sia il backend.

**Obbligatori** (senza, il backend non parte):

| Variabile | Come ottenerla |
|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | a scelta; restano quelli del volume del database una volta creato |
| `AUTH_CODE_HMAC_KEY` | 32 byte casuali in Base64. PowerShell: `$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)` |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` | insieme, da `backend/`: `java tools/GenerateVapidKeys.java` |
| `MAIL_HOST=localhost`, `MAIL_PORT=1025` | già giusti in `.env.example` |

I valori vanno scritti **senza virgolette** (D-018): con le virgolette una chiave Base64 non è più valida e l'avvio si ferma.

**Consigliati in sviluppo**:

| Variabile | Perché |
|---|---|
| `ADMIN_EMAIL` | crea l'account admin all'avvio (la tua email; senza, nessun admin) |
| `SMS_TO_MAILPIT=true` | gli SMS con il codice arrivano in Mailpit a `sms-<numero>@sms.local`; senza, l'accesso via SMS risponde 503 e il sito nasconde la scheda SMS |
| `OPENAPI_ENABLED=true` | accende il contratto `/v3/api-docs`, che serve a `npm run api` (solo sviluppo: in produzione si lascia fuori) |
| `APP_URL=http://localhost:3000` | l'indirizzo dei link nelle email e del ritorno da Stripe (già il default) |

**Per i pagamenti con carta** (facoltativi, modalità di prova):

```
PAYMENT_ACCOUNTS=prova
STRIPE_PROVA_SECRET_KEY=rk_test_...      (o sk_test_..., dalla dashboard di Stripe in modalità di prova)
STRIPE_PROVA_WEBHOOK_SECRET=whsec_...    (quello stampato da `stripe listen`, vedi sotto)
PAYMENT_LIVE=false
```

Con `PAYMENT_ACCOUNTS` vuoto il sito funziona lo stesso: nessun professionista è "prenotabile online" e si prenota solo con i crediti.

### 3. Le dipendenze del frontend

Da `frontend/`:

```powershell
npm install
npm run e2e:install    # solo se si vogliono lanciare i test end-to-end (scarica Chromium)
```

---

## Ogni giorno: l'avvio

Nell'ordine, ognuno nel suo terminale (tranne il primo, che finisce subito).

**1. Database e Mailpit**, dalla cartella principale:

```powershell
docker compose -f infra/docker-compose.dev.yml --env-file .env up -d
```

Restano accesi anche dopo, finché non li fermi o spegni Docker.

**2. Backend**, da `backend/`:

```powershell
.\mvnw.cmd spring-boot:run
```

È pronto quando scrive `Started BookingApplication`. Flyway applica da solo le migrazioni nuove al database.

**3. Frontend**, da `frontend/`:

```powershell
npm run dev
```

È pronto quando scrive `Ready`. Il sito è su `http://localhost:3000`. Se il backend non è sulla 8080: `$env:BACKEND_URL='http://localhost:XXXX'; npm run dev`. Per aprirlo da un telefono nella stessa rete: `npm run dev -- --hostname 0.0.0.0` e l'indirizzo del computer (ma vedi l'ultima riga di "Problemi già incontrati").

**4. Stripe CLI** (solo per provare i pagamenti con carta), da qualsiasi cartella:

```powershell
stripe listen --events checkout.session.completed,checkout.session.expired,payment_intent.succeeded,payment_intent.canceled --forward-to localhost:8080/api/webhooks/stripe/prova
```

Stampa `Your webhook signing secret is whsec_...`: deve essere uguale a `STRIPE_PROVA_WEBHOOK_SECRET` nel `.env`. Di solito resta lo stesso da un giorno all'altro; se cambia, aggiorna il `.env` e riavvia il backend. La parola finale dell'indirizzo (`prova`) è il nome del conto in `PAYMENT_ACCOUNTS`.

### Controllare che tutto risponda

| Cosa | Dove | Risposta attesa |
|---|---|---|
| Il sito | `http://localhost:3000` | l'elenco dei professionisti |
| Il backend attraverso il sito | `http://localhost:3000/api/public/practitioners` | un JSON con i professionisti |
| Il contratto (se `OPENAPI_ENABLED=true`) | `http://localhost:8080/v3/api-docs` | un JSON grande |
| Le email e gli SMS | `http://localhost:8025` | la casella di Mailpit |

---

## Usare il sistema in locale

- **Cliente**: dal sito, "Accedi" con un'email qualsiasi (anche inventata): il codice arriva in Mailpit. Al primo accesso il sito chiede nome e cognome.
- **Admin e professionisti**: il loro pannello web è del passo 13. Fino ad allora si usano le API da PowerShell con `tools/api.ps1`, che tiene una sessione per persona e manda il gettone CSRF:

  ```powershell
  . .\tools\api.ps1
  Api admin POST /api/public/auth/login-code '{"email": "<ADMIN_EMAIL>"}'
  # il codice arriva in Mailpit
  Api admin POST /api/public/auth/session '{"email": "<ADMIN_EMAIL>", "code": "123456"}'
  Api admin GET /api/admin/practitioners
  ```

  `ApiForget admin` dimentica la sessione. Un professionista entra allo stesso modo con la sua email (`Api anna ...`).
- **Carte di prova di Stripe**: `4242 4242 4242 4242`, una data futura qualsiasi, un CVC qualsiasi. La procedura completa della prova dal sito è in `passi/12-frontend-cliente.md`.

---

## Dopo un cambio delle API

Con il backend acceso e `OPENAPI_ENABLED=true`, da `frontend/`:

```powershell
npm run api         # scarica openapi.json e rigenera src/api/schema.d.ts
npm run typecheck   # se il backend ha cambiato qualcosa che il sito usa, qui non compila
```

I due file generati si committano (D-083).

---

## I test

| Test | Da dove | Comando | Note |
|---|---|---|---|
| Backend (tutti) | `backend/` | `.\mvnw.cmd clean test` | Testcontainers avvia un PostgreSQL suo: serve solo Docker acceso |
| End-to-end del sito | `frontend/` | `npm run e2e` | avviano da soli un backend sulla **8081** (database `posture_studio_e2e`, cancellato e ricreato a ogni giro) e un sito sulla **3001**, accanto a quelli di sviluppo; servono il PostgreSQL e il Mailpit di Compose accesi e il `.env`; le schermate del telefono restano in `frontend/test-results/` (non versionata) |

---

## Spegnere

- Backend, frontend e Stripe CLI: `Ctrl+C` nel loro terminale.
- Database e Mailpit: `docker compose -f infra/docker-compose.dev.yml --env-file .env stop`. I dati del database restano nel volume.
- Per **cancellare** il database e ripartire da zero: `docker compose -f infra/docker-compose.dev.yml --env-file .env down -v` (il `-v` toglie il volume: tutti i dati locali si perdono).

---

## Problemi già incontrati

| Sintomo | Causa | Soluzione |
|---|---|---|
| `password authentication failed` all'avvio del backend; il container PostgreSQL resta `Created` | un altro progetto usa già la 5432 | `POSTGRES_PORT=5433` nel `.env` (vale per Compose e per il backend) |
| Il backend si ferma subito parlando di una chiave | `AUTH_CODE_HMAC_KEY` o le chiavi VAPID mancano o hanno le virgolette | generarle come sopra, senza virgolette |
| Il backend si ferma parlando di una chiave `_live_` | una chiave di produzione con `PAYMENT_LIVE=false` | in sviluppo solo chiavi `_test_` |
| L'accesso con SMS non compare nel sito | `SMS_TO_MAILPIT` non è `true` | aggiungerlo e riavviare il backend |
| `npm run api` fallisce con 404 | il contratto è spento | `OPENAPI_ENABLED=true` e riavviare il backend |
| Dopo il pagamento il sito resta su "Pagamento in verifica" e poi dice che la conferma non è arrivata | `stripe listen` spento, o il suo `whsec_` diverso da quello del `.env` | accenderlo; se il segreto è cambiato, aggiornare il `.env`, riavviare il backend e `stripe events resend <evt>` |
| Da un telefono vero nella stessa rete l'accesso non resta | il cookie di sessione è `Secure`: funziona solo su `localhost` o in HTTPS | provare con la simulazione del telefono nel browser; HTTPS vero al passo 14 |
