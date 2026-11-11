# Prenotazione dal pannello con credito — guida di studio

Questa guida accompagna il **pezzo 4 del passo 10**. D-024 l'aveva deciso fin dall'inizio: quando il professionista prenota dal pannello per un suo cliente, **se il cliente ha un credito valido per quel servizio, si usa**; altrimenti la seduta resta "da pagare in studio" (D-040).

```
POST /api/panel/bookings   (la stessa richiesta di oggi)
  → credito utilizzabile?  sì: CONFIRMED, paidWith CREDIT, −1 sul lotto che scade prima
                           no: CONFIRMED, paidWith IN_STUDIO                         (come oggi)
```

Teoria di riferimento: D-024, D-040, D-048 (prenotazione dal pannello: fasce e assenze sì, griglia, anticipo, orizzonte e limite no; cliente trovato o creato per email), D-059 (il link di pagamento solo per le sedute "in studio"), D-071 (prenotare con un credito).

---

## Parte 1 — Cosa c'è già

Quasi tutto, dal pezzo 3:
- `CreditService.lockUsableLot` (il lotto che scade prima, con il lucchetto e il saldo riletto) e `commit` (−1);
- la prenotazione dal pannello prende **già** il lucchetto sul cliente (D-048: non rispetta il limite, ma conta nel limite), quindi l'ordine dei lucchetti di D-069 (cliente, poi lotto) viene da sé;
- la V15 accetta `paid_with = CREDIT` anche con `booked_by = PRACTITIONER`.

Manca: la scelta nel metodo `bookFromPanel`, e il modo di dirlo a chi guarda.

---

## Parte 2 — La stessa regola del cliente

Le domande del pezzo 3 hanno già una risposta, e qui valgono uguali:
- **quale lotto**: quello di quel cliente, professionista e servizio che copre il giorno della seduta, con saldo positivo, che scade prima (FEFO, D-071);
- **un credito che scade prima della seduta non si usa** (D-071, da confermare con lo studio). Dal pannello capita più spesso che dal sito, perché il professionista non ha l'orizzonte di 21 giorni: una seduta fissata a due mesi va "in studio" anche se il cliente ha un credito che scade prima.

Un caso nuovo: il **cliente appena creato** dal pannello (D-048) non ha crediti, quindi la sua prima seduta è sempre "in studio". Niente da scrivere: la query non trova niente.

---

## Parte 3 — Usarlo da solo, o lasciar scegliere il professionista?

D-024 dice "si usano". Dal pannello però c'è una persona che parla con il cliente, e potrebbe sentirsi dire "questa la pago in studio, i crediti li tengo".

- **Usarlo da solo** (raccomandato): coerente con il sito (D-071) e con D-024, nessun campo nuovo. Il professionista vede nella risposta come è stata pagata (parte 5). Nel caso raro in cui il cliente voleva pagare in studio, il professionista lo vede subito.
- **Un campo `useCredit`** facoltativo nella richiesta del pannello: utile solo in quel caso raro, e porta una domanda in più nel pannello per tutte le prenotazioni.

**Raccomandazione: usarlo da solo.** Se lo studio chiederà la scelta, un campo facoltativo con default "sì" si aggiunge senza rompere niente.

---

## Parte 4 — L'ordine dentro `bookFromPanel`

Oggi (D-048): servizio proprio (404) → cliente trovato o creato → lucchetto sul cliente → non nel passato → fasce e assenze → blocchi scaduti → `insert` → evento.

Con il credito si aggiungono due passi, nello stesso punto del pezzo 3:
- **prima dell'`insert`**: `lockUsableLot`, il lotto che scade prima con il suo lucchetto;
- **dopo l'`insert`**: `commit`, il −1 con l'id della prenotazione appena scritta.

La prenotazione nasce `CONFIRMED` in entrambi i casi: dal pannello non c'è approvazione. Con il credito, il credito è **usato** subito: non c'è una richiesta che possa essere rifiutata e restituirlo. Lo restituirà solo una cancellazione in tempo, al **passo 11** (`CANCELLATION_RESTORE`).

**Il link di pagamento** (D-059) resta solo per le sedute `IN_STUDIO`: una seduta pagata con un credito risponde già 409 `PAYMENT_LINK_NOT_ALLOWED`, perché `awaitsPaymentInStudio()` guarda proprio `paid_with`. Niente da cambiare, ma un test lo fissa.

---

## Parte 5 — Chi lo vede, e come

- **Il professionista**: `PanelBookingView`, la risposta della `POST`, oggi non dice come è pagata la seduta (lo dicono già le liste, `PanelBookingRow`). Riceve `paidWith`, così il pannello (passo 13) può mostrare subito "pagata con un credito" o "da pagare in studio", e il professionista coglie il caso della parte 3.
- **Il cliente**: riceve già l'email "Prenotazione confermata" (D-053). Se è stato usato un suo credito, deve saperlo: è un credito suo, speso da un'altra persona. L'email aggiunge una riga: "Per questa seduta è stato usato uno dei tuoi crediti." La stessa riga vale per una richiesta con credito approvata (pezzo 3), dove è comunque vera.

---

## Parte 6 — I test

| Test | Cosa prova |
|---|---|
| `BookingFromPanelTest` (aggiunte) | con un credito: `CONFIRMED`, `CREDIT`, −1 sul lotto che scade prima, evento `BookingConfirmed` con `CREDIT`; credito che scade prima della seduta → `IN_STUDIO`, saldo intatto; credito di un altro servizio → `IN_STUDIO`; cliente nuovo → `IN_STUDIO`; ultimo credito: una seconda prenotazione dal pannello va `IN_STUDIO` |
| `BookingPanelApiTest` (aggiunta) | la risposta della `POST` ha `paidWith` |
| `PaymentLinkApiTest` (aggiunta) | una seduta pagata con un credito non riceve un link: 409 `PAYMENT_LINK_NOT_ALLOWED` |
| `BookingEmailsTest` (aggiunta) | la conferma con credito dice del credito usato; quella con la carta o in studio no |

**Le difese tolte a turno**: la scelta del credito nel pannello (sempre `IN_STUDIO`), il −1 dopo l'`insert`, la riga dell'email.

---

## Parte 7 — Cosa c'è da decidere

| Scelta | Raccomandazione | Dove |
|---|---|---|
| Regola | la stessa del cliente (D-071): copre il giorno della seduta, il lotto che scade prima | parte 2 |
| Chi sceglie | nessuno: se c'è un credito si usa (D-024) | parte 3 |
| Ordine | `lockUsableLot` prima dell'`insert`, `commit` dopo, sotto il lucchetto sul cliente che c'è già | parte 4 |
| Visibilità | `paidWith` in `PanelBookingView`; una riga nell'email di conferma | parte 5 |

Dopo l'implementazione: una scheda (D-072), che **chiude** la parte di D-024 rimasta da fare.

---

## Parte 8 — Com'è andata (implementazione)

Costruito come raccomandato nella parte 7 (D-072), su indicazione di Nicholas del 2026-11-11. Nessuna migrazione. 6 test nuovi, 965 in tutto, `mvnw clean test` verde.

| File | Cosa cambia |
|---|---|
| `Booking.bookedFromPanel` | riceve `paidWith`: solo `IN_STUDIO` o `CREDIT`, ogni altro valore è un errore di programmazione |
| `BookingService.bookFromPanel` | `lockUsableLot` prima dell'`insert`, `commit` dopo; l'`insert` usa lo stesso aiutante della richiesta del cliente |
| `PanelBookingView` | `paidWith` |
| `BookingEmails` | nella conferma, "Per questa seduta è stato usato uno dei tuoi crediti." quando è vero |

Test: quattro casi in `BookingFromPanelTest` (credito usato sul lotto che scade prima; credito che scade prima della seduta; credito di un altro servizio; ultimo credito, poi "in studio"), `paidWith` nella risposta di `BookingPanelApiTest` (e nella risposta attesa scritta per intero dell'approvazione), la seduta pagata con un credito senza link in `PaymentLinkApiTest`, la riga dell'email in `BookingEmailsTest`.

### L'errore più importante del passo, trovato qui

Per togliere le difese a turno, gli script dei pezzi 1–4 lanciavano Maven con `cmd /c mvnw.cmd`. Lanciato da Python in questo ambiente, `cmd` **non cerca i programmi nella cartella corrente**: rispondeva "mvnw.cmd non è riconosciuto come comando", con un codice d'uscita diverso da zero. Lo script lo leggeva come "il test è fallito", cioè come "la difesa è protetta". **Maven non è mai partito.**

Me ne sono accorto qui perché, imparata la lezione del pezzo 3, lo script ora salvava l'output: i log contenevano solo quella riga. Il "fallimento inatteso" del pezzo 3 (il lucchetto sul lotto tolto) era la stessa cosa.

Cosa reggeva comunque, perché provato **a mano**, fuori dallo script: il lucchetto del trigger del saldo (pezzo 1, `[true, true]`), `credit_lot_payment_uq` (pezzo 2, visto nel registro), il lucchetto sul cliente che basta (pezzo 3, quattro esecuzioni). Il resto era da rifare.

Correzione: il percorso completo di `mvnw.cmd`, e un'esecuzione conta solo se l'output contiene `Tests run:`; altrimenti lo script scrive "DID NOT RUN". Tutte le difese dei pezzi 1–4 sono state rifatte con lo script corretto (risultati nella scheda D-072 e nello stato).

**La lezione**, da colloquio: un test che fallisce non prova niente finché non sai **perché** fallisce. Un controllo che conta solo "verde o rosso" scambia un errore dell'ambiente per una prova. Per ogni esperimento: salva l'output, controlla che il test sia davvero partito, e leggi il motivo del fallimento.

---

## Domande da colloquio

### Domanda 1: Due punti d'ingresso (il sito e il pannello) devono applicare la stessa regola. Come eviti che divergano?

**Risposta modello.** La regola sta in un posto solo, il servizio del modulo dei crediti: "il lotto utilizzabile che scade prima" è un metodo che entrambi chiamano, con lo stesso lucchetto. I due punti d'ingresso differiscono in quello che deve differire (il pannello non ha approvazione, griglia né limite), non nella regola del credito. Se un giorno la regola cambia, cambia lì.

### Domanda 2: Il professionista prenota per un cliente che ha crediti. Perché non chiedere ogni volta se usarli?

**Risposta modello.** Perché la regola del prodotto dice che si usano, e una domanda in più su ogni prenotazione costa più del caso raro che risolverebbe. Rendo invece visibile il risultato: la risposta dice come è stata pagata la seduta, e il cliente riceve un'email che dice che è stato usato un suo credito. Se la scelta servirà davvero, si aggiunge un campo facoltativo senza rompere il contratto.

### Domanda 3: Perché il cliente deve sapere che è stato usato un suo credito?

**Risposta modello.** Perché è un valore suo, speso da un'altra persona: se non lo sa, se ne accorgerà solo quando proverà a usarlo e non lo troverà, e penserà a un errore. Una riga nell'email di conferma costa niente ed evita una telefonata. È lo stesso principio dei movimenti del registro: ogni cambiamento del saldo ha una spiegazione che il cliente può vedere.

### Domanda 4: Una seduta pagata con un credito può ricevere un link di pagamento?

**Risposta modello.** No, e non serve codice nuovo: il link esiste solo per le sedute confermate da pagare in studio, e il controllo guarda proprio come è pagata la seduta. Ma lo fisso con un test, perché è il genere di regola che un cambiamento futuro può rompere senza che nessuno se ne accorga.

---

## Documentazione ufficiale

- (nessuna nuova: i concetti sono quelli delle guide 36–38)
