# I lavori programmati — guida di studio

Questa guida accompagna il **pezzo 4 del passo 7**. Due lavori girano ogni minuto e chiudono ciò che il tempo ha fatto scadere:
- **blocchi di pagamento scaduti**: `PENDING_PAYMENT` con `hold_expires_at` passato → `EXPIRED` (D-008);
- **richieste senza risposta**: `PENDING_APPROVAL` con `approval_deadline` passata → `APPROVAL_EXPIRED`, con l'annullamento del blocco sulla carta e l'evento `ApprovalExpired` (D-030, D-031).

Teoria di riferimento: guida 19, parte 5 (la scadenza pigra, la stessa transizione fatta prima); guida 20, parti 3 e 4 (lucchetto sulle transizioni, eventi); D-035 (il `Clock`).

---

## Parte 1 — Cosa si costruisce

| Classe | Dove | Cosa fa |
|---|---|---|
| `SchedulingConfiguration` | `shared` | `@EnableScheduling`, acceso da `booking.jobs.enabled` (default `true`) |
| `BookingJobs` | `booking` | I due metodi `@Scheduled`: nessuna logica, chiamano `BookingService` |
| `BookingService.expireHolds` | `booking` | Un `update` in blocco: tutti i blocchi scaduti in una volta |
| `BookingService.expireApproval(id)` | `booking` | Una richiesta alla volta, ognuna in una transazione sua |
| `Booking.expireApproval` | `booking` | La transizione `PENDING_APPROVAL` → `APPROVAL_EXPIRED` |
| `BookingEvent.ApprovalExpired` | `booking` | Il quinto evento: al passo 8, email al cliente |

---

## Parte 2 — Prima scelta: come si dice "ogni minuto"

| Strada | Significato | Due esecuzioni possono sovrapporsi? |
|---|---|---|
| `fixedRate = 60s` | parte ogni 60 secondi, contati dall'**inizio** della precedente | con un solo thread no, ma le esecuzioni in ritardo si accumulano e partono una dopo l'altra |
| **`fixedDelay = 60s`** | parte 60 secondi dopo la **fine** della precedente | no |
| `cron = "0 * * * * *"` | al secondo 0 di ogni minuto, sull'orologio | no, ma si allinea al minuto: utile per "alle 3 di notte", inutile qui |

**Raccomandazione: `fixedDelay`** (`fixedDelayString = "PT1M"`, una `Duration` ISO-8601). Il lavoro non ha bisogno di partire a un'ora precisa, e non deve mai rincorrersi. Il ritardo massimo con cui una richiesta scaduta viene chiusa è circa un minuto più la durata del lavoro: la scadenza pigra (guida 19, parte 5) e il controllo della scadenza nelle risposte (guida 20, 6.2) coprono già quel minuto.

Spring Boot crea un solo thread per i lavori programmati (`spring.task.scheduling.pool.size=1`): i due lavori girano uno dopo l'altro, mai insieme. Va bene così.

---

## Parte 3 — Seconda scelta: una transazione per tutto o una per riga

### 3.1 I blocchi di pagamento: un `update` in blocco

Nessuna chiamata esterna, nessun evento: basta un'istruzione.

```sql
update booking set status = 'EXPIRED', version = version + 1
where status = 'PENDING_PAYMENT' and hold_expires_at <= :now
```

È la scadenza pigra del pezzo 2 senza il limite all'orario richiesto. La condizione `where` è la guardia: una prenotazione pagata un attimo prima non è più `PENDING_PAYMENT` e non viene toccata. Se la riga è bloccata da un pagamento in corso (guida 20, 3.4), l'`update` aspetta e poi rilegge la condizione.

### 3.2 Le richieste scadute: una per volta

Qui ogni riga ha un **effetto esterno** (annullare il blocco sulla carta) e un **evento**. Un `update` in blocco non può fare né l'uno né l'altro. Quindi:

```java
// BookingJobs, ogni minuto
for (long id : service.expiredApprovals()) {
    service.expireApproval(id);        // una transazione per ognuna
}
```

```java
// BookingService
@Transactional
public void expireApproval(long bookingId) {
    Booking booking = bookings.findLockedById(bookingId)...;          // lucchetto, come ogni transizione
    if (booking.getStatus() != PENDING_APPROVAL || deadline in the future) return;   // rileggere la guardia
    booking.expireApproval();
    cards.cancel(booking.getId());
    events.publishEvent(ApprovalExpired.of(booking));
}
```

- **Una transazione per richiesta**: se una fallisce (per esempio l'annullamento al passo 9), le altre vanno avanti, e la riga resta bloccata solo per il tempo di quella chiamata.
- **La guardia si rilegge dopo il lucchetto**: tra la lista e il lucchetto il professionista può aver approvato. Allora il lavoro non fa niente, **senza errore**: un conflitto qui è normale, non un bug.
- **Attenzione alla chiamata interna**: se `BookingJobs` fosse la stessa classe di `expireApproval`, la chiamata `this.expireApproval(id)` salterebbe il *proxy* di Spring e quindi `@Transactional`. Per questo i lavori stanno in una classe a parte, che chiama il servizio attraverso il suo *proxy*.

---

## Parte 4 — Il `Clock`, e i test senza aspettare

Il lavoro chiede l'ora al `Clock` (D-035), come tutto il resto. Nei test si usa un **orologio che si sposta a comando** (`MovableClock`, solo nei test):

```
10:00  il cliente chiede lunedì 9:00 → blocco fino alle 10:30
       clock.advance(31 minuti)
10:31  jobs.expireHolds() → EXPIRED
```

Nessun `Thread.sleep`, nessuna attesa di un minuto vero: il test dura millisecondi e dice esattamente cosa succede al confine (10:29 no, 10:30 sì, perché la condizione è `hold_expires_at <= now`).

I test chiamano i metodi di `BookingJobs` **direttamente**: provano cosa fa il lavoro, non che Spring lo chiami ogni minuto. Che `@Scheduled` funzioni è responsabilità di Spring.

---

## Parte 5 — Due istanze dell'applicazione

Oggi gira una sola istanza (D-002). Ma il brief chiede cosa succederebbe con due: **entrambe** eseguirebbero i lavori ogni minuto.

- **Blocchi**: due `update` sulle stesse righe. Il secondo aspetta il primo, rilegge la condizione, aggiorna zero righe. Corretto, solo lavoro doppio.
- **Richieste**: entrambe le istanze trovano la stessa richiesta scaduta. La prima prende il lucchetto, la fa scadere, annulla il blocco, conferma. La seconda aspetta il lucchetto, rilegge lo stato (`APPROVAL_EXPIRED`) e non fa niente. Il blocco viene annullato **una volta sola**, e l'evento parte una volta sola.

Il codice è quindi già corretto con più istanze, grazie alla guardia riletta dopo il lucchetto. Se il lavoro doppio diventasse un problema, le strade sono:
- **una proprietà** per accendere i lavori su una sola istanza: c'è già, `booking.jobs.enabled`;
- **un lucchetto condiviso** tra istanze, per esempio la libreria ShedLock, che salva in una tabella chi sta eseguendo il lavoro.

---

## Parte 6 — Le scelte piccole

| # | Scelta | Proposta | Perché |
|---|---|---|---|
| 1 | **Evento per i blocchi scaduti** | Nessuno | Il cliente non ha pagato: nessuno da avvisare. Se servirà, al passo 8 |
| 2 | **Log** | Una riga per esecuzione, solo se ha chiuso qualcosa | Un log ogni minuto con "0" riempirebbe i file senza dire niente |
| 3 | **Ordine** | Prima i blocchi, poi le richieste | Indipendenti: l'ordine non cambia il risultato |
| 4 | **Interruttore** | `booking.jobs.enabled=${BOOKING_JOBS_ENABLED:true}` | Acceso per default: senza i lavori il sistema non funziona. Spegnibile su un'istanza in più |

---

## Parte 7 — I test (`BookingJobsTest`, `MovableClock`)

| # | Caso | Atteso |
|---|---|---|
| 1 | Richiesta alle 10:00, orologio alle 10:29 | resta `PENDING_PAYMENT` |
| 2 | Orologio alle 10:30 | `EXPIRED`, versione incrementata |
| 3 | Pagata alle 10:10, orologio alle 11:00 | resta `PENDING_APPROVAL`: il lavoro dei blocchi non la tocca |
| 4 | Pagata, orologio oltre la scadenza (22:00) | `APPROVAL_EXPIRED`, blocco annullato, evento `ApprovalExpired`; l'orario torna libero |
| 5 | Pagata, orologio alle 21:59 | resta in attesa |
| 6 | Approvata prima della scadenza, orologio oltre | resta `CONFIRMED`, nessun annullamento |
| 7 | `expireApproval` su una richiesta che nel frattempo è stata ritirata | nessun errore, nessun annullamento in più |
| 8 | Dopo le 22:00 il professionista approva | `APPROVAL_DEADLINE_PASSED` (già coperto dal pezzo 3) |

---

## Parte 8 — Cosa è stato deciso

Scelte secondo le raccomandazioni di questa guida, su indicazione di Nicholas (registrate in D-047): `fixedDelay` di un minuto; blocchi con un `update` in blocco, richieste una per transazione con il lucchetto e la guardia riletta; lavori in una classe separata dal servizio; interruttore `booking.jobs.enabled`, acceso per default.

---

## Domande da colloquio

### Domanda 1: `fixedRate`, `fixedDelay` e `cron` in `@Scheduled`: che differenza c'è?
**Risposta modello.** `fixedRate` conta dall'inizio dell'esecuzione precedente, quindi esecuzioni lente possono accumularsi; `fixedDelay` conta dalla fine, quindi non si sovrappongono mai; `cron` si aggancia all'orologio, utile per "ogni notte alle 3". Per un lavoro di pulizia che non deve rincorrersi uso `fixedDelay`.

### Domanda 2: Il tuo lavoro programmato gira su due istanze dell'applicazione. Cosa succede?
**Risposta modello.** Entrambe lo eseguono. Lo rendo corretto comunque: ogni modifica ha nella condizione lo stato di partenza, e dove c'è un effetto esterno blocco la riga e rileggo lo stato dopo il lucchetto, così la seconda istanza trova la riga già chiusa e non fa niente. Resta solo lavoro doppio; se è un problema, accendo il lavoro su una sola istanza con una proprietà, o uso un lucchetto condiviso come ShedLock.

### Domanda 3: Come testi un lavoro che dipende dal tempo senza aspettare?
**Risposta modello.** Il codice non legge mai l'ora di sistema: chiede a un `Clock` iniettato. Nei test uso un orologio che posso spostare in avanti, e chiamo il metodo del lavoro direttamente. Così provo i confini esatti (un secondo prima e un secondo dopo la scadenza) in millisecondi, e non dipendo da quando gira il test.

### Domanda 4: Perché una transazione per riga invece di una per tutto il lavoro?
**Risposta modello.** Perché ogni riga ha un effetto esterno: con una transazione unica, un errore sulla decima annullerebbe le nove già chiuse nel database, ma non le chiamate esterne già fatte. Con una transazione per riga ogni passo è indipendente, e le righe restano bloccate per poco. Attenzione: il metodo transazionale va chiamato attraverso il *proxy* di Spring, cioè da un altro bean; una chiamata interna con `this` ignorerebbe `@Transactional`.

---

## Documentazione ufficiale

- Spring Framework, `@Scheduled` e `@EnableScheduling`: https://docs.spring.io/spring-framework/reference/integration/scheduling.html
- Spring Boot, esecuzione e pianificazione dei lavori: https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html
- Spring Framework, perché le chiamate interne saltano `@Transactional`: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html
- ShedLock: https://github.com/lukas-krecan/ShedLock
