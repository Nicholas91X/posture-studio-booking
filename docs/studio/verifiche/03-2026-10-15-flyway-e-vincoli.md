# Verifica 03 — Flyway e vincoli

2026-10-15 · modalità: argomento indicato ("flyway e vincoli") · guida di riferimento: [04](../04-flyway-e-migrazione-v1.md)

## Esito in breve

| # | Domanda | Formato | Sicurezza | Esito |
|---|---|---|---|---|
| 1 | V1 modificata dopo la condivisione (ripasso PD-07) | Cosa succede se | 2 · 2 · 2 | ✅ · 🟡 · ✅ |
| 2 | Tre universi per l'email di Mario | Confronto | 2 | 🟡 (A) · ❌ (B) · ❌ (C) · ✅ (ordine) |
| 3 | Sei il vincolo `check` senza `not null` | Punto di vista di una cosa | 1 · 2 | ✅ · ❌ |
| 4 | La migrazione dei prestiti | Trova l'errore | 2 · 1 · 1 | 🟡 · 🟡 · ✅ |
| 5 | "Non è duplicazione inutile?" | Colloquio simulato | — · 2 | 🟡 · 🟡 |

---

## Domanda 1 — V1 modificata dopo la condivisione (cosa succede se, ripasso PD-07)

**Domanda.** V1 è committata, pubblicata e già applicata anche sul database di Giulia. Due giorni dopo Nicholas cambia direttamente in V1 il default di `slot_step_minutes` da 30 a 15. (1) Cosa succede al riavvio sul suo computer, chi se ne accorge e come? (2) Nicholas fa `down -v` e riparte: ha risolto? Cosa succede a Giulia al prossimo *pull*, e nel test con Testcontainers? (3) Qual era la strada giusta?

**Sicurezza:** 2 · 2 · 2

**Risposta di Nicholas:**
> 1. - 2 Si verifica l'errore Migration checksum mismatch poichè i checksum della migrazione applicata non corrisponde a quello della migrazione dopo la modifica di slot_step_minutes. Se ne accorge Nicholas poichè l'applicazione non parte.
>
> 2. - 2 Non ha risolto poichè adesso si trova disallineato alle versioni delle persone con cui ha condiviso la migrazione V1 prima della modifca slot_step_minutes. Al prossimo pull Giulia avrà il codice della migrazione V1 diverso rispetto a quello della sua che è già stata implementata, quindi direi Migration checksum mismatch anche per lei. Nel test con Testcontainers non ho idea di ciò che succede.
>
> 3. - 2 La strada giusta era quella di creare una nuova migrazione per la modifica poichè una volta condivio il codice di una migrazione questo non deve più essere modificato.

**Correzione.**

✅ **1.** `Migration checksum mismatch`, e l'applicazione non parte. Precisazione sull'attore (PD-01): chi se ne accorge è **Flyway**, all'avvio, quando confronta il checksum registrato in `flyway_schema_history` con quello ricalcolato dal file. Nicholas lo scopre leggendo l'errore.

🟡 **2.** ✅ Non ha risolto, e a Giulia succede esattamente quello che dici, con una precisazione: non al *pull*, ma al **primo avvio dopo il pull**, quando Flyway valida. ❌ Testcontainers (non risposto): il database dei test è **nuovo** a ogni esecuzione, quindi Flyway applica la V1 **modificata** senza nulla da confrontare, e **il test passa**, con default 15. È la parte insidiosa, la stessa di `update` nella verifica 01 (D5): i test sono verdi mentre il database di Giulia (e quello di produzione, se V1 ci fosse già) è fermo. Ogni database ha la sua storia.

| Database | Cosa contiene V1 | Cosa succede |
|---|---|---|
| Nicholas, dopo `down -v` | Versione nuova (15) | Funziona |
| Giulia | Versione vecchia (30) | `checksum mismatch` al primo avvio |
| Test (Testcontainers) | Versione nuova (15) | Verde: il problema non si vede |
| Produzione (se V1 era già lì) | Versione vecchia | Il rilascio non parte |

✅ **3.** Una V2. Un dettaglio in più, da colloquio: cambiare il **default** con una V2 (`alter table ... alter column ... set default ...`) vale solo per le righe **future**. I professionisti già inseriti restano con 30. Se anche loro devono passare a 15, la stessa V2 deve contenere un `update`: è una decisione sui **dati**, non solo sullo schema.

**Da ripassare:** guida 04, 1.4; verifica 01, D5.

---

## Domanda 2 — Tre universi per l'email di Mario (confronto)

**Domanda.** Tre universi: A (`check` minuscolo + `unique`), B (indice unico su `lower(email)`), C (`citext`). In tutti, Java alla registrazione dimentica di convertire in minuscolo; al login converte e cerca con una semplice uguaglianza (`email = ?`). Mario si registra come `Mario.Rossi@Gmail.com` e una settimana dopo fa login con `mario.rossi@gmail.com`. Per ciascun universo: cosa succede alla registrazione e al login? In quale il bug si scopre prima, in quale resta nascosto più a lungo?

**Sicurezza:** 2

**Risposta di Nicholas:**
> 1. - 2 check: alla registrazione l'insert fallisce, alla login visto che la registrazione impone minuscole, non dovrebbero esserci problemi a patto di inserire la mail sempe in minuscolo
> lower(email): la funzione che converte in minuscolo è legata alla colonna quindi in registrazione sono ammessi anche lettere maiuscole. Quindi alla login funzionerà tutto ma nella colonna a databse potremmo avere valori in maiuscolo e in minuscolo
> citext: onestamente non l'ho capito. Non ho capito a che punto interviene
>
> Credo che il primo a scoprire il bug sia il check, mentre l'utlimo il citext

**Correzione.**

La chiave della domanda è **in quale momento interviene ciascuna strada**:

| Strada | Quando interviene | Su cosa |
|---|---|---|
| A, `check` | Quando si **scrive** (`insert`, `update`) | Rifiuta il valore se non è minuscolo |
| B, indice su `lower()` | Quando si **scrive** | Controlla solo l'**unicità**, sulla versione minuscola. Non cambia il valore salvato, e **non cambia come funziona `=`** |
| C, `citext` | Quando si **confronta** (`=`, `unique`, `where`) | È il **tipo** della colonna: l'operatore `=` stesso ignora maiuscole e minuscole |

🟡 **A.** ✅ Alla registrazione l'`insert` fallisce. Ma allora al login **Mario non esiste**: la registrazione non è mai andata a buon fine. Il bug si vede **subito**, alla prima registrazione con una maiuscola: è il *fail fast*.

❌ **B.** ✅ Alla registrazione passa, e salva `Mario.Rossi@Gmail.com`. ❌ Al login **non funziona**: la ricerca è `email = 'mario.rossi@gmail.com'`, che confronta le stringhe esatte, e la stringa salvata è diversa. L'indice su `lower(email)` serve solo a impedire i duplicati; per essere usato, la query dovrebbe scrivere `lower(email) = ...` (guida 04, 3.3). Peggio: con D-014 il login o la prima prenotazione possono **creare l'account** se l'utente non viene trovato, e il nuovo `insert` di `mario.rossi@gmail.com` viene rifiutato dall'indice unico, perché in minuscolo è un duplicato. Mario non riesce né a entrare né a registrarsi: il bug esplode **una settimana dopo**, in un punto lontano dalla causa.

❌ **C.** (non capito) Alla registrazione passa, e salva `Mario.Rossi@Gmail.com` com'è arrivata. Al login `email = 'mario.rossi@gmail.com'` è **vero**, perché con `citext` l'uguaglianza ignora maiuscole e minuscole. **Funziona tutto.** Il bug c'è (il valore salvato non è minuscolo, requisito b), ma non dà mai fastidio: resta nascosto indefinitamente.

✅ **Ordine.** Giusto: A lo scopre per primo (alla registrazione), C per ultimo (mai, in pratica). B sta in mezzo, ed è il caso peggiore: il bug si manifesta tardi e lontano dalla causa.

**Collegamento:** A è lo stesso principio della password senza default (passo 4, errore 3): meglio un errore immediato nel posto giusto che un dato sbagliato che esplode altrove.

**Da ripassare:** guida 04, 3.2–3.4.

---

## Domanda 3 — Sei il vincolo `check` senza `not null` (punto di vista di una cosa)

**Domanda.** Sei `check (cancellation_notice_hours > 0)` su `practitioner`; la colonna ha `default 24` ma non `not null`. Quattro `insert`: (a) `-5`; (b) la colonna non è nominata; (c) `NULL` esplicito; (d) `0`. Per ciascuno: salvata o rifiutata, e quale valore finisce nella colonna? Cosa cambia aggiungendo `not null`?

**Sicurezza:** 1 (senza `not null`) · 2 (con `not null`)

**Risposta di Nicholas:**
> Senza Not Null: - 1
> a) rifiutata
> b) salvata
> c) salvata
> d) rifiutata
>
> Con Not Null: - 2
> Tutte rifiutate

**Correzione.**

✅ **Senza `not null`: tutte e quattro giuste**, con sicurezza 1. Hai anche evitato la trappola della (c). Mancano i valori finali, che la domanda chiedeva, e il perché della (c):
- (a) rifiutata dal `check`: `-5 > 0` è falso;
- (b) salvata con **24**: la colonna non è nominata, quindi il database usa il **default**, e `24 > 0` è vero;
- (c) salvata con **`NULL`**. Perché: in SQL `NULL > 0` non è né vero né falso, è *sconosciuto*. E un `check` rifiuta una riga solo se la condizione è **falsa**: con "sconosciuto" la lascia passare. Inoltre il default **non** interviene, perché la colonna è stata nominata (con `NULL`): il default vale solo quando la colonna manca dall'`insert`. Verificato sul nostro PostgreSQL;
- (d) rifiutata: `0 > 0` è falso.

❌ **Con `not null`: non tutte rifiutate.** Cambia **solo la (c)**:

| `insert` | Senza `not null` | Con `not null` | Chi decide |
|---|---|---|---|
| (a) `-5` | ❌ rifiutata | ❌ rifiutata | `check` |
| (b) colonna non nominata | ✅ salvata con 24 | ✅ **salvata con 24** | il default riempie la colonna **prima** dei controlli: `not null` vede 24, non un vuoto |
| (c) `NULL` esplicito | ✅ salvata con `NULL` | ❌ **rifiutata** | `not null` |
| (d) `0` | ❌ rifiutata | ❌ rifiutata | `check` |

L'errore sulla (b) nasce dall'ordine: prima il database **completa** la riga con i default, poi controlla `not null` e `check`. È la coppia della guida 04 (2.7): il default dà il valore sensato quando manca, `check` rifiuta quelli assurdi, e `not null` chiude il buco del `NULL`, che `check` da solo lascia passare.

**Da ripassare:** guida 04, 2.3, 2.5, 2.7.

---

## Domanda 4 — La migrazione dei prestiti (trova l'errore)

**Domanda.** Nella biblioteca della guida, un socio può avere molti prestiti nel tempo. File `V2_add_loans.sql`:

```sql
create table loan (
    id          bigint generated by default as identity primary key,
    member_id   bigint references library_member (id),
    due_days    int default 14 check (due_days > 0),
    created_at  timestamp default now(),
    unique (member_id)
);
```

(1) Trova tutti gli errori. (2) Ordinali per gravità. (3) Quale non produce nessun messaggio d'errore, e cosa vede il collega all'avvio?

**Sicurezza:** 2 · 1 · 1

**Risposta di Nicholas:**
> 1. - 2
>
> 1) al nome del file manca il doppio trattino basso dopo V2
> 2) sarebbe meglio utilizzare generated always as identity per "id". Inoltre il fatto di essere primary key dovrebbe essere dichiato nei constraint utilizzando anche un mome per il vincolo
> 3) stesso discorso per member_id che dovrebbe innanzitutto essere reso not null. Dovrebbe essere creato un constraint con nome del vincolo, la foreign key member_id e la sua referenza (library_member (id)). Infine sempre con un constraint sarebbe da assegnare un nome al vincolo unique.
> 4) il check di due_days sarebbere da rendere con un constraint e not null
>
> 2. - 1 Forse quello che fa più danni è il nome del SQL file sbagliato. Il resto non saprei
>
> 3. - 1 Penso che su tratti ancora del nome del SQL file sbagliato. La migrazione viene semplicemnte ignorata

**Correzione.**

🟡 **1.** Trovati: nome del file ✅, identity `by default` → `always` ✅, `member_id` senza `not null` ✅, `due_days` senza `not null` ✅ (collegamento diretto con la D3: senza `not null`, il `check` lascia passare `NULL`), vincoli senza nome ✅. Scrivere la chiave primaria accanto alla colonna non è un errore in sé: il vantaggio dei vincoli in fondo è il **nome**.

Mancano due errori, e il primo è il più importante:
- ❌ **`unique (member_id)`**: l'hai tenuto, chiedendo solo di dargli un nome. Ma `unique` sulla chiave esterna vuol dire "**al massimo un** prestito per socio" (guida 04, 2.4): il secondo prestito di qualsiasi socio verrebbe rifiutato. Va tolto. Era la trappola: lo schema "al massimo uno" usato dove la regola è "molti".
- ❌ **`created_at timestamp`**: va `timestamptz` (un istante preciso, in UTC: convenzione del progetto) e `not null`. Con `timestamp` senza fuso si salva un'ora "da orologio da muro" senza sapere di quale fuso.

Bonus: una volta tolto `unique`, `member_id` resta **senza indice** (PostgreSQL non lo crea per le chiavi esterne, guida 04, 2.6). "Tutti i prestiti del socio X" è proprio la ricerca più frequente: l'indice va creato.

🟡 **2.** Il nome del file è grave, ma **si scopre presto**. L'ordine, ragionato su "quanto lontano arriva il danno":

| # | Errore | Danno |
|---|---|---|
| 1 | `unique (member_id)` | Regola del dominio sbagliata: arriva fino agli utenti, al secondo prestito |
| 2 | `V2_` con un solo trattino | La tabella non esiste; con `validate` e un'entità `Loan`, l'applicazione non parte (vedi punto 3) |
| 3 | `member_id` senza `not null` | Prestiti senza socio |
| 4 | `due_days` senza `not null` | `NULL` passa il `check` (D3) |
| 5 | `timestamp` invece di `timestamptz`, senza `not null` | Ore ambigue, possibili `NULL` |
| 6 | Identity `by default` | Si possono inserire id a mano, in conflitto con quelli generati |
| 7 | Vincoli senza nome | Messaggi poco chiari, manutenzione più difficile |

✅ **3.** Giusto: Flyway **ignora in silenzio** un file con un nome che non riconosce. Verificato sul nostro backend: con solo quel file, il log dice `No migrations found`. Con V1 presente, Flyway dice solo che lo schema è aggiornato, senza nessun accenno a V2. Cosa vede il collega: se esiste un'entità `Loan`, l'errore arriva da **Hibernate** (`validate`: tabella `loan` mancante). Cioè un errore che punta a Hibernate, mentre la causa è il nome di un file: lontano dalla causa (PD-05).

**Da ripassare:** guida 04, 1.2, 2.4, 2.6, 2.10.

---

## Domanda 5 — "Non è duplicazione inutile?" (colloquio simulato)

**Domanda.** L'intervistatore: "Nel vostro database avete un sacco di vincoli. Ma il backend Java non controlla già tutto? Non è duplicazione inutile? E il giorno che cambia una regola, la dovete cambiare in due posti."

**Sicurezza:** non indicata

**Risposta di Nicholas:**
> Non è duplicazione inutile perchè i controlli e vincoli a database sono l'ultima guardia.

**Approfondimento dell'intervistatore.** "'Ultima guardia' è una bella frase. Ma contro cosa, concretamente? Mi faccia due situazioni reali in cui il controllo in Java, da solo, non basterebbe a proteggere i dati del vostro studio."

**Sicurezza:** 2

**Risposta di Nicholas:**
> - 2 L'esempio è quello della registrazione con la mail di prima. Dovesse essere scritto male il codice Java di controllo che quindi permette l'inserimento anche di caratteri maiuscoli, abbiamo una guardia a database che controlla che i caratteri siano solo minuscoli prevenendo errori

**Correzione.**

🟡 **Risposta principale.** La direzione è giusta, ma a un colloquio "è l'ultima guardia" è uno slogan: l'intervistatore l'ha subito messo alla prova. E l'obiezione "la dovete cambiare in due posti" è rimasta **senza risposta**.

🟡 **Approfondimento.** ✅ Il bug in Java è un esempio valido, e collegato bene alla D2. Ma ne erano chiesti due, e manca **il più forte**, quello che l'intervistatore aspetta:

| Situazione | Perché Java da solo non basta | Nel progetto |
|---|---|---|
| **Bug nel codice Java** (il tuo esempio) | Un controllo scritto male, o dimenticato in un endpoint nuovo tra un anno | L'email in minuscolo (D2) |
| **Richieste contemporanee** | Java fa "controllo, poi salvo". Due richieste nello stesso istante controllano entrambe "libero?", entrambe ricevono "sì", entrambe salvano. Solo il database, che vede le due scritture in ordine, può fermare la seconda | **D-013**: due clienti che prenotano lo stesso orario nello stesso secondo. L'*exclusion constraint* è l'unica difesa vera. Vale anche per due registrazioni simultanee con la stessa email: le ferma `unique` |
| **Chi scrive senza passare da Java** | Una correzione a mano con `psql`, uno script di importazione, un lavoro programmato scritto in fretta | Una correzione dei dati fatta in produzione |

**La risposta all'obiezione "due posti":** non è lo **stesso** lavoro fatto due volte.
- **Java** valida per dare all'utente un messaggio utile, presto e in italiano ("l'email non è valida"), e applica le regole che dipendono dal contesto (l'ora attuale, il preavviso, chi sta operando).
- **Il database** garantisce le **invarianti**: ciò che deve essere vero per ogni riga, sempre, chiunque scriva.

Non tutte le regole sono duplicate: quelle tra più tabelle o legate al contesto stanno solo in Java (guida 04, parte 4, domanda 5). E cambiare un vincolo è una migrazione, versionata e testata come il codice.

**Risposta modello, da colloquio:** *"Non è duplicazione: fanno due lavori diversi. Java valida per dare messaggi chiari all'utente e applica le regole di contesto; il database garantisce le invarianti, chiunque scriva. Il caso decisivo è la concorrenza: se due clienti prenotano lo stesso orario nello stesso istante, entrambe le richieste passano il controllo in Java, e solo il vincolo del database ferma la seconda. Poi ci sono i bug e le scritture che non passano dall'applicazione. Le regole tra tabelle o di contesto restano solo in Java, quindi non tutto è duplicato; e un vincolo che cambia è una migrazione versionata, non una modifica nascosta."*

**Da ripassare:** guida 04, 2.1; decisione D-013.

---

## Riepilogo

**Esito per punto:** ✅ 5 · 🟡 6 · ❌ 3. Nessuna risposta ⚠️ (sicura e sbagliata). Da notare: in tutto il test non hai mai usato sicurezza 3, e alla D3 hai indovinato tutto con sicurezza 1. La sicurezza resta più bassa di quello che sai.

**Cosa è chiaro:**
- perché una migrazione condivisa non si modifica, e che la correzione è una V2;
- il comportamento del `check` (anche la trappola del `NULL`, risolta con sicurezza 1);
- che un file con un nome sbagliato viene ignorato in silenzio;
- gli elementi di una buona migrazione (nomi dei vincoli, identity `always`, `not null`);
- che con la strada A il bug dell'email si scopre per primo.

**Cosa ripassare:**

| Tema | Cosa fissare | Dove |
|---|---|---|
| Migrazione modificata e test | Il database dei test è nuovo a ogni esecuzione: applica la versione modificata e **passa**, mentre gli altri database sono rotti | Questa verifica, D1; guida 04, 1.4 |
| Quando interviene ogni strada dell'email | A rifiuta alla scrittura; B controlla solo i duplicati e **non cambia `=`**; C cambia il **confronto** stesso | Questa verifica, D2; guida 04, 3.2–3.3 |
| Ordine default → controlli | Il default riempie la colonna mancante **prima** di `not null` e `check`; un `NULL` esplicito non attiva il default e passa il `check` | Questa verifica, D3; guida 04, 2.5, 2.7 |
| `unique` su una chiave esterna | Vuol dire "al massimo uno": giusto per professionista ↔ utente, sbagliato per i prestiti di un socio | Questa verifica, D4; guida 04, 2.4 |
| `timestamptz` | Un istante in UTC; `timestamp` senza fuso è ambiguo | Guida 04, 2.10 |
| Perché i vincoli nel database | Concorrenza (il caso decisivo), bug, scritture fuori da Java; Java e database fanno lavori diversi | Questa verifica, D5; guida 04, 2.1 |

**Punti deboli aggiornati** ([registro](../punti-deboli.md)):
- PD-07 (la deriva dello schema): resta 🔴. Sul punto chiave, i test che passano nascondendo il problema, la risposta è mancata anche questa volta;
- nuovi: PD-14 (ordine default → `not null` → `check`, e il `NULL`), PD-15 (quando interviene ogni strada dell'email), PD-16 (`unique` su chiave esterna e `timestamptz`), PD-17 (perché i vincoli nel database: la concorrenza).

**Guida migliorata:** la guida 04 è stata integrata in 2.1 (Java e database fanno lavori diversi), 2.5 e 2.7 (`NULL` e `check`, ordine dei controlli) e 3.3 (quando interviene ogni strada).
