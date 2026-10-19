# Requisiti

Le regole del prodotto, decise con lo studio. Il "come" tecnico sta in `architettura.md` e `modello-dati.md`.

## Il problema

I clienti prenotano e poi cancellano all'ultimo momento senza conseguenze, perché non hanno pagato in anticipo. Lo studio perde l'ora e non può offrirla a qualcun altro.

## Chi usa il sistema

- **Professionisti** (2 nello stesso studio): ognuno ha il proprio calendario, i propri servizi, i propri pacchetti e le proprie impostazioni. Gestiscono tutto da un pannello.
- **Clienti**: prenotano, pagano, vedono le proprie sedute e i crediti rimasti.
- **Amministratore** (Nicholas, gestore della piattaforma): crea, modifica e disattiva i professionisti (D-021). Un professionista non si cancella mai, per conservarne la storia.

## Regole

### Servizi e prezzi
- Ogni professionista definisce i propri servizi, con nome, durata, prezzo netto e aliquota IVA.
- Prezzo iniziale di riferimento: 30 € + IVA a seduta.
- Prezzo e aliquota si modificano dal pannello.
- Al cliente il prezzo si mostra **IVA inclusa**.
- Una modifica di prezzo non cambia le prenotazioni e i pagamenti già fatti: ognuno conserva il prezzo del momento.

### Prenotazione e pagamento
- Una seduta singola si paga **per intero al momento della prenotazione**, con carta, tramite Stripe.
- In alternativa si prenota usando un **credito** (da pacchetto o da cancellazione in tempo).
- Mentre il cliente paga, l'orario scelto resta bloccato per lui per un tempo limitato. Se non completa il pagamento, l'orario torna libero.
- Il sistema non vede e non conserva mai i dati della carta.

### Pacchetti
- I pacchetti sono una novità per lo studio. Si creano e si modificano dal pannello: numero di sedute, prezzo, aliquota, durata di validità.
- Un pacchetto è **legato a un solo professionista** e a un servizio.
- Si paga per intero all'acquisto. Le sedute diventano crediti da usare per prenotare.
- I crediti scadono alla fine della validità del pacchetto.

### Cancellazioni
- **Entro il preavviso** (default 24 ore prima della seduta): il cliente riceve un **credito**, mai un rimborso in denaro.
  - Se la seduta era stata pagata con un credito di un pacchetto, il credito torna a quel pacchetto e mantiene la sua scadenza.
  - Se la seduta era stata pagata con carta, nasce un credito nuovo valido 90 giorni.
- **Oltre il preavviso**: la seduta è persa.
- Mancata presentazione: come una cancellazione oltre il preavviso.

### Accesso dei clienti
- Senza password: il cliente inserisce la propria email e riceve un codice di 6 cifre.
- La prima prenotazione crea l'account in automatico.
- Il processo deve essere di una facilità disarmante.

## Impostazioni modificabili dal pannello

Ogni professionista ha le proprie.

| Impostazione | Valore iniziale |
|---|---|
| Preavviso minimo per cancellare | 24 ore |
| Validità del credito nato da una seduta singola cancellata in tempo | 90 giorni |
| Prezzi e aliquota IVA di ogni servizio | 30 € netti, IVA da definire |
| Pacchetti in vendita (sedute, prezzo, validità) | nessuno |

## Fuori dalla prima versione

- **Fattura elettronica**: lo studio continua a emetterla come oggi. Stripe rilascia solo la ricevuta del pagamento.
- Rimborsi in denaro dal sistema (se proprio servono, si fanno a mano dalla dashboard Stripe).
- Promemoria via SMS o WhatsApp.

## Punti aperti

Da decidere prima del passo indicato.

1. **Un conto Stripe o due?** (prima del passo dei pagamenti) — Se i due professionisti fatturano separatamente, ognuno deve incassare sul proprio conto e il sistema deve gestire due account Stripe. Da chiedere allo studio.
2. **Se è lo studio a cancellare una seduta**, il cliente riceve sempre il credito, a prescindere dal preavviso. Proposta da confermare.
3. **Ogni quanto possono iniziare le sedute?** (prima del passo del calendario) — Ogni 30 minuti, ogni ora, o subito dopo la seduta precedente? Proposta: uno "scatto" modificabile per professionista, iniziale 30 minuti.
4. **Aliquota IVA corretta**: da chiedere al commercialista dello studio.
5. **Servizio per l'invio delle email** (prima del passo dell'accesso): orientamento verso **Brevo** (proposto da Nicholas), da confermare verificando i limiti del piano gratuito. Stima del volume: con 2 professionisti e ~16 prenotazioni al giorno, al massimo una trentina di email al giorno (codice di accesso + conferma). Per ridurre i codici di accesso: sessioni lunghe che si rinnovano a ogni visita (decisione del passo 8), non password (D-014 resta valida).
6. **Concessioni discrezionali del professionista** (prima del passo dei crediti) — Proposta di Nicholas: il professionista deve poter decidere caso per caso, per esempio concedere comunque il credito per una cancellazione oltre il preavviso, o regalare sedute. Perimetro deciso da Nicholas (2026-10-16): **credito su una cancellazione oltre il preavviso**, **sedute in omaggio**, **proroga della scadenza** di un credito; ogni professionista le decide per i propri clienti e i propri servizi. Da definire: eventuali limiti e la traccia (chi, quando, perché, con una nota). Nota: si tratta sempre di **crediti**, mai di rimborsi in denaro (D-015). Analisi in `stato.md`, note per i passi futuri.
7. **Codice di accesso anche via SMS** (da decidere: passo dell'accesso o dopo la prima versione) — Proposta di Nicholas: se l'utente ha un numero di telefono verificato, inviare il codice via SMS come prima opzione (Brevo offre anche SMS transazionali). Da valutare: costo per messaggio (gli SMS sono a pagamento, a differenza delle email nel piano gratuito), verifica del numero, protezione dagli abusi, consenso. Oggi "SMS e WhatsApp" sono tra le cose fuori dalla prima versione, ma solo per i promemoria.
8. **Richiesta di cancellazione dei dati di un cliente (GDPR)** (prima della messa online) — Disattivare l'account (`app_user.active = false`) non basta: i dati personali restano. Le prenotazioni e i pagamenti vanno però conservati per obblighi fiscali. Soluzione abituale da valutare: **anonimizzazione** (sovrascrivere email, nome, telefono) mantenendo la storia economica. Da verificare con chi segue la privacy dello studio.
