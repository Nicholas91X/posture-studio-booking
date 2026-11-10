# Il registro dei crediti: lotti, movimenti e pagamenti dei pacchetti — guida di studio

Questa guida accompagna il **pezzo 1 del passo 10**: la migrazione **V15**. Nessun credito si vende o si usa ancora (pezzi 2–5): qui si decide **la forma dei dati** e **quali regole fa rispettare il database da solo**, anche con un bug in Java o con un `update` scritto a mano.

```
credit_lot            un gruppo di crediti con la stessa origine e la stessa scadenza
credit_movement       il registro: +N, −1, +1, mai modificato
credit_lot_extension  la storia delle proroghe (D-067)
payment               riceve i pacchetti: scopo, pacchetto, cliente
booking               paid_with riceve CREDIT
```

Teoria di riferimento: D-009 (registro di movimenti), D-010 (lotti con scadenza, "scade prima, si usa prima"), D-011 e D-022 (prezzo fotografato, centesimi), D-013 (il database come ultima difesa), D-025 (foreign key composta), D-034 (`valid_from` come data locale), D-042 (nei `check` solo i valori che il codice produce), D-045 (lucchetto sul cliente, `READ COMMITTED`), D-060 (colonne dei pacchetti in `payment`), D-066–D-068 (clienti, concessioni, niente `LATE_PAYMENT`).

---

## Indice

- Parte 1 — Cosa c'è già, e cosa manca
- Parte 2 — Registro contro saldo salvato
- Parte 3 — Prima scelta: origini e motivi
- Parte 4 — Seconda scelta: le colonne del lotto
- Parte 5 — Terza scelta: le colonne del movimento
- Parte 6 — Quarta scelta: il registro non si modifica, neanche con SQL
- Parte 7 — Quinta scelta: il saldo mai sotto zero
- Parte 8 — Sesta scelta: la scadenza
- Parte 9 — Settima scelta: la proroga nello schema
- Parte 10 — Ottava scelta: `payment` per i pacchetti
- Parte 11 — `booking`: `paid_with = CREDIT`
- Parte 12 — La migrazione intera
- Parte 13 — I test
- Parte 14 — Cosa c'è da decidere
- Domande da colloquio

---

## Parte 1 — Cosa c'è già, e cosa manca

**C'è:**
- `package_offer` (V2): nome, sedute (almeno 2), prezzo, validità in giorni, attivo. **Si modifica** dal pannello: `changeDetails` cambia nome, sedute, prezzo e validità; il servizio no. Tienilo a mente per la parte 10;
- `practitioner.single_credit_validity_days` (V1, default 90): la validità delle sedute singole, che servirà all'omaggio (D-067) e al passo 11;
- `payment` (V10–V14) solo per le sedute, con `booking_id` obbligatorio;
- `booking.paid_with` con `CARD` e `IN_STUDIO`;
- il modulo `credit` previsto dall'architettura ("Lotti e movimenti dei crediti"), **ancora vuoto**.

**Manca:** le tabelle dei crediti, le regole che le proteggono, i pagamenti dei pacchetti.

---

## Parte 2 — Registro contro saldo salvato

Due modi di sapere quanti crediti ha un cliente.

**Saldo salvato**: una colonna `credits_left` che si aggiorna (`update ... set credits_left = credits_left - 1`). Semplice da leggere. Ma:
- **la storia sparisce**: "perché ne ho 2 e non 3?" non ha risposta;
- un errore si **sovrascrive**: se un bug toglie un credito di troppo, il valore giusto non esiste più da nessuna parte;
- con due aggiornamenti contemporanei scritti male (leggi, calcola in Java, scrivi) uno dei due si perde (*lost update*).

**Registro** (*ledger*, D-009): si scrivono solo **movimenti**, mai modificati; il saldo è la **somma**. È l'estratto conto della banca: la banca non "corregge" una riga sbagliata, ne aggiunge una che la compensa. Il saldo si può sempre ricostruire, e ogni numero ha una spiegazione.

Il prezzo: ogni lettura del saldo è una `sum(...)`. A questa scala (decine di movimenti per cliente) è irrilevante; in un sistema con milioni di righe si aggiunge un saldo salvato come **cache**, ricalcolabile dal registro, ma il registro resta la verità.

Una conseguenza importante: **la scadenza non è un movimento**. Un lotto scaduto con 2 crediti ha ancora saldo 2: semplicemente non si può più usare. Scrivere un movimento "−2 scaduti" con un lavoro programmato sarebbe un fatto *dedotto dall'orologio* spacciato per un fatto *accaduto*; e una proroga dopo la scadenza (D-067) dovrebbe rimettere "+2". Il saldo dice quanti crediti ci sono, la scadenza dice se si possono usare: due domande diverse.

---

## Parte 3 — Prima scelta: origini e motivi

Il brief chiede i **nomi definitivi**. Un nome nel database è come il nome di un conto di incasso (guida 30, 3.4): non si cambia più senza una migrazione.

### 3.1 Origine del lotto (`credit_lot.source`)

| Origine | Quando nasce | Passo |
|---|---|---|
| `PACKAGE` | pagamento di un pacchetto riuscito | 10 |
| `GRANT` | seduta omaggio del professionista (D-067) | 10 |
| `CANCELLATION` | seduta pagata con carta, cancellata in tempo (nuovo lotto da 1) | 11 |

`LATE_PAYMENT` non esiste (D-068). Il credito concesso su una cancellazione tardiva (passo 11) userà `GRANT` legato a quella prenotazione, oppure un'origine sua: lo decide il passo 11.

### 3.2 Motivo del movimento (`credit_movement.reason`)

| Motivo | Importo | Quando | Passo |
|---|---|---|---|
| `LOT_CREATED` | +N | nasce il lotto (qualunque origine) | 10 |
| `BOOKING` | −1 | il credito è impegnato per una prenotazione | 10 |
| `BOOKING_RELEASE` | +1 | torna per rifiuto, risposta scaduta o ritiro (D-030) | 10 |
| `ADJUSTMENT` | ±N | correzione del professionista (D-067) | 10 |
| `CANCELLATION_RESTORE` | +1 | torna per una cancellazione in tempo | 11 |

`BOOKING_RELEASE` sostituisce il provvisorio `APPROVAL_RELEASE` del modello dei dati: il ritiro non è un'approvazione, e il nome deve dire *cosa* succede (la prenotazione lascia andare il credito), non *chi* lo decide.

### 3.3 Cosa entra nei `check`

D-042 dice: nel `check` solo i valori che il codice sa produrre. Ma la guida 30 (7.2) ha precisato: per una **tabella nuova** il passo è un tutt'uno, e tutti i valori che il passo produrrà entrano subito, anche se il pezzo 1 non ne scrive nessuno.

**Raccomandazione**: nei `check` della V15 i valori del **passo 10** (`PACKAGE`, `GRANT`; `LOT_CREATED`, `BOOKING`, `BOOKING_RELEASE`, `ADJUSTMENT`); `CANCELLATION` e `CANCELLATION_RESTORE` arrivano con la migrazione del passo 11. I nomi però li fissiamo **ora**, nella scheda.

In Java: due `enum` (`LotSource`, `MovementReason`) con gli stessi valori, come `BookingStatus` con il suo `check`.

---

## Parte 4 — Seconda scelta: le colonne del lotto

```
credit_lot: id, customer_id, practitioner_id, service_id, source,
            payment_id, package_offer_id, price_cents,     ← solo PACKAGE
            granted_by, note,                              ← solo GRANT
            valid_until, created_at
```

### 4.1 Il servizio è del professionista del lotto

Stesso problema di D-025: `practitioner_id` e `service_id`, ognuno con la sua foreign key, accettano un lotto di Rossi sul servizio di Bianchi. Stessa soluzione: foreign key **composta** verso `service (id, practitioner_id)`, che esiste già (`service_id_practitioner_id_uk`). Il modello dei dati lo aveva già indicato come candidato.

### 4.2 `initial_credits`: no

Il modello dei dati prevede `initial_credits`. Ma il movimento `LOT_CREATED` dice già quanti crediti sono nati: due colonne per lo stesso fatto possono **non essere d'accordo**, e un `check` non può confrontare una riga con un'altra tabella. Nel registro la verità è una sola. "3 su 5" si legge con una query: `sum(amount) filter (where reason = 'LOT_CREATED')`.

**Raccomandazione: niente `initial_credits`.**

### 4.3 `price_cents`: sì, anche se `payment` lo sa già

Sembra lo stesso ragionamento: il prezzo pagato è in `payment.amount_cents`. Ma qui c'è un **confine tra moduli**: `credit` non legge le tabelle di `payment` (D-002). Il lotto si porta la sua copia del prezzo, come la prenotazione ha la sua (D-011): una fotografia **tra moduli** è voluta, un doppione **dentro lo stesso modulo** no. Servirà a sapere quanto vale un credito (prezzo diviso sedute), per esempio al passo 11.

### 4.4 La traccia dell'omaggio sta nel lotto

D-067: chi, quando, perché. Per un omaggio il fatto è il lotto stesso, quindi la traccia va nella sua riga: `granted_by` (l'account del professionista, → `app_user`), `note` (obbligatoria), `created_at`. Il vantaggio è che un `check` sulla **stessa riga** la può pretendere:

```sql
check ((source = 'GRANT') = (granted_by is not null and note is not null))
```

Se la traccia stesse nel movimento `LOT_CREATED`, servirebbe un controllo tra due righe di tabelle diverse, che un `check` non sa fare.

### 4.5 Il collegamento con il pagamento: dal lotto, unico

Il modello dei dati prevede `payment.credit_lot_id` (il pagamento sa quale lotto ha prodotto). Propongo il verso opposto: **`credit_lot.payment_id`, unico**.

Perché: il pericolo del pezzo 2 è un avviso di Stripe doppio che crea **due lotti** per lo stesso pagamento (un pacchetto regalato). Con `payment.credit_lot_id` il secondo lotto si crea comunque, e resta orfano; con `unique (payment_id)` sul lotto **il database rifiuta il secondo**. È la stessa idea dell'indice "un solo `PENDING` per seduta" (guida 30, 7.4): la regola "un pagamento, un lotto" la garantisce un vincolo, non la memoria di chi scrive il webhook. Il confronta-e-cambia sul pagamento (D-062) resta la prima difesa; questo è la seconda.

`unique` con i `NULL`: due lotti `GRANT` hanno entrambi `payment_id` nullo, e vanno bene, perché per PostgreSQL due `NULL` non sono uguali in un indice unico.

---

## Parte 5 — Terza scelta: le colonne del movimento

```
credit_movement: id, credit_lot_id, amount, reason, booking_id,
                 created_by, note,       ← solo ADJUSTMENT
                 created_at
```

### 5.1 Un `check` per motivo

Ogni motivo ha una forma precisa, e il database la può pretendere:

```sql
check (
       (reason = 'LOT_CREATED'     and amount > 0  and booking_id is null)
    or (reason = 'BOOKING'         and amount = -1 and booking_id is not null)
    or (reason = 'BOOKING_RELEASE' and amount = 1  and booking_id is not null)
    or (reason = 'ADJUSTMENT'      and amount <> 0 and booking_id is null
                                   and created_by is not null and note is not null and btrim(note) <> '')
)
```

più `created_by` e `note` vuoti per tutti gli altri motivi.

**La trappola del `NULL` in un `check`.** Un `check` rifiuta una riga solo se la condizione è **falsa**; se è **sconosciuta** (`NULL`), la accetta. `amount > 0` con `amount` nullo vale `NULL`, non `false`. Qui `amount` è `not null`, quindi va bene; ma alla parte 10 vedremo un caso dove la trappola scatta davvero, e va chiusa con un `is not null` esplicito.

### 5.2 Le regole "una volta sola", con indici unici parziali

| Indice | Regola |
|---|---|
| `(credit_lot_id) where reason = 'LOT_CREATED'` | un lotto nasce una volta |
| `(booking_id) where reason = 'BOOKING'` | una prenotazione impegna al massimo un credito |
| `(booking_id) where amount > 0 and booking_id is not null` | a una prenotazione si **restituisce** al massimo un credito |

La terza è la più preziosa. Al pezzo 3 il rifiuto del professionista e il lavoro programmato delle risposte scadute potrebbero arrivare quasi insieme: il lucchetto sulla prenotazione (D-046) li mette in fila, e il secondo trova lo stato già cambiato. Ma se un giorno un bug li lasciasse passare entrambi, il cliente si troverebbe un credito **regalato**. L'indice lo impedisce comunque, e vale anche per il passo 11 (`CANCELLATION_RESTORE` è anche lui positivo con una prenotazione): rifiuto *e* cancellazione per la stessa prenotazione non possono restituire due volte.

### 5.3 Cosa il database non controlla

Che il lotto del movimento `BOOKING` sia **dello stesso cliente, professionista e servizio** della prenotazione. Si potrebbe, con la tecnica di D-025 (copiare le tre colonne nel movimento e due foreign key composte, verso il lotto e verso la prenotazione), ma tre colonne duplicate in ogni movimento sono un prezzo alto per una regola che vive in **un solo** metodo Java (la scelta del lotto, pezzo 3). Lo scrivo come alternativa scartata.

---

## Parte 6 — Quarta scelta: il registro non si modifica, neanche con SQL

In Java è facile: l'entità `CreditMovement` non ha *setter*, e il repository non espone `delete`. Ma il brief vuole la regola **anche nel database**: un `update` scritto a mano in una console, uno script di correzione, un bug in una query nativa.

### 6.1 Strada A: i permessi

```sql
revoke update, delete, truncate on credit_movement from posture_studio;
```

Elegante: è il database stesso a dire "non puoi". **Ma da noi non funziona**, per due motivi:
1. l'applicazione si collega con `POSTGRES_USER`, e l'immagine ufficiale di PostgreSQL crea quell'utente come **superuser**. Un superuser **salta tutti i controlli dei permessi**;
2. anche se non lo fosse, è il **proprietario** delle tabelle (le crea Flyway con lo stesso utente), e il proprietario può ridarsi i permessi da solo con un `grant`.

I permessi funzionano con **due utenti**: uno proprietario dello schema, usato solo da Flyway, e uno per l'applicazione, con i soli permessi che le servono. È la configurazione giusta per la produzione, e la segno per il **passo 14**. Oggi sarebbe una modifica a Compose, al `.env`, alla configurazione di Flyway e dei test, per un beneficio che arriva solo in produzione.

### 6.2 Strada B: un trigger

Un *trigger* è una funzione che il database esegue da solo a ogni `insert`, `update` o `delete` su una tabella. Uno che si limita a rifiutare:

```sql
create function forbid_change() returns trigger language plpgsql as $$
begin
    raise exception '% is append-only: % is not allowed', tg_table_name, tg_op;
end $$;

create trigger credit_movement_no_update_delete_tg
    before update or delete on credit_movement
    for each row execute function forbid_change();

-- the row triggers above do not fire on truncate: it needs its own, per statement
create trigger credit_movement_no_truncate_tg
    before truncate on credit_movement
    for each statement execute function forbid_change();
```

Un trigger vale **anche per il superuser**. Il dettaglio di `truncate` è una domanda da colloquio: `truncate` non cancella le righe una per una, svuota la tabella, quindi i trigger "per riga" non scattano. Serve un trigger "per istruzione".

**Onestà sui limiti.** Un superuser può spegnere un trigger (`alter table ... disable trigger`) o tutti insieme (`set session_replication_role = replica`). Il trigger protegge dagli **errori**, non da un amministratore malintenzionato. Contro quello servono i permessi della strada A, con un'applicazione che non è superuser.

**Raccomandazione: B ora, A al passo 14** (nota nello stato). Le due insieme sono la difesa completa.

### 6.3 Il prezzo nei test

Alcuni test fanno commit veri (concorrenza, eventi dopo il commit) e poi puliscono con `delete from ...` (per esempio `BookingConcurrencyTest`). Con il trigger, `delete from credit_movement` fallisce, e con lui la pulizia di lotti, prenotazioni e clienti collegati.

Le possibilità:
- **lasciare le righe**: ma le prenotazioni e i professionisti collegati non si possono più cancellare (foreign key), e altri test che contano "tutti i professionisti attivi" ne vedrebbero di più;
- **pulire con `session_replication_role = replica`**, in un solo aiutante dei test (es. `TestDatabase.deleteCredits(...)`), dentro una transazione: spegne i trigger per quella transazione. Funziona perché l'utente di Testcontainers è superuser, ed è proprio la porta che la strada A chiuderà in produzione.

**Raccomandazione**: la seconda, in un solo punto, con un commento che spiega perché esiste. Attenzione: `replica` spegne anche i controlli delle foreign key (in PostgreSQL sono trigger anche loro), quindi l'aiutante cancella nell'ordine giusto comunque.

---

## Parte 7 — Quinta scelta: il saldo mai sotto zero

### 7.1 Perché il database da solo non basta

Un `check` vede **una riga**. "La somma dei movimenti di questo lotto è ≥ 0" riguarda **tante righe**: nessun `check` la può esprimere. E non basta controllare in Java prima di scrivere:

```
T1: somma del lotto 7 = 1  → ok, scrivo −1
T2: somma del lotto 7 = 1  → ok, scrivo −1     (T1 non ha ancora fatto commit)
T1: commit
T2: commit                 → saldo −1
```

È lo stesso problema del limite di prenotazioni (D-045): un controllo "leggi, poi scrivi" senza lucchetto lascia passare due transazioni contemporanee.

### 7.2 Quale lucchetto

Serve una cosa su cui **tutti quelli che tolgono crediti a un lotto** si mettano in fila. Candidati:
- **la riga del cliente** (D-045): la prendono già la richiesta del cliente e la prenotazione dal pannello. Ma la correzione del professionista (D-067, un −1) non passa da lì, e farle prendere un lucchetto sul cliente "perché sì" mescola due regole diverse;
- **la riga del lotto**: `select ... from credit_lot where id = ? for update`. È esattamente la cosa contesa. Chiunque tolga crediti a quel lotto aspetta lì, e nessun altro (due lotti diversi non si disturbano).

**Raccomandazione: il lucchetto sulla riga del lotto**, preso da **ogni** movimento negativo. I movimenti positivi (`LOT_CREATED`, `BOOKING_RELEASE`, una correzione +1) non ne hanno bisogno: aggiungere non può mai portare sotto zero.

**L'ordine dei lucchetti**, per non creare *deadlock*: sempre cliente → lotto (la richiesta prende il cliente, poi il lotto); la correzione prende solo il lotto; il rifiuto prende la prenotazione e aggiunge (+1), senza lotto. Nessuno prende il lotto e poi il cliente: nessun giro possibile.

### 7.3 Dove sta la regola

- **Solo in Java** (`CreditService.debit`: lucchetto sul lotto, somma, poi `insert`): chiara, con un errore leggibile (409). Ma un `insert` scritto a mano, o un secondo metodo che si dimentica il lucchetto, la scavalcano.
- **Un trigger che fa lo stesso** prima di ogni `insert` negativo:

```sql
create function credit_movement_keep_balance() returns trigger language plpgsql as $$
declare
    balance bigint;
begin
    if new.amount < 0 then
        -- the lot row is the lock: every debit of the same lot waits here
        perform 1 from credit_lot where id = new.credit_lot_id for update;
        select coalesce(sum(amount), 0) into balance
          from credit_movement where credit_lot_id = new.credit_lot_id;
        if balance + new.amount < 0 then
            raise exception 'credit lot % cannot go below zero', new.credit_lot_id
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end $$;
```

**Raccomandazione: tutte e due**, come per le sovrapposizioni (D-013): Java controlla per dare la risposta giusta al cliente, il database è l'ultima difesa. Il lucchetto è lo stesso: se Java l'ha già preso, il trigger lo trova già suo e non aspetta.

### 7.4 Il dettaglio che fa funzionare il trigger

Dopo l'attesa sul lucchetto, la `select sum(...)` vede il −1 che T1 ha appena confermato? **Sì, ma solo in `READ COMMITTED`**, il livello di isolamento di default che usiamo (D-045). In quel livello ogni istruzione dentro una funzione `volatile` (i trigger lo sono) prende una **fotografia nuova** del database: la somma, eseguita dopo il lucchetto, vede il commit di T1.

In `REPEATABLE READ` la fotografia è una sola per tutta la transazione, presa prima dell'attesa: la somma **non** vedrebbe il movimento di T1, e il saldo potrebbe andare sotto zero lo stesso. È la ragione per cui D-045 ha scritto "isolamento di default, necessario": la stessa frase vale qui. Il test di concorrenza (parte 13) lo dimostra togliendo il `for update`.

### 7.5 Il lucchetto sul cliente basta già per le prenotazioni?

Il brief chiede di verificarlo al pezzo 3. Anticipo il ragionamento: due richieste dello stesso cliente si mettono già in fila sul cliente (D-045), e la seconda, dopo l'attesa, cerca il lotto con una query nuova che vede il −1 della prima. Quindi **sì, per le prenotazioni basterebbe**; il lucchetto sul lotto serve per la correzione e come regola unica per tutti. Il test di concorrenza del pezzo 3 lo proverà.

---

## Parte 8 — Sesta scelta: la scadenza

### 8.1 Che cosa vuol dire "valido 90 giorni" e "scade il 15 marzo"

Per una persona, "scade il 15 marzo" vuol dire: **il 15 marzo si può ancora usare, il 16 no**. Il giorno è quello del calendario di Roma, non di Greenwich. Propongo una regola sola:

> Un lotto ha un **ultimo giorno valido**, un giorno del calendario di `Europe/Rome`. Si può usare in un istante *t* se il giorno di *t*, a Roma, non è dopo l'ultimo giorno valido.
> "Valido N giorni" vuol dire: ultimo giorno = il giorno dell'acquisto, a Roma, più N giorni.

Esempio: pacchetto da 90 giorni comprato il 10 novembre 2026 alle 18 → ultimo giorno l'**8 febbraio 2027**. L'8 febbraio alle 23:59 si usa, il 9 febbraio alle 00:01 no.

### 8.2 Come si salva: due strade

**(A) Un istante**, `expires_at timestamptz`: il primo istante in cui il lotto **non** vale più, cioè la mezzanotte di Roma del giorno dopo (9 febbraio 00:00 a Roma = 8 febbraio 23:00 UTC). Coerente con la convenzione del progetto (istanti in UTC); la query è `expires_at > :now`. Ma per mostrare "scade l'8 febbraio" bisogna convertire e togliere un giorno, e una proroga "al 30 aprile" va convertita in un istante. Ogni conversione è un'occasione di errore.

**(B) Una data**, `valid_until date`: l'**ultimo giorno valido**, come lo dice una persona. La query è `valid_until >= :oggiARoma`, con *oggi* calcolato in Java dal `Clock` e dalla zona dello studio. Nessuna mezzanotte da calcolare nel database, e nessun cambio dell'ora da sbagliare: una data non ha ore.

C'è già un precedente: `working_schedule.valid_from` è una `date` locale (D-034), per la stessa ragione (le versioni dello schema cominciano "dal 1° giugno", non da un istante).

**Raccomandazione: (B).** Attenzione: la convenzione di CLAUDE.md dice "nel database in UTC (`timestamptz`)", con un'eccezione per gli orari settimanali. Con (B) l'eccezione si allarga ai **giorni di calendario** (`valid_from`, `valid_until`), e va scritta nella scheda: una data che vuol dire "un giorno di calendario a Roma" è un fatto diverso da un istante.

### 8.3 La regola in Java, in un posto solo

```java
// a calendar day in the studio's zone: the last day the credits can be used
public record ValidUntil(LocalDate lastDay) {

    public static ValidUntil daysAfter(Instant purchase, int days, ZoneId zone) {
        return new ValidUntil(purchase.atZone(zone).toLocalDate().plusDays(days));
    }

    public boolean covers(Instant moment, ZoneId zone) {
        return !moment.atZone(zone).toLocalDate().isAfter(lastDay);
    }
}
```

`plusDays` su una `LocalDate` conta **giorni di calendario**, che è quello che vuole la persona. Un `record`, perché è un valore e non ha identità (come `Email`, `TimeRange`).

### 8.4 Le tre trappole, ognuna con il suo test

1. **Mezzanotte in UTC.** Acquisto l'11 novembre alle 00:30 a Roma = 10 novembre 23:30 UTC. Se il giorno dell'acquisto si prende in UTC, è il **10**: un giorno di validità perso. Il giorno va preso **a Roma** (`atZone(zone)`). È la stessa trappola di mezzanotte già vista nel calcolo degli orari liberi (passo 6).
2. **"N volte 24 ore".** Acquisto il 1° ottobre 2026 alle 00:30 a Roma (ora legale, = 30 settembre 22:30 UTC), validità 30 giorni. Con `instant.plus(30 × 24 ore)` si arriva al 30 ottobre 22:30 UTC, che a Roma (ora solare, dal 25 ottobre) è il **30 ottobre** alle 23:30. Con `LocalDate.plusDays(30)` l'ultimo giorno è il **31 ottobre**. Il 25 ottobre dura 25 ore: contare ore invece di giorni perde un giorno.
3. **L'ultimo istante del giorno, nei giorni del cambio.** Ultimo giorno 29 marzo 2026 (giorno da 23 ore, ora legale dalle 2): vale fino alle 21:59:59 UTC, non alle 22:59:59. Ultimo giorno 25 ottobre 2026 (25 ore): vale fino alle 22:59:59 UTC. Chi scrivesse "fine giorno = 23:00 UTC" sbaglierebbe uno dei due. Con la data non c'è niente da scrivere: `covers` converte l'istante e confronta i giorni.

### 8.5 Le altre scadenze, sulla stessa regola

- **Omaggio** (D-067): ultimo giorno scelto dal professionista (non nel passato), oppure `daysAfter(adesso, single_credit_validity_days)`;
- **cancellazione in tempo** (passo 11): `daysAfter(adesso, single_credit_validity_days)`.

La domanda del pezzo 3 ("un credito che scade prima della seduta si può usare?") diventa una scelta tra `covers(adesso)` e `covers(inizio della seduta)`: la regola resta una, cambia l'istante che le si dà.

---

## Parte 9 — Settima scelta: la proroga nello schema

D-067: la proroga è l'unica concessione che **modifica** (la scadenza di un lotto), e la sua storia non deve perdersi.

### 9.1 Due strade

**(A) Colonna modificabile + tabella della storia.** `credit_lot.valid_until` cambia; nella stessa transazione si scrive una riga in `credit_lot_extension` (lotto, vecchia data, nuova data, chi, perché, quando). La query "lotti utilizzabili" resta semplice: legge `valid_until`.

**(B) Tutto a sola aggiunta.** `credit_lot.valid_until` non cambia mai; la scadenza attuale è l'ultima proroga, se c'è, altrimenti quella iniziale. Purissimo, ma ogni query sui lotti utilizzabili (anche la scelta del lotto del pezzo 3) ha bisogno di una sotto-query per trovare la scadenza vera.

**Raccomandazione: (A)**, con due difese nel database:
- `credit_lot_extension` è a sola aggiunta (lo stesso trigger della parte 6);
- un trigger su `credit_lot` permette di cambiare **solo** `valid_until`, e **solo** se esiste la riga della proroga con le stesse due date. Così "la scadenza cambia solo con la sua traccia" vale anche per un `update` scritto a mano. Con JPA l'ordine è giusto da solo: la riga della proroga, con l'id generato dal database, si scrive subito al `persist`, prima dell'`update` del lotto.

### 9.2 Solo in avanti?

Il brief lascia la scelta al pezzo 5, ma lo schema la deve sapere adesso. Proposta: **solo in avanti** (`check (new_valid_until > old_valid_until)`). Anticipare una scadenza toglie al cliente giorni che aveva comprato, in silenzio; se il professionista ha sbagliato una data appena scritta, il caso vero è "ho dato troppi giorni", e un lotto con troppi giorni non danneggia il cliente. Se serve togliere crediti, c'è la correzione, che lascia la sua traccia. Se un giorno servirà anticipare, si allarga il `check` con una migrazione.

Una proroga si può fare anche su un lotto **già scaduto** (è il caso tipico: "mi sono scaduti due crediti, me li rimetti?"). Funziona da sola, perché la scadenza non ha mai scritto movimenti (parte 2).

---

## Parte 10 — Ottava scelta: `payment` per i pacchetti

### 10.1 Le colonne nuove (D-060)

- `purpose` (`BOOKING`, `PACKAGE`): lo scopo;
- `package_offer_id`: il pacchetto comprato;
- `customer_id`: chi lo compra. Per una seduta si ricava dalla prenotazione: **solo per i pacchetti**, così non c'è un doppione che può non essere d'accordo;
- `booking_id` diventa facoltativo.

Niente `credit_lot_id`: il collegamento va dal lotto al pagamento (4.5).

### 10.2 La fotografia va presa alla creazione della pagina, non all'avviso

Il pacchetto **si modifica** (parte 1). Il cliente apre la pagina di Stripe con "5 sedute, 120 €, 90 giorni"; mentre paga, il professionista cambia il pacchetto in "4 sedute". All'avviso il cliente ha pagato 120 € per **5** sedute: è quello che ha visto e accettato.

Quindi il pagamento fotografa, **quando nasce**, tutto quello che il lotto dovrà avere:
- `amount_cents` (c'è già): il prezzo;
- `package_credits`: le sedute;
- `package_validity_days`: la validità.

All'avviso il lotto nasce **dalla fotografia del pagamento**, non dal pacchetto in vendita. Così il caso del brief "pacchetto disattivato tra il pagamento e l'avviso" non è nemmeno un caso: il pacchetto non viene riletto.

### 10.3 Il `check` che tiene coerenti scopo e colonne, e la trappola del `NULL`

```sql
check (
       (purpose = 'BOOKING' and booking_id is not null
            and package_offer_id is null and customer_id is null
            and package_credits is null and package_validity_days is null)
    or (purpose = 'PACKAGE' and booking_id is null
            and package_offer_id is not null and customer_id is not null
            and package_credits is not null and package_credits >= 2
            and package_validity_days is not null and package_validity_days > 0
            and source = 'REQUEST' and status in ('PENDING', 'SUCCEEDED', 'EXPIRED'))
)
```

Ecco la trappola annunciata in 5.1. Se si scrivesse solo `package_credits >= 2`, un pagamento di un pacchetto con `package_credits` nullo darebbe: `NULL >= 2` → `NULL`; tutto il ramo `PACKAGE` → `NULL`; il ramo `BOOKING` → `false`; `false or NULL` → `NULL`; e un `check` che vale `NULL` **accetta la riga**. Per questo ogni colonna che deve esserci ha il suo `is not null`. Un test lo prova (parte 13).

Le ultime due righe dicono cose vere per costruzione:
- un pacchetto nasce solo dalla richiesta del cliente: `source = 'REQUEST'` (il link è delle sedute, D-065);
- con l'incasso **immediato** (D-031) un pacchetto passa da `PENDING` a `SUCCEEDED`, o scade: mai `AUTHORIZED` né `CANCELED`. Di conseguenza `next_release_at` (V13) non lo toccherà mai.

### 10.4 `purpose` contro `source`

Si potrebbero fondere in una colonna sola (`BOOKING_REQUEST`, `BOOKING_LINK`, `PACKAGE`). Più compatto, ma vorrebbe dire riscrivere il codice e i test del passo 9 che leggono `source`. **Raccomandazione**: due colonne, legate dal `check` sopra.

---

## Parte 11 — `booking`: `paid_with = CREDIT`

Come previsto da D-042 e dallo stato ("passi 10 e 11, schema delle prenotazioni"): `drop constraint` e `add constraint` con il valore nuovo, e `CREDIT` nell'`enum` `PaidWith`.

Più una regola nuova, vera per costruzione (architettura, "Prenotazione con credito": la richiesta va subito in attesa di approvazione):

```sql
-- a booking paid with a credit never waits for a payment
check (paid_with <> 'CREDIT' or status <> 'PENDING_PAYMENT')
```

`CREDIT` vale per entrambi `booked_by`: il cliente lo usa dalla richiesta (pezzo 3), il professionista dal pannello (pezzo 4).

Che una prenotazione `CREDIT` abbia davvero il suo movimento `BOOKING` è una regola tra due tabelle: la tiene Java, nella stessa transazione (pezzo 3).

---

## Parte 12 — La migrazione intera

`V15__create_credit_lots_and_movements.sql`, in quest'ordine (le tabelle prima dei trigger che le usano):

1. `booking`: `paid_with` con `CREDIT`, `check` "credito senza attesa di pagamento";
2. `payment`: le quattro colonne nuove e le due della fotografia, `booking_id` facoltativo, il `check` di 10.3, la foreign key verso `package_offer` e `customer`, un indice su `customer_id`;
3. `credit_lot`: colonne della parte 4, foreign key composta verso `service`, `unique (payment_id)`, `check` di origine e traccia; indice `(customer_id, practitioner_id, service_id, valid_until)` per la scelta del lotto del pezzo 3, e `(practitioner_id)` per le liste del pannello;
4. `credit_movement`: colonne della parte 5, il `check` per motivo, i tre indici unici parziali, un indice su `credit_lot_id` (la somma);
5. `credit_lot_extension`: lotto, vecchia e nuova data (`new > old`), `extended_by`, `note`, `created_at`;
6. le funzioni e i trigger: a sola aggiunta per i movimenti e le proroghe (riga e `truncate`), saldo mai negativo, "solo la scadenza, solo con la sua traccia" per il lotto.

Nessun dato da spostare: le tabelle sono nuove, e le righe esistenti di `payment` diventano `BOOKING` con un default che poi si toglie (così Java dovrà dire sempre lo scopo, come per `source`).

`created_at` di lotti, movimenti e proroghe lo scrive Java con il `Clock`, come `booking` e `payment`: è il "quando" di D-067, e nei test l'orologio si sposta.

---

## Parte 13 — I test

| Test | Cosa prova |
|---|---|
| `ValidUntilTest` (unitario) | Le tre trappole di 8.4: mezzanotte a Roma, 30 giorni attraverso il 25 ottobre, l'ultimo istante del 29 marzo e del 25 ottobre; "valido N giorni" sul calcolo dell'esempio (10 novembre + 90 = 8 febbraio) |
| `CreditSchemaTest` (con database, SQL diretto) | Ogni `check` del movimento (motivo per motivo) e del lotto (origine e traccia); servizio di un altro professionista rifiutato (foreign key composta); i tre indici "una volta sola"; un secondo lotto per lo stesso pagamento rifiutato |
| `CreditLedgerImmutabilityTest` (SQL diretto) | `update`, `delete` e `truncate` su `credit_movement` e `credit_lot_extension` rifiutati; `update` del lotto su una colonna diversa dalla scadenza rifiutato; proroga senza la sua riga rifiutata, con la riga accettata; proroga all'indietro rifiutata. È il criterio "provato anche con SQL diretto" del brief |
| `CreditBalanceTest` (SQL diretto) | −1 su un lotto a zero rifiutato dal trigger; −2 con saldo 1 rifiutato; +1 sempre accettato |
| `CreditBalanceConcurrencyTest` (commit veri) | Due transazioni tolgono l'ultimo credito dello stesso lotto nello stesso momento: una passa, l'altra è rifiutata. **Tolto il `for update` dal trigger, passano entrambe**: il test sa fallire |
| `PaymentSchemaTest` (aggiunte) | Pagamento `PACKAGE` con `package_credits` nullo rifiutato (la trappola del `NULL`); `PACKAGE` con `booking_id` rifiutato; `PACKAGE` `AUTHORIZED` rifiutato; le righe esistenti sono `BOOKING` |
| `BookingSchemaTest` (aggiunte) | `CREDIT` accettato per entrambi `booked_by`; `CREDIT` in `PENDING_PAYMENT` rifiutato |

Per il test di concorrenza: due thread, ognuno con la sua transazione (`TransactionTemplate`), un `CountDownLatch` per farli partire insieme, come `BookingConcurrencyTest`; la pulizia con l'aiutante della parte 6.3.

**Le difese tolte a turno**, come al passo 9: il `for update` del trigger, il trigger di `truncate`, l'`is not null` di `package_credits`, l'indice "una sola restituzione", `unique (payment_id)`. Ognuna deve far fallire il suo test.

---

## Parte 14 — Cosa c'è da decidere

| Scelta | Raccomandazione | Dove |
|---|---|---|
| Nomi | Origini `PACKAGE`, `GRANT`, `CANCELLATION` (passo 11); motivi `LOT_CREATED`, `BOOKING`, `BOOKING_RELEASE`, `ADJUSTMENT`, `CANCELLATION_RESTORE` (passo 11). Nei `check` solo quelli del passo 10 | parte 3 |
| Lotto | Foreign key composta verso `service`; niente `initial_credits`; `price_cents` sì; traccia dell'omaggio nel lotto con un `check`; `payment_id` unico nel lotto | parte 4 |
| Movimento | Un `check` per motivo; traccia della correzione nel movimento; tre indici "una volta sola" | parte 5 |
| Registro immutabile | Trigger (riga e `truncate`) ora; permessi con un utente non superuser al passo 14; pulizia dei test con `session_replication_role` in un solo aiutante | parte 6 |
| Saldo ≥ 0 | Lucchetto sulla riga del lotto per ogni movimento negativo; regola in Java **e** in un trigger; vale in `READ COMMITTED` | parte 7 |
| Scadenza | `valid_until date`, ultimo giorno valido a Roma; "N giorni" = giorno dell'acquisto a Roma + N; un `record ValidUntil`; eccezione alla convenzione scritta nella scheda | parte 8 |
| Proroga | Colonna modificabile + `credit_lot_extension`; il lotto cambia solo la scadenza e solo con la sua riga; **solo in avanti** | parte 9 |
| Pagamenti dei pacchetti | `purpose`, `package_offer_id`, `customer_id` (solo pacchetti), `package_credits`, `package_validity_days` fotografati alla creazione; `check` con gli `is not null`; `purpose` e `source` separati | parte 10 |
| `booking` | `CREDIT` per entrambi; mai `PENDING_PAYMENT` | parte 11 |

Dopo le tue scelte: la V15, il modulo `credit` con le entità e `ValidUntil`, una scheda nuova in `decisioni.md` (D-069), `modello-dati.md` aggiornato con le tabelle vere (e lo scostamento da `initial_credits` e `payment.credit_lot_id`), e la nota per il passo 14 sui permessi.

---

## Domande da colloquio

### Domanda 1: Perché un registro di movimenti invece di una colonna con il saldo?

**Risposta modello.** Perché il registro non perde niente: ogni numero ha la sua spiegazione, un errore si corregge con un movimento che lo compensa invece di sovrascriverlo, e il saldo si ricostruisce sempre. Una colonna aggiornata perde la storia ed è fragile con gli aggiornamenti concorrenti. Il prezzo è una somma a ogni lettura, irrilevante a questi volumi; se servisse, aggiungerei un saldo salvato come cache, ma la verità resterebbe il registro.

### Domanda 2: Come impedisci `update` e `delete` su una tabella anche a chi scrive SQL a mano?

**Risposta modello.** Con i permessi, se l'applicazione usa un utente che non è proprietario delle tabelle né superuser: si revocano `update`, `delete` e `truncate`. Con un trigger `before update or delete` che lancia un errore, che vale anche per il superuser, più un trigger per istruzione su `truncate`, perché i trigger per riga non scattano. Il trigger protegge dagli errori, non da un amministratore che lo spegne: la difesa completa è avere tutte e due.

### Domanda 3: Una regola dice "la somma di queste righe non può essere negativa". Come la fai rispettare sotto concorrenza?

**Risposta modello.** Un `check` vede una riga sola, e un controllo "leggi e poi scrivi" lascia passare due transazioni contemporanee. Serve una cosa su cui mettersi in fila: blocco la riga padre, qui il lotto, con `select ... for update`, poi sommo e scrivo. Lo faccio in Java per dare un errore chiaro, e in un trigger come ultima difesa. Funziona in `READ COMMITTED`, perché dopo l'attesa la somma prende una fotografia nuova e vede il commit dell'altra transazione; in `REPEATABLE READ` no.

### Domanda 4: Come salvi e controlli una scadenza come "valido 90 giorni"?

**Risposta modello.** Prima la definisco: l'ultimo giorno valido è il giorno dell'acquisto nel fuso dello studio più 90 giorni di calendario, e il credito vale fino alla fine di quel giorno. La salvo come data, non come istante, perché è un giorno di calendario e così non c'è una mezzanotte da calcolare. Le trappole sono tre: prendere il giorno in UTC invece che a Roma, contare 24 ore invece di giorni (il giorno del cambio dell'ora ne ha 23 o 25), e fissare la fine del giorno a un'ora UTC. Ognuna ha il suo test.

### Domanda 5: Un webhook che crea qualcosa può arrivare due volte. Cosa impedisce di creare due volte?

**Risposta modello.** Due livelli: l'elaborazione è idempotente, con la tabella degli eventi visti e un confronta-e-cambia sullo stato del pagamento; e un vincolo nel database rende impossibile il doppione, qui un `unique` sul pagamento che ha generato il lotto. Ho messo il collegamento dalla parte del lotto apposta: con il collegamento dal pagamento al lotto, un secondo lotto si creerebbe comunque e resterebbe orfano.

### Domanda 6: Cosa succede in un `check` quando una colonna è `NULL`?

**Risposta modello.** Un `check` rifiuta solo se la condizione è falsa; se è sconosciuta, cioè `NULL`, accetta. Quindi `quantita >= 2` non rifiuta una quantità nulla. Nei `check` con più rami, per esempio "se è un pacchetto allora queste colonne ci sono", ogni colonna obbligatoria va scritta con il suo `is not null`, e lo provo con un test che inserisce proprio il `NULL`.

---

## Documentazione ufficiale

- PostgreSQL, trigger (`before`, per riga e per istruzione, `truncate`): https://www.postgresql.org/docs/17/sql-createtrigger.html
- PostgreSQL, funzioni `volatile` e fotografie dentro una funzione: https://www.postgresql.org/docs/17/xfunc-volatility.html
- PostgreSQL, `select ... for update`: https://www.postgresql.org/docs/17/sql-select.html#SQL-FOR-UPDATE-SHARE
- PostgreSQL, `check` e i valori nulli: https://www.postgresql.org/docs/17/ddl-constraints.html#DDL-CONSTRAINTS-CHECK-CONSTRAINTS
- PostgreSQL, `session_replication_role`: https://www.postgresql.org/docs/17/runtime-config-client.html#GUC-SESSION-REPLICATION-ROLE
- Immagine Docker di PostgreSQL, `POSTGRES_USER` (creato come superuser): https://hub.docker.com/_/postgres
- Java, `LocalDate.plusDays` e `ZonedDateTime`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/LocalDate.html
