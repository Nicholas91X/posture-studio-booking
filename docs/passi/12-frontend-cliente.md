# Passo 12 — Frontend del cliente

## Obiettivo

Il sito dei clienti: accesso, scelta di professionista, servizio e orario, pagamento con Stripe o con un credito, "le mie sedute" con ritiro e cancellazione, crediti e pacchetti, profilo. Tutte le regole restano nel backend: il frontend mostra e invia, non decide (D-005). Prima, il contratto OpenAPI da cui il frontend genera i suoi tipi.

Il codice l'ha scritto Claude (D-005, percorso). Al posto delle guide di studio, una nota breve sui punti in cui frontend e backend si incontrano: `docs/studio/47-frontend-e-backend.md`.

## Criteri di "fatto" (dal brief)

- [x] **Contratto OpenAPI generato; tipi del frontend generati, nessuno a mano** — `frontend/openapi.json` e `frontend/src/api/schema.d.ts`, da `npm run api` (D-083). Gli unici tipi scritti nel frontend sono interni alle pagine (stati di una pagina, "in attesa"/"scaduto"), mai una forma delle API.
- [x] **"Prenotabile online" nell'API pubblica e nel sito** — `PublicPractitioner.onlineBooking` (D-084), l'avviso nell'elenco e sulla pagina del professionista prima di scegliere l'orario; provato in `PublicApiTest` e nei test end-to-end.
- [ ] **Un cliente nuovo accede, prenota, paga in modalità di prova, vede la seduta confermata dopo l'approvazione** — **prova manuale da fare** (Nicholas, con la Stripe CLI e il conto di prova): procedura qui sotto. Il codice c'è; i test automatici non parlano con Stripe.
- [x] **Prenotazione con credito, ritiro, cancellazione in tempo e tardiva con la frase giusta prima della conferma** — test end-to-end 3, 4, 5.
- [x] **Crediti e acquisto di un pacchetto** — pagina dei crediti e storia di un lotto (test end-to-end 8); l'acquisto apre Stripe e torna a `/crediti?acquisto=...`, da provare a mano con la stessa procedura.
- [x] **Nessun errore tecnico mostrato al cliente; codice sconosciuto gestito** — un solo dizionario (`errors.ts`), test end-to-end 6 (orario preso nel frattempo) e 7 (codice inventato e `detail` tecnico: compare solo la frase generica).
- [x] **Test end-to-end verdi; pagine usabili da telefono** — 8 su 8 su un Pixel 7 simulato; il test 8 visita ogni pagina, controlla che niente sia più largo dello schermo e salva le schermate (guardate una per una).
- [x] **stato.md aggiornato** (avvio del frontend, note per i passi 13 e 14); **PR verso main**: PR #9, unita con un merge.
- [x] **Riepilogo per la chat di pianificazione**.

## Cosa è stato costruito

### Pezzo 0 — Il contratto OpenAPI (backend, D-083, D-084)

- `pom.xml`: springdoc 3.2.0 (`springdoc-openapi-starter-webmvc-api`), costruito su Spring Boot 4.1.2.
- `application.properties`: contratto acceso solo con `OPENAPI_ENABLED=true`, OpenAPI 3.0, chiavi in ordine, `application/json`, parametri della query piatti, niente webhook. `SecurityConfiguration`: `GET /v3/api-docs` aperto (esiste solo se acceso).
- `shared/OpenApiConfiguration`: le risposte d'errore `ApiProblem` con la lista chiusa dei codici; i campi delle risposte obbligatori; i riferimenti nullable come `allOf`. `shared/ProblemCodes` + `auth/AuthProblemCodes`: i codici di un modulo nel contratto.
- `@Schema(nullable = true)` sui campi che possono essere `null` (`CustomerBookingView`, `PackagePurchaseView`, `CreditEntry`, `AccountPhoneView`, e per il passo 13 `PanelBookingRow`, `PanelBookingView`, `PanelCancellationView`, `UnavailabilityView`); `@ApiResponse` sulle risposte `ResponseEntity<?>` dell'accesso e sui 201/202/204 del cliente.
- Prenotabile online: `practitioner/OnlinePayments` (interfaccia), `payment/AccountOnlinePayments` (implementazione), `practitioner/PublicPractitioners` (l'elenco pubblico), `PublicPractitioner` con `onlineBooking`, `bookingApproval`, `approvalWindowHours`, `cancellationNoticeHours`.
- Test: `shared/OpenApiContractTest` (6), due in `PublicApiTest`, `PractitionerServiceTest` adattato.

### Pezzo 1 — La base (D-085)

- `frontend/` con Next.js 16.4.6, React 19.3, TypeScript 5.9.3 rigoroso; `next.config.ts` con la riscrittura `/api` verso il backend.
- `src/brand.ts` e `src/app/tokens.css`: nome e colori in un punto solo, tema scuro compreso; `globals.css`: i blocchi delle pagine, pensati per il telefono (barra in basso, bersagli da 44 px, campi da 16 px).
- `src/api/client.ts` (client unico: tipi generati, CSRF, `ApiError`), `src/api/errors.ts` (dizionario), `src/lib/format.ts` (date in Europe/Rome, giorni di calendario, prezzi da centesimi), `src/lib/labels.ts` (stati in parole), `src/lib/session.tsx` (chi è entrato), `src/lib/directory.ts` (nomi di professionisti e servizi), `src/lib/poll.ts`, `src/lib/navigation.ts`.

### Pezzo 2 — Accesso

`/accedi`: email o SMS, il codice (`autocomplete="one-time-code"`), nome e cognome al primo accesso; frasi che non dicono se l'indirizzo o il numero esistono; `?torna=` solo verso un percorso del sito. `RequireCustomer` per le pagine riservate. L'uscita dal profilo.

### Pezzo 3 — Scegliere e prenotare

`/` (professionisti), `/professionisti/{id}` (cosa sapere prima: approvazione, preavviso, pagamento online; i crediti utilizzabili del cliente; servizi e pacchetti), `/professionisti/{id}/servizi/{serviceId}` (giorni e orari liberi, riepilogo, prenotazione: con un credito alla pagina della seduta, con la carta su Stripe). `SLOT_NOT_AVAILABLE` rilegge gli orari; `BOOKING_LIMIT_REACHED` porta alle sedute. `/prenotazioni/{id}` è anche il ritorno da Stripe: rilegge ogni 2 secondi per un minuto.

### Pezzo 4 — Le mie sedute

`/prenotazioni` (prossime e passate, `?period`), `BookingCard`: stato in parole, "Completa il pagamento" finché vale `paymentUrl`, ritiro di una richiesta, cancellazione con la frase prima della conferma (`lib/cancellation.ts`) e il risultato del backend dopo.

### Pezzo 5 — Crediti e pacchetti

`/crediti` (lotti utilizzabili e non disponibili in evidenza, esauriti e scaduti a parte; `PRACTITIONER_UNAVAILABLE` con "contatta lo studio"; ritorno da Stripe di un acquisto), `/crediti/{id}` (la storia, senza note); l'acquisto di un pacchetto dalla pagina del professionista.

### Pezzo 6 — Profilo

`/profilo`: nome e cognome, l'email (solo lettura), il cellulare da aggiungere, verificare, cambiare o togliere (D-055), l'uscita.

### Prove

`frontend/e2e/`: `start-backend.mjs` (backend sulla 8081 con il database `posture_studio_e2e` ricreato a ogni giro, admin dei test, nessun conto Stripe), `support.ts` (codici da Mailpit, attori che chiamano le API con sessione e CSRF), `customer.spec.ts` (8 test), `playwright.config.ts` (Pixel 7, frontend sulla 3001 in `.next-e2e`).

## Prova manuale con Stripe (da fare)

Come le prove dei passi 9 e 10 (guida 32, parte 13.1; guida 37, parte 12.1), ma dal sito.

1. `.env` con il conto `prova` (`PAYMENT_ACCOUNTS=prova` e le sue due chiavi) e `OPENAPI_ENABLED=true`; database e Mailpit su.
2. Backend: da `backend/`, `.\mvnw.cmd spring-boot:run`. Frontend: da `frontend/`, `npm run dev`. Stripe: `stripe listen --events checkout.session.completed,checkout.session.expired,payment_intent.succeeded,payment_intent.canceled --forward-to localhost:8080/api/webhooks/stripe/prova` (il `whsec_` che stampa è `STRIPE_PROVA_WEBHOOK_SECRET`).
3. Con `tools/api.ps1`: l'admin assegna il conto `prova` ad Anna; Anna ha un servizio, gli orari e l'approvazione **manuale**.
4. Dal telefono o dal browser, `http://localhost:3000`: Anna **non** ha l'avviso "non si può pagare online". Accedi con un'email nuova (codice da Mailpit, `http://localhost:8025`), nome e cognome.
5. Scegli un orario, "Prenota": si apre Stripe. Paga con `4242 4242 4242 4242`. Al ritorno: "Pagamento in verifica", poi "Pagamento ricevuto: l'importo è bloccato…" con la scadenza della risposta. In `stripe listen`: `checkout.session.completed → 200`.
6. Da Anna, `POST /api/panel/bookings/{id}/approve`. Ricarica "Le mie sedute": **Confermata**. In dashboard: "Riuscito".
7. Variante annullata: un'altra prenotazione, su Stripe "indietro": la pagina dice "Il pagamento non è stato completato" e "Completa il pagamento" riapre la stessa pagina di Stripe.
8. Variante senza webhook: ferma `stripe listen`, paga: dopo un minuto il messaggio "La conferma del pagamento non è ancora arrivata…"; riaccendi `stripe listen` e `stripe events resend <evt>`; "Controlla di nuovo" mostra lo stato nuovo.
9. Un pacchetto: dalla pagina di Anna "Acquista", paga, al ritorno su `/crediti` "Acquisto confermato: N crediti, validi fino al …".
10. Annota l'esito in `stato.md` e spunta il criterio qui sopra.

## Concetti chiave

- **Contratto prima dei tipi.** Il frontend non conosce il backend se non attraverso il contratto. Un contratto sbagliato produce tipi sbagliati in silenzio: obbligatorietà, `null` ed errori vanno curati nel backend, dove sono i `record` (nota 47, parte 1).
- **Una sola origine.** Il cookie `HttpOnly` lo manda il browser, solo alla stessa origine: per questo in sviluppo Next riscrive `/api` come Caddy in produzione, invece di aprire CORS (nota 47, parte 2).
- **Double submit cookie.** Il CSRF di Spring con `csrf.spa()`: un cookie leggibile solo dal nostro dominio, rimandato in un header; nel frontend in un solo punto, il middleware del client.
- **Il ritorno da Stripe non prova niente.** Il browser e il webhook sono due strade indipendenti; la pagina rilegge lo stato, con un limite di tempo e un messaggio che non invita a pagare due volte (nota 47, parte 3).
- **Errori come dati.** Il codice del `ProblemDetail` è il contratto; la frase è del frontend, da un solo dizionario tipato sull'enum del contratto, con una frase generica per l'ignoto (nota 47, parte 4).
- **La compilazione come controllo.** `Record<ApiErrorCode, string>` e `Record<BookingStatus, ...>`: un codice o uno stato nuovo nel backend fa fallire la compilazione del frontend, come lo `switch` senza `default` sulle `sealed interface` in Java.
- **Test end-to-end isolati.** Un backend, un database e un frontend propri, accanto a quelli di sviluppo; dati preparati con le API; i codici letti dove li leggerebbe una persona (la casella di posta).

## Decisioni

- **D-083** — contratto OpenAPI: springdoc 3.2.0, acceso solo in sviluppo, copia nel repository, tre correzioni.
- **D-084** — il professionista pubblico dice se è prenotabile online, come conferma e il preavviso (completa D-030).
- **D-085** — il frontend: dati letti dal browser, una sola origine, un client, un dizionario, il ritorno da Stripe che rilegge, test end-to-end isolati.

## Errori fatti e come sono stati corretti

1. **I tipi generati dicevano "forse non c'è" per tutto.** springdoc non segna obbligatori i campi dei `record`, e non dichiarava gli errori: ogni campo diventava `campo?: T`, e `paymentUrl` sembrava una stringa sempre presente. Corretto nel backend (D-083), non con un'opzione del generatore che avrebbe nascosto i `null`.
2. **`returnedCredit` perdeva il `null`.** In OpenAPI 3.0 un `$ref` ignora quello che ha accanto: `@Schema(nullable = true)` spariva. Visto confrontando il contratto con l'aspettativa, corretto con `allOf` e coperto da `OpenApiContractTest`.
3. **Gli orari liberi chiedevano un parametro `range`.** springdoc descriveva il `record` `SlotRange` come un unico oggetto; il frontend **non compilava** passando `from` e `to`. Corretto con `springdoc.default-flat-param-object=true`: il compilatore TypeScript ha trovato un errore del contratto.
4. **Dopo "Esci" si finiva sulla pagina di accesso.** La guardia della pagina riservata vedeva "nessuno è entrato" prima che partisse il ritorno alla home. Trovato dal test end-to-end 1; ora l'uscita ricarica il sito dalla home.
5. **Un test cliccava il giorno invece dell'orario.** `getByRole("group", { name: "Orari liberi" })` confronta per sottostringa: prendeva anche "Giorni con orari liberi". Errore del test, visto nella traccia di Playwright; corretto con `exact: true`.
6. **Il codice di accesso "non arrivava".** Due cause sovrapposte: l'email dell'omaggio può arrivare **dopo** quella del codice (parte in un altro thread dopo il commit), e l'orologio del container di Mailpit è indietro di circa due secondi rispetto al computer, quindi il test scartava l'email giusta come "più vecchia". Corretto cercando per oggetto e confrontando l'id dell'ultima email prima e dopo la richiesta, mai l'ora.
7. **`mvnw.cmd` non trovato** dallo script dei test end-to-end: lo stesso problema del passo 10 (D-072); corretto con il percorso completo.
8. **Riletto dalle schermate**: una seduta cancellata in tempo, riaperta più tardi, non diceva più quale credito era tornato; sul telefono il riepilogo compariva sotto gli orari, fuori dallo schermo. Corretti entrambi.

## Domande da colloquio

### Domanda 1: Come generi i tipi del frontend dal backend, e cosa può andare storto?
**Risposta modello.** springdoc pubblica un contratto OpenAPI dai controller e dai DTO; openapi-typescript ne genera i tipi. Va storto se il contratto non dice il vero: campi che in Java ci sono sempre ma risultano facoltativi, `null` non dichiarati, errori assenti, parametri descritti male. Si corregge nel backend (un customizer, annotazioni sui campi nullable) e si prova con un test sul JSON del contratto. In CI si rigenera e si confronta con la copia versionata.

### Domanda 2: Perché il contratto è spento in produzione?
**Risposta modello.** Serve a chi sviluppa, non a chi usa il sito; acceso, descrive a chiunque ogni endpoint, compresi pannello e admin. Spento per default e fuori dal prefisso che il reverse proxy inoltra, quindi irraggiungibile anche se acceso per errore. Il frontend usa la copia committata.

### Domanda 3: Frontend e backend sono due processi. Come funzionano sessione e CSRF in sviluppo?
**Risposta modello.** Come in produzione: una sola origine. Next riscrive `/api/*` verso il backend, Caddy farà lo stesso. Il cookie `HttpOnly` lo gestisce il browser; il token CSRF è un cookie leggibile che il client rimanda in un header a ogni richiesta che cambia qualcosa. Con due origini e CORS, cookie e CSRF si comporterebbero diversamente dalla produzione.

### Domanda 4: Il cliente torna da Stripe. Cosa mostri e perché non confermi lì?
**Risposta modello.** Il ritorno è un indirizzo che chiunque può aprire, e il cliente può non tornare affatto: la conferma è il webhook firmato. La pagina rilegge lo stato dal backend ogni 2 secondi per un minuto; se cambia mostra il risultato, se no dice che la conferma può tardare e di non pagare di nuovo, con un pulsante per controllare.

### Domanda 5: Il backend aggiunge un codice d'errore. Cosa succede al frontend?
**Risposta modello.** Rigenerati i tipi, il dizionario `Record<ApiErrorCode, string>` non compila finché il codice non ha la sua frase. Se un backend più nuovo manda un codice che questa versione non conosce, la frase è generica: mai il testo tecnico del `detail`.

### Domanda 6: Come scrivi test end-to-end che non dipendano dai dati di chi li lancia?
**Risposta modello.** Un backend dedicato su un'altra porta, con un database creato da zero a ogni giro e i valori sensibili fissati (admin dei test, nessun pagamento vero); indirizzi email diversi a ogni giro; i dati preparati con le API, come farebbe il pannello; i codici di accesso letti dalla casella di prova cercando per oggetto e per id, non per ora, perché gli orologi di container e computer possono non coincidere.
