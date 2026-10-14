# Metodo di studio — le verifiche di comprensione

Come Nicholas verifica di aver capito, non solo letto. Nato il 2026-10-14 dal primo test sul passo 4 (`studio/verifiche/01-...`).

Il principio: **una definizione si può ripetere senza capirla; una catena di cause no.** Le verifiche non chiedono "cos'è X", ma "chi fa cosa, in che ordine, e cosa succede se qualcosa cambia".

---

## Come si lancia

In Claude Code, in qualsiasi momento, anche più volte al giorno e sullo stesso argomento:

| Comando | Cosa fa |
|---|---|
| `/verifica` | Test sull'argomento più utile in quel momento: punti deboli in scadenza + materiale recente |
| `/verifica docker compose` | Test sull'argomento indicato (testo libero: un tema, un passo, una guida) |
| `/verifica ripasso` | Solo punti deboli del registro, ognuno posto in modo nuovo |
| `/verifica colloquio passo 4` | Colloquio simulato: domande incalzanti, con "e perché?" dopo ogni risposta |

Ogni test produce **sempre**:
1. un file in `docs/studio/verifiche/` con domande, risposte di Nicholas (testuali), correzioni ed esito;
2. l'aggiornamento di `docs/studio/punti-deboli.md`;
3. una riga nell'indice `docs/studio/verifiche/README.md`.

---

## Le regole del test

1. **Una domanda alla volta.** La successiva arriva solo dopo la correzione della precedente.
2. **A libro chiuso.** Si risponde con parole proprie, senza riaprire guide o codice. Citare la guida a memoria non conta come risposta: conta il ragionamento.
3. **"Non lo so" è una risposta valida**, e vale più di un tentativo a caso: dice esattamente dove insistere.
4. **Grado di sicurezza.** Prima di ogni risposta Nicholas scrive quanto è sicuro:
   - **1** = tiro a indovinare
   - **2** = credo
   - **3** = sono sicuro

   Una risposta **sicura (3) e sbagliata** è il segnale più importante del test: è una convinzione errata, ed è lì che si cade ai colloqui. Va segnalata con ⚠️ e diventa un punto debole prioritario.
5. **5–6 domande** per test (un ripasso veloce può averne 3).

## Le regole della correzione

Dopo ogni risposta, Claude:
- divide il giudizio in **✅ giusto**, **🟡 giusto a metà / impreciso**, **❌ sbagliato**, punto per punto;
- **spiega il perché**, non solo la risposta giusta: ricostruisce la catena completa (chi fa cosa, in che ordine);
- quando serve, usa uno schema o una tabella;
- **collega** la risposta a quelle precedenti, soprattutto se due risposte si contraddicono;
- rimanda alla **sezione precisa** della guida da rileggere;
- se Nicholas ha citato la guida invece di ragionare, glielo dice;
- se una premessa della domanda era sbagliata, **lo ammette** e la corregge.

## Come sono fatte le domande

**Ogni domanda guarda il problema da un punto di vista diverso.** I formati:

| Formato | Esempio | Cosa allena |
|---|---|---|
| **Punto di vista di una cosa** | "Sei la password nel `.env`: racconta il tuo viaggio" | Seguire un dato attraverso tutti gli attori |
| **Punto di vista di un processo** | "Sei il processo Java lanciato da `mvnw`: cosa succede fino a `Started`?" | Ordine e dipendenze tra i passaggi |
| **Punto di vista di una persona** | "Giulia clona il repository su un portatile nuovo..." | Cosa è nel repository, cosa no, cosa arriva da solo |
| **Detective** | "Lunedì, dopo un riavvio: `Connection refused`. Qual è l'anello rotto?" | Dal sintomo alla causa, per livelli |
| **Cosa succede se** | "Nicholas mette `update` invece di `validate`: cosa succede nei test e in produzione?" | Conseguenze a distanza di una scelta |
| **Trova l'errore** | Un pezzo di configurazione o codice con un errore nascosto | Leggere con occhio critico (come in una code review) |
| **Spiegalo a un posturologo** | "Spiega a Marco perché il sito non perde le prenotazioni se si spegne il server" | Se non si riesce a dirlo semplice, non è capito del tutto |
| **Confronto** | "Named volume o bind mount? Quando l'uno, quando l'altro?" | Scegliere con motivazioni, non a memoria |
| **Colloquio simulato** | Domanda classica + "e perché?" + "e se invece...?" | Reggere le domande successive di un intervistatore |

Inoltre:
- **almeno una trappola** per test: uno scenario dove la risposta ovvia è sbagliata;
- **domande collegate**: almeno una riprende un concetto di una domanda precedente;
- **il test apre con 1–2 domande sui punti deboli** del registro che sono in scadenza (vedi sotto), poste in modo **diverso** dall'ultima volta: mai la stessa domanda due volte;
- prima di proporre uno scenario, Claude **verifica che la premessa regga** (codice, documentazione, comportamento reale degli strumenti).

---

## Il registro dei punti deboli

`docs/studio/punti-deboli.md` raccoglie i concetti emersi come non chiari, con uno stato:

| Stato | Significato | Quando si ripropone |
|---|---|---|
| 🔴 **da ripassare** | Sbagliato o non saputo | Al test successivo |
| 🟡 **in miglioramento** | Risposto bene una volta dopo l'errore | Dopo almeno 2 giorni |
| 🟢 **consolidato** | Risposto bene due volte, in giorni diversi | Ogni tanto, nei ripassi misti |

Regole di passaggio:
- un punto 🔴 risposto bene → 🟡;
- un punto 🟡 risposto bene **in un altro giorno** → 🟢;
- qualsiasi punto risposto male → torna 🔴;
- una risposta **sicura e sbagliata** (⚠️) entra come 🔴 con priorità alta.

Il motivo dei giorni diversi: si ricorda ciò che si **richiama** a distanza di tempo, non ciò che si rilegge subito (*ripetizione dilazionata*).

---

## Quando fare un test

Oltre a quando Nicholas lo lancia:
- **dopo ogni guida di studio**, prima di scrivere il codice di quel pezzo;
- **a fine passo**, includendo le domande da colloquio del documento di passo;
- **ripasso misto** ogni tanto, sui passi vecchi.

Claude lo propone in questi momenti, senza imporlo.

---

## Il file di ogni test

`docs/studio/verifiche/NN-AAAA-MM-GG-argomento.md`, numerato in sequenza (lo stesso argomento può avere più test). Struttura:

```markdown
# Verifica NN — Argomento

Data · modalità · guide di riferimento

## Esito in breve
(tabella: domanda, formato, sicurezza, esito)

## Domanda 1 — Titolo (formato)
**Domanda.** ...
**Sicurezza:** 2
**Risposta di Nicholas:** (testuale)
**Correzione.** ✅ / 🟡 / ❌ con spiegazione
**Da ripassare:** guida, sezione

...

## Riepilogo
Cosa è chiaro · cosa ripassare · punti deboli aggiornati
```

Il file si scrive **durante** il test, dopo ogni risposta, non solo alla fine: se la sessione si interrompe, il lavoro non va perso.
