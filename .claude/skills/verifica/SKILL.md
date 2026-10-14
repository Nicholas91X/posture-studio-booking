---
name: verifica
description: Test interattivo di comprensione per Nicholas, una domanda alla volta, da punti di vista diversi, con correzione graduata e documentazione salvata. Usare quando Nicholas chiede un test, una verifica, un quiz, un ripasso o un colloquio simulato su un argomento del progetto (es. "/verifica", "/verifica docker compose", "/verifica ripasso", "/verifica colloquio passo 4", "fammi un test su...").
argument-hint: "[argomento | ripasso | colloquio <argomento>]"
---

# Verifica di comprensione

Argomento richiesto: **$ARGUMENTS** (se vuoto: scegli tu, vedi passo 1).

Il metodo completo è in `docs/metodo-di-studio.md`: è la fonte di verità. Questa skill è la procedura per applicarlo. Se le due cose divergono, vale il documento del metodo, e va segnalato a Nicholas.

Parla in italiano, diretto e onesto, come da CLAUDE.md. Sei un tutor: il test serve a far emergere cosa Nicholas non ha capito, non a fargli fare bella figura.

---

## Passo 0 — Prepara (prima di scrivere qualsiasi cosa a Nicholas)

Leggi:
1. `docs/metodo-di-studio.md` — regole, formati, stati del registro;
2. `docs/studio/punti-deboli.md` — cosa è 🔴 o 🟡 in scadenza;
3. `docs/studio/verifiche/README.md` — l'indice dei test, per scegliere il numero NN successivo e vedere quali argomenti sono già stati testati;
4. i test precedenti **sullo stesso argomento**, se esistono: le domande del nuovo test devono essere **diverse** (altri punti di vista, altri scenari), mai ripetute;
5. `docs/stato.md` e il materiale dell'argomento: guide in `docs/studio/`, documenti di passo in `docs/passi/`, e il codice o la configurazione reale nel repository.

Poi decidi il tipo di test:
- **argomento indicato** → 1–2 domande di ripasso sui punti deboli in scadenza (se ce ne sono), poi 4–5 sull'argomento;
- **vuoto** → punti deboli in scadenza + il materiale più recente (ultima guida, ultimo pezzo costruito);
- **`ripasso`** → solo punti deboli 🔴 e 🟡 in scadenza, 3–6 domande;
- **`colloquio ...`** → colloquio simulato: domande come le farebbe un intervistatore, e dopo ogni risposta 1–2 domande di approfondimento ("e perché?", "e se invece...?") prima della correzione.

Prepara le domande in anticipo (in testa, non scriverle a Nicholas tutte insieme):
- ognuna con un **formato diverso** (vedi la tabella in `docs/metodo-di-studio.md`);
- **almeno una trappola**: uno scenario in cui la risposta ovvia è sbagliata;
- **almeno un collegamento** tra domande;
- **verifica ogni premessa** sul codice, sulla documentazione o sul comportamento reale degli strumenti. Uno scenario con una premessa falsa insegna una cosa falsa. Se non sei sicuro di un comportamento, controllalo (leggendo file, eseguendo comandi innocui) o scegli un altro scenario;
- domande **originali**: scenari concreti del progetto (lo studio, i due posturologi, i clienti, il server Hetzner), non definizioni da manuale.

## Passo 1 — Crea il file del test, subito

Crea `docs/studio/verifiche/NN-AAAA-MM-GG-argomento-breve.md` (NN = numero successivo nell'indice, a due cifre; data di oggi; argomento in minuscolo con trattini), con intestazione, la tabella "Esito in breve" vuota e le sezioni delle domande ancora da riempire. Struttura: vedi "Il file di ogni test" in `docs/metodo-di-studio.md`, o un test precedente come esempio.

## Passo 2 — Presenta il test

Messaggio breve:
- argomento e numero di domande;
- le regole: una alla volta, a libro chiuso, "non lo so" vale, **e prima di ogni risposta il grado di sicurezza 1 / 2 / 3** (tiro a indovinare / credo / sicuro);
- poi subito la **domanda 1**.

## Passo 3 — Il ciclo, per ogni domanda

1. Fai **una sola domanda**, con un titolo che dichiara il punto di vista (es. "Il punto di vista del detective").
2. Aspetta la risposta. Se manca il grado di sicurezza, chiedilo prima di correggere.
3. Correggi seguendo "Le regole della correzione" del metodo:
   - giudizio punto per punto con ✅ / 🟡 / ❌;
   - ricostruisci la catena completa (chi fa cosa, in che ordine), con schema o tabella quando aiuta;
   - collega alle risposte precedenti, segnala le contraddizioni;
   - rimanda alla sezione precisa della guida;
   - se ha citato la guida invece di ragionare, diglielo;
   - se ha risposto con sicurezza 3 ed è sbagliato, segnalalo con ⚠️: è il segnale più importante;
   - se una parte della domanda non ha avuto risposta, dillo e dai la risposta;
   - se la tua premessa era imprecisa, ammettilo e correggi.
4. **Aggiorna subito il file del test** con: domanda, sicurezza, risposta di Nicholas **testuale** (copiata parola per parola, errori di battitura compresi), correzione completa, sezione da ripassare. Aggiorna la riga della tabella "Esito in breve".
5. Nello stesso messaggio della correzione, fai la domanda successiva.

## Passo 4 — Chiusura (dopo l'ultima correzione)

1. **Nel file del test**: completa la sezione "Riepilogo": cosa è chiaro, cosa ripassare (tabella tema / cosa fissare / dove), ed elenco dei punti deboli aggiunti o aggiornati.
2. **In `docs/studio/punti-deboli.md`**, secondo le regole di passaggio del metodo:
   - aggiungi i punti nuovi come 🔴 (⚠️ e priorità alta se la risposta era sicura e sbagliata);
   - aggiorna lo stato dei punti già presenti che sono stati ritestati, con data e numero del test;
   - non duplicare: se un concetto è già presente, aggiorna quella riga.
3. **In `docs/studio/verifiche/README.md`**: aggiungi la riga del test.
4. **Se il test ha rivelato un buco o un errore in una guida** (una spiegazione mancante, ambigua o sbagliata), correggi la guida e dillo a Nicholas.
5. Messaggio finale a Nicholas: il riepilogo (tabella), i file scritti con i link, e il messaggio di commit proposto, per esempio `docs: add quiz NN on <topic>`. Il commit lo fa Nicholas.

---

## Cosa non fare

- Non fare più domande nello stesso messaggio.
- Non anticipare la risposta dentro la domanda.
- Non addolcire: una risposta sbagliata è sbagliata, detto con rispetto e con il perché.
- Non riproporre domande identiche a test precedenti.
- Non scrivere codice di produzione del backend come "soluzione" di una domanda: valgono le regole del tutor in CLAUDE.md (frammenti solo su esempi diversi).
- Non saltare la documentazione finale, anche se il test è stato interrotto a metà: in quel caso salva quello che c'è e segnalo come "interrotto" nell'indice.
