# Cosa si vede dei crediti — guida di studio

Questa guida accompagna il **pezzo 6 del passo 10**, l'ultimo prima della chiusura. Finora i crediti si creano, si usano e si correggono, ma nessuno li può **leggere**: il cliente non sa quanti ne ha, e il professionista non ha l'id di un lotto da prorogare (pezzo 5).

```
Cliente
GET /api/customer/credits                       i miei lotti: saldo, ultimo giorno valido, stato
GET /api/customer/credits/{id}                  uno, con la sua storia (senza le note del professionista)

Professionista
GET /api/panel/customers                        "i miei clienti" (D-066)
GET /api/panel/customers/{id}/credits           i lotti di un mio cliente, solo i miei
GET /api/panel/credit-lots/{id}                 un mio lotto, con la storia e le note
```

Teoria di riferimento: D-009 (registro), D-026 (la cosa di un altro è un 404), D-029 (niente sotto `/api/public`, domande su un insieme di id), D-049 (liste del pannello senza leggere i clienti degli altri), D-066 (i clienti di un professionista), D-067 e D-073 (la nota è la traccia interna), D-069 (`ValidUntil`).

---

## Parte 1 — Lo stato di un lotto, deciso dal backend

Il cliente vuole sapere una cosa sola: "quanti ne posso usare, e fino a quando". Con il saldo e la data il frontend potrebbe calcolarlo, ma "scaduto" è una regola (D-069: il giorno di Roma, la fine del giorno) e il frontend non contiene regole (D-005). Quindi il backend dice lo **stato**:

| Stato | Quando |
|---|---|
| `USABLE` | saldo > 0 e l'ultimo giorno valido non è passato (a Roma) |
| `USED_UP` | saldo 0, qualunque sia la data |
| `EXPIRED` | saldo > 0 ma l'ultimo giorno valido è passato |

`USABLE` vuol dire "si può usare per una seduta fino all'ultimo giorno valido" (D-071): una seduta dopo quel giorno va con la carta.

**I lotti finiti si mostrano?** Sì, tutti: la storia è del cliente, e un lotto scaduto con crediti è proprio quello per cui chiederà una proroga. Come mostrarli (raggruppati, in grigio) lo decide il frontend; l'ordine lo dà il backend: **per ultimo giorno valido**, il più vicino prima, a parità il più vecchio.

---

## Parte 2 — Nomi o id?

`CustomerBookingView` dà `practitionerId` e `serviceId`, non i nomi: il frontend li prende dal catalogo pubblico, che già li mostra. I crediti fanno lo stesso: **id**, come le prenotazioni. Un'API che restituisse anche i nomi costringerebbe `credit` a chiedere a `practitioner` e `catalog` per ogni lista, per un dato che il frontend ha già.

Nel pannello è diverso per "i miei clienti": lì nome, cognome ed **email** servono (il professionista cerca il cliente per nome, e l'email è come fa un omaggio, D-066). Sono **i suoi** clienti, quindi può vederli (D-066).

---

## Parte 3 — La storia di un lotto

Il registro ha due tabelle (movimenti e proroghe), ma la persona legge una storia sola, in ordine di tempo:

| Voce | Da | Cosa dice |
|---|---|---|
| `LOT_CREATED` | movimento | +N, l'origine (pacchetto o omaggio) |
| `BOOKING` | movimento | −1, la prenotazione |
| `BOOKING_RELEASE` | movimento | +1, la prenotazione |
| `ADJUSTMENT` | movimento | ±N |
| `EXTENSION` | proroga | dalla vecchia alla nuova data |

Il backend unisce le due tabelle e ordina per istante (a parità, i movimenti prima e poi per id): un'unica lista, ognuna con i campi che le servono e gli altri vuoti.

**Le note**: nel pannello sì (sono la traccia del professionista: perché ha regalato, corretto, prorogato), al cliente **no**, per la stessa ragione delle email (D-073). Lo stesso record con la nota vuota per il cliente sarebbe fragile: un giorno qualcuno la riempirebbe per sbaglio. Meglio la regola nel punto che costruisce la vista: un parametro "con le note" che solo il pannello passa a vero, e un test che controlla che il cliente non le riceva mai.

---

## Parte 4 — "I miei clienti" (D-066)

Un cliente è del professionista se ha **almeno una prenotazione** con lui, in qualunque stato, **o almeno un lotto** con lui.

**Chi la costruisce.** Le prenotazioni le sa `booking`, i lotti `credit`, i nomi `customer` e le email `auth`. Serve un modulo che possa chiedere a tutti senza creare un ciclo: `booking`, che dipende già da `credit` e da `customer` (pezzo 3). Quindi:
- `credit` espone `customerIdsOf(practitionerId)`: gli id dei clienti con un suo lotto;
- `booking` unisce con gli id dei clienti delle sue prenotazioni;
- `customer` riceve `contactsOf(insieme di id)`: nomi ed email in **due** domande in tutto (una a `customer`, una ad `auth`), non una per cliente (D-029).

**Riservatezza.** Un cliente che ha prenotazioni o lotti **solo** con l'altro professionista non compare. I lotti di un cliente comune: il professionista vede **solo i suoi**. Il lotto di un altro, chiesto per id, è un 404 (D-026). Sono i test di riservatezza del brief, come al passo 8.

Ordine: per cognome e nome, come un'agenda. Nessuna paginazione: due professionisti, qualche centinaio di clienti al massimo. Se un giorno servirà, si aggiunge.

---

## Parte 5 — Niente sotto `/api/public`

D-029: niente crediti senza accesso. Le API stanno sotto `/api/customer` (solo clienti) e `/api/panel` (solo professionisti), e Spring Security le assegna già per prefisso (D-051). Il test di riservatezza controlla anche l'anonimo (401) e il ruolo sbagliato (403).

---

## Parte 6 — I test

| Test | Cosa prova |
|---|---|
| `CustomerCreditsApiTest` | i propri lotti con saldo, ultimo giorno e stato (`USABLE`, `USED_UP`, `EXPIRED`), nell'ordine; un lotto con la storia in ordine (nato, prenotazione, restituzione, correzione, proroga) **senza note**; il lotto di un altro cliente 404; anonimo 401; professionista 403 |
| `PanelCustomersApiTest` | "i miei clienti": con una prenotazione, con un lotto, con entrambi (una volta), non quelli dell'altro professionista, con nome ed email, in ordine; i lotti di un cliente comune: solo i propri; il lotto con le note; un cliente 403 |

**Le difese tolte a turno** (lo script corretto): il filtro del professionista nei lotti di un cliente, le note escluse per il cliente, il calcolo di `EXPIRED` sul giorno di Roma, l'unione delle due fonti di "i miei clienti".

---

## Parte 7 — Cosa c'è da decidere

| Scelta | Raccomandazione | Dove |
|---|---|---|
| Stato di un lotto | `USABLE`, `USED_UP`, `EXPIRED`, deciso dal backend con il giorno di Roma | parte 1 |
| Lotti finiti | si mostrano tutti, per ultimo giorno valido | parte 1 |
| Nomi | id per i crediti (come le prenotazioni); nome, cognome ed email per "i miei clienti" | parte 2 |
| Storia | movimenti e proroghe in una lista, in ordine di tempo; note solo nel pannello | parte 3 |
| "I miei clienti" | costruita da `booking`, con `credit.customerIdsOf` e `customer.contactsOf` su un insieme | parte 4 |

Dopo l'implementazione: una scheda (D-074), poi la chiusura del passo 10.

---

## Parte 8 — Com'è andata (implementazione)

Costruito come raccomandato nella parte 7 (D-074), su indicazione di Nicholas del 2026-11-11. Nessuna migrazione. 10 test nuovi, 998 in tutto, `mvnw clean test` verde.

| File | Cosa fa |
|---|---|
| `CreditViews` | le letture: i lotti di un cliente, di un cliente di un professionista, un lotto con la storia; lo stato con il giorno di Roma |
| `CreditLotSummary`, `CreditLotDetail`, `CreditEntry`, `LotBalance` | le viste e il saldo raggruppato |
| `CustomerCreditController` | `/api/customer/credits` |
| `CreditPanelController` | `/api/panel/customers/{id}/credits`; `/api/panel/credit-lots/{id}` ora con la storia |
| `CreditService.customerIdsOf`, `BookingRepository.findCustomerIdsOf`, `CustomerService.contactsOf` | le tre domande su un insieme per "i miei clienti" |
| `BookingService.customersForPanel`, `PanelCustomerController`, `PanelCustomerView` | "i miei clienti" |

Test: `CustomerCreditsApiTest` (5) e `PanelCustomersApiTest` (5), più l'adattamento di un test del pezzo 5: `GET /api/panel/credit-lots/{id}` ora risponde con `lot` e `history`, e il saldo è in `$.lot.balance`.

**Una difesa della guida non era proteggibile, e l'ho sostituita.** La parte 6 prometteva di togliere "il giorno di Roma" dal calcolo di `EXPIRED`. Ma l'orologio fisso dei test è alle 10 di Roma (le 9 UTC): a quell'ora il giorno UTC e quello di Roma sono lo stesso, e il test resterebbe verde anche con il giorno sbagliato. Togliere una difesa che nessun test può vedere non prova niente. Il giorno di Roma lo garantisce `StudioTime.today`, scritto e provato al passo 6 per la trappola di mezzanotte. Al suo posto ho tolto una difesa che questi test vedono: "l'ultimo giorno vale ancora oggi" (con `!isAfter` al posto di `isBefore`, il lotto che scade oggi diventava `EXPIRED`).

**Le difese tolte a turno**, con lo script corretto, quattro su quattro:

| Tolto | Test che se ne accorge |
|---|---|
| Il professionista nella lista dei lotti di un cliente | `PanelCustomersApiTest`: Anna vedeva il lotto di Marco del cliente comune |
| Le note escluse per il cliente | `CustomerCreditsApiTest` |
| "L'ultimo giorno vale ancora oggi" | `CustomerCreditsApiTest` |
| I clienti con un lotto in "i miei clienti" | `PanelCustomersApiTest` |

Anche un errore mio nello script (un "a capo" finito dentro una stringa Python) è stato preso subito: lo script non è partito, e il confronto finale ha detto che il codice era rimasto intatto.

---

## Domande da colloquio

### Domanda 1: Il saldo di un lotto è una somma di movimenti. Come mostri una lista di lotti senza una query per ogni lotto?

**Risposta modello.** Con una sola query raggruppata: le somme dei movimenti per tutti i lotti della lista, con `group by` sull'id del lotto e un `in` sugli id, poi le unisco in memoria. Sono due query in tutto, qualunque sia il numero di lotti. Una query per lotto sarebbe il problema N+1: invisibile con tre lotti, lento con trecento.

### Domanda 2: Chi decide se un credito è scaduto, il frontend o il backend?

**Risposta modello.** Il backend. Sembra un confronto di date, ma è una regola: il giorno va preso nel fuso dello studio, la fine del giorno è la mezzanotte di Roma, e domani la regola potrebbe cambiare. Se la scrivessi anche nel frontend, avrei due copie che prima o poi non vanno d'accordo. Il backend restituisce lo stato, e il frontend lo mostra.

### Domanda 3: La stessa entità si mostra a due persone, e una non deve vedere un campo. Come lo eviti?

**Risposta modello.** Con la regola nel punto che costruisce la vista, non con un campo lasciato vuoto da chi se ne ricorda: chi costruisce la vista del cliente non passa mai le note. E con un test che lo controlla, così un cambiamento futuro che le facesse uscire fallisce. Due record diversi sarebbero ancora più sicuri; qui la storia è la stessa, e un parametro esplicito tiene il codice in un posto solo.

### Domanda 4: Una lista ha bisogno di dati di quattro moduli. Dove la costruisci?

**Risposta modello.** Nel modulo che può dipendere da tutti senza creare cicli: qui le prenotazioni, che conoscono già crediti e clienti. Ogni modulo risponde per la sua parte con una domanda su un insieme di id, non una per riga, e il modulo che compone unisce in memoria. Se nessun modulo potesse farlo senza un ciclo, l'alternativa sarebbe un'interfaccia dichiarata da chi ha bisogno e implementata da chi ha i dati.

---

## Documentazione ufficiale

- (nessuna nuova: i concetti sono quelli delle guide 36–40)
