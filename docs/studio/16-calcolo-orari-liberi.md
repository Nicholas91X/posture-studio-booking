# Il calcolo degli orari liberi — guida di studio

Questa guida accompagna il **pezzo 3 del passo 6**: la **funzione pura** che, dati gli orari di lavoro, le assenze e le regole di un professionista, restituisce gli orari in cui un servizio può iniziare. Nessun database, nessuna API: quelle arrivano nel pezzo 4. Qui c'è solo logica e tanti test unitari.

È il pezzo da studiare con più cura del passo: è il tipo di codice che ai colloqui si chiede di scrivere alla lavagna, e contiene due trappole sul tempo che si vedono solo se le cerchi.

Contiene:
- **la parte 1**: cosa entra e cosa esce;
- **la parte 2**: **prima scelta**: la forma degli ingressi;
- **la parte 3**: **seconda scelta**: come si avanza dentro una fascia. Qui c'è la trappola di mezzanotte;
- **la parte 4**: **terza scelta**: come si trova la versione valida in un giorno;
- **la parte 5**: l'algoritmo completo, passo per passo, e il suo costo;
- **la parte 6**: le scelte piccole, e un'anticipazione sul passo 7;
- **la parte 7**: i test.

Teoria di riferimento: guida 13, parti 2 e 5 (i tipi del tempo, il cambio dell'ora, le regole, gli intervalli semiaperti, l'esempio di lunedì 9 novembre); D-033 (scatto e griglia), D-034 (versioni), D-035 (funzione pura, anticipo, orizzonte).

---

## Indice

1. [Cosa entra e cosa esce](#parte-1--cosa-entra-e-cosa-esce)
2. [Prima scelta: la forma degli ingressi](#parte-2--prima-scelta-la-forma-degli-ingressi)
3. [Seconda scelta: come si avanza dentro una fascia](#parte-3--seconda-scelta-come-si-avanza-dentro-una-fascia)
4. [Terza scelta: la versione valida in un giorno](#parte-4--terza-scelta-la-versione-valida-in-un-giorno)
5. [L'algoritmo completo](#parte-5--lalgoritmo-completo)
6. [Le scelte piccole](#parte-6--le-scelte-piccole)
7. [I test](#parte-7--i-test)
8. [Cosa devi decidere](#parte-8--cosa-devi-decidere)
9. [Domande da colloquio](#domande-da-colloquio)
10. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — Cosa entra e cosa esce

### 1.1 Gli ingressi

| Cosa | Tipo | Da dove arriverà (pezzo 4) |
|---|---|---|
| Le versioni dello schema, ognuna con la sua data e le sue fasce | `LocalDate` + `List<WorkingBand>` | `WorkingScheduleRepository` |
| Gli intervalli **occupati** | `List<TimeRange>` (istanti) | le indisponibilità; dal passo 7 anche le prenotazioni |
| La durata del servizio | `Duration` | `catalog` |
| Lo scatto, l'anticipo minimo, l'orizzonte | `int` | `practitioner` |
| I giorni richiesti | `LocalDate from`, `to` | la richiesta del cliente |
| Adesso | `Instant` | il `Clock` |

La zona (`Europe/Rome`) è la costante di `StudioTime`.

### 1.2 L'uscita

Una lista di `TimeRange(start, end)`, cioè l'inizio e la fine di ogni seduta possibile, in **ordine di inizio**. Il pezzo 4 la convertirà nel JSON con l'offset di Roma.

### 1.3 Un `record` per gli intervalli, con le sue regole

Il concetto "intervallo di tempo semiaperto" compare ovunque: assenze, prenotazioni, orari liberi. Merita un tipo suo, con il controllo nel costruttore compatto e la regola di sovrapposizione in un posto solo (guida 13, 5.4 e 5.6):

```java
public record TimeRange(Instant start, Instant end) {
    public TimeRange {
        // non null, e start prima di end: un intervallo rovesciato non può esistere
    }
    public boolean overlaps(TimeRange other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }
}
```

---

## Parte 2 — Prima scelta: la forma degli ingressi

### 2.1 Il problema

Sono **nove** valori. Un metodo con nove parametri si chiama così:

```java
compute(versions, busy, Duration.ofMinutes(60), 30, 24, 21, from, to, now);
```

Che cosa sono 30, 24 e 21? Basta scambiarne due, e il compilatore non se ne accorge: sono tutti `int`.

### 2.2 Strada A — Un metodo con nove parametri

- **Pro**: niente tipi nuovi.
- **Contro**: illeggibile, e sono scambi che nessun compilatore e nessuna review notano.

### 2.3 Strada B — Un *parameter object*: un `record` con gli ingressi

```java
// esempio diverso: il preventivo di un viaggio
record TripQuote(LocalDate departure, LocalDate returnDate, int adults, int children, Duration maxLayover) {}

Price price = calculator.price(new TripQuote(departure, back, 2, 1, Duration.ofHours(3)));
```

Per noi: `SlotQuery`, con i nove campi, e il calcolo `FreeSlots.compute(query)`. Un test si scrive costruendo una `SlotQuery` diversa per ogni caso. Con un piccolo metodo di supporto nel test, che parte dai valori dell'esempio di Anna e ne cambia uno, i test restano brevi.

- **Pro**: ogni valore ha un nome. Il `record` controlla i propri valori nel costruttore compatto: per esempio scatto 30 o 60, e `from` non dopo `to`.
- **Contro**: un tipo in più.

### 2.4 Strada C — Un *builder*

`SlotQuery.builder().duration(...).step(30)...build()`.
- **Pro**: si leggono i nomi anche nella chiamata.
- **Contro**: molto codice da scrivere a mano (in Java non c'è un builder automatico per i `record`), e un campo dimenticato si scopre solo quando si esegue.

### 2.5 Raccomandazione

**B.** È l'uso tipico dei `record`. Per alleggerire i test basta un metodo di supporto, senza un builder.

---

## Parte 3 — Seconda scelta: come si avanza dentro una fascia

### 3.1 La trappola di mezzanotte

L'idea più naturale è avanzare con `LocalTime`:

```java
LocalTime t = band.startTime();
while (!t.plus(duration).isAfter(band.endTime())) {   // "la seduta finisce entro la fascia"
    ...
    t = t.plus(step);
}
```

Funziona con 9–13. Ma `LocalTime` **gira a mezzanotte**: `23:00 + 2 ore` fa `01:00`, non "25:00". Con una fascia 22:00–23:30 e un servizio di 2 ore:
- `22:00 + 2h = 00:00`, e `00:00` **non** è dopo `23:30`;
- quindi la seduta 22:00–00:00 risulta "dentro la fascia", anche se finisce mezz'ora dopo.

Allo studio non succederà mai, perché chiude alle 19. Ma un calcolo che sbaglia "solo" in certi casi è un calcolo sbagliato. E se un giorno il ciclo avanzasse oltre mezzanotte, `t` tornerebbe a 00:00 e il `while` non finirebbe **mai**.

### 3.2 Strada A — Avanzare in `LocalTime`

Come sopra. **Contro**: la trappola di mezzanotte, e il cambio dell'ora non è gestito (`LocalTime` non sa che le 2:30 del 29 marzo non esistono).

### 3.3 Strada B — Avanzare in minuti dall'inizio del giorno

Si convertono gli orari in numeri (`9:00` = 540) e si lavora con gli `int`, che non girano.
- **Pro**: niente mezzanotte.
- **Contro**: il cambio dell'ora resta fuori. E alla fine bisogna riconvertire tutto, con un'altra occasione di errore.

### 3.4 Strada C — Convertire la fascia in istanti, poi avanzare in istanti

Per ogni giorno e ogni fascia:
1. inizio e fine della fascia diventano **istanti**, con `ZonedDateTime.of(giorno, ora, Europe/Rome)` (guida 13, 2.3);
2. dentro la fascia si avanza **in istanti**: `start = start.plus(step)`;
3. un orario va bene se `start + durata` non supera la fine della fascia, **confrontando istanti**.

Gli istanti non girano a mezzanotte: `22:00 + 2h` è le 00:00 **del giorno dopo**, che è dopo le 23:30. Trappola sparita.

E il cambio dell'ora? Dentro un giorno si avanza in tempo reale. Fra un giorno e l'altro, invece, si **riparte dall'ora locale** della fascia: il bug "istante + 24 ore" della guida 13 non può succedere. Anche una fascia notturna nel giorno del cambio funziona, perché il salto è di 60 minuti, un multiplo dello scatto. Lo prova un test (parte 7, caso 13).

- **Pro**: un solo modo di confrontare, gli istanti, che è anche quello di assenze e prenotazioni.
- **Contro**: nessuno serio. La conversione costa una riga per fascia.

### 3.5 Raccomandazione

**C.** È la stessa regola della guida 13 portata fino in fondo: **in locale solo ciò che è "da calendario a muro"** (il giorno, l'ora di inizio e fine della fascia), **tutto il resto in istanti**.

---

## Parte 4 — Terza scelta: la versione valida in un giorno

### 4.1 Il problema

Per ogni giorno serve "la versione con la `valid_from` più recente non successiva al giorno" (D-034). Con 21 giorni e due o tre versioni non è un problema di velocità. È un problema di **chiarezza**: è la riga in cui è più facile sbagliare un `<` con un `<=`.

### 4.2 Strada A — Un filtro per ogni giorno

```java
versions.stream()
        .filter(v -> !v.validFrom().isAfter(day))
        .max(Comparator.comparing(Version::validFrom));
```

- **Pro**: si capisce.
- **Contro**: la regola si riscrive a mano, e il confine (`isAfter` o `isBefore`?) è proprio dove si sbaglia.

### 4.3 Strada B — Una mappa ordinata: `TreeMap.floorEntry`

Una `TreeMap` tiene le chiavi **in ordine**. `floorEntry(k)` restituisce la voce con la chiave più grande **minore o uguale** a `k`. È esattamente la regola di D-034, già scritta e già provata:

```java
// esempio diverso: le tariffe di un parcheggio, ognuna valida da una data
NavigableMap<LocalDate, Tariff> tariffs = new TreeMap<>();
tariffs.put(LocalDate.of(2026, 1, 1), winter);
tariffs.put(LocalDate.of(2026, 6, 1), summer);

tariffs.floorEntry(LocalDate.of(2026, 5, 31));   // winter
tariffs.floorEntry(LocalDate.of(2026, 6, 1));    // summer: "uguale" conta
tariffs.floorEntry(LocalDate.of(2025, 12, 31));  // null: nessuna tariffa ancora valida
```

- **Pro**: la regola è nel nome del metodo, e funziona per costruzione. È una struttura dati che ai colloqui vale la pena conoscere: `floorKey`, `ceilingKey`, `headMap` e `tailMap` risolvono molti problemi "il più vicino a…".
- **Pro**: `TreeMap` è anche una *sequenced collection* di Java 21 (`firstEntry()`, `lastEntry()`).
- **Contro**: va costruita una volta all'inizio. Due versioni con la stessa data sarebbero un errore, ma il database lo impedisce già (`unique`, D-034).

### 4.4 Raccomandazione

**B.** Il codice dice quello che fa: "per questo giorno, la versione al piano sotto".

---

## Parte 5 — L'algoritmo completo

### 5.1 In pseudocodice

```
today      = data di "now" a Roma
lastDay    = today + horizonDays
earliest   = now + minNotice            (un orario va bene solo se start > earliest)
dalla prima data utile: max(from, today); fino a: min(to, lastDay)

per ogni giorno D:
    versione = versionsByDate.floorEntry(D)          → se null: giorno vuoto
    per ogni fascia della versione con dayOfWeek == giorno della settimana di D:
        bandStart = istante di (D, fascia.start) a Roma
        bandEnd   = istante di (D, fascia.end) a Roma
        start = bandStart
        finché start + durata ≤ bandEnd:
            candidato = [start, start + durata)
            se start > earliest e il candidato non si sovrappone a nessun intervallo occupato:
                aggiungi candidato
            start = start + scatto
ordina per inizio (le fasce di un giorno non sono per forza in ordine)
```

### 5.2 Il costo, perché ai colloqui lo chiedono

- Giorni: al massimo `horizonDays` (21), perché l'intervallo si taglia prima del ciclo. Anche una richiesta `from=2026-01-01&to=2099-12-31` costa 21 giorni, non 27.000.
- Candidati per giorno: con fasce 9–13 e 14–19 e scatto 30 sono circa 20.
- Per ogni candidato si guardano tutti gli intervalli occupati: poche decine in 21 giorni.

Totale: qualche migliaio di confronti, cioè microsecondi. Il confronto "ogni candidato con ogni occupato" costa `O(candidati × occupati)`. Si potrebbe scendere a `O(candidati + occupati)` ordinando entrambi e scorrendoli insieme (*sweep*), ma qui il guadagno non si misura, e il codice diventa più difficile da verificare. La regola è "prima corretto e leggibile, poi veloce **se** misuri che serve". Ai colloqui conviene dirlo così, citando l'alternativa.

### 5.3 Perché tagliare l'intervallo prima del ciclo

Non è solo velocità: è **sicurezza**. L'API pubblica la può chiamare chiunque. Un ciclo che dipende dai valori del client senza un limite è un modo gratuito per far lavorare il server per niente, cioè una piccola porta per un *denial of service*. Con il taglio sull'orizzonte, il lavoro massimo è fissato dal professionista, non da chi chiama.

### 5.4 L'esempio della guida 13, rifatto con l'algoritmo

Adesso: venerdì 6 novembre 2026, ore 10:00 a Roma. Anna ha scatto 30, fasce lun–ven 9–13 e 14–19, un'assenza lunedì 9 dalle 11:15 alle 12:00. Seduta da 60 minuti, `from` = `to` = lunedì 9.

- `earliest` = sabato 7, ore 10:00. `lastDay` = venerdì 27.
- Lunedì 9, fascia 9–13: `bandStart` = 08:00Z, `bandEnd` = 12:00Z. Candidati: 9:00, 9:30, 10:00, 10:30, 11:00, 11:30, 12:00.
- Escono 10:30, 11:00 e 11:30, che si sovrappongono all'assenza.
- **Restano 9:00, 9:30, 10:00, 12:00**, più i nove del pomeriggio. È il primo test da scrivere.

---

## Parte 6 — Le scelte piccole

Le propongo senza chiederti; dimmi se qualcuna non ti torna.

| # | Scelta | Proposta | Perché |
|---|---|---|---|
| 1 | **Dove vive il calcolo** | `FreeSlots`, classe `final` del package `schedule`, non pubblica, con un metodo `static compute(SlotQuery)` | Non ha stato e non ha dipendenze: non serve un bean Spring. Lo usa solo `ScheduleService` (pezzo 4) |
| 2 | **Dove vive `TimeRange`** | `shared`, pubblico | Lo useranno `schedule` e `booking` (passo 7), e non appartiene a nessuno dei due |
| 3 | **Assenze e prenotazioni insieme** | Una sola lista di intervalli occupati | Al calcolo non interessa **perché** un orario è occupato. In più è riservatezza per costruzione: il calcolo non sa nemmeno che esistono clienti |
| 4 | **Anticipo e orizzonte dentro il calcolo** | Sì, non nel servizio | Tutte le regole degli orari liberi in un posto solo, coperte dagli stessi test unitari |
| 5 | **Controlli della `SlotQuery`** | Nel costruttore compatto: niente `null`, durata positiva, scatto 30 o 60, anticipo e orizzonte positivi, `from` non dopo `to` | Una richiesta impossibile non arriva al ciclo. L'API (pezzo 4) li controllerà prima, con un 400 |
| 6 | **Durata che non sta in nessuna fascia** (es. 6 ore) | Elenco vuoto, non errore | È un dato, non uno sbaglio: quel servizio quel giorno non ci sta |
| 7 | **Durata non multipla dello scatto** (es. 45 minuti con scatto 30) | Ammessa | Una seduta 9:00–9:45 lascia libera la 10:00: la griglia riguarda gli inizi, non le fini |
| 8 | **Test parametrizzati** | `@ParameterizedTest` con `@CsvSource` per i confini (anticipo, orizzonte) | Un caso per riga: "adesso, orario, atteso". Si leggono come una tabella |

### 6.1 Un'anticipazione sul passo 7: chi fornisce le prenotazioni?

Al passo 7 il modulo `booking` chiederà a `schedule` "questo orario è libero?". Quindi `booking` dipende da `schedule`. Ma per calcolare gli orari liberi `schedule` avrà bisogno delle prenotazioni, che sono di `booking`: **un ciclo**. Le uscite possibili:
- `schedule` dichiara un'**interfaccia** ("dammi gli intervalli occupati di questo professionista in questo periodo"), e `booking` la implementa. La dipendenza va in un verso solo, `booking` → `schedule`. È l'inversione delle dipendenze (*dependency inversion*), la stessa idea di `CurrentPractitioner` (D-028);
- oppure l'API pubblica degli orari liberi si sposta in `booking`, che combina orari e prenotazioni.

Non si decide ora. Ma è il motivo per cui il calcolo riceve gli occupati come **lista generica di intervalli**: qualunque strada si scelga, `FreeSlots` non cambia.

---

## Parte 7 — I test

### 7.1 Il calcolo (`FreeSlotsTest`), unitari, senza Spring

I casi della guida 13, 7.1, più quelli emersi qui. "Adesso", salvo dove indicato: venerdì 6 novembre 2026, 10:00 a Roma.

| # | Caso | Atteso |
|---|---|---|
| 1 | L'esempio della parte 5.4 | mattina: 9:00, 9:30, 10:00, 12:00; pomeriggio: nove orari |
| 2 | Stesso giorno, scatto 60 | 9:00, 10:00, 12:00, 14:00 … 18:00 |
| 3 | Durata 30, scatto 30 | la mattina arriva alle 12:30 compresa |
| 4 | Durata 45, scatto 30 | 9:00 … 12:00 (12:00–12:45 sta nella fascia; 12:30–13:15 no) |
| 5 | Sabato e domenica | nessun orario |
| 6 | Assenza che finisce **esattamente** all'inizio di un orario | l'orario resta (semiaperto) |
| 7 | Assenza su tutto il giorno | nessun orario quel giorno |
| 8 | Anticipo: adesso lunedì 10:00, orario martedì 10:00 | escluso ("più di" 24 ore) |
| 9 | Anticipo: orario martedì 10:30 | incluso |
| 10 | Orizzonte: venerdì 27 novembre incluso, sabato 28 no (con una fascia anche il sabato) | |
| 11 | Intervallo richiesto enorme (`to` nel 2099) | si ferma all'orizzonte |
| 12 | **Cambio dell'ora di marzo**: venerdì 27 e lunedì 30 marzo 2026, fascia dalle 9 | prima seduta `08:00Z` e poi `07:00Z`: le 9:00 di Roma in entrambi i giorni |
| 13 | **Fascia notturna nel giorno del cambio** (domenica 29 marzo, 01:00–04:00, scatto 60) | 01:00, 03:00 (le 2:00 non esistono): due orari distinti, nessun doppione |
| 14 | Cambio dell'ora di ottobre: venerdì 23 e lunedì 26 ottobre 2026 | `07:00Z` e poi `08:00Z` |
| 15 | **Trappola di mezzanotte**: fascia 22:00–23:30, servizio di 2 ore | nessun orario |
| 16 | Due versioni: il giorno prima della nuova e il giorno stesso | vale la vecchia, poi la nuova |
| 17 | Nessuna versione, o versione che inizia dopo i giorni richiesti | nessun orario |
| 18 | Versione senza fasce | nessun orario |
| 19 | Intervallo occupato da una prenotazione finta | escluso come un'assenza |
| 20 | Fasce passate in disordine (pomeriggio prima della mattina) | risultato comunque in ordine di inizio |
| 21 | `SlotQuery` con `from` dopo `to`, o scatto 45 | `IllegalArgumentException` |

Per i casi 12–14 vale la pena di "vedere" il tempo: il test confronta istanti UTC scritti a mano (`Instant.parse("2026-03-30T07:00:00Z")`), non orari ricalcolati con lo stesso codice che si sta provando. Un test che riusa la logica del codice per calcolare l'atteso sbaglia insieme al codice.

### 7.2 `TimeRange` (`TimeRangeTest`)

- Inizio dopo la fine, o uguale: rifiutato.
- Sovrapposizioni: dentro, a sinistra, a destra, uguali → sì; attaccati, lontani → no. Ogni caso nei **due versi** (`a.overlaps(b)` e `b.overlaps(a)`): una sovrapposizione è simmetrica, e un errore nella formula spesso si vede solo in uno dei due versi.

---

## Parte 8 — Cosa devi decidere

> **Decise il 2026-11-06**: `SlotQuery` (B), avanzare in istanti (C), `TreeMap.floorEntry` (B), scelte piccole tutte accettate. Codice scritto da Claude su richiesta di Nicholas.

1. **La forma degli ingressi** (parte 2): A (nove parametri), B (un `record` `SlotQuery`) o C (un *builder*)? Io: **B**.
2. **Come si avanza dentro una fascia** (parte 3): A (`LocalTime`), B (minuti) o C (istanti)? Io: **C**.
3. **La versione di un giorno** (parte 4): A (filtro) o B (`TreeMap.floorEntry`)? Io: **B**.
4. **Le scelte piccole** (parte 6): ok su tutte?

---

## Domande da colloquio

### Domanda 1: Scrivi un algoritmo che restituisca gli orari liberi di un calendario.
**Risposta modello.** Per ogni giorno dell'intervallo, tagliato prima su un limite massimo: prendo le fasce di lavoro di quel giorno, le converto in istanti nella zona giusta, e dentro ogni fascia genero i candidati ogni "scatto", finché la seduta sta nella fascia. Scarto i candidati troppo vicini ad adesso e quelli che si sovrappongono a un intervallo occupato, con intervalli semiaperti (`a1 < b2 && b1 < a2`). Costo `O(candidati × occupati)`, che con numeri piccoli va bene; con molti dati ordinerei entrambi e li scorrerei insieme. La logica è una funzione pura, che riceve anche "adesso", così si prova con decine di casi.

### Domanda 2: Perché non usare `LocalTime` per avanzare dentro una fascia?
**Risposta modello.** Perché `LocalTime` gira a mezzanotte: `23:00 + 2h` fa `01:00`, che risulta "prima" della fine della fascia. Il confronto "la seduta finisce entro la fascia" diventa sbagliato, e un ciclo che supera la mezzanotte può non finire mai. Converto la fascia in istanti con `ZonedDateTime.of` e avanzo in istanti: niente giro, e il cambio dell'ora è gestito dalla conversione.

### Domanda 3: Come trovi in modo efficiente "la regola valida in una certa data", tra regole che hanno una data di inizio?
**Risposta modello.** Con una `TreeMap` indicizzata per data di inizio e `floorEntry(data)`: restituisce la voce con la chiave più grande minore o uguale, in `O(log n)`, ed è esattamente la definizione di "la più recente già iniziata". Evita di riscrivere a mano il confronto, che è dove si sbaglia un `<` con un `<=`.

### Domanda 4: Un metodo ha molti parametri dello stesso tipo. Come lo rendi sicuro?
**Risposta modello.** Con un *parameter object*: un `record` con un campo per ogni valore, così ogni valore ha un nome e il costruttore compatto controlla le combinazioni impossibili. Un *builder* aiuta quando molti campi sono facoltativi; se sono tutti obbligatori, il `record` è più semplice e un campo mancante è un errore di compilazione.

### Domanda 5: Perché, in un test, l'atteso va scritto a mano e non calcolato?
**Risposta modello.** Perché se calcolo l'atteso con la stessa logica del codice sotto test, un errore nella logica produce lo stesso risultato sbagliato in entrambi, e il test passa. Per il tempo questo vuol dire scrivere gli istanti UTC attesi (`2026-03-30T07:00:00Z`), non riconvertirli con il codice. È anche documentazione: chi legge il test vede il risultato concreto.

### Domanda 6: Un'API pubblica riceve un intervallo di date dal client. Cosa può andare storto?
**Risposta modello.** Che il lavoro del server dipenda da un valore scelto da chiunque: con `to` nel 2099, un ciclo giorno per giorno diventa un modo gratuito per tenere occupato il server. L'intervallo va tagliato su un limite deciso dal server (qui l'orizzonte di prenotazione) prima di qualsiasi ciclo, e validato (`from` non dopo `to`) con un 400.

---

## Documentazione ufficiale

- `java.util.TreeMap` e `NavigableMap` (`floorEntry`): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/TreeMap.html
- `java.time.ZonedDateTime.of`, gap e overlap: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/ZonedDateTime.html
- `java.time.Duration`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Duration.html
- `record` e costruttori compatti (JEP 395): https://openjdk.org/jeps/395
- Sequenced collections (JEP 431): https://openjdk.org/jeps/431
- JUnit 5, test parametrizzati: https://junit.org/junit5/docs/current/user-guide/#writing-tests-parameterized-tests
