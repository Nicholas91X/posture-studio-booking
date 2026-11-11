# Architettura

Come è fatto il sistema e come si muovono le informazioni. Le regole del prodotto stanno in `requisiti.md`, le tabelle in `modello-dati.md`, il perché di ogni scelta in `decisioni.md`.

## I pezzi

```
                 Internet
                     │
              ┌──────▼──────┐
              │    Caddy    │  HTTPS automatico, smista le richieste
              └──┬───────┬──┘
     /           │       │  /api/*
          ┌──────▼──┐  ┌─▼─────────────┐      ┌──────────┐
          │ Next.js │  │ Spring Boot   │◄─────┤  Stripe  │ (webhook)
          │frontend │  │ backend       ├─────►│          │ (crea pagamenti)
          └─────────┘  └──┬─────────┬──┘      └──────────┘
                          │         │
                   ┌──────▼───┐  ┌──▼──────────┐  ┌─────────────────┐
                   │PostgreSQL│  │ Servizio    │  │ Servizi push dei│
                   └──────────┘  │ email       │  │ browser (Web    │
                                 └─────────────┘  │ Push, D-032)    │
                                                  └─────────────────┘
```

Tutto gira con Docker Compose su un server Hetzner. Stripe, il servizio email e i servizi push dei produttori dei browser (Apple, Google, Mozilla) sono esterni. Le notifiche push partono dal backend.

| Pezzo | Tecnologia | Chi lo scrive | Cosa fa |
|---|---|---|---|
| Backend | Java 21, Spring Boot | Nicholas | Tutta la logica: calendario, prenotazioni, crediti, regole, pagamenti |
| Database | PostgreSQL 17 | Nicholas (migrazioni) | Conserva i dati e fa rispettare alcune regole (niente sovrapposizioni) |
| Frontend | Next.js, TypeScript | Claude | Pagine per prenotare e pannello dei professionisti. Mostra e invia, non decide |
| Reverse proxy | Caddy | Nicholas | Certificato HTTPS, indirizza `/api` al backend e il resto al frontend |
| Pagamenti | Stripe Checkout | — | Pagina di pagamento ospitata da Stripe |
| Email | da scegliere (orientamento: Brevo) | — | Codici di accesso, conferme, riserva delle notifiche push |
| Notifiche push | Web Push con chiavi VAPID (D-032) | — | Avvisano il professionista delle prenotazioni da approvare |

## Struttura del repository

Un unico repository (monorepo) con tutto dentro:

```
posture-studio-booking/
├── CLAUDE.md
├── docs/            documentazione (questa cartella)
├── backend/         Spring Boot (Maven)
├── frontend/        Next.js
└── infra/           docker-compose, Caddyfile, script di backup
```

## Backend

### Moduli

Un'unica applicazione, divisa in package per area. Ogni modulo espone verso gli altri solo un "servizio" pubblico: nessun modulo legge direttamente le tabelle di un altro.

| Modulo | Responsabilità |
|---|---|
| `practitioner` | Professionisti e loro impostazioni |
| `customer` | Clienti |
| `auth` | Codici di accesso, sessioni, ruoli |
| `catalog` | Servizi e pacchetti in vendita, prezzi |
| `schedule` | Orari di lavoro, indisponibilità, calcolo degli orari liberi |
| `booking` | Prenotazioni, blocco temporaneo, approvazione (D-030), cancellazioni |
| `credit` | Lotti e movimenti dei crediti |
| `payment` | Integrazione Stripe, webhook |
| `notification` | Invio email e notifiche push, dispositivi registrati (D-032). Ascolta gli eventi di `booking` dopo il commit, in un altro thread (D-053); push cifrate e firmate con le classi del JDK, solo verso i servizi push noti, con l'email al professionista come riserva (D-054); nessun modulo dipende da lui |

### Livelli dentro ogni modulo

- **Controller**: riceve la richiesta HTTP, valida l'input, chiama il servizio. Nessuna logica.
- **Service**: le regole del dominio e le transazioni.
- **Repository**: accesso al database con Spring Data JPA.
- **Oggetti di scambio**: le API ricevono e restituiscono `record` dedicati (DTO), mai le entità JPA.

### API

- REST, JSON, tutte sotto `/api`, divise per prefisso secondo chi le usa (D-029, D-045): `/api/public/**` senza accesso (professionisti, catalogo, orari liberi), `/api/panel/**` per i professionisti, `/api/customer/**` per i clienti. Al passo 8 Spring Security assegna ogni prefisso al suo ruolo con una regola sola. `/api/dev/**` contiene solo il pagamento simulato provvisorio, spento per default (D-046), da togliere al passo 9.
- Contratto OpenAPI generato in automatico (springdoc). Il frontend genera i suoi tipi da lì.
- Errori in formato standard *Problem Details* (RFC 9457), con un codice leggibile (es. `SLOT_NOT_AVAILABLE`) che il frontend traduce in un messaggio in italiano.

### Lavori programmati

- **Scadenza dei blocchi**: ogni minuto, le prenotazioni in attesa di pagamento con blocco scaduto passano a "scaduta" e l'orario si libera.
- **Scadenza delle approvazioni** (D-030): ogni minuto, le prenotazioni in attesa di approvazione oltre la scadenza passano ad "approvazione scaduta". Il blocco sulla carta si annulla o il credito torna, l'orario si libera e il cliente riceve un'email.
- **Sedute passate**: ogni notte, le prenotazioni confermate già avvenute passano a "svolta".

### Test

- Test unitari per le regole del dominio (calcolo disponibilità, consumo dei crediti, regole di cancellazione).
- Test di integrazione con Testcontainers: un PostgreSQL vero, avviato in Docker durante i test.
- Il webhook di Stripe si testa con eventi di esempio firmati.

## Database

- PostgreSQL, in un container Docker con i dati su un volume.
- Lo schema cambia solo con migrazioni Flyway numerate (`V1__...sql`, `V2__...sql`).
- Alcune regole le fa rispettare il database stesso, come ultima difesa: due prenotazioni attive non possono sovrapporsi per lo stesso professionista.
- Backup: dump giornaliero, copiato fuori dal server.

## Frontend

- Next.js con App Router, TypeScript.
- Due aree:
  - **Pubblica**: scelta del professionista e del servizio, calendario, accesso con codice, pagamento, "le mie sedute" e crediti.
  - **Pannello** (solo professionisti): agenda, richieste da approvare, servizi e prezzi, pacchetti, orari, indisponibilità, impostazioni. Installabile sul telefono come app (*PWA*), con un *service worker* che riceve le notifiche push (D-032).
- Nessuna regola di business: il frontend chiede al backend e mostra quello che riceve.
- Mobile prima di tutto: la maggior parte dei clienti prenoterà dal telefono.

## Accesso e sessioni

1. L'utente inserisce l'email.
2. Il backend genera un codice di 6 cifre, ne salva solo l'impronta (HMAC con una chiave segreta, D-050), con scadenza di 10 minuti e un massimo di 5 tentativi, e lo invia via email. La risposta è identica che l'email esista o no; limiti per email e per IP (429).
3. L'utente inserisce il codice. Se è corretto, e l'email non era mai stata vista, nasce l'account `CUSTOMER` (D-050). Il backend crea la sessione e la invia come cookie `SESSION`, `HttpOnly`, `Secure`, `SameSite=Lax`, con `Max-Age` di 90 giorni. All'accesso l'id della sessione cambia (*session fixation*).
4. Frontend e backend sono sullo stesso dominio (grazie a Caddy), quindi il cookie funziona senza configurazioni particolari.
5. **La sessione** (D-051) vive in PostgreSQL con Spring Session JDBC, e sopravvive ai riavvii. Scade dopo 30 giorni senza richieste, e comunque 90 giorni dopo l'accesso. Contiene solo l'id dell'utente: a ogni richiesta il backend rilegge l'account, quindi un account disattivato è fuori subito e il ruolo è sempre quello attuale.
6. **Regole per area** (Spring Security, una regola per prefisso): `/api/public/**` aperto, `/api/panel/**` solo professionisti, `/api/customer/**` solo clienti, `/api/admin/**` solo l'admin, `/api/account/**` chiunque sia entrato (il proprio numero di telefono, D-055: clienti e professionisti, non l'admin, D-056), tutto il resto chiuso a tutti. Nessuna sessione: 401; ruolo sbagliato: 403; la cosa di un altro: 404.
7. **CSRF**: ogni richiesta che cambia qualcosa, accesso compreso, rimanda nell'header `X-XSRF-TOKEN` il token del cookie `XSRF-TOKEN`.
8. Un cliente appena entrato senza nome e cognome li dà una volta (`PUT /api/customer/profile`); prima riceve 409 `PROFILE_INCOMPLETE`.
9. **Via SMS** (D-055, D-056): un cliente o un professionista entrato può aggiungere un cellulare italiano, verificato con un codice mandato a quel numero (`/api/account/phone`). Da allora può accedere anche con un codice via SMS (`/api/public/auth/login-code/sms`, `/api/public/auth/session/sms`). Un numero non crea mai un account. La risposta è uguale per ogni numero, e l'SMS parte in un altro thread; limiti per numero, IP e account, e un tetto di 50 SMS al giorno. L'admin accede solo con l'email: niente numero, quindi niente SMS (contro lo scambio di SIM). Senza un fornitore configurato le API SMS rispondono 503.

Lo stesso meccanismo vale per i professionisti: la loro email ha il ruolo `PRACTITIONER`, che apre il pannello. I professionisti li crea e li disattiva l'admin (`/api/admin/practitioners`, D-052). Il primo admin nasce all'avvio dall'email in `ADMIN_EMAIL`, una variabile d'ambiente: nel repository non c'è.

## Flussi principali

### Prenotazione con carta

1. Il cliente sceglie un orario libero (che inizia più di 24 ore dopo, D-030). Se il professionista approva a mano, il cliente vede il tempo di risposta prima di confermare.
2. Il backend crea la prenotazione in stato "in attesa di pagamento", con un blocco di 32 minuti (D-061). Se il professionista non ha un conto di incasso (D-057), la richiesta è rifiutata prima. Se l'orario nel frattempo è stato preso, il database rifiuta e il cliente vede "orario non più disponibile".
3. Nella stessa transazione, il backend crea una sessione Stripe Checkout con *manual capture* (D-031), sul conto del professionista e con scadenza uguale al blocco, registra il pagamento "in attesa" e restituisce l'indirizzo della pagina. Se Stripe non risponde, non resta niente: il cliente riprova (D-061).
4. Il cliente paga sulla pagina di Stripe e torna sul sito, che mostra "pagamento in verifica". Stripe ha solo **bloccato** l'importo sulla carta.
5. Stripe avvisa il backend con il webhook `checkout.session.completed`. La prenotazione passa ad "in attesa di approvazione", con la scadenza fotografata.
6. **Approvazione**:
   - automatica: il backend approva subito;
   - manuale: il backend avvisa il professionista con una notifica push su tutti i suoi dispositivi; se nessuno la accetta, con un'email (D-054). Lui approva o rifiuta dal pannello entro la scadenza.
7. **Approvata**: il backend chiede a Stripe l'incasso, e solo quando Stripe risponde che è riuscito la prenotazione è confermata e il cliente riceve l'email di conferma. Se il blocco non c'è più, la richiesta resta in attesa e il professionista riceve un errore che lo dice (D-063).
8. **Rifiutata, scaduta o ritirata dal cliente**: il backend annulla il blocco sulla carta, l'orario si libera, e il cliente riceve un'email. Non è stato addebitato niente.

### Link di pagamento per una seduta "in studio" (D-059, D-065)

1. Il professionista, su una sua seduta confermata da pagare in studio, chiede un link. Il backend fa scadere su Stripe la pagina del link precedente (se non è appena stata pagata), crea una sessione Checkout con *manual capture* che vale 24 ore o fino all'inizio della seduta, e restituisce l'indirizzo della pagina e quello di WhatsApp con il messaggio pronto.
2. Il professionista manda il messaggio dal suo telefono; il cliente paga: importo bloccato.
3. Al webhook: se la seduta aspetta ancora di essere pagata in studio, il backend **incassa subito** e la seduta passa a "pagata con carta"; altrimenti (cancellata, già pagata da un altro link) **annulla il blocco**. Di due pagamenti, vince il primo che arriva.

### Prenotazione con credito

1. Il cliente sceglie un orario, con la stessa richiesta di sempre. Il backend vede che ha un credito per quel professionista e servizio **il cui ultimo giorno valido copre il giorno della seduta** (D-071); il cliente non sceglie: se il credito c'è si usa, altrimenti si paga con la carta. Il limite di prenotazioni future vale anche qui.
2. In un'unica transazione, con il lucchetto sul cliente e sul lotto: crea la prenotazione in attesa di approvazione e registra un movimento −1 sul lotto che scade prima (il credito è **impegnato**). Un professionista senza conto di incasso riceve comunque queste prenotazioni.
3. Approvazione come per la carta, senza niente da incassare (in automatica, subito). Approvata: confermata. Rifiutata, scaduta o ritirata: movimento +1 sullo stesso lotto, che mantiene la sua scadenza. Le email parlano del credito, non della carta.

Nessun passaggio su Stripe.

### Concessioni del professionista (D-067, D-073)

Dal pannello, sui propri lotti e servizi, sempre con chi (l'account), quando e perché (una nota obbligatoria):
- **seduta omaggio**: un lotto nuovo per un cliente trovato o creato per email, valido fino al giorno scelto o quanto una seduta singola;
- **proroga**: l'ultimo giorno valido di un lotto più avanti, anche se è già scaduto, con la riga della storia;
- **correzione**: un movimento ±N, mai sotto zero.

Il cliente riceve un'email per ognuna, senza la nota del professionista.

### Acquisto di un pacchetto

1. Il cliente sceglie un pacchetto in vendita (`POST /api/customer/package-purchases`). Se il professionista non ha un conto di incasso, la richiesta è rifiutata prima di chiamare Stripe (D-057).
2. Nella stessa transazione il backend crea la sessione Stripe Checkout con **incasso immediato** (D-031) sul conto del professionista, che dura **un'ora**, e registra il pagamento "in attesa" con la **fotografia** di quello che il cliente vede: prezzo, sedute, validità (D-069). Se Stripe non risponde, non resta niente.
3. Il cliente paga e torna alla pagina dei suoi crediti, che chiede lo stato degli acquisti (D-007).
4. Al webhook `checkout.session.completed` con `payment_status = paid`: il pagamento diventa "riuscito" e nasce il lotto dalla fotografia (anche se nel frattempo il pacchetto è cambiato o è stato disattivato), con il movimento +N e l'ultimo giorno valido contato dal giorno del pagamento a Roma. **Una volta sola**: evento già visto, pagamento letto con il lucchetto, un lotto per pagamento nel database (D-070).
5. Dopo il commit, il cliente riceve l'email "Acquisto confermato".

### Cancellazione

1. Il backend confronta l'ora attuale con l'inizio della seduta e il preavviso del professionista.
2. **In tempo**: stato "cancellata in tempo", l'orario si libera, e:
   - se era pagata con credito: movimento +1 sullo stesso lotto (mantiene la sua scadenza);
   - se era pagata con carta: nuovo lotto da 1, con la validità prevista per le sedute singole.
3. **Tardi**: stato "cancellata tardi", l'orario si libera, nessun credito.

## Stripe

Cosa si usa, con le decisioni da cui viene:

- **Stripe Checkout**: la pagina di pagamento è di Stripe. Il backend crea una *Checkout Session* con il prezzo fotografato, il nome del servizio e l'id della prenotazione nei metadati, e riceve un indirizzo a cui mandare il cliente (D-061). Il sistema non vede mai la carta.
- **Conti di incasso** (D-057, D-060): un conto Stripe per professionista o uno comune, dichiarati nella configurazione e assegnati dall'admin; ogni pagamento resta sul conto su cui è nato, e incasso e annullamento avvengono sempre lì.
- **Scadenza della sessione**: Stripe accetta scadenze da 30 minuti a 24 ore **dalla creazione della sessione**, che arriva un attimo dopo la richiesta. Per questo il blocco dell'orario dura **32 minuti** e la sessione scade nello stesso istante, arrotondata per difetto al secondo (D-061, che modifica D-008).
- **Tempi d'attesa verso Stripe** (D-061): 2 secondi per collegarsi, 5 per leggere, un nuovo tentativo; ogni errore di Stripe diventa un 503 e annulla la transazione.
- **Manual capture** (D-031, D-063): per le sedute la sessione blocca l'importo senza incassarlo. All'**approvazione** il backend legge il PaymentIntent e, se è ancora da incassare, incassa; poi conferma (D-064). Al **rifiuto**, al **ritiro** e alla **scadenza della risposta** annulla il blocco; se Stripe non risponde, un lavoro programmato riprova con pause crescenti, poi si arrende (D-064). Il blocco dura in genere 7 giorni. I pacchetti (passo 10) si incassano subito.
- **Webhook** (D-062, D-063): un indirizzo per conto, `/api/webhooks/stripe/{conto}`, fuori dal CSRF e protetto dalla firma. **Sugli endpoint di Stripe si abilitano quattro eventi**, formato completo, versione dell'API `2026-10-28.endive`:
  - `checkout.session.completed` → pagamento bloccato, prenotazione in attesa di approvazione. Con l'incasso manuale la sessione dice `payment_status = unpaid`: conta `status = complete`. Per un **pacchetto** (incasso immediato) deve dire `paid`: il pagamento è riuscito e nasce il lotto di crediti (D-070).
  - `checkout.session.expired` → pagamento scaduto; la prenotazione la chiude il lavoro programmato entro un minuto.
  - `payment_intent.succeeded` → riconciliazione: se il nostro commit dopo l'incasso era fallito, il pagamento risulta incassato e la prenotazione confermata, senza incassare di nuovo.
  - `payment_intent.canceled` → il blocco sciolto da Stripe dopo 7 giorni, o un nostro annullamento che Stripe non aveva confermato.
  - ogni altro tipo: risposta 200, niente registrato.
- **Verifica della firma**: HMAC-SHA256 di timestamp e corpo grezzo con il segreto dell'endpoint, 5 minuti di tolleranza; firma mancante o sbagliata → 400 (D-062).
- **Idempotenza**, nei due versi: verso Stripe, ogni chiamata che crea qualcosa porta una chiave (`booking-{id}-checkout`; per incasso e annullamento una chiave nuova a ogni tentativo, perché Stripe restituisce lo stesso 500 alla stessa chiave per 24 ore, D-064). Da Stripe, ogni evento si elabora una volta: tabella `stripe_event` nella stessa transazione, e ogni passaggio del pagamento sull'entità letta con il lucchetto, prima di chiedere qualcosa alla prenotazione (D-062, D-063).
- **Pagamento arrivato tardi**: se un pagamento arriva per una prenotazione già scaduta, il backend **annulla quel blocco** sul conto del pagamento (D-031, prima era un credito) e avvisa il cliente via email (D-062).
- **Modalità test**: in sviluppo si usano le chiavi di test e le carte di prova di Stripe. La Stripe CLI inoltra i webhook al backend in esecuzione sul computer locale (guida 32, parte 13.1).
- **Rimborsi**: non gestiti dal sistema (D-015). In casi eccezionali, che il registro segnala come errori (un incasso per una prenotazione che non si può più confermare), si fanno a mano dalla dashboard di Stripe.

## Docker e messa online

### In locale
- `infra/docker-compose.dev.yml` avvia PostgreSQL e **Mailpit**, un finto server di posta che mostra le email ricevute su `http://localhost:8025` senza spedirle (D-050). Backend e frontend girano dall'IDE, per avere riavvii rapidi e debug.

### In produzione (Hetzner)
- `infra/docker-compose.yml` con quattro servizi: `caddy`, `frontend`, `backend`, `postgres`.
- Solo Caddy è esposto su internet (porte 80 e 443). Gli altri parlano tra loro sulla rete interna di Docker.
- I segreti stanno in un file `.env` sul server, mai nel repository.
- Le immagini Docker si costruiscono con GitHub Actions e si pubblicano su GitHub Container Registry. Il server scarica l'ultima versione e riavvia.
- Il backend si costruisce con un `Dockerfile` a due fasi: una per compilare con Maven, una leggera con solo il JRE per eseguire.
- Backup giornaliero del database con `pg_dump`, copiato su uno spazio esterno al server.
