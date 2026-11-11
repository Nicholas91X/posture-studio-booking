# Concessioni — guida di studio

Questa guida accompagna il **pezzo 5 del passo 10**. Le tre concessioni di D-067, dal pannello:

```
POST /api/panel/credit-grants                    seduta omaggio: un lotto nuovo
     {"customer": {"email", "firstName", "lastName"}, "serviceId", "credits", "validUntil"?, "note"}
POST /api/panel/credit-lots/{id}/extensions       proroga: l'ultimo giorno valido più avanti
     {"validUntil", "note"}
POST /api/panel/credit-lots/{id}/adjustments      correzione: un movimento ±N
     {"amount", "note"}
GET  /api/panel/credit-lots/{id}                  un lotto proprio
```

Teoria di riferimento: D-066 (concessioni per email a qualunque cliente, anche nuovo), D-067 (le tre concessioni, chi/quando/perché, nessun limite oltre ai propri lotti e al saldo mai negativo), D-048 (cliente trovato o creato per email), D-069 (registro, trigger, proroga solo in avanti con la sua traccia, lucchetto sul lotto per ogni addebito), D-026 (la cosa di un altro è un 404).

---

## Parte 1 — Cosa c'è già

Quasi tutto, dai pezzi 1 e 3:
- nella V15 le colonne della traccia (`granted_by` e `note` nel lotto, `created_by` e `note` nel movimento, `credit_lot_extension`), con i loro `check` e i trigger: un omaggio senza nota, una correzione senza autore o una proroga senza la sua riga **il database li rifiuta già**;
- nelle entità `CreditLot.grant`, `CreditLot.extendTo` e `CreditMovement.adjustment`, provate dal pezzo 1;
- `CustomerService.findOrCreate` (D-048) per il cliente per email.

Manca: il servizio che le mette insieme con le regole, le API, e cosa sa il cliente.

---

## Parte 2 — Chi ha fatto la concessione

D-067 vuole "chi": l'**account** del professionista (`app_user`), non la riga `practitioner`. Lo dà `CurrentUser.id()` del modulo `auth`, la stessa domanda che usa già il professionista corrente. Il controller passa al servizio due cose: il professionista (di chi sono lotti e servizi) e l'account (chi firma la traccia). Oggi coincidono sempre; scritti separati, il giorno in cui un admin potrà fare una correzione per conto di un professionista non servirà cambiare la traccia.

---

## Parte 3 — Seduta omaggio

**Per email, a qualunque cliente** (D-066): `customer` come nella prenotazione dal pannello (D-048), trovato o creato. Un cliente esistente tiene il suo nome.

**Il servizio deve essere proprio e in vendita** (`CatalogService.serviceOnSale`, 404 altrimenti). Un omaggio per un servizio disattivato non si potrebbe usare: nessuna prenotazione lo accetta.

**Quante sedute**: almeno 1. Nessun massimo (D-067: nessun limite); il controllo è la traccia.

**Ultimo giorno valido**: scelto dal professionista, oppure, se manca, quello delle sedute singole: `ValidUntil.daysAfter(adesso, single_credit_validity_days)`, come vuole D-067. Un giorno già passato non ha senso: 409 **`CREDIT_VALID_UNTIL_IN_THE_PAST`**. Oggi va bene: vale fino a mezzanotte.

**Il "perché"**: obbligatorio, non vuoto (Bean Validation per un 400 chiaro, e il `check` della V15 come ultima difesa).

Nessun lucchetto: il lotto è nuovo, nessun altro può toccarlo.

---

## Parte 4 — Proroga

- **Solo un lotto proprio**: letto **con il lucchetto** e con il professionista nella query, così il lotto di un altro è un 404 (D-026) e due proroghe contemporanee dello stesso lotto si mettono in fila.
- **Solo in avanti** (D-069): una data non successiva all'attuale è un 409 **`CREDIT_EXTENSION_NOT_LATER`**. Il `check` della V15 la rifiuterebbe comunque, ma con un 500: il controllo in Java c'è per la risposta giusta.
- **Mai verso il passato**: una proroga a una data già passata (lotto scaduto il 1° ottobre, oggi 11 novembre, nuova data 5 ottobre) non restituisce niente al cliente: 409 `CREDIT_VALID_UNTIL_IN_THE_PAST`, lo stesso codice dell'omaggio.
- **Un lotto già scaduto si può prorogare**: è il caso tipico ("mi sono scaduti due crediti"). Funziona da solo perché la scadenza non ha mai scritto movimenti (guida 36, parte 2).
- **L'ordine**: `CreditLot.extendTo` restituisce la riga della storia, il servizio la salva, e il trigger del lotto trova la traccia al *flush* (provato al pezzo 1).

---

## Parte 5 — Correzione

- **Solo un lotto proprio, con il lucchetto**: è un addebito come gli altri, e D-069 vuole il lucchetto sul lotto per ogni movimento negativo. È proprio il caso per cui il pezzo 3 l'aveva tenuto: la correzione non passa dal lucchetto sul cliente.
- **Mai sotto zero** (D-067): con il lucchetto, saldo letto, e se `saldo + importo < 0` 409 **`CREDIT_BALANCE_INSUFFICIENT`**. Il trigger del saldo resta l'ultima difesa.
- **Importo diverso da zero**: Bean Validation; e l'entità rifiuta lo zero.
- **Su un lotto scaduto?** Sì: una correzione sistema un numero sbagliato, e può servire anche su un lotto scaduto (per esempio per togliere un credito dato per errore prima di prorogarlo). Nessuna regola in più.

L'**ordine dei lucchetti** resta quello di D-069: la correzione prende solo il lotto; la richiesta del cliente prende cliente e poi lotto. Nessuno prende il lotto e poi il cliente.

---

## Parte 6 — Il cliente lo sa?

Ogni concessione cambia i crediti del cliente. Il principio della guida 39 (parte 5): un cambiamento del saldo deve avere una spiegazione che il cliente può vedere.

- **Omaggio**: un'email "Hai ricevuto N sedute in omaggio", con servizio, professionista, ultimo giorno valido.
- **Proroga**: un'email "La scadenza dei tuoi crediti è stata prorogata", con la nuova data.
- **Correzione**: un'email "I tuoi crediti sono stati corretti", con l'importo e il saldo che resta.

**La nota non va nell'email.** È la traccia interna (D-067: "perché"), scritta dal professionista per sé e per chi controllerà; può contenere cose che non sono per il cliente. Il cliente legge cosa è cambiato; il motivo, se lo vuole, glielo dice lo studio.

Tre eventi nuovi in `CreditEvent` (`CreditGranted`, `CreditExtended`, `CreditAdjusted`), pubblicati nella transazione e ascoltati dopo il commit, come `PackagePurchased`. **Nessuna push e nessuna email al professionista**: l'ha fatto lui. Lo `switch` di `PushMessages` e di `CreditEmails` non ha `default`: con tre eventi nuovi non compilano finché qualcuno non decide cosa mandano.

---

## Parte 7 — I test

| Test | Cosa prova |
|---|---|
| `CreditGrantApiTest` (API, sessioni vere) | omaggio a un cliente nuovo (creato) e a uno esistente (tiene il nome); traccia nel lotto (account, nota); ultimo giorno di default = oggi + validità delle sedute singole; data scelta; data nel passato → 409; servizio di un altro o disattivato → 404; nota vuota → 400; un cliente → 403 |
| `CreditExtensionApiTest` | proroga di un lotto proprio, con la riga della storia (vecchia e nuova data, account, nota); di un lotto scaduto; non in avanti → 409; nel passato → 409; lotto di un altro → 404 |
| `CreditAdjustmentApiTest` | −1 e +2 con la traccia nel movimento; sotto zero → 409 e niente scritto; zero → 400; lotto di un altro → 404 |
| `CreditEmailsTest` (aggiunte) | le tre email, senza la nota |

**Le difese tolte a turno** (con lo script corretto del pezzo 4): il controllo "lotto proprio" nella query, il controllo del saldo in Java (deve rispondere il trigger, con un 500 invece del 409), "solo in avanti" in Java, la data di default.

---

## Parte 8 — Cosa c'è da decidere

| Scelta | Raccomandazione | Dove |
|---|---|---|
| Chi | l'account (`CurrentUser`), separato dal professionista | parte 2 |
| Omaggio | per email, servizio proprio in vendita, ≥ 1 seduta, data scelta o default delle sedute singole, mai nel passato | parte 3 |
| Proroga | lotto proprio con il lucchetto, solo in avanti, mai nel passato, anche se scaduto | parte 4 |
| Correzione | lotto proprio con il lucchetto, mai sotto zero, anche su un lotto scaduto | parte 5 |
| Cliente | un'email per ognuna, senza la nota; niente al professionista | parte 6 |
| Codici | `CREDIT_VALID_UNTIL_IN_THE_PAST`, `CREDIT_EXTENSION_NOT_LATER`, `CREDIT_BALANCE_INSUFFICIENT` | parti 3–5 |

Dopo l'implementazione: una scheda (D-073).

---

## Parte 9 — Com'è andata (implementazione)

Costruito come raccomandato nella parte 8 (D-073), su indicazione di Nicholas del 2026-11-11. Nessuna migrazione: la V15 aveva già tutto. 23 test nuovi, 988 in tutto, `mvnw clean test` verde.

| File | Cosa fa |
|---|---|
| `CreditConcessions` | le tre concessioni e la lettura di un lotto proprio |
| `CreditPanelController` | le quattro API, con professionista e account dalla sessione |
| `NewCreditGrant`, `NewCreditExtension`, `NewCreditAdjustment` | le richieste; lo zero della correzione con un `@AssertTrue` |
| `PanelCreditLotView` | il lotto con saldo e ultimo giorno, senza nomi |
| `CreditLotRepository` | `findLockedByIdAndPractitionerId`, `findByIdAndPractitionerId` |
| `CreditEvent` | `CreditGranted`, `CreditExtended`, `CreditAdjusted` |
| `CreditEmails`, `PushMessages` | le tre email al cliente; nessuna push |
| `ErrorCode` | `CREDIT_VALID_UNTIL_IN_THE_PAST`, `CREDIT_EXTENSION_NOT_LATER`, `CREDIT_BALANCE_INSUFFICIENT` |

I test della parte 7 sono in una classe sola, `CreditConcessionsApiTest` (20 casi), più tre casi in `CreditEmailsTest`.

**Un dettaglio di Bean Validation**: "diverso da zero" non ha un'annotazione standard. Un metodo `isAmountNotZero()` con `@AssertTrue` sulla richiesta: Bean Validation lo trova perché ha il nome di un *getter*, e l'errore esce sul campo `amountNotZero`.

**Un dettaglio d'italiano**: "puoi usarle" con una seduta sola diventa "puoi usarla"; "tolte: 1 seduta" invece di "tolte 1 seduta". I testi ora seguono il numero.

**Le difese tolte a turno**, con lo script corretto del pezzo 4 (Maven davvero lanciato, motivo nel registro), quattro su quattro:

| Tolto | Test che se ne accorge |
|---|---|
| Il professionista nella query del lotto | `theLotOfAnotherPractitionerIsNotFound` |
| Il controllo del saldo in Java | `aCorrectionNeverGoesBelowZero`: risponde il trigger, ma con un 500 invece del 409 |
| "Solo in avanti" in Java | `anExtensionMovesForwardOnly`: l'entità lancia, 500 invece del 409 |
| La data di default dell'omaggio | `withoutADateAGiftLastsAsASingleSession` |

---

## Domande da colloquio

### Domanda 1: Come registri chi ha fatto un'operazione amministrativa, in modo che non si possa perdere?

**Risposta modello.** Nella riga stessa dell'operazione, con l'account di chi agisce, l'istante e una nota obbligatoria, e un vincolo del database che la pretende: se la traccia sta nella stessa riga, un `check` può renderla obbligatoria. Le righe sono a sola aggiunta, con un trigger che rifiuta modifiche e cancellazioni. L'account lo prendo dall'identità della richiesta, mai da un campo mandato dal client.

### Domanda 2: Una correzione può togliere crediti a un cliente mentre lui prenota. Cosa impedisce di andare sotto zero?

**Risposta modello.** Entrambe le operazioni tolgono crediti dallo stesso lotto, quindi entrambe prendono il lucchetto sulla riga del lotto: la seconda aspetta, rilegge il saldo, e se non basta la correzione risponde 409 e la prenotazione va con la carta. Il trigger del database rifiuta comunque un saldo negativo. L'ordine dei lucchetti è sempre cliente poi lotto, e la correzione prende solo il lotto, quindi non c'è un giro che possa bloccarsi.

### Domanda 3: Perché validare in Java una regola che il database controlla già?

**Risposta modello.** Per la risposta: il vincolo del database arriva come un'eccezione generica, cioè un 500, mentre il controllo in Java dà un 409 con un codice che il frontend traduce. Il database resta l'ultima difesa per tutto quello che non passa dal codice, ma non deve essere il modo normale di dire "no" a un utente.

### Domanda 4: Il professionista scrive una nota sul perché di una correzione. La mandi al cliente?

**Risposta modello.** No. La nota è la traccia interna per chi controllerà, e il professionista la scrive pensando a quello: metterla in un'email cambierebbe cosa ci scrive, o farebbe arrivare al cliente cose non pensate per lui. Il cliente riceve cosa è cambiato, l'importo e il saldo; il motivo, se lo chiede, glielo spiega lo studio.

---

## Documentazione ufficiale

- (nessuna nuova: i concetti sono quelli delle guide 36–39)
