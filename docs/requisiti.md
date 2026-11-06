# Requisiti

Le regole del prodotto, decise con lo studio. Il "come" tecnico sta in `architettura.md` e `modello-dati.md`.

## Il problema

I clienti prenotano e poi cancellano all'ultimo momento senza conseguenze, perché non hanno pagato in anticipo. Lo studio perde l'ora e non può offrirla a qualcun altro.

## Chi usa il sistema

- **Professionisti** (2 nello stesso studio): ognuno ha il proprio calendario, i propri servizi, i propri pacchetti e le proprie impostazioni. Gestiscono tutto da un pannello. La **stanza è una sola**: una visita di valutazione dell'uno sovrapposta a una seduta dell'altro va **segnalata**, non impedita (vedi "Calendario").
- **Clienti**: prenotano, pagano, vedono le proprie sedute e i crediti rimasti.
- **Amministratore** (Nicholas, gestore della piattaforma): crea, modifica e disattiva i professionisti (D-021). Un professionista non si cancella mai, per conservarne la storia.

## Regole

### Servizi e prezzi
- Ogni professionista definisce i propri servizi, con nome, durata e prezzo.
- Prezzo iniziale di riferimento: 30 € a seduta.
- **Niente IVA**: entrambi i professionisti sono in regime forfettario (D-022). Il prezzo è uno solo: quello mostrato al cliente è quello incassato.
- Il prezzo si modifica dal pannello.
- Una modifica di prezzo non cambia le prenotazioni e i pagamenti già fatti: ognuno conserva il prezzo del momento.

### Prenotazione e pagamento
- Una seduta singola si paga **per intero al momento della prenotazione**, con carta, tramite Stripe.
- In alternativa si prenota usando un **credito** (da pacchetto o da cancellazione in tempo).
- Mentre il cliente paga, l'orario scelto resta bloccato per lui per un tempo limitato. Se non completa il pagamento, l'orario torna libero.
- Il sistema non vede e non conserva mai i dati della carta.
- **Approvazione** (D-030): ogni professionista sceglie se le prenotazioni dei clienti si confermano da sole (automatica) o le approva lui (manuale). In manuale l'importo resta **bloccato** sulla carta e si incassa solo all'approvazione (D-031). Il professionista, avvisato con una notifica push (D-032), risponde entro un tempo che sceglie lui e che il cliente vede prima di prenotare. Se rifiuta o non risponde in tempo, la prenotazione si annulla: il blocco sulla carta si scioglie o il credito torna, senza addebito. Finché la richiesta è in attesa, il cliente può ritirarla gratis.
- Il cliente può prenotare al massimo un certo numero di giorni avanti (**orizzonte di prenotazione**) e avere aperte al massimo un certo numero di **prenotazioni future** con lo stesso professionista (contano quelle in attesa di pagamento e quelle confermate). Valori nelle impostazioni (D-023).

### Prenotazione dal pannello
- Il professionista può prenotare **a nome di un suo cliente** (D-024), senza orizzonte né limite di prenotazioni future. Queste prenotazioni contano comunque nel limite quando il cliente prenota da solo.
- Se il cliente ha crediti validi per quel servizio, si usano.
- Altrimenti, proposta: **pagamento in studio**, seduta confermata subito; se cancellata in tempo non genera credito. Da confermare (punto aperto 10).
- Se il cliente non esiste ancora, il professionista lo crea dal pannello con nome, cognome ed email.

### Calendario
Risposte dello studio del 2026-11-02 e del 2026-11-06.
- Le sedute iniziano **ogni ora** (scatto di 60 minuti). Eccezione: la **visita di valutazione** dura 30 minuti e può iniziare anche alla mezz'ora; dopo una valutazione, la seduta successiva può iniziare anch'essa alla mezz'ora (es. valutazione 9:00–9:30, seduta 9:30–10:30). Resta un dettaglio (punto aperto 3).
- **Nessuna pausa** tra una seduta e l'altra.
- Una seduta sta sempre **tutta dentro** una fascia di lavoro: non finisce mai oltre l'orario.
- **Anticipo minimo** (2026-11-06, sostituisce "non per il giorno stesso"): il cliente può prenotare solo orari che iniziano **più di 24 ore dopo**. Esempio: alle 10:00 il primo orario prenotabile è alle 10:30 del giorno dopo (con le mezz'ore) o alle 11:00 (D-030).
- Gli orari di lavoro seguono uno **schema settimanale** (per entrambi oggi: lunedì–venerdì 9–13 e 14–19). Orari e date devono essere **modificabili quando e come vogliono** i professionisti (punto aperto 13). Non lavorano mai fuori dal proprio schema.
- **Stanza unica**: i due professionisti lavorano nella stessa stanza, ognuno con il proprio cliente, nello stesso momento. Fa eccezione la **visita di valutazione**, ma la regola non è rigida: si decide di volta in volta. Il sistema **non blocca** la prenotazione: **segnala** nel pannello la sovrapposizione tra una valutazione dell'uno e una seduta dell'altro, e i professionisti decidono cosa fare (punto aperto 12).

### Pacchetti
- I pacchetti sono una novità per lo studio. Si creano e si modificano dal pannello: numero di sedute, prezzo, durata di validità.
- Un pacchetto è **legato a un solo professionista** e a un servizio.
- Si paga per intero all'acquisto. Le sedute diventano crediti da usare per prenotare.
- I crediti scadono alla fine della validità del pacchetto.

### Cancellazioni
- **Entro il preavviso** (default 24 ore prima della seduta): il cliente riceve un **credito**, mai un rimborso in denaro.
  - Se la seduta era stata pagata con un credito di un pacchetto, il credito torna a quel pacchetto e mantiene la sua scadenza.
  - Se la seduta era stata pagata con carta, nasce un credito nuovo valido 90 giorni.
  - Se la seduta era da pagare in studio (prenotazione dal pannello, D-024): nessun credito, perché non è stato pagato niente.
- **Oltre il preavviso**: la seduta è persa.
- Mancata presentazione: come una cancellazione oltre il preavviso.

### Accesso dei clienti
- Senza password: il cliente inserisce la propria email e riceve un codice di 6 cifre.
- La prima prenotazione crea l'account in automatico.
- Il processo deve essere di una facilità disarmante.

### Riservatezza del calendario
- Il calendario **non è pubblico**. Senza accesso si vedono solo gli **orari liberi**: mai chi ha prenotato, né l'elenco delle sedute.
- Il professionista vede solo le **proprie** prenotazioni, non quelle dell'altro professionista. Unica eccezione, per la stanza unica: la segnalazione di una sovrapposizione con una visita di valutazione dice all'altro professionista l'orario e che si tratta di una valutazione, senza il nome del cliente (proposta, punto aperto 12).
- Il cliente vede solo le **proprie** prenotazioni.

## Impostazioni modificabili dal pannello

Ogni professionista ha le proprie.

| Impostazione | Valore iniziale |
|---|---|
| Preavviso minimo per cancellare | 24 ore |
| Validità del credito nato da una seduta singola cancellata in tempo | 90 giorni |
| Prezzo di ogni servizio | 30 € |
| Pacchetti in vendita (sedute, prezzo, validità) | nessuno |
| Orizzonte di prenotazione: quanti giorni avanti può prenotare il cliente | 21 giorni (proposta, punto aperto 11) |
| Prenotazioni future aperte al massimo per cliente | 2 (proposta, punto aperto 11) |
| Scatto: ogni quanto può iniziare una seduta | 60 minuti; inizio alla mezz'ora attivabile (30) (D-033) |
| Approvazione delle prenotazioni: automatica o manuale | manuale (D-030) |
| Tempo di risposta per l'approvazione manuale | da 1 ora all'anticipo minimo, proposta 12 ore (D-030) |
| Anticipo minimo per prenotare | 24 ore (D-030) |
| Orari di lavoro settimanali (fasce per giorno) | lunedì–venerdì 9–13 e 14–19, per entrambi (studio, 2026-11-06) |

## Fuori dalla prima versione

- **Fattura elettronica**: lo studio continua a emetterla come oggi. Stripe rilascia solo la ricevuta del pagamento.
- Rimborsi in denaro dal sistema (se proprio servono, si fanno a mano dalla dashboard Stripe).
- Promemoria via SMS o WhatsApp.

## Punti aperti

Da decidere prima del passo indicato.

1. **Un conto Stripe o due?** (prima del passo dei pagamenti) — Se i due professionisti fatturano separatamente, ognuno deve incassare sul proprio conto e il sistema deve gestire due account Stripe. Da chiedere allo studio. Nota (2026-10-29): entrambi sono forfettari, quindi due partite IVA distinte: quasi certamente servono **due conti**. Da confermare.
2. **Se è lo studio a cancellare una seduta**, il cliente riceve sempre il credito, a prescindere dal preavviso. Proposta da confermare.
3. ~~**Ogni quanto possono iniziare le sedute?**~~ — **Chiuso** (2026-11-06, D-033): scatto 30 o 60, default 60, griglia dall'inizio della fascia, fasce all'ora piena o alla mezz'ora. Storia: chiuso in parte (2026-11-02): griglia fissa, ogni ora, per entrambi; nessuna pausa; la seduta sta tutta dentro la fascia. Il valore 60 è un dato (`practitioner.slot_step_minutes`, modificabile dal pannello dal passo 5; nello schema il default è ancora 30). Visita di valutazione (2026-11-06): può iniziare anche alla mezz'ora, e dopo una valutazione la seduta successiva può iniziare alla mezz'ora. Risposta (2026-11-06): **sì**, anche una seduta da un'ora può iniziare alla mezz'ora. Proposta di Nicholas: ogni professionista sceglie dalle impostazioni se permettere l'inizio alla mezz'ora (sì: griglia di 30 minuti; no: griglia di un'ora). Da scegliere: un interruttore nuovo o lo `slot_step_minutes` già esistente limitato a 30 e 60. Da decidere noi nella guida del passo 6: a cosa si aggancia la griglia (orologio o inizio della fascia) e quali valori sono ammessi (oggi qualsiasi numero > 0).
4. ~~**Aliquota IVA corretta**~~ — **Chiuso** (2026-10-29): entrambi i professionisti sono forfettari, l'IVA non si applica (D-022).
5. **Servizio per l'invio delle email** (prima del passo dell'accesso): orientamento verso **Brevo** (proposto da Nicholas), da confermare verificando i limiti del piano gratuito. Stima del volume: con 2 professionisti e ~16 prenotazioni al giorno, al massimo una trentina di email al giorno (codice di accesso + conferma). Per ridurre i codici di accesso: sessioni lunghe che si rinnovano a ogni visita (decisione del passo 8), non password (D-014 resta valida).
6. **Concessioni discrezionali del professionista** (prima del passo dei crediti) — Proposta di Nicholas: il professionista deve poter decidere caso per caso, per esempio concedere comunque il credito per una cancellazione oltre il preavviso, o regalare sedute. Perimetro deciso da Nicholas (2026-10-16): **credito su una cancellazione oltre il preavviso**, **sedute in omaggio**, **proroga della scadenza** di un credito; ogni professionista le decide per i propri clienti e i propri servizi. Da definire: eventuali limiti e la traccia (chi, quando, perché, con una nota). Nota: si tratta sempre di **crediti**, mai di rimborsi in denaro (D-015). Analisi in `stato.md`, note per i passi futuri.
7. **Codice di accesso anche via SMS** (da decidere: passo dell'accesso o dopo la prima versione) — Proposta di Nicholas: se l'utente ha un numero di telefono verificato, inviare il codice via SMS come prima opzione (Brevo offre anche SMS transazionali). Da valutare: costo per messaggio (gli SMS sono a pagamento, a differenza delle email nel piano gratuito), verifica del numero, protezione dagli abusi, consenso. Oggi "SMS e WhatsApp" sono tra le cose fuori dalla prima versione, ma solo per i promemoria.
8. **Richiesta di cancellazione dei dati di un cliente (GDPR)** (prima della messa online) — Disattivare l'account (`app_user.active = false`) non basta: i dati personali restano. Le prenotazioni e i pagamenti vanno però conservati per obblighi fiscali. Soluzione abituale da valutare: **anonimizzazione** (sovrascrivere email, nome, telefono) mantenendo la storia economica. Da verificare con chi segue la privacy dello studio.
9. **Marca da bollo** (prima del passo dei pagamenti) — Sulle ricevute non soggette a IVA di importo superiore a 77,47 € va applicata una marca da bollo da 2 €. Riguarda soprattutto i **pacchetti** (una seduta singola da 30 € resta sotto la soglia). Da chiedere al commercialista se viene addebitata al cliente: in quel caso cambia l'importo incassato tramite Stripe.
10. **Modo di pagamento delle prenotazioni fatte dal pannello** (prima del passo della prenotazione) — Proposta: pagamento in studio, seduta confermata subito, nessun credito se cancellata in tempo (D-024). Da confermare con lo studio.
11. **Valori iniziali di orizzonte e limite** (prima del passo della prenotazione) — Proposta: 21 giorni e 2 prenotazioni future per cliente (D-023). Da confermare con lo studio.
12. **Stanza unica e visita di valutazione** (prima del passo della prenotazione) — Risposta dello studio (2026-11-06): la regola è **facoltativa e decisa di volta in volta**; il sistema non blocca, **segnala** nel pannello. Quindi i calendari restano indipendenti: gli orari liberi (passo 6) e il vincolo di non sovrapposizione nel database (passo 7, per professionista) non cambiano. Da decidere noi: come si riconosce un servizio di valutazione (proposta: una proprietà del servizio, impostata dal professionista); cosa mostra la segnalazione (proposta: a entrambi i professionisti, l'orario della sovrapposizione e "valutazione", senza nome del cliente); quando compare (nell'elenco delle proprie sedute e prima di confermare una prenotazione dal pannello).
13. ~~**Modifiche agli orari di lavoro**~~ — **Chiuso** (2026-11-06, D-034): versioni settimanali con data di inizio; le prenotazioni già fatte restano valide e il pannello le segnala (passo 7). Storia: risposte dello studio (2026-11-06): non lavorano mai fuori dal proprio schema; orari e date devono essere modificabili "quando e come vogliono". Tre casi da coprire: (a) cambiare lo schema da subito: c'è già (`working_hours` modificabile); (b) togliere ore in un giorno preciso (un pomeriggio libero, le ferie): c'è già, con le indisponibilità; (c) cambiare lo schema **da una data futura** (es. orario estivo dal 1° giugno): oggi non si può, perché una modifica dello schema vale subito e toccherebbe anche le settimane prima di quella data. Proposta: schema con una data di inizio validità, da valutare nella guida del passo 6. Proposta da confermare: una modifica non tocca le prenotazioni già fatte, che restano valide anche se finiscono fuori dal nuovo orario, e il pannello le segnala.
14. ~~**"Non per il giorno stesso" e preavviso di cancellazione**~~ — **Chiuso** (2026-11-06), poi **sostituito** lo stesso giorno da D-030: non "non per il giorno stesso", ma un anticipo minimo di **più di 24 ore**, impostazione per professionista che vale solo quando prenota il cliente. Con anticipo e preavviso entrambi di 24 ore, chi prenota può sempre cancellare in tempo subito dopo; resta il caso della conferma che arriva tardi (D-030, conseguenze). Il professionista può comunque concedere il credito a mano (punto aperto 6).
15. ~~**Approvazione delle prenotazioni da parte del professionista**~~ — **Chiuso** (2026-11-06) con tre decisioni: approvazione automatica o manuale per professionista, con tempo di risposta e annullamento se non risponde (D-030); importo bloccato sulla carta e incassato all'approvazione (D-031); notifiche push con Web Push (D-032). Valore iniziale: manuale. Una conferma che arriva dentro il preavviso di cancellazione va bene: il preavviso si conta dall'inizio della seduta (D-030, conseguenze).
