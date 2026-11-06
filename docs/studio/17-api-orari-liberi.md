# L'API pubblica degli orari liberi — guida di studio

Questa guida accompagna il **pezzo 4 del passo 6**, l'ultimo. Mette in fila quello che abbiamo costruito:
- `ScheduleService` raccoglie dati da tre moduli;
- chiede l'ora al `Clock`;
- chiama `FreeSlots`;
- risponde a chiunque, senza accesso, con gli orari in cui un servizio può iniziare.

Poi si chiude il passo.

```
GET /api/public/practitioners/{id}/services/{serviceId}/slots?from=2026-11-09&to=2026-11-15
```

Contiene:
- **la parte 1**: cosa si costruisce, e il percorso di una richiesta;
- **la parte 2**: **prima scelta**: come `schedule` sa la durata del servizio, e se è in vendita;
- **la parte 3**: **seconda scelta**: come si validano `from` e `to`;
- **la parte 4**: **terza scelta**: quali assenze caricare;
- **la parte 5**: riservatezza: cosa esce e cosa no;
- **la parte 6**: i test;
- **la parte 7**: la chiusura del passo.

Teoria di riferimento: guida 12 (API pubbliche, `record` dedicati, 404 per "disattivato"); guida 15, parte 3 (vincoli di classe); guida 16 (il calcolo); D-029 (dipendenze in un verso solo), D-035.

---

## Indice

1. [Cosa si costruisce](#parte-1--cosa-si-costruisce)
2. [Prima scelta: durata e "in vendita"](#parte-2--prima-scelta-durata-e-in-vendita)
3. [Seconda scelta: validare `from` e `to`](#parte-3--seconda-scelta-validare-from-e-to)
4. [Terza scelta: quali assenze caricare](#parte-4--terza-scelta-quali-assenze-caricare)
5. [Riservatezza](#parte-5--riservatezza)
6. [I test](#parte-6--i-test)
7. [La chiusura del passo](#parte-7--la-chiusura-del-passo)
8. [Cosa devi decidere](#parte-8--cosa-devi-decidere)
9. [Domande da colloquio](#domande-da-colloquio)
10. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — Cosa si costruisce

### 1.1 I pezzi

| Classe | Dove | Cosa fa |
|---|---|---|
| `SchedulePublicController` | `schedule` | `GET /api/public/practitioners/{id}/services/{serviceId}/slots` |
| `ScheduleService.freeSlots(...)` | `schedule` | Raccoglie i dati, chiama `FreeSlots`, converte il risultato |
| `PublicSlot` | `schedule` | `record` pubblico con solo `start` ed `end`, in ora di Roma |
| `SlotRange` | `schedule` | `record` dei parametri `from` e `to` (parte 3) |
| Un metodo nuovo in `CatalogService` | `catalog` | La durata di un servizio in vendita (parte 2) |
| Un metodo nuovo in `UnavailabilityRepository` | `schedule` | Le assenze in una finestra (parte 4) |

### 1.2 Il percorso di una richiesta

```
cliente ──GET──► SchedulePublicController
                     │  @Valid SlotRange (from ≤ to, altrimenti 400)
                     ▼
                 ScheduleService.freeSlots(practitionerId, serviceId, range)
                     ├─► CatalogService: servizio in vendita? durata         (404 se no)
                     │       └─► PractitionerService.requireActive           (404 se no)
                     ├─► PractitionerService.getSettings: scatto, anticipo, orizzonte
                     ├─► WorkingScheduleRepository: versioni con le fasce   (1 query)
                     ├─► UnavailabilityRepository: assenze nella finestra    (1 query)
                     ├─► Clock: adesso
                     └─► FreeSlots.compute(SlotQuery)                        (nessuna query)
                     ▼
                 List<PublicSlot>  ──JSON──► cliente
```

Le dipendenze tra moduli restano in un verso solo (D-029): `schedule` → `catalog` → `practitioner` → `auth`, e `schedule` → `practitioner`.

---

## Parte 2 — Prima scelta: durata e "in vendita"

### 2.1 Il problema

`schedule` deve sapere due cose del servizio, entrambe di `catalog`:
- se è **in vendita**, cioè del professionista indicato, attivo, e con il professionista attivo;
- quanto **dura**.

### 2.2 Strada A — Un metodo nuovo e piccolo in `CatalogService`

```java
// catalog
public ServiceOnSale serviceOnSale(long practitionerId, long serviceId)   // NotFoundException se non in vendita

public record ServiceOnSale(long id, int durationMinutes) {}
```

- **Pro**: `schedule` chiede esattamente ciò che gli serve (D-002, D-026). La regola "in vendita" resta in `catalog`, come per il catalogo pubblico, che già chiama `requireActive`.
- **Pro**: una query, con la proprietà dentro (`findByIdAndPractitionerIdAndActiveTrue`). Il servizio di Marco chiesto sotto Anna dà 404, come uno inesistente.
- **Contro**: un metodo e un `record` in più nella porta di `catalog`.

### 2.3 Strada B — Riusare `publicCatalog` e cercare il servizio nella lista

- **Contro**: carica tutti i servizi e tutti i pacchetti per usarne uno, e la regola "in vendita" diventa un filtro scritto in `schedule`, cioè fuori dal modulo che la possiede.

### 2.4 Raccomandazione

**A.** È la stessa forma di `AccountService.activeAmong` (D-029): una domanda precisa al modulo che sa la risposta.

---

## Parte 3 — Seconda scelta: validare `from` e `to`

### 3.1 Il problema

`from` e `to` arrivano nella query string. Tre errori possibili:
- **mancano**: 400;
- **non sono date** (`from=domani`): 400, e lo fa già Spring (guida 12, caso 9);
- **`from` dopo `to`**: 400 sul campo. Ma è una regola **tra due parametri**.

Se nessuno la controlla prima, la fa scattare il costruttore di `SlotQuery`, con un `IllegalArgumentException` che oggi diventa un **500**.

### 3.2 Strada A — Un `record` per i parametri, con un vincolo di classe

```java
@ValidRange                                  // from non dopo to, errore sul campo "to"
public record SlotRange(@NotNull LocalDate from, @NotNull LocalDate to) {}

@GetMapping("/{id}/services/{serviceId}/slots")
List<PublicSlot> slots(@PathVariable long id, @PathVariable long serviceId, @Valid SlotRange range)
```

Senza `@RequestBody`, Spring riempie il `record` dai **parametri della query string** (è un `@ModelAttribute` implicito), usando il suo costruttore. Con `@Valid`, un errore diventa la stessa `MethodArgumentNotValidException` dei corpi JSON: lo stesso 400, con `errors[].field`, che il nostro `ApiExceptionHandler` sa già formattare.

- **Pro**: stessa tecnica della guida 15 (vincolo di classe), stesso formato d'errore di tutto il resto.
- **Contro**: un'annotazione in più.

### 3.3 Strada B — Due `@RequestParam` e un controllo nel servizio

- **Contro**: il servizio lancerebbe una `BusinessRuleException`, cioè un 409. Ma `from` dopo `to` non dipende dallo stato: è la richiesta a essere sbagliata (D-027).

### 3.4 Strada C — Lasciar fare a `SlotQuery`

- **Contro**: 500. Un errore del client presentato come un errore del server.

### 3.5 Raccomandazione

**A.** Due date obbligatorie, nessun valore di default: il frontend sa quale settimana sta mostrando, e una richiesta esplicita è più facile da leggere nei log. Nessun limite sulla distanza tra le due date: la taglia comunque l'orizzonte (guida 16, 5.3).

---

## Parte 4 — Terza scelta: quali assenze caricare

### 4.1 Il problema

Il calcolo riceve gli intervalli occupati. Caricare **tutte** le assenze del professionista funzionerebbe, ma cresce con il tempo: dopo tre anni sono centinaia di righe per guardarne cinque.

### 4.2 Strada A — Le assenze che toccano i giorni richiesti

Si convertono `from` e `to` in istanti (inizio del giorno `from`, fine del giorno `to`) e si cercano le assenze che si sovrappongono.
- **Contro**: con `to` nel 2099 la finestra è enorme, e di nuovo il lavoro dipende dal client.

### 4.3 Strada B — Le assenze nella finestra decisa dal server

La finestra va da **adesso** alla **fine dell'ultimo giorno dell'orizzonte**: gli unici istanti in cui un orario può essere libero, qualunque cosa chieda il client. La query è la sovrapposizione della guida 13, 5.4, scritta in SQL:

```java
// assenze che si sovrappongono a [windowStart, windowEnd): starts_at < windowEnd e ends_at > windowStart
List<Unavailability> findByPractitionerIdAndStartsAtBeforeAndEndsAtAfter(Long practitionerId, Instant windowEnd, Instant windowStart);
```

Attenzione all'**ordine dei parametri**: nel nome del metodo `StartsAtBefore` viene prima, quindi il primo istante è la **fine** della finestra. È un punto in cui si sbaglia facilmente, e la ragione per cui serve un test con un'assenza a cavallo dell'inizio della finestra.

- **Pro**: lavoro limitato dal server; il "taglio" è nello stesso spirito del calcolo.
- **Contro**: nessuno serio.

### 4.4 Raccomandazione

**B.** L'indice `(practitioner_id, starts_at)` della V3 serve proprio questa ricerca.

---

## Parte 5 — Riservatezza

Requisiti, "Riservatezza del calendario": chi non ha accesso vede solo gli **orari liberi**, mai chi ha prenotato né perché un orario è occupato.

| Esce | Non esce |
|---|---|
| `start` ed `end` di ogni orario libero, in ora di Roma | il motivo delle assenze (`reason`) |
| | quali orari sono occupati, e da cosa: un orario semplicemente non compare |
| | impostazioni del professionista (scatto, anticipo, orizzonte): se ne vede solo l'effetto |
| | id interni delle versioni o delle assenze |

Un `record` dedicato, `PublicSlot(OffsetDateTime start, OffsetDateTime end)`, e un test che controlla **l'elenco esatto** dei campi (guida 12, 4.2). Con il passo 7 arriveranno le prenotazioni negli intervalli occupati: la risposta non cambierà forma, perché il calcolo non sa nemmeno perché un intervallo è occupato.

Un limite da conoscere: dalla forma degli orari liberi si può **intuire** che a una certa ora c'è qualcosa (con la griglia di 30 minuti, un buco di un'ora e mezza è probabilmente una seduta). È inevitabile in qualunque sistema che mostri disponibilità, e i requisiti lo accettano: non si vede **chi** né **cosa**.

---

## Parte 6 — I test

### 6.1 `ScheduleService.freeSlots` (`@DataJpaTest`, `Clock` fisso)

Il calcolo è già provato in `FreeSlotsTest`. Qui si prova il **collante**: che i dati giusti arrivino al calcolo.

| # | Caso | Atteso |
|---|---|---|
| 1 | Anna con una versione, un'assenza lunedì 11:15–12:00, seduta da 60 | gli orari dell'esempio della guida 13 |
| 2 | Lo scatto di Anna passa a 60 | cambiano gli orari: le impostazioni arrivano davvero |
| 3 | Assenza a cavallo dell'inizio della finestra (iniziata prima di adesso, finisce dopo) | considerata |
| 4 | Assenza di Marco | non tocca gli orari di Anna |
| 5 | Servizio disattivato, servizio di Marco, servizio inesistente | `NotFoundException`, stesso messaggio |
| 6 | Professionista disattivato | `NotFoundException` |

### 6.2 API (`@SpringBootTest` + `MockMvcTester`, `Clock` fisso)

| # | Caso | Atteso |
|---|---|---|
| 7 | Senza header `X-Practitioner-Id` | 200: le API pubbliche non chiedono chi sei |
| 8 | I campi di ogni orario | **esattamente** `start` ed `end`, con l'offset `+01:00` |
| 9 | Un'assenza con un motivo | il motivo non compare da nessuna parte nella risposta |
| 10 | `from` dopo `to` | 400 sul campo `to` |
| 11 | `from` mancante | 400 sul campo `from` |
| 12 | `from=domani` | 400 |
| 13 | Settimana oltre l'orizzonte | 200 e lista vuota |
| 14 | Servizio non in vendita, professionista disattivato | 404 |

---

## Parte 7 — La chiusura del passo

Dopo il codice e i test verdi (CLAUDE.md, "A fine sessione"):

1. **`passi/06-calendario.md`**: il pezzo 4, gli errori, lo stato "chiuso", le domande da colloquio con le risposte modello (ci sono già, si completano).
2. **`stato.md`**: passo 6 chiuso; per il passo 7, il problema del ciclo tra `schedule` e `booking` (guida 16, 6.1) come prima cosa da decidere.
3. **`decisioni.md`**: le scelte di questa guida, in una scheda.
4. **I criteri del passo**: il professionista imposta orari e assenze dal pannello; il cliente vede gli orari liberi di un servizio; anticipo, orizzonte e cambio dell'ora sono provati da test. Li controllo uno per uno.
5. **Il commit**, e il **riepilogo** per la chat di pianificazione.

---

## Parte 8 — Cosa devi decidere

> **Decise il 2026-11-06**: metodo nuovo `serviceOnSale` in `CatalogService` (A), `SlotRange` con vincolo di classe e date obbligatorie (A), finestra delle assenze decisa dal server (B) (D-038). Codice scritto da Claude su richiesta di Nicholas.

1. **Durata e "in vendita"** (parte 2): A (un metodo nuovo in `CatalogService`) o B (riusare il catalogo pubblico)? Io: **A**.
2. **`from` e `to`** (parte 3): A (`record` con vincolo di classe), B (servizio, 409) o C (lasciar fare a `SlotQuery`, 500)? Io: **A**, con le due date obbligatorie.
3. **Quali assenze** (parte 4): A (i giorni richiesti) o B (la finestra decisa dal server)? Io: **B**.

---

## Domande da colloquio

### Domanda 1: Come validi un vincolo tra due parametri della query string in Spring?
**Risposta modello.** Raccolgo i parametri in un `record` senza `@RequestBody`: Spring lo costruisce dalla query string come `@ModelAttribute`. Ci metto `@Valid` e un vincolo di classe che controlla la relazione tra i campi, segnalando l'errore su uno dei due. L'errore diventa la stessa `MethodArgumentNotValidException` dei corpi JSON, quindi lo stesso 400 con lo stesso formato. Non la lascio arrivare a un costruttore che lancia `IllegalArgumentException`, perché diventerebbe un 500.

### Domanda 2: Come scrivi con Spring Data la query "gli intervalli che si sovrappongono a una finestra"?
**Risposta modello.** È la condizione `start < fineFinestra AND end > inizioFinestra`, cioè `findBy...StartsAtBeforeAndEndsAtAfter(fine, inizio)`. I parametri seguono l'ordine del nome, quindi la fine della finestra va per prima: è un errore facile, da coprire con un test su un intervallo a cavallo del bordo. Un indice che parte da `(owner_id, starts_at)` serve la ricerca.

### Domanda 3: Un'API pubblica mostra disponibilità. Come proteggi la riservatezza?
**Risposta modello.** Con un DTO dedicato che contiene solo gli orari liberi, controllato da un test sull'elenco esatto dei campi; mai motivi, nomi o id interni. E con un calcolo che riceve gli occupati come intervalli anonimi, così non può far trapelare ciò che non conosce. Resta intuibile che un orario è occupato, ed è inevitabile; non si vede chi né perché.

### Domanda 4: Come eviti che il lavoro di un'API dipenda da ciò che chiede il client?
**Risposta modello.** Tagliando ogni intervallo su limiti decisi dal server prima di cicli e query: qui l'orizzonte di prenotazione, sia per i giorni del calcolo sia per la finestra delle assenze. Così una richiesta fino al 2099 costa quanto una di una settimana.

### Domanda 5: Un servizio deve sapere qualcosa che appartiene a un altro modulo. Come progetti la domanda?
**Risposta modello.** Un metodo nella porta pubblica dell'altro modulo, che risponde esattamente alla domanda (qui: "questo servizio è in vendita, e quanto dura?") con un `record` piccolo. La regola resta nel modulo che la possiede, e chi chiede non dipende dalle sue entità. Il contrario, cioè caricare un oggetto grande e filtrarlo fuori, sposta la regola nel posto sbagliato.

---

## Documentazione ufficiale

- Spring MVC, `@ModelAttribute` e binding con il costruttore: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/modelattrib-method-args.html
- Spring MVC, validazione: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html
- Spring Data JPA, parole chiave nei nomi dei metodi (`Before`, `After`): https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html
