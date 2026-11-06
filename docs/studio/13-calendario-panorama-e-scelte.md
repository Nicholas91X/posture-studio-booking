# Il calendario — guida di studio (passo 6: panorama e scelte)

Questa guida apre il **passo 6**: orari di lavoro, indisponibilità e **calcolo degli orari liberi**. È il cuore del sistema: ogni prenotazione del passo 7 partirà da un orario che questo calcolo ha dichiarato libero.

A differenza delle guide del passo 5, questa non accompagna un pezzo solo. Viene **prima** di tutti, perché le scelte che contiene cambiano la migrazione V3, e una migrazione eseguita non si tocca più. Le guide dei singoli pezzi arriveranno dopo, una per pezzo.

Nota: per il passo 6 non c'è ancora un brief della chat di pianificazione. La guida parte dai documenti (requisiti, "Calendario"; D-030; `modello-dati.md`). Se poi arriva un brief, si confrontano e si allineano.

Contiene:
- **la parte 1**: cosa si costruisce, in quali pezzi;
- **la parte 2**: tre modi di guardare il tempo in Java, e perché il cambio dell'ora è la trappola principale del passo;
- **la parte 3**: **prima scelta**, lo scatto e a cosa si aggancia la griglia;
- **la parte 4**: **seconda scelta**, gli orari che cambiano da una data futura;
- **la parte 5**: il calcolo degli orari liberi, passo per passo;
- **la parte 6**: le scelte piccole che propongo senza chiederti (dimmi se non ti tornano);
- **la parte 7**: i test;
- **la parte 8**: il riepilogo di cosa decidere.

Teoria di riferimento: requisiti, "Calendario" e "Riservatezza del calendario"; D-030 (anticipo minimo); D-023 (orizzonte); D-002 e D-026 (moduli); guida 12, parte 4 (cosa esce da un'API pubblica).

---

## Indice

1. [Cosa si costruisce](#parte-1--cosa-si-costruisce)
2. [Tre modi di guardare il tempo](#parte-2--tre-modi-di-guardare-il-tempo)
3. [Prima scelta: lo scatto e la griglia](#parte-3--prima-scelta-lo-scatto-e-la-griglia)
4. [Seconda scelta: orari che cambiano da una data](#parte-4--seconda-scelta-orari-che-cambiano-da-una-data)
5. [Il calcolo degli orari liberi](#parte-5--il-calcolo-degli-orari-liberi)
6. [Le scelte piccole](#parte-6--le-scelte-piccole)
7. [I test](#parte-7--i-test)
8. [Cosa devi decidere](#parte-8--cosa-devi-decidere)
9. [Domande da colloquio](#domande-da-colloquio)
10. [Documentazione ufficiale](#documentazione-ufficiale)

---

## Parte 1 — Cosa si costruisce

### 1.1 Il risultato, visto dal cliente

Alla fine del passo il sito potrà chiedere:

```
GET /api/public/practitioners/1/services/3/slots?from=2026-11-09&to=2026-11-15
```

e ricevere gli orari in cui **quel servizio** di **quel professionista** può iniziare:

```json
[
  { "start": "2026-11-09T09:00:00+01:00", "end": "2026-11-09T10:00:00+01:00" },
  { "start": "2026-11-09T09:30:00+01:00", "end": "2026-11-09T10:30:00+01:00" },
  ...
]
```

Il servizio entra nella domanda perché la durata cambia la risposta. Alle 12:30, con la fascia che finisce alle 13:00, c'è posto per una valutazione da 30 minuti ma non per una seduta da 60.

### 1.2 I pezzi

| # | Pezzo | Cosa contiene |
|---|---|---|
| 1 | Migrazione V3 | Tabelle degli orari di lavoro e delle indisponibilità; nuove impostazioni in `practitioner` (anticipo minimo, orizzonte, scatto limitato) |
| 2 | Modulo `schedule`, lato pannello | Entità, repository, `ScheduleService`: il professionista imposta i suoi orari e le sue indisponibilità. API sotto `/api/panel` |
| 3 | Il calcolo | Una **funzione pura** (parte 5) che riceve tutto ciò che serve e restituisce gli orari liberi. Tanti test unitari, senza Spring né database |
| 4 | L'API pubblica | `ScheduleService` raccoglie i dati, chiama il calcolo, risponde sotto `/api/public`. Test di integrazione |

Il pezzo 3 è quello da studiare con più cura: è logica pura, ed è il tipo di codice che ai colloqui si chiede di scrivere alla lavagna.

### 1.3 Le dipendenze tra moduli

`schedule` ha bisogno di due cose che non sono sue:
- la **durata del servizio** e il fatto che sia in vendita, da `catalog`;
- le **impostazioni** (scatto, anticipo minimo, orizzonte) e il fatto che il professionista sia attivo, da `practitioner`.

```
schedule ──► catalog ──► practitioner ──► auth
    └─────────────────────────┘
```

Sempre in un verso solo, come vuole D-029: nessun modulo di destra conosce `schedule`. Al passo 7 si aggiungerà `booking`, che chiederà a `schedule` "questo orario è libero?". La freccia andrà da `booking` verso `schedule`, non al contrario.

### 1.4 Cosa resta fuori

- **Le prenotazioni**: arrivano al passo 7. Il calcolo però nasce già pronto a riceverle, come un elenco di "intervalli occupati" che oggi è sempre vuoto (parte 5).
- **Approvazione, segnalazione della stanza, notifiche** (D-030, D-032, punto aperto 12): passo 7 e seguenti.
- **Le pagine del pannello e del calendario**: passi 12 e 13.

---

## Parte 2 — Tre modi di guardare il tempo

### 2.1 I tipi di `java.time`

Prima di qualsiasi scelta, il concetto che regge tutto il passo:

| Tipo | Cosa rappresenta | Esempio | Nel progetto |
|---|---|---|---|
| `Instant` | Un **istante** sulla linea del tempo, uguale in tutto il mondo | `2026-11-09T08:00:00Z` | Ciò che si salva in `timestamptz`: indisponibilità, prenotazioni |
| `LocalTime`, `LocalDate`, `LocalDateTime` | Un orario o una data **"da calendario a muro"**, senza fuso | `09:00`, `2026-11-09` | Gli orari di lavoro: "il lunedì alle 9" vale d'estate e d'inverno |
| `ZonedDateTime` | Una data e un orario **in un fuso preciso**: è il ponte tra i due | `2026-11-09T09:00+01:00[Europe/Rome]` | Il calcolo: "lunedì 9 alle 9:00 a Roma" → `Instant` |

### 2.2 Perché gli orari di lavoro sono locali e le indisponibilità no

- "Lavoro dalle 9 alle 13" è una frase **da calendario a muro**. Vale le 9 di Roma a novembre (UTC+1) e a luglio (UTC+2). Se la salvassimo come istante UTC, cioè "08:00Z", d'estate diventerebbe le 10:00. Per questo `working_hours` usa `time`, ed è l'eccezione scritta in CLAUDE.md.
- "Sono dal dentista giovedì 12 dalle 11:15 alle 12:00" è un fatto **unico**, accade una volta sola. È un istante, e si salva in `timestamptz` come tutto il resto.

### 2.3 La trappola: il cambio dell'ora

In Italia, nel 2026, l'ora legale è iniziata **domenica 29 marzo** (alle 2:00 gli orologi sono andati alle 3:00) ed è finita **domenica 25 ottobre** (alle 3:00 sono tornati alle 2:00).

Il bug classico è ragionare in istanti e aggiungere giorni come blocchi di 24 ore:

```
lunedì 23 marzo, 09:00 a Roma  = 08:00Z      (UTC+1)
+ 7 × 24 ore                   = lunedì 30 marzo, 08:00Z
ma lunedì 30 marzo, 08:00Z     = 10:00 a Roma (UTC+2)   ← il cliente vede "10:00" invece di "9:00"
```

La regola che lo evita: **si ragiona in date e orari locali, e si converte in `Instant` all'ultimo momento**, un giorno alla volta:

```java
// un esempio diverso: l'apertura di una palestra
ZoneId rome = ZoneId.of("Europe/Rome");
LocalDate day = LocalDate.of(2026, 3, 30);
Instant opening = ZonedDateTime.of(day, LocalTime.of(9, 0), rome).toInstant();   // 07:00Z, corretto
```

### 2.4 Gli orari che non esistono e quelli doppi

- Il 29 marzo le **2:30 non esistono**: è un *gap*. `ZonedDateTime.of` sposta in avanti della durata del salto, quindi le 2:30 diventano le 3:30.
- Il 25 ottobre le **2:30 esistono due volte**: è un *overlap*. `ZonedDateTime.of` sceglie il primo dei due, cioè quello ancora in ora legale.

Con fasce dalle 9 alle 19 questi casi non capitano mai, e cadono comunque di domenica. Ma un test che attraversa le due date del cambio è obbligatorio (parte 7): è l'unico modo per essere certi che la regola della 2.3 sia rispettata.

### 2.5 "Adesso" non si chiede al sistema: `Clock`

L'anticipo minimo (D-030) dipende da **adesso**. Se il codice chiama `Instant.now()`, ogni test dipende dall'ora in cui lo lanci: un test che passa il venerdì può fallire il sabato. La soluzione standard è `java.time.Clock`:
- in produzione, un bean Spring `Clock.systemUTC()`;
- nei test, `Clock.fixed(...)`, cioè un orologio fermo su un istante scelto da noi.

Il servizio chiede l'ora a `Instant.now(clock)`. Come arriva "adesso" fino al calcolo è nella parte 5.3.

---

## Parte 3 — Prima scelta: lo scatto e la griglia

### 3.1 Cosa sappiamo

Dallo studio e da te: le sedute iniziano ogni ora, ma ogni professionista può permettere anche l'inizio alla mezz'ora. Oggi `practitioner.slot_step_minutes` accetta qualsiasi numero positivo, con default 30 (V1).

### 3.2 Come esprimere "mezz'ora sì / no"

**Strada A — `slot_step_minutes` limitato a 30 e 60.**
- V3 cambia il vincolo: `check (slot_step_minutes in (30, 60))`. Default 60, cioè la regola base dello studio. Chi vuole la mezz'ora la attiva.
- Il pannello mostra un interruttore e invia 30 o 60. Il frontend traduce, ma non decide niente.
- **Pro**: nessuna colonna nuova; il calcolo usa direttamente il numero. Se un giorno servirà 15 o 45, si cambia solo il vincolo.
- **Contro**: nel database il concetto si legge come "minuti", non come "mezz'ora sì/no". Va scritto un commento.

**Strada B — un booleano nuovo, `allow_half_hour_starts`.**
- **Pro**: il nome dice esattamente la regola.
- **Contro**: `slot_step_minutes` va eliminata con una migrazione, e il calcolo deve tradurre `true/false` in 30 o 60. Se un giorno servirà un terzo valore, il booleano non basta, ed è lo stesso ragionamento fatto per `booking_approval` in D-030.

**Raccomandazione: A.** C'è già, è più generale, e il nome del concetto lo mostra il pannello.

### 3.3 A cosa si aggancia la griglia

Con scatto 60 e una fascia 9:00–13:00 è tutto ovvio: 9, 10, 11, 12. Ma se una fascia inizia alle 9:30?

| | Agganciata all'**inizio della fascia** | Agganciata all'**orologio** |
|---|---|---|
| Fascia 9:30–13:00, scatto 60 | 9:30, 10:30, 11:30 | 10:00, 11:00, 12:00 (la mezz'ora 9:30–10:00 resta vuota) |
| Pro | Rispetta esattamente l'orario che il professionista ha scritto; nessun tempo perso | Orari "tondi" per il cliente |
| Contro | Orari meno tondi se la fascia inizia in modo strano | Spreca tempo; una fascia 9:30–10:30 con scatto 60 non offrirebbe nessun orario |

**Raccomandazione: inizio della fascia.** Se il professionista scrive "dalle 9:30", vuole lavorare dalle 9:30. In più, per chiarezza, propongo di accettare solo fasce che iniziano e finiscono su **multipli di 30 minuti** (9:00, 9:30, 14:00…). Così, con gli orari reali dello studio, le due strade danno lo stesso risultato, e i casi strani non esistono. È una validazione del pannello, non un vincolo del database.

### 3.4 Cosa vuol dire, in un esempio

Lunedì 9 novembre, fascia 9:00–13:00, seduta da 60 minuti:
- scatto **60**: 9:00, 10:00, 11:00, 12:00;
- scatto **30**: 9:00, 9:30, 10:00, 10:30, 11:00, 11:30, 12:00. Le 12:30 no, perché finirebbero alle 13:30, oltre la fascia (requisiti: "una seduta non finisce mai oltre l'orario").

---

## Parte 4 — Seconda scelta: orari che cambiano da una data

### 4.1 Il problema

Lo studio vuole poter cambiare gli orari "quando e come vogliono" (punto aperto 13). I casi sono tre:

| Caso | Esempio | Con il modello attuale |
|---|---|---|
| (a) Cambiare lo schema da subito | "Da ora il mercoledì pomeriggio non lavoro" | ✅ si modifica `working_hours` |
| (b) Togliere ore in un giorno preciso | Ferie, un pomeriggio libero | ✅ un'indisponibilità |
| (c) Cambiare lo schema **da una data futura** | "Dal 1° giugno orario estivo, 8–14" | ❌ |

Perché (c) non funziona: il 20 maggio Anna inserisce l'orario estivo, ma la modifica vale subito. Fino al 31 maggio il sito mostrerebbe gli orari sbagliati. Dovrebbe ricordarsi di farlo la sera del 31, e intanto i clienti, con 21 giorni di orizzonte, avrebbero già prenotato sul vecchio schema.

### 4.2 Strada A — Solo lo schema settimanale (com'è oggi)

- Per (c) ci si arrangia: o si fa la modifica il giorno giusto, o si coprono le settimane con indisponibilità.
- **Pro**: zero complessità.
- **Contro**: il caso (c) è proprio quello che lo studio ha chiesto. E le indisponibilità usate come trucco sporcano l'agenda.

### 4.3 Strada B — Versioni dello schema, ognuna con una data di inizio

Uno schema settimanale completo si chiama **versione**, e ha una data da cui vale:

```
working_schedule                                working_hours
┌────┬─────────────────┬────────────┐          ┌───────────┬─────────────┬───────┬───────┐
│ id │ practitioner_id │ valid_from │          │schedule_id│ day_of_week │ start │ end   │
├────┼─────────────────┼────────────┤          ├───────────┼─────────────┼───────┼───────┤
│ 10 │ 1               │ 2026-11-01 │ ───────► │ 10        │ 1 (lun)     │ 09:00 │ 13:00 │
│    │                 │            │          │ 10        │ 1 (lun)     │ 14:00 │ 19:00 │
│    │                 │            │          │ …         │ …           │ …     │ …     │
│ 11 │ 1               │ 2027-06-01 │ ───────► │ 11        │ 1 (lun)     │ 08:00 │ 14:00 │
└────┴─────────────────┴────────────┘          └───────────┴─────────────┴───────┴───────┘
```

**La regola di lettura**: per un giorno D, vale la versione con la `valid_from` più recente tra quelle **non successive** a D. Il 31 maggio 2027 vale la 10, il 1° giugno la 11.

**Le regole di scrittura** che propongo:
- una versione si salva **intera**: tutta la settimana in una richiesta, e il servizio controlla che le fasce di uno stesso giorno non si sovrappongano;
- una versione **già iniziata non si modifica**: per cambiare "da subito" se ne crea una nuova con `valid_from` di oggi. Così la storia resta, e si può sempre sapere che orario valeva in un giorno passato;
- una versione **futura** si può sostituire o cancellare;
- `unique (practitioner_id, valid_from)`: due versioni con la stessa data sarebbero ambigue;
- nessuna versione → nessun orario libero. Un professionista nuovo non è prenotabile finché non imposta i suoi orari.

Un dettaglio che aiuta: con l'anticipo minimo di più di 24 ore (D-030), una nuova versione da oggi non tocca orari che un cliente potrebbe ancora prenotare per oggi. Il caso (a) è quindi semplicemente "una versione con `valid_from` = oggi".

- **Pro**: copre (a) e (c) con un solo meccanismo; conserva la storia; la regola di lettura è una sola riga di codice.
- **Contro**: una tabella in più, e un pannello un po' più ricco ("orario attuale", "orario dal 1° giugno").

### 4.4 Strada C — Una validità su ogni riga

Ogni riga di `working_hours` ha `valid_from` e `valid_to`.
- **Pro**: una tabella sola.
- **Contro**: la "settimana" non esiste più come oggetto, e righe di periodi diversi si possono accavallare. Per esempio il lunedì 9–13 valido fino a giugno e il lunedì 8–14 valido da maggio: due righe vere nello stesso giorno. Impedirlo richiede vincoli complicati. È la strada che, nei sistemi reali, produce i bug più difficili.

### 4.5 Confronto

| | A — solo schema | B — versioni | C — validità per riga |
|---|---|---|---|
| Caso (c) | ❌ a mano | ✅ | ✅ |
| Storia degli orari passati | ❌ si perde | ✅ | ✅ |
| Righe in conflitto possibili | no | no (una versione alla volta) | sì |
| Tabelle | 1 | 2 | 1 |
| Complessità del calcolo | minima | una ricerca in più per giorno | alta |

### 4.6 Raccomandazione

**B.** È ciò che lo studio ha chiesto, e il costo è contenuto. È anche un concetto che vale la pena conoscere: si chiama ***effective dating*** (dati con data di efficacia), e lo trovi ovunque ci siano listini, tariffe o contratti che cambiano nel tempo. In banca l'avrai visto con i tassi e le condizioni dei conti.

---

## Parte 5 — Il calcolo degli orari liberi

### 5.1 Le regole, tutte insieme

Un orario è libero per il cliente se **tutte** queste condizioni sono vere:

1. sta dentro una **fascia di lavoro** della versione valida quel giorno, dall'inizio alla fine della seduta;
2. inizia su un punto della **griglia** (inizio della fascia + multipli dello scatto);
3. **non si sovrappone** a nessuna indisponibilità;
4. non si sovrappone a nessuna prenotazione che occupa l'orario. Al passo 6 l'elenco è vuoto; al passo 7 lo fornisce `booking`;
5. inizia **più di 24 ore** dopo adesso (anticipo minimo, D-030);
6. cade **entro l'orizzonte** (21 giorni, D-023).

### 5.2 L'algoritmo, a parole

```
per ogni giorno D tra "from" e "to" (date locali, Europe/Rome):
    versione = la più recente con valid_from ≤ D        (se non c'è: giorno vuoto)
    per ogni fascia della versione nel giorno della settimana di D:
        t = inizio della fascia
        finché t + durata ≤ fine della fascia:          (confronto in istanti)
            candidato = [t, t + durata)
            se rispetta 3, 4, 5, 6 → è libero
            t = t + scatto
```

Le date e gli orari si generano **in locale**, e ogni candidato si converte in `Instant` con `ZonedDateTime.of(D, t, Europe/Rome)`. È la regola della 2.3.

### 5.3 Una funzione pura

Il calcolo **non legge il database e non chiede l'ora**: riceve tutto come parametro e restituisce un risultato. Vantaggi:
- si testa con decine di casi in millisecondi, senza Spring, senza container;
- un test si legge come un esempio: "dati questi orari, questa indisponibilità e questo adesso, gli orari liberi sono questi";
- `ScheduleService` resta semplice: carica i dati, chiede l'ora al `Clock`, chiama il calcolo, converte il risultato.

**Raccomandazione:** il calcolo riceve `now` come `Instant`, non un `Clock`. Il `Clock` lo usa il servizio, che è l'unico posto dove "adesso" entra nel sistema. Il calcolo non ha più nessuna dipendenza dal tempo reale.

### 5.4 Intervalli semiaperti

Un intervallo `[inizio, fine)` contiene l'inizio ma **non** la fine. Così una seduta 10:00–11:00 e una 11:00–12:00 non si sovrappongono, e due sedute attaccate sono possibili. È la stessa convenzione dell'*exclusion constraint* del passo 7 (`modello-dati.md`, `booking`: `[starts_at, ends_at)`): il calcolo e il database devono pensarla allo stesso modo, altrimenti il calcolo offre un orario che il database poi rifiuta.

Due intervalli `[a1, a2)` e `[b1, b2)` si sovrappongono se e solo se:

```
a1 < b2  &&  b1 < a2
```

È una delle domande più frequenti alle prove di programmazione, e va saputa a memoria con il disegno: i casi "uno dentro l'altro", "parziale a sinistra", "parziale a destra" e "uguali" sono tutti coperti da questa sola condizione.

### 5.5 Un esempio completo

Venerdì 6 novembre 2026, ore 10:00. Anna ha:
- scatto 30;
- fasce lun–ven 9:00–13:00 e 14:00–19:00;
- un'indisponibilità **lunedì 9 dalle 11:15 alle 12:00**.

Un cliente chiede gli orari per la seduta da 60 minuti.

- **Sabato 7 e domenica 8**: nessuna fascia.
- **Anticipo minimo**: tutto deve iniziare dopo sabato 7 alle 10:00. Lunedì è comunque oltre.
- **Lunedì 9, mattina**: i candidati sono 9:00, 9:30, 10:00, 10:30, 11:00, 11:30, 12:00.
  - 10:30–11:30, 11:00–12:00 e 11:30–12:30 si sovrappongono all'indisponibilità: esclusi.
  - 10:00–11:00 no: finisce prima delle 11:15.
  - 12:00–13:00 no: l'indisponibilità finisce alle 12:00, e con gli intervalli semiaperti non si toccano.
  - **Liberi: 9:00, 9:30, 10:00, 12:00.**
- **Lunedì 9, pomeriggio**: dalle 14:00 alle 18:00, ogni mezz'ora: 9 orari.
- **Limite dell'orizzonte**: l'ultimo giorno è venerdì 27 novembre (6 + 21).

Con scatto 60, la mattina di lunedì sarebbe: 9:00, 10:00, 12:00 (le 11:00 sono escluse).

### 5.6 Java moderno: `record` con controlli nel costruttore

Il calcolo lavora con piccoli oggetti di valore, che sono il caso ideale per i `record`. Un `record` può avere un **costruttore compatto**, che controlla i valori senza ripetere i campi. L'esempio è su un dominio diverso, la prenotazione di una sala riunioni:

```java
record Meeting(Instant start, Instant end) {
    Meeting {                                    // costruttore compatto: niente parametri, niente assegnazioni
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start must be before end");
        }
    }

    boolean overlaps(Meeting other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }
}
```

Un `Meeting` con la fine prima dell'inizio non può esistere: l'errore si scopre dove nasce, non tre metodi dopo.

Un'altra novità di Java 21 utile qui sono le ***sequenced collections***. Se le versioni sono ordinate per `valid_from`, "l'ultima tra quelle già iniziate" si legge `validOnes.getLast()`, invece del vecchio `list.get(list.size() - 1)`.

---

## Parte 6 — Le scelte piccole

Le propongo senza chiederti; dimmi se qualcuna non ti torna.

| # | Scelta | Proposta | Perché |
|---|---|---|---|
| 1 | **Orizzonte (D-023) nella V3** invece che al passo 7 | `booking_horizon_days` nasce ora, default 21 | Il calendario pubblico deve fermarsi all'orizzonte già da questo passo. Cambia solo **quando** nasce la colonna, rispetto a ciò che dice D-023: lo annoto lì |
| 2 | **Anticipo minimo nella V3** | `min_booking_notice_hours`, default 24, `check > 0` | Serve al calcolo (D-030) |
| 3 | **Formato delle ore nelle API** | ISO-8601 **con l'offset di Roma**: `2026-11-09T09:00:00+01:00`, in Java `OffsetDateTime` | Non ambiguo, come `Z`, e già leggibile: il frontend mostra "09:00" senza conversioni. In ingresso (le indisponibilità dal pannello) lo stesso formato |
| 4 | **Ultimo giorno dell'orizzonte** | Data locale: oggi a Roma + 21 giorni, compreso | "21 giorni" è una frase da calendario a muro, come gli orari di lavoro |
| 5 | **Intervallo richiesto** (`from`, `to`) | Date locali, `from ≤ to`, tagliato in automatico su anticipo e orizzonte. Fuori dall'orizzonte → elenco vuoto, non errore | Il cliente sfoglia le settimane: chiedere una settimana oltre l'orizzonte non è un errore, semplicemente non c'è niente |
| 6 | **Servizio non in vendita** o professionista disattivato | 404, come il catalogo pubblico | Stessa regola della guida 12, 4.3 |
| 7 | **Indisponibilità** | `ends_at > starts_at` (vincolo); possono sovrapporsi tra loro; si cancellano davvero (non sono storia di soldi) | Due indisponibilità sovrapposte non fanno danni: il calcolo toglie l'unione |
| 8 | **Il `reason` delle indisponibilità** | Solo nel pannello, mai nelle API pubbliche | Requisiti, "Riservatezza del calendario" |
| 9 | **Validazione dello scatto** (solo 30 o 60) | Da vedere nella guida del pezzo 2: Bean Validation non ha un'annotazione "uno tra questi valori", e ci sono tre modi di farla | È anche la prova generale della validazione incrociata che servirà al passo 7 (`approval_window_hours` ≤ `min_booking_notice_hours`, D-030) |

---

## Parte 7 — I test

### 7.1 Il calcolo: unitari, senza Spring

È la parte più importante del passo. I casi minimi:

| # | Caso | Atteso |
|---|---|---|
| 1 | Giorno senza fasce (sabato) | nessun orario |
| 2 | Fascia 9–13, durata 60, scatto 60 | 9, 10, 11, 12 |
| 3 | Stessa fascia, scatto 30 | 9:00 … 12:00, **non** 12:30 |
| 4 | Durata 30, scatto 30 | fino alle 12:30 compresa |
| 5 | Indisponibilità 11:15–12:00 | l'esempio della 5.5 |
| 6 | Indisponibilità che finisce **esattamente** all'inizio di un orario | l'orario resta libero (semiaperto) |
| 7 | Indisponibilità su tutta la giornata | nessun orario quel giorno |
| 8 | Anticipo: adesso lunedì 10:00, orario martedì 10:00 | **escluso** (serve "più di" 24 ore) |
| 9 | Anticipo: orario martedì 10:30 | incluso |
| 10 | Orizzonte: ultimo giorno incluso, quello dopo no | |
| 11 | **Cambio dell'ora**: venerdì 27 marzo e lunedì 30 marzo 2026, fascia dalle 9 | 9:00 a Roma in entrambi i giorni, quindi `08:00Z` e `07:00Z` |
| 12 | Cambio dell'ora di ottobre: venerdì 23 e lunedì 26 ottobre 2026 | `07:00Z` e `08:00Z` |
| 13 | Due versioni: il giorno prima della nuova e il giorno stesso | vale la vecchia, poi la nuova |
| 14 | Nessuna versione | nessun orario |
| 15 | Un intervallo occupato (prenotazione finta) | escluso come un'indisponibilità: prepara il passo 7 |

### 7.2 Il resto

- **Schema** (V3): i vincoli rifiutano una fascia con fine ≤ inizio, uno scatto diverso da 30 e 60, un'indisponibilità rovesciata, due versioni con la stessa data.
- **`ScheduleService`** (`@DataJpaTest` + `@Import`, con un `Clock` fisso): salvataggio di una versione, rifiuto di fasce sovrapposte, rifiuto della modifica di una versione già iniziata, proprietà (le cose di un altro professionista danno 404).
- **API** (`@SpringBootTest` + `MockMvcTester`): l'API pubblica risponde senza header; i campi sono esattamente `start` ed `end`; nessun `reason` nella risposta; servizio disattivato → 404; `from > to` → 400.

---

## Parte 8 — Cosa devi decidere

Prima di scrivere la guida del pezzo 1 (migrazione V3), mi servono:

1. **Lo scatto** (parte 3.2): A (`slot_step_minutes` limitato a 30 e 60, default 60) o B (booleano)? Io: **A**.
2. **L'aggancio della griglia** (parte 3.3): inizio della fascia o orologio? E fasce solo su multipli di 30 minuti? Io: **inizio della fascia, sì ai multipli di 30**.
3. **Gli orari da una data futura** (parte 4): A, B o C? Io: **B, versioni con data di inizio**.
4. **Le scelte piccole** (parte 6): vanno bene tutte? In particolare la 1, che anticipa una colonna del passo 7.

Quando le avremo fissate, aggiorno `modello-dati.md` e `decisioni.md`, chiudo i punti aperti 3 e 13, e passo alla guida della migrazione V3.

---

## Domande da colloquio

### Domanda 1: Perché salvi gli orari di lavoro come `time` locale e le indisponibilità come `timestamptz`?
**Risposta modello.** Perché sono due cose diverse. "Il lunedì dalle 9" è una regola che si ripete e segue l'orologio a muro: d'estate e d'inverno restano le 9 a Roma, anche se in UTC cambiano. Salvata in UTC, sbaglierebbe di un'ora per metà dell'anno. Un'indisponibilità o una prenotazione è un fatto unico, che accade una volta in un istante preciso: si salva come istante (in UTC), e si converte nel fuso solo per mostrarlo.

### Domanda 2: Come generi gli orari di una settimana senza sbagliare il cambio dell'ora?
**Risposta modello.** Ragionando in date e orari locali e convertendo in `Instant` un giorno alla volta, con `ZonedDateTime.of(data, ora, zona)`. L'errore classico è partire da un istante e aggiungere 24 ore per ogni giorno: nella settimana del cambio l'orario locale si sposta di un'ora. E serve un test che attraversi le date del cambio, perché è un bug che non si vede per undici mesi l'anno.

### Domanda 3: Come rendi testabile del codice che dipende da "adesso"?
**Risposta modello.** Non chiamando mai `Instant.now()` direttamente, ma `Instant.now(clock)`, con un `java.time.Clock` iniettato: in produzione `Clock.systemUTC()`, nei test `Clock.fixed(...)`. Ancora meglio, la logica pura riceve `now` come parametro, e il `Clock` lo usa solo il servizio che la chiama: la logica non ha più nessuna dipendenza dal tempo reale, e un test può provare "venerdì alle 10:00" senza aspettare venerdì.

### Domanda 4: Quando due intervalli si sovrappongono?
**Risposta modello.** Con intervalli semiaperti `[a1, a2)` e `[b1, b2)`, si sovrappongono se e solo se `a1 < b2 && b1 < a2`. Una sola condizione copre tutti i casi (contenuto, parziale a sinistra o a destra, uguale). Semiaperti perché due appuntamenti attaccati, 10–11 e 11–12, non devono risultare in conflitto. La stessa convenzione va usata ovunque: nel calcolo in Java e nel vincolo del database, altrimenti l'uno offre ciò che l'altro rifiuta.

### Domanda 5: Un listino, o un orario, cambia da una data futura. Come lo modelli?
**Risposta modello.** Con versioni che hanno una data di inizio validità (*effective dating*): per un giorno D vale la versione più recente con `valid_from ≤ D`. Le versioni già iniziate non si modificano, se ne crea una nuova: così si conserva la storia, e si può sempre ricostruire cosa valeva in passato. Una validità `from/to` su ogni singola riga è più flessibile, ma permette righe di periodi diversi in conflitto, ed è difficile da vincolare.

### Domanda 6: Perché il calcolo degli orari liberi è una funzione pura e non un metodo di un servizio Spring?
**Risposta modello.** Perché è la logica più ricca del sistema e merita molti test, veloci e leggibili come esempi. Se leggesse il database o l'ora di sistema, ogni test avrebbe bisogno di un container e di dati inseriti, e dipenderebbe dal giorno in cui gira. Separata, si prova con decine di casi in millisecondi. Il servizio intorno fa solo da collante: carica, chiede l'ora, chiama, converte. È il principio "functional core, imperative shell".

---

## Documentazione ufficiale

- `java.time`, panoramica: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/package-summary.html
- `ZonedDateTime.of`, gap e overlap del cambio dell'ora: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/ZonedDateTime.html#of(java.time.LocalDate,java.time.LocalTime,java.time.ZoneId)
- `Clock`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Clock.html
- `record` e costruttori compatti (JEP 395): https://openjdk.org/jeps/395
- Sequenced collections (JEP 431): https://openjdk.org/jeps/431
- PostgreSQL, tipi di data e ora: https://www.postgresql.org/docs/17/datatype-datetime.html
- PostgreSQL, tipi di intervallo e *exclusion constraint* (per il passo 7): https://www.postgresql.org/docs/17/rangetypes.html
