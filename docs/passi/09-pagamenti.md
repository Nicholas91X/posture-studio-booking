# Passo 09 — Pagamenti con Stripe

> **Stato: chiuso** (2026-11-10). Pezzi 1–6 fatti, 812 test verdi (`mvnw clean test`). Prova manuale con Stripe in modalità di prova fatta il 2026-11-10. Uniti in `main` con le PR #5 (pezzi 1–4), #6 (incasso con lettura prima) e #7 (pezzo 5 e correzioni della prova manuale); il pezzo 6 con la PR di chiusura.

## Obiettivo
Il pagamento simulato sparisce. Il cliente paga la seduta sulla pagina di Stripe, con l'importo **bloccato** sulla carta e **incassato** solo all'approvazione (D-031). I conti di incasso sono configurabili, uno per professionista o uno comune, e si cambiano in produzione. Il professionista può mandare un link di pagamento per le sedute "in studio".

I pezzi:
1. Conti di incasso e tabella dei pagamenti — ✅ (guida 30, D-057, D-060)
2. Pagamento della seduta con Stripe Checkout — ✅ (guida 31, D-061)
3. Webhook — ✅ (guida 32, D-062)
4. Incasso e annullamento veri — ✅ (guida 33, D-063, D-064)
5. Link di pagamento per le sedute "in studio" — ✅ (guida 34, D-059, D-065)
6. Via il pagamento simulato e chiusura — ✅ (guida 35; la rimozione è avvenuta ai pezzi 3 e 4)

Decisioni preliminari: D-057 (conti, senza Connect), D-058 (marca da bollo), D-059 (link con WhatsApp). Punti aperti 1, 9 e 16 chiusi.

## Criteri di "fatto" (dal brief)

| Criterio | Stato | Dove si vede |
|---|---|---|
| Tre decisioni registrate, punti aperti 1, 9, 16 chiusi | ✅ | D-057, D-058, D-059; `requisiti.md` |
| Un cliente paga in modalità di prova, con approvazione manuale e automatica (prova con Stripe CLI, documentata) | ✅ 2026-11-10 | `stato.md`; guida 32, parte 13.1; guida 34, parte 12 |
| Lo stesso avviso due volte → un solo effetto; firma sbagliata rifiutata; pagamento tardivo annullato | ✅, anche con due consegne nello stesso istante | `StripeWebhookApiTest`, `StripeWebhookRollbackTest` |
| Cambiare il conto non sposta incassi e annullamenti già fatti | ✅ | `StripeCardHoldsTest`, `StripeWebhookApiTest`, `PaymentAccountServiceTest` |
| Incasso fallito e "incassato ma commit fallito" gestiti e provati | ✅, con commit veri | `StripeCardHoldsTest`, `StripeReconciliationTest` |
| Link: uno solo valido, pagamento → `CARD`, pagato dopo una cancellazione → niente incassato | ✅; **annullamento del blocco** invece del rimborso (D-059 precisata, D-015 intatta); il percorso vero della cancellazione è un criterio del passo 11 | `PaymentLinkApiTest`, `PaymentLinkWebhookTest`, `PaymentLinkConcurrencyTest` |
| Pagamento simulato rimosso, nessun test lo usa | ✅ ai pezzi 3 e 4; tracce tolte e porta chiusa provata al pezzo 6 | `ConfidentialityTest` |
| `modello-dati.md` aggiornato; test verdi; branch e PR | ✅ V10–V14 | `modello-dati.md`; PR #5, #6, #7 |

## Cosa è stato costruito

### Pezzo 1 — Conti di incasso e tabella dei pagamenti (D-057, D-060)
- `PAYMENT_ACCOUNTS=nome,nome` più `STRIPE_<NOME>_SECRET_KEY` e `STRIPE_<NOME>_WEBHOOK_SECRET`, letti dall'`Environment` (`PaymentAccounts`, `PaymentConfiguration`). Per aggiungere un conto bastano tre righe nel `.env` e un riavvio.
- Controlli all'avvio: formato dei nomi, prefissi delle chiavi, `PAYMENT_LIVE` uguale al modo di ogni chiave; i conti assegnati e quelli dei pagamenti aperti devono essere configurati (`PaymentStartupCheck`). `PaymentAccount.toString()` non mostra mai i segreti.
- `practitioner_payment_account` e `payment` (V10), con il conto su cui è nato ogni pagamento; un solo `PENDING` per prenotazione (indice unico parziale).
- API dell'admin: `GET /api/admin/payment-accounts`, `GET`/`PUT /api/admin/practitioners/{id}/payment-account`.

### Pezzo 2 — Pagamento della seduta (D-061)
- `booking` dichiara `CardCheckout`, e `payment` lo implementa (`StripeCardCheckout`). Nella transazione della richiesta: 409 `CARD_PAYMENTS_UNAVAILABLE` se il professionista non ha un conto; poi `insert`, sessione Checkout con incasso manuale e riga `PENDING`.
- Blocco dell'orario da 30 a **32 minuti**, sessione che scade nello stesso istante; `Idempotency-Key: booking-{id}-checkout`.
- `StripeClientGateway`: un `StripeClient` per conto, 2 s / 5 s e un nuovo tentativo; ogni errore di Stripe diventa un 503 `PAYMENT_PROVIDER_UNAVAILABLE` e annulla tutto.
- `payment.checkout_url` (V11); `paymentUrl` nelle viste del cliente.

### Pezzo 3 — Webhook (D-062)
- `POST /api/webhooks/stripe/{conto}`: firma verificata con il segreto di quel conto e il nostro `Clock`; fuori dal CSRF.
- `stripe_event` (V12) nella stessa transazione; passaggi del pagamento fatti **prima** di chiedere alla prenotazione.
- `paymentAuthorized` risponde con `sealed PaymentOutcome`; il pagamento tardivo si annulla, e il cliente riceve un'email (`LatePaymentReleased`).
- Via il pagamento simulato.

### Pezzo 4 — Incasso e annullamento (D-063, D-064)
- `StripeCardHolds` sostituisce `LoggingCardHolds`. Prima il controllo del passaggio, poi l'incasso, poi la conferma; il rifiuto definitivo è un valore (`CaptureResult`), e all'approvazione diventa 409 `CARD_CAPTURE_FAILED`.
- Riconciliazione con `payment_intent.succeeded`; `payment_intent.canceled` chiude i blocchi.
- Passaggi del pagamento nell'entità letta con il lucchetto, al posto degli `update` JPQL.
- Annullamenti falliti riprovati da `PaymentJobs` (V13), con pause crescenti e una chiave nuova a ogni tentativo; **lettura del PaymentIntent prima di ogni incasso**, e incasso con una chiave nuova.

### Pezzo 5 — Link di pagamento (D-059, D-065)
- `POST /api/panel/bookings/{id}/payment-link`: una pagina di Stripe con incasso manuale (24 ore meno 2 minuti, o fino all'inizio della seduta) e un indirizzo WhatsApp con il messaggio pronto.
- `payment.source` (V14). Prima di un link nuovo, la pagina vecchia si legge su Stripe e si fa scadere.
- Al pagamento: se la seduta è ancora "in studio" si incassa **quel** PaymentIntent e `paid_with` passa a `CARD`; altrimenti si annulla il blocco. Vince il primo pagamento, grazie al lucchetto sulla seduta.

### Pezzo 6 — Chiusura (guida 35)
- Tolte le tracce: i metadati di `booking.simulated-payments` (al loro posto le proprietà `payment.*`) e due commenti che descrivevano il passato.
- Un test che vuole chiusa a tutti la vecchia porta `/api/dev/**`.
- `tools/api.ps1`, per le prove a mano.

## Concetti chiave

**Blocco e incasso separati** (*authorize* e *capture*). Con `capture_method = manual` la banca del cliente blocca l'importo, e Stripe lo incassa solo quando glielo chiediamo, entro circa 7 giorni. Serve per un'approvazione che arriva dopo il pagamento: un rifiuto annulla il blocco e non costa niente, mentre un rimborso costerebbe le commissioni e contraddirebbe D-015. Con l'incasso manuale, la sessione completata dice `payment_status = unpaid`: conta `status = complete`.

**Webhook e "almeno una volta".** Stripe ci avvisa con chiamate firmate (HMAC-SHA256 di timestamp e corpo grezzo, 5 minuti di tolleranza). Le consegna almeno una volta, a volte in doppio, in ordine non garantito, e riprova per 3 giorni se non rispondiamo con un `2xx`. Ci si difende su due livelli: la tabella degli eventi già visti, nella **stessa** transazione del lavoro, e passaggi di stato che cambiano qualcosa solo se lo stato di partenza è quello giusto. Un errore fa *rollback* della transazione e non perde l'evento, perché Stripe lo rimanda.

**Idempotenza, nei due versi.** Verso Stripe, ogni chiamata che crea qualcosa porta una chiave. Stripe però ricorda per 24 ore la risposta di una chiave, **anche un 500**: per i nuovi tentativi di incasso e annullamento serve una chiave nuova, e la lettura dello stato prima dell'incasso impedisce di incassare due volte. Da Stripe verso di noi: gli eventi già visti.

**Doppia scrittura.** Il nostro database e Stripe non condividono una transazione. Si ragiona caso per caso:
- prima si fa l'operazione esterna che si può abbandonare (una sessione orfana scade da sola);
- per quella che sposta soldi (l'incasso) si riconcilia: `payment_intent.succeeded` ci dice che Stripe ha incassato, anche se il nostro commit era fallito;
- la chiamata dentro la transazione è una scelta consapevole, con il caso peggiore misurato (circa 15 secondi) e l'alternativa della compensazione scritta in D-061.

**Esiti attesi come valori, eccezioni per l'inatteso.** Un'eccezione uscita da un bean `@Transactional` segna la transazione come "solo rollback", anche se poi viene catturata. Per questo un incasso rifiutato è un `CaptureResult`, e la risposta della prenotazione è un `PaymentOutcome`. Entrambi sono `sealed`, e gli `switch` non hanno `default`: un caso nuovo non compila finché non è gestito. Al pezzo 3 così è venuto fuori lo `switch` delle push, che avevo dimenticato.

**Lucchetti e ordine.** Chi chiama Stripe tiene già il lucchetto sulla riga del pagamento. Il webhook blocca prima il pagamento e poi la prenotazione. La creazione di un link blocca solo i pagamenti `PENDING`, mai la seduta, perché altrimenti potrebbe incrociarsi con il webhook di un link vecchio. "Il primo vince" funziona grazie al lucchetto sulla seduta, tenuto fino al commit.

**Persistence context.** Un `update` JPQL scrive nel database ma non nell'oggetto già in memoria, che resta vecchio. Nella stessa transazione si legge con il lucchetto e si cambia l'entità. Hibernate esegue subito l'`insert` di una riga con id generato dal database, ma rimanda gli `update`: prima di inserire una riga che un indice unico confronta con una appena cambiata, serve un `flush` esplicito.

**Come si prova un servizio esterno.**
- Un'interfaccia nostra (`StripeGateway`) con un **finto** in ogni contesto di test, così nessun test chiama Stripe vero per sbaglio. Il finto deve conoscere i **limiti** del vero.
- Il client vero contro un server HTTP locale (`setApiBase`), per vedere cosa spedisce davvero la libreria.
- Le firme calcolate a mano.
- La prova manuale con la Stripe CLI.
- I test che fanno commit veri, quando conta il *rollback* o la contemporaneità.

**Configurazione con più conti.** Segreti solo da variabili d'ambiente. Una configurazione sbagliata ferma l'avvio, e i messaggi nominano la variabile, mai il valore. Un interruttore "prova o produzione" deve corrispondere a ogni chiave.

## Decisioni
D-057 (conti di incasso, senza Connect), D-058 (marca da bollo), D-059 (link di pagamento, precisata da Nicholas: blocco, non incasso), D-060 (conti: configurazione, controlli, assegnazione), D-061 (Checkout nella richiesta, 32 minuti, tempi d'attesa; modifica D-008), D-062 (webhook), D-063 (incasso e annullamento; precisa D-046), D-064 (annullamenti riprovati, lettura prima dell'incasso), D-065 (link di pagamento). Verifiche chiuse in D-031: durata del blocco e commissione dei blocchi annullati.

## Errori fatti e come sono stati corretti

- **30 minuti non bastavano** (pezzo 2). Stripe vuole una sessione di almeno 30 minuti dalla **sua** creazione, che arriva dopo la richiesta. Il blocco è passato a 32 minuti, con la sessione che scade nello stesso istante.
- **E 24 ore erano troppe** (prova manuale). Il primo link è stato rifiutato: *"must be less than 24 hours from Checkout Session creation"*. Il nostro orologio era avanti di un secondo rispetto a Stripe (lo si vedeva già nella prova: sessione creata alle :53, richiesta scritta alle :54,2). Il margine c'era sul minimo, non sul massimo; ora vale per tutti e due.
- **Il finto Stripe non conosceva i limiti di Stripe**, quindi nessun test poteva vedere l'errore delle 24 ore. Ora rifiuta come il vero. Lezione: un finto troppo accomodante lascia passare test che il vero farebbe fallire.
- **Un test che seguiva la costante** (pezzo 2). Il test dei nuovi tentativi confrontava il numero di richieste con `1 + MAX_NETWORK_RETRIES`: cambiando la costante, il test cambiava con lei. Ora il numero è scritto per esteso.
- **Il test transazionale che non vede il rollback** (pezzo 2). In una classe `@Transactional` la richiesta si unisce alla transazione del test, e il suo *rollback* avviene solo a fine test. I casi di *rollback* vivono in classi a parte, con commit veri.
- **La cache di JPA nei test**. Un `update` SQL non si vede se l'entità è già in memoria. Le righe di partenza dei test si scrivono con SQL, e prima di rileggere con SQL si fa `flush` e `clear`.
- **Gson non disponibile** (pezzo 3). Arriva con `stripe-java` solo per l'esecuzione, non per la compilazione. Si è usato Jackson 3, già fornito da Boot.
- **`LoggingCardHolds` non più innocua** (pezzo 3). Con il webhook le richieste arrivavano davvero in attesa di approvazione, e un'approvazione confermava senza incassare. I pezzi 3 e 4 sono entrati in `main` insieme.
- **L'oggetto vecchio in memoria** (pezzo 4): gli `update` JPQL del pezzo 3. Trovato ragionando sull'approvazione automatica, sostituito con l'entità letta con il lucchetto.
- **"Non più annullabile" non vuol dire "annullato"** (pezzo 4). Un PaymentIntent già incassato sarebbe finito `CANCELED`. Ora resta com'è, e l'evento di Stripe porta la verità.
- **Stripe ricorda anche i 500** (dopo il pezzo 4). Con la stessa chiave, i nuovi tentativi riceverebbero lo stesso errore per 24 ore. Ora c'è una chiave nuova a ogni tentativo, e la lettura prima dell'incasso.
- **Ricordarsi l'errore era impossibile** (dopo il pezzo 4). "Leggi lo stato solo dopo un errore" richiedeva di scriverlo in una transazione separata, su una riga già bloccata dalla transazione principale: si sarebbe aspettata da sola. Si legge prima di **ogni** incasso.
- **Hibernate e l'ordine delle scritture** (pezzo 5). Il nuovo `PENDING` veniva inserito prima che il vecchio fosse scritto come `EXPIRED`, e l'indice unico lo rifiutava. Serve un `flush` esplicito.
- **Il finto che ricordava tra un test e l'altro** (pezzo 5). Un PaymentIntent incassato in un test restava "incassato" nel successivo. Ora `reset()` dimentica tutto, e `forgetCalls()` tiene lo stato quando serve.
- **I test leggevano il `.env` di chi li lancia** (prova manuale). Con `ADMIN_EMAIL` nel proprio `.env`, un test che conta gli utenti ne trovava uno in più. Ora la configurazione dei test fissa tutto quello che un `.env` può cambiare.
- **Maven incrementale** (pezzo 4). Dopo un cambio di firma, `test-compile` diceva "tutto aggiornato". La suite si lancia sempre con `clean`.

## Domande da colloquio

### Domanda 1: Perché bloccare l'importo e incassarlo dopo, invece di incassarlo subito?
**Risposta modello.** Perché tra il pagamento e la decisione c'è un'approvazione che può dire di no. Con l'incasso subito, un rifiuto richiederebbe un rimborso: costa le commissioni, e qui contraddirebbe la regola "niente rimborsi". Con il blocco, il rifiuto annulla l'autorizzazione e al cliente non resta niente. Il costo è gestire la durata del blocco (7 giorni circa per le carte online, ben oltre le 48 ore massime di risposta) e due chiamate in più, l'incasso e l'annullamento.

### Domanda 2: Stripe manda lo stesso webhook due volte, o due webhook diversi per la stessa cosa. Come ottieni un solo effetto?
**Risposta modello.** Su due livelli. Una tabella con l'id di ogni evento elaborato, scritta con `insert ... on conflict do nothing` nella stessa transazione dell'elaborazione: se l'elaborazione fallisce, sparisce anche la riga, e il nuovo tentativo di Stripe viene elaborato. E passaggi di stato che cambiano qualcosa solo dallo stato giusto, su una riga letta con il lucchetto: il secondo evento diverso trova lo stato già cambiato e si ferma, **prima** di chiedere qualcosa alla prenotazione, che altrimenti farebbe annullare un blocco valido.

### Domanda 3: Hai incassato su Stripe e il commit del tuo database è fallito. Come lo scopri e lo ripari?
**Risposta modello.** Me lo dice Stripe con `payment_intent.succeeded`. Se trovo il pagamento ancora "autorizzato", il mio commit era fallito: lo segno incassato e confermo la prenotazione senza incassare di nuovo. Se il professionista nel frattempo riprova ad approvare, prima di incassare leggo lo stato del PaymentIntent: è già incassato, e confermo e basta. La fonte di verità sui soldi è Stripe, e il mio sistema converge verso di lei.

### Domanda 4: Come usi le chiavi d'idempotenza, e quando una chiave nuova è meglio di quella vecchia?
**Risposta modello.** Una chiave identifica un'intenzione: dentro lo stesso tentativo, la libreria riprova con la stessa chiave dopo un errore di rete, e Stripe restituisce la prima risposta invece di rifare l'operazione. Ma Stripe ricorda anche le risposte di errore, compresi i 500, per 24 ore: per un nuovo tentativo deciso da me (un lavoro che riprova, un professionista che ripreme il pulsante) uso una chiave nuova. La protezione contro il doppio incasso diventa una lettura dello stato prima di agire.

### Domanda 5: Il fornitore impone una durata minima e massima della sessione. Come le hai rispettate, e cosa hai imparato?
**Risposta modello.** Il vincolo vale dal momento in cui il **fornitore** crea la sessione, con il **suo** orologio. Il mio server calcola le scadenze con il suo, e i due orologi non sono mai identici. Ho messo un margine di due minuti su entrambi i lati: 32 minuti invece di 30, 24 ore meno 2 minuti invece di 24. Il secondo l'ho dimenticato, e me l'ha mostrato la prova con Stripe vero, perché il mio finto non conosceva il limite. Ora il finto rifiuta come il vero.

### Domanda 6: Fai una chiamata HTTP dentro una transazione del database. È accettabile?
**Risposta modello.** Dipende dal costo, quindi l'ho misurato. Durante la chiamata la transazione tiene i lucchetti, e aspetta solo chi vuole lo stesso orario. Con tempi d'attesa corti (2 s per collegarsi, 5 per leggere, un nuovo tentativo) il caso peggiore è di circa 15 secondi, poi un 503 senza niente a metà. In cambio ho un solo stato possibile. Se il traffico crescesse, separerei: commit, chiamata fuori dalla transazione e, se fallisce, una transazione di compensazione, più un lavoro che ripara i casi rimasti a metà.

### Domanda 7: Due pagamenti arrivano quasi insieme per la stessa seduta. Come ne incassi uno solo?
**Risposta modello.** Decido con una regola sullo stato della seduta: "si incassa solo se è ancora da pagare". Il lucchetto sulla riga della seduta, tenuto fino al commit, mette in fila i due webhook: il primo incassa e cambia lo stato, il secondo legge lo stato nuovo e annulla il suo blocco. L'ho provato con due richieste in parallelo e commit veri; senza il lucchetto, li incassa tutti e due.

### Domanda 8: Come testi un'integrazione con un servizio di pagamento senza chiamarlo davvero?
**Risposta modello.**
- Un'interfaccia mia con un finto, registrato in ogni contesto di test, così nessun test chiama il servizio vero per sbaglio. Il finto deve rispettare i limiti del vero, altrimenti nasconde errori.
- Il client vero contro un server HTTP locale, per vedere parametri, header, chiavi d'idempotenza e nuovi tentativi.
- Firme calcolate a mano nei test del webhook.
- Test con commit veri per *rollback* e contemporaneità.
- Una prova manuale nell'ambiente di prova del fornitore.

E tolgo a turno ogni difesa, per vedere che un test se ne accorga.

### Domanda 9: Perché un esito atteso come valore di ritorno e non come eccezione?
**Risposta modello.** In Spring, un'eccezione che esce da un metodo `@Transactional` di un altro bean segna la transazione come "solo rollback", anche se poi la catturo: al commit ricevo una `UnexpectedRollbackException` e perdo il lavoro. Un valore di ritorno non segna niente, e con un tipo `sealed` il compilatore mi obbliga a gestire ogni esito. Le eccezioni le uso per l'inatteso, e quando voglio davvero annullare tutto.
