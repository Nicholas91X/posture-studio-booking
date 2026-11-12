# Dove il frontend incontra il backend — nota del passo 12

Il frontend del passo 12 l'ha scritto Claude (D-005): questa non è una guida completa, ma una nota sui **quattro punti** in cui il sito tocca le scelte del backend. Sono quelli che un intervistatore chiede a uno sviluppatore Java che "ha fatto anche il frontend". Decisioni: D-083 (contratto), D-084 (prenotabile online), D-085 (il frontend).

```
browser ──── /api/...  stessa origine ────► Next (sviluppo: riscrive /api)  ──► Spring Boot
   │         cookie SESSION (HttpOnly)       Caddy (produzione: smista /api)
   │         cookie XSRF-TOKEN → header X-XSRF-TOKEN
   │
   └── i tipi delle richieste e delle risposte: generati da /v3/api-docs (springdoc)
```

---

## Parte 1 — Il contratto e i tipi generati (pezzo 0)

**Il problema.** Il backend ha 60 endpoint con i loro `record`. Se il frontend riscrive a mano i tipi (`type Booking = { id: number; ... }`), prima o poi uno cambia e l'altro no, e l'errore si vede solo davanti al cliente.

**La soluzione.** springdoc legge i controller e i `record` e pubblica un **contratto OpenAPI** (un JSON che descrive ogni percorso, parametro, corpo e risposta). Dal contratto, `openapi-typescript` genera i tipi TypeScript. Il ciclo:

1. backend avviato con `OPENAPI_ENABLED=true`;
2. `npm run api` nel frontend: scarica `openapi.json` e rigenera `src/api/schema.d.ts`;
3. `npx tsc --noEmit`: se il backend ha cambiato qualcosa che il frontend usa, **non compila**.

**Perché springdoc da solo non basta** (le tre correzioni di `OpenApiConfiguration`):

- **Gli errori.** Il contratto generato da springdoc non diceva niente delle risposte di `ApiExceptionHandler` (verificato: solo i 200). Ora ogni operazione dichiara gli stati d'errore con lo schema `ApiProblem`, e `code` è un **enum** con tutti i codici. Nel frontend `Record<ApiErrorCode, string>` obbliga ad avere una frase per ognuno: un codice nuovo nel backend rompe la compilazione del frontend finché qualcuno non scrive la frase.
- **Obbligatorio o no.** In Java un `record` ha sempre tutti i campi; in OpenAPI un campo senza `required` è facoltativo, e in TypeScript diventa `campo?: T`, cioè "forse non c'è". Per le risposte si segnano tutti obbligatori, e i pochi che possono essere `null` si annotano: `@Schema(nullable = true) URI paymentUrl` diventa `paymentUrl: string | null`. Per le richieste resta Bean Validation (`@NotNull` → obbligatorio).
- **Un `$ref` non accetta vicini.** In OpenAPI 3.0 `{ "$ref": "...", "nullable": true }` perde il `nullable`. La forma corretta è `{ "allOf": [{ "$ref": "..." }], "nullable": true }`: lo fa un `PropertyCustomizer`.

**Spento in produzione.** Il contratto serve solo a chi sviluppa. Spento per default (`false`), e fuori da `/api`, quindi Caddy non lo raggiunge nemmeno se acceso per errore.

**Da ricordare** (passo 13): un campo nullable nuovo in un `record` di risposta va annotato, altrimenti il tipo dice "mai null" e il frontend non controlla.

---

## Parte 2 — Cookie e CSRF (pezzi 1 e 2)

**Il cookie di sessione** (`SESSION`, D-051) è `HttpOnly`: JavaScript non lo può leggere. È voluto: uno script iniettato non può rubare la sessione. Il browser lo manda da solo a ogni richiesta **della stessa origine**. Per questo frontend e backend devono sembrare un solo sito:

- in produzione lo fa Caddy (D-017): `/api/*` al backend, il resto a Next;
- in sviluppo lo fa Next stesso, con una *rewrite* in `next.config.ts`: il browser chiama `http://localhost:3000/api/...`, Next inoltra a `http://localhost:8080/api/...`. Niente CORS, niente cookie "di terze parti": tutto si comporta come dietro Caddy.

**Il gettone CSRF** (*Cross-Site Request Forgery*: un altro sito che fa partire una richiesta dal browser del cliente, che porta con sé il cookie di sessione). La difesa di Spring Security con `csrf.spa()` è il *double submit cookie*: il backend scrive un cookie `XSRF-TOKEN` **leggibile** da JavaScript; ogni richiesta che cambia qualcosa deve rimandarne il valore nell'header `X-XSRF-TOKEN`. Un altro sito non può leggere i cookie del nostro dominio, quindi non può mettere l'header giusto.

Nel frontend lo fa un *middleware* del client (`src/api/client.ts`), una volta sola per tutte le chiamate. Caso particolare: alla primissima visita il cookie non c'è ancora; prima della prima `POST` il client fa una `GET` leggera, e il backend lo scrive.

**Accesso e uscita.** Dopo il codice giusto il backend cambia l'id della sessione (contro la *session fixation*); il frontend non fa niente di speciale: il browser prende il cookie nuovo. All'uscita il frontend chiama `DELETE /api/public/auth/session` e **ricarica** il sito dalla home (in memoria non resta niente del cliente).

---

## Parte 3 — Il pagamento asincrono (pezzi 3 e 5)

**Il punto.** Quando Stripe rimanda il cliente a `/prenotazioni/42?pagamento=completato`, quell'indirizzo **non prova niente**: chiunque lo può aprire, e il cliente può chiudere la finestra prima di tornare. Il pagamento lo conferma solo il **webhook** di Stripe, firmato, che arriva al backend per conto suo (D-007, D-062). Ritorno del browser e webhook sono due strade indipendenti, in un ordine qualsiasi.

**Cosa fa la pagina di ritorno** (`BookingClient`, `CreditsClient`):

1. legge la prenotazione (`GET /api/customer/bookings/{id}`);
2. se è ancora `PENDING_PAYMENT`, mostra "pagamento in verifica" e la **rilegge ogni 2 secondi** (`pollUntil` in `src/lib/poll.ts`);
3. appena lo stato cambia, mostra quello che è successo (in attesa di approvazione, confermata, scaduta);
4. dopo **60 secondi** smette e dice chiaramente: "non pagare di nuovo, quando arriva ti mandiamo un'email", con un pulsante per riprovare.

Con `?pagamento=annullato` la pagina offre "Completa il pagamento" finché il blocco vale: `paymentUrl` arriva dal backend solo in quel caso (D-061). Per i pacchetti l'indirizzo di ritorno non nomina l'acquisto: la pagina dei crediti guarda l'ultimo.

**Perché rileggere e non aspettare una notifica dal server** (WebSocket, Server-Sent Events): per un'attesa di qualche secondo, una volta per pagamento, una `GET` ogni 2 secondi costa niente, e non serve una connessione aperta in più da far passare da Caddy.

---

## Parte 4 — Gli errori come tipo unico (pezzo 1)

**Dal backend** ogni errore arriva come `ProblemDetail` (RFC 9457): `status`, `detail` (per il log), e spesso `code` (`SLOT_NOT_AVAILABLE`).

**Nel frontend** passa da due soli punti:

- `unwrap()` in `client.ts`: trasforma **qualsiasi** fallimento in un `ApiError` (stato, codice, campi sbagliati, `Retry-After`); una risposta che non arriva affatto (rete giù) diventa `ApiError` con stato 0. Le pagine non leggono mai una `Response`.
- `messageFor()` in `errors.ts`: un solo dizionario codice → frase. Un codice che questa versione non conosce (il backend è più nuovo) o uno stato senza codice danno una frase generica; `detail` non si mostra **mai**. Il test end-to-end lo prova con un 409 finto e un codice inventato.

**Una pagina può reagire a un codice** oltre a mostrarne la frase: `SLOT_NOT_AVAILABLE` toglie la scelta e rilegge gli orari liberi; `BOOKING_LIMIT_REACHED` aggiunge il link alle sedute; `SMS_UNAVAILABLE` nasconde la scheda SMS. La frase resta sempre quella del dizionario.

**Il confine di D-005.** La frase prima di una cancellazione ("sei in tempo: il credito ti torna" / "fuori tempo: la seduta è persa") la costruisce il frontend, ma **dai dati del backend**: `freeCancellationUntil` (il preavviso fotografato) e `paidWith`. Nessuna regola nuova: il frontend non sa quante ore di preavviso ci sono, né quanto vale il credito che torna. Dopo il clic mostra la risposta del backend, che decide con il suo orologio.

---

## Domande da colloquio

### Domanda 1: Come eviti che frontend e backend vadano fuori allineamento sui tipi?
**Risposta modello.** Un contratto OpenAPI generato dal codice del backend (springdoc), una copia versionata, e i tipi TypeScript generati da lì. Se il backend cambia, la rigenerazione fa fallire la compilazione del frontend dove serve. In CI si rigenera e si controlla con `git diff --exit-code` che contratto e tipi committati siano quelli del codice. Il contratto va però curato: obbligatorietà, `null` ed errori, altrimenti i tipi generati mentono.

### Domanda 2: Il cookie di sessione è HttpOnly. Come fa il frontend a sapere chi è entrato?
**Risposta modello.** Non legge il cookie: chiede al backend (`GET /api/public/auth/session`, 401 se nessuno). HttpOnly impedisce a uno script iniettato di rubare la sessione; il browser lo manda da solo, purché frontend e backend siano la stessa origine (Caddy in produzione, una rewrite di Next in sviluppo).

### Domanda 3: Perché serve il CSRF se il cookie è SameSite=Lax?
**Risposta modello.** `SameSite=Lax` blocca il cookie sulle richieste di altri siti, ma è una difesa sola e dipende dal browser. Il gettone CSRF (double submit cookie) è la seconda: il valore di un cookie che solo il nostro dominio può leggere, rimandato in un header. Il frontend lo aggiunge in un solo punto, il client delle API.

### Domanda 4: Il cliente torna da Stripe con `?pagamento=completato`. Confermi la prenotazione?
**Risposta modello.** No: quell'indirizzo lo può aprire chiunque. Conferma solo il webhook firmato, che arriva per conto suo. La pagina rilegge lo stato dal backend ogni pochi secondi, per un tempo limitato, e se la conferma tarda lo dice senza invitare a pagare di nuovo.

### Domanda 5: Come mostri al cliente un errore del backend senza mostrare testo tecnico?
**Risposta modello.** Il backend manda un codice leggibile nel ProblemDetail; il frontend ha un solo dizionario codice → frase, tipato sull'enum del contratto, e una frase generica per i codici che non conosce. Il `detail` è per il log, non per il cliente.
