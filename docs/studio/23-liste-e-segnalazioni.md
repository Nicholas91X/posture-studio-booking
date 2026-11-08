# Le liste e le segnalazioni — guida di studio

Questa guida accompagna il **pezzo 6 del passo 7**, l'ultimo. Le prenotazioni si possono vedere:
- dal **professionista**: le proprie in un periodo, e le richieste da approvare, con due **segnalazioni** sulle righe;
- dal **cliente**: le proprie.

Niente sotto `/api/public` (D-029): una prenotazione appartiene a qualcuno.

Teoria di riferimento: requisiti, "Riservatezza del calendario"; D-002, D-026 e D-029 (domande tra moduli su insiemi di id); D-034 (versioni degli orari); D-043 (valutazione).

---

## Parte 1 — Cosa si costruisce

| API | Cosa restituisce |
|---|---|
| `GET /api/panel/bookings?from=2026-11-09&to=2026-11-15` | Le prenotazioni del professionista che **si sovrappongono** al periodo (date locali, entrambe comprese), in tutti gli stati, in ordine di inizio |
| `GET /api/panel/bookings/requests` | Le richieste in attesa (`PENDING_APPROVAL`) ancora rispondibili, prima quella che scade prima |
| `GET /api/customer/bookings` | Le prenotazioni del cliente, dalla più lontana nel futuro alla più vecchia |

Ogni riga del pannello (`PanelBookingRow`) ha: id, cliente (id, nome, cognome: il professionista vede i **propri** clienti), servizio, orari, stato, chi l'ha creata, come si paga, scadenza della risposta, e le due segnalazioni.

---

## Parte 2 — Le due segnalazioni

### 2.1 Fuori dall'orario di lavoro attuale (D-034)

Una nuova versione degli orari può lasciare fuori orario prenotazioni già fatte: restano valide e si **segnalano** (`outsideWorkingHours: true`). "Attuale" vuol dire **la versione valida nel giorno della prenotazione** secondo i dati di oggi: se il 1° giugno parte l'orario estivo, una seduta del 3 giugno alle 18:30 si confronta con l'orario estivo.

`schedule` risponde con la stessa regola della prenotazione dal pannello (guida 22): una seduta è "dentro" se sta tutta in una fascia della versione valida quel giorno. Una domanda sola per tutte le righe: `outsideWorkingHours(practitionerId, ranges)` carica le versioni una volta.

### 2.2 Stanza unica: sovrapposizione con una valutazione (D-043)

Una riga di Anna si segnala se si sovrappone a una prenotazione di **un altro professionista** e almeno una delle due è una **valutazione**:
- una seduta di Anna mentre Marco ha una valutazione;
- una valutazione di Anna mentre Marco ha una seduta (la segnalazione va **a entrambi**, requisiti, punto aperto 12).

La segnalazione dice **solo l'intervallo** della sovrapposizione: `evaluationOverlaps: [{"start": ..., "end": ...}]`. Il nome del campo dice già "valutazione". Mai il cliente di Marco, mai l'id della sua prenotazione, mai il suo servizio.

### 2.3 Come si calcola senza far trapelare niente

```
1. le righe di Anna nel periodo                                     (booking, 1 query)
2. le prenotazioni DEGLI ALTRI che occupano l'orario nello stesso arco:
   SOLO servizio, inizio, fine — la query non legge nemmeno customer_id  (booking, 1 query)
3. quali di quei servizi sono valutazioni?                          (catalog, 1 query su un insieme)
4. in Java: per ogni riga di Anna, le intersezioni con quelle degli altri
   in cui almeno una delle due è una valutazione
```

Il punto 2 è la riservatezza **per costruzione**, come nel calcolo degli orari liberi (guida 16): se il dato non viene letto, non può uscire per errore. La query restituisce un `record` interno, `BookedRange(serviceId, start, end)`, costruito direttamente dalla query.

Il punto 3 è una domanda a `catalog` su un **insieme** di id (D-029): `evaluationsAmong(serviceIds)`. Il flag non si fotografa nella prenotazione (D-043): se il professionista corregge il servizio, le segnalazioni cambiano subito.

### 2.4 Su quali righe

Solo sulle righe che **occupano l'orario adesso** (`PENDING_APPROVAL`, `CONFIRMED`, `PENDING_PAYMENT` con il blocco in corso): una prenotazione rifiutata o scaduta non usa la stanza. E tra quelle degli altri, la stessa regola. È la terza volta che compare "occupa l'orario adesso" (dopo la query degli intervalli occupati e quella del limite): qui serve in Java, `Booking.holdsTimeAt(now)`, con un commento che rimanda alle altre due.

---

## Parte 3 — Le scelte piccole

| # | Scelta | Proposta | Perché |
|---|---|---|---|
| 1 | **Periodo** | `from` e `to` date locali, con lo stesso `record` `SlotRange` degli orari liberi (obbligatorie, `from` non dopo `to`, 400 sul campo) | Stessa forma, stesso errore, nessun codice nuovo. La finestra va dall'inizio di `from` all'inizio del giorno dopo `to`, a Roma |
| 2 | **Limite al periodo** | Nessuno | Sono i dati del professionista, pochi: anche un anno intero sono qualche centinaio di righe |
| 3 | **Stati nella lista** | Tutti | Il pannello può nascondere rifiutate e scadute; il backend non decide cosa interessa |
| 4 | **Richieste da approvare** | Solo quelle con scadenza nel futuro | Le altre non si possono più approvare (D-030, guida 20, 6.2), e il lavoro programmato le chiude entro un minuto |
| 5 | **Lista del cliente** | Tutte, con `CustomerBookingView`, inizio decrescente | "Le mie sedute": prima le prossime lontane, poi giù fino alle passate. Il frontend la divide come vuole |
| 6 | **Nome del cliente nel pannello** | Sì, chiesto a `customer` per tutte le righe insieme (`namesOf`) | Il professionista vede i propri clienti; una domanda per tutte le righe, non una per riga (N+1 tra moduli, D-029) |

---

## Parte 4 — I test

| # | Caso | Atteso |
|---|---|---|
| 1 | Anna ha tre prenotazioni nella settimana, una di Marco nello stesso periodo | le tre di Anna, in ordine, con il nome dei suoi clienti; mai quella di Marco |
| 2 | Una prenotazione che inizia prima del periodo e finisce dentro | inclusa (si sovrappone) |
| 3 | Nuova versione degli orari che toglie il venerdì pomeriggio | la seduta di venerdì alle 15 ha `outsideWorkingHours`, le altre no |
| 4 | Seduta di Anna lunedì 9–10, valutazione di Marco 9:30–10 | `evaluationOverlaps` = 9:30–10 |
| 5 | Valutazione di Anna 9–9:30, seduta di Marco 9–10 | `evaluationOverlaps` = 9–9:30: la segnalazione arriva anche a chi ha la valutazione |
| 6 | Seduta di Anna e seduta di Marco nello stesso orario | nessuna segnalazione: due sedute convivono nella stanza |
| 7 | Valutazione di Marco rifiutata | nessuna segnalazione |
| 8 | **Riservatezza**: il JSON della riga di Anna con la sovrapposizione | nessuna traccia del nome del cliente di Marco; la sovrapposizione ha **esattamente** `start` ed `end` |
| 9 | Richieste: una in attesa, una scaduta non ancora chiusa, una di Marco | solo la prima |
| 10 | Il cliente vede le sue, non quelle di un altro | come detto |
| 11 | `from` dopo `to`; senza header | 400 su `to`; 401 |

---

## Parte 5 — Cosa è stato deciso

Scelte secondo le raccomandazioni di questa guida, su indicazione di Nicholas (D-049): le tre API; le due segnalazioni calcolate solo sulle righe che occupano l'orario; la query sugli altri che legge solo servizio e orari; `evaluationsAmong` su un insieme; `SlotRange` riusato per il periodo.

---

## Domande da colloquio

### Domanda 1: Devi mostrare a un utente un'informazione che dipende dai dati di un altro utente, senza rivelarli. Come lo progetti?
**Risposta modello.** Faccio in modo che il dato riservato non venga nemmeno letto: la query sui dati dell'altro seleziona solo le colonne che servono al calcolo (qui servizio e orari, non il cliente) e restituisce un oggetto interno senza quei campi. La risposta ha un DTO con l'elenco esatto dei campi, e un test che controlla quei campi e l'assenza del dato riservato nel corpo. Riservatezza per costruzione, non per filtro.

### Domanda 2: Una lista ha bisogno di dati di tre moduli. Come eviti l'N+1?
**Risposta modello.** Ogni modulo risponde a una domanda su un insieme di id: carico le righe, raccolgo gli id dei clienti e dei servizi, e chiedo una volta sola ai moduli che li possiedono. Le query restano costanti, qualunque sia il numero di righe, e nessun modulo legge le tabelle di un altro. Il prezzo è una combinazione in Java, che a questi volumi non costa niente.

### Domanda 3: Come trovi la sovrapposizione tra due intervalli, e la sua parte comune?
**Risposta modello.** Due intervalli semiaperti `[a1, b1)` e `[a2, b2)` si sovrappongono se `a1 < b2` e `a2 < b1`. La parte comune va dal più tardo dei due inizi al più presto delle due fini. Con gli intervalli semiaperti, due sedute una dopo l'altra (9–10 e 10–11) non si sovrappongono, ed è il comportamento che serve.

---

## Documentazione ufficiale

- Spring Data JPA, proiezioni e DTO con le espressioni costruttore: https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html
