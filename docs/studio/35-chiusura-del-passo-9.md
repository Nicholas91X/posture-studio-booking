# Via il pagamento simulato, e la chiusura del passo 9 — guida di studio

Questa guida accompagna il **pezzo 6 del passo 9**. Il brief lo chiamava "Via il pagamento simulato (criterio di fatto)": rimuovere `SimulatedPaymentController`, `LoggingCardHolds`, la proprietà `booking.simulated-payments`, la riga di `.env.example` e `/api/dev/**` dalle regole di Spring Security. Quasi tutto è già stato fatto **prima**, nel momento in cui il pezzo che lo sostituiva era pronto:
- al **pezzo 3** (D-062), con il webhook: via il controller, la proprietà, la riga di `.env.example`, `/api/dev/**` e la sua eccezione CSRF, e il loro test;
- al **pezzo 4** (D-063), con l'incasso vero: via `LoggingCardHolds`.

Restano tre cose: le **tracce**, cioè quello che parla ancora di un codice che non esiste più; la **verifica** di ogni criterio di "fatto" del brief, con la prova di dove sta; e la **chiusura** come vuole CLAUDE.md.

---

## Parte 1 — Perché le tracce contano

Una classe rimossa non dà più errori. Un **commento** o un **metadato** che ne parla, invece, continua a mentire in silenzio: il prossimo che legge (fra tre mesi, Nicholas stesso) cerca `LoggingCardHolds` e non lo trova, o si fida di un commento che descrive un comportamento sparito. L'inventario fatto all'inizio del pezzo ha trovato:

| Traccia | Dove | Cosa si fa |
|---|---|---|
| `booking.simulated-payments` nei **metadati delle proprietà** | `META-INF/additional-spring-configuration-metadata.json` | Via la voce. Al suo posto le proprietà vere del passo 9: `payment.accounts`, `payment.live`, `payment.return-base-url`, `payment.stripe.api-base` |
| "The card is the provisional LoggingCardHolds" | commento di `BookingPanelApiTest` | Corretto: la carta è `StripeCardHolds` con il finto Stripe |
| "and later (step 9) Stripe Checkout, the webhook..." | `package-info` di `payment` | Riscritto: cosa fa il modulo adesso, pezzo per pezzo |

Che cosa sono i **metadati delle proprietà**? Un file JSON che descrive le proprietà nostre (nome, tipo, default, significato). L'IDE lo legge: scrivendo `payment.` in `application.properties` propone le voci con la loro spiegazione, e segnala come sconosciuta una proprietà che non c'è. Una voce per una proprietà rimossa la farebbe sembrare ancora valida.

Le note storiche nei documenti (`stato.md`, le schede, le guide 20 e 31) **restano**: raccontano com'era, ed è giusto così. Si corregge solo quello che descrive il presente.

---

## Parte 2 — Una porta che resta chiusa

Il pagamento simulato era `POST /api/dev/bookings/{id}/payment-authorized`. Tolta la regola `/api/dev/**`, quell'indirizzo cade nel `denyAll()` di D-051: **chiuso a tutti**. È così, ma oggi nessun test lo dice per quell'indirizzo preciso. Proposta: un caso in `ConfidentialityTest`, accanto a quello degli indirizzi fuori dalle aree. Un anonimo riceve 401, chiunque sia entrato 403, e la prenotazione non cambia. Se un giorno qualcuno rimettesse `/api/dev/**` per comodità, il test lo direbbe.

---

## Parte 3 — I criteri di "fatto" del brief, uno per uno

| Criterio | Dove sta la prova |
|---|---|
| Le tre decisioni registrate, punti aperti 1, 9, 16 chiusi | D-057, D-058, D-059; `requisiti.md` |
| Un cliente paga in modalità di prova, dalla richiesta alla conferma, con approvazione manuale e automatica (prova manuale con Stripe CLI, documentata) | Fatta il 2026-11-10: `stato.md`, guida 32 parte 13.1, guida 34 parte 12 |
| Test: lo stesso avviso due volte → un solo effetto; firma sbagliata rifiutata; pagamento tardivo annullato | `StripeWebhookApiTest`, `StripeWebhookRollbackTest` (anche in parallelo) |
| Test: cambiare il conto di un professionista non sposta incassi e annullamenti già fatti | `StripeCardHoldsTest` (incasso e annullamento), `StripeWebhookApiTest` (pagamento tardivo), `PaymentAccountServiceTest` |
| Incasso fallito e "incassato ma commit fallito" gestiti e provati | `StripeCardHoldsTest` (409, 503, 500 e risposta persa), `StripeReconciliationTest` (commit veri) |
| Link: uno solo valido, pagamento che porta a `CARD`, rimborso se pagato dopo una cancellazione | `PaymentLinkApiTest`, `PaymentLinkWebhookTest`, `PaymentLinkConcurrencyTest`. Il "rimborso" è diventato **annullamento del blocco** (D-059 precisata, D-015 intatta); la prova con il percorso vero è un criterio del passo 11 |
| Pagamento simulato rimosso, nessun test lo usa | Pezzi 3 e 4; le tracce della parte 1; il test della parte 2 |
| `modello-dati.md` aggiornato; test verdi; branch e PR verso `main` | V10–V14 in `modello-dati.md`; PR #5, #6, #7 |
| Chiusura come da CLAUDE.md, con riepilogo per la chat di pianificazione | Parte 4 |

Due criteri li abbiamo **ampliati** strada facendo, su richiesta di Nicholas: gli annullamenti falliti riprovati dal lavoro programmato (D-064) e la lettura del PaymentIntent prima di ogni incasso (D-064 precisata).

---

## Parte 4 — La chiusura (CLAUDE.md, "A fine sessione")

1. **`stato.md`**: passo 9 chiuso, cosa resta (note per i passi 10–14).
2. **`decisioni.md`**: nessuna decisione nuova in questo pezzo. Le schede D-057–D-065 sono complete.
3. **`docs/passi/09-pagamenti.md`**: il documento del passo, con lo schema di CLAUDE.md (obiettivo, cosa è stato costruito, concetti chiave, decisioni, errori fatti e come sono stati corretti, domande da colloquio con le risposte modello).
4. **Commit e PR**.
5. **Riepilogo** di 5–10 righe per la chat di pianificazione.

---

## Parte 5 — Cosa c'è da decidere

| Scelta | Raccomandazione | Dove |
|---|---|---|
| Metadati delle proprietà | Via `booking.simulated-payments`; dentro le quattro `payment.*` | parte 1 |
| Commenti | Corretti i due che descrivono il passato come presente; le note storiche nei documenti restano | parte 1 |
| La porta `/api/dev/**` | Un test che la vuole chiusa a tutti | parte 2 |
| Chiusura | `stato.md`, `passi/09-pagamenti.md`, commit, riepilogo; nessuna scheda nuova | parte 4 |

Nessuna migrazione.

---

## Parte 6 — Com'è andata

Costruito come raccomandato nella parte 5, su richiesta di Nicholas del 2026-11-10: le tre tracce corrette, il test della porta chiusa in `ConfidentialityTest`, e la chiusura in `stato.md` e in `docs/passi/09-pagamenti.md`.
