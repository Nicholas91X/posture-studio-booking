# Stato del progetto

Ultimo aggiornamento: 2026-11-06 (avvio del passo 7)

## Dove siamo

Progettazione chiusa (passi 1–3). **Passo 4 chiuso** (`passi/04-fondamenta.md`). **Passo 5 chiuso** (catalogo e professionisti, `passi/05-catalogo.md`, con le risposte modello alle domande da colloquio). Codice dei cinque pezzi scritto da Claude su richiesta esplicita di Nicholas, dopo una guida di studio per ciascuno e le sue scelte. Costruito: migrazione V2 (`service`, `package_offer`, foreign key composta D-025); modulo `practitioner` (impostazioni, professionista corrente provvisorio D-028); modulo `catalog` (servizi e pacchetti, regola `SERVICE_NOT_ACTIVE` D-027, catalogo pubblico con `join fetch`); API del pannello sotto `/api/panel` con Bean Validation e `ProblemDetail` da un solo `ApiExceptionHandler`; API pubbliche sotto `/api/public` (professionisti attivi, catalogo di un professionista), con "attivo" chiesto al nuovo `AccountService` di `auth` (D-029). Regola di proprietà: la cosa di un altro dà 404 come quella inesistente. 108 test verdi (`mvnw clean test`). Prossimo: passo 6, calendario.

Fatto nel passo 4: struttura del repository, progetto Spring Boot 4.1.1 su Java 21, PostgreSQL 17 in locale con `infra/docker-compose.dev.yml`, configurazione da variabili d'ambiente (D-018, D-019) con `ddl-auto=validate`, migrazione V1 (`app_user`, `customer`, `practitioner`; D-020, D-021), prima entità `AppUser` con `Email`, `EmailConverter`, `Role` e `AppUserRepository`. Test: `EmailTest` (12 unitari, senza Spring) e `AppUserRepositoryTest` (9 di integrazione con Testcontainers su `postgres:17`), tutti verdi.

**Verifiche di comprensione sospese** dal 2026-10-29 per scelta di Nicholas (`docs/metodo-di-studio.md`); `/verifica` resta disponibile su richiesta. Verifiche fatte: 01 (attori del passo 4), 02 (JDBC), 03 (Flyway e vincoli); 04 e 05 abbandonate. Guide di studio: 01–13 in `docs/studio/` (06 come funziona Spring, 07 costruttori, 08 migrazione V2, 09 modulo `practitioner`, 10 modulo `catalog`, 11 API del pannello, 12 API pubbliche, 13 calendario: panorama e scelte del passo 6, 14 migrazione V3, 15 modulo `schedule` lato pannello, 16 calcolo degli orari liberi, 17 API pubblica degli orari liberi, 18 migrazione V4 e ciclo tra moduli, 19 richiesta di prenotazione).

**Passo 6 chiuso** (2026-11-06, `passi/06-calendario.md`, con le risposte modello alle domande da colloquio). Quattro pezzi, codice scritto da Claude su richiesta esplicita di Nicholas dopo una guida di studio per ciascuno (13–17) e le sue scelte: migrazione V3 (versioni dello schema settimanale D-034, fasce senza sovrapposizioni con *exclusion constraint* D-036, indisponibilità, scatto 30/60 D-033, anticipo minimo e orizzonte D-035) e cinque impostazioni con `@OneOf` (D-037); modulo `schedule` nel pannello (aggregato con `@OneToMany`, sostituzione delle fasce con `flush` esplicito, verificato necessario; `Clock` e zona dello studio in `shared`); calcolo degli orari liberi come funzione pura (`FreeSlots`, `SlotQuery`, `TimeRange`), provato sui cambi dell'ora e sulla trappola di mezzanotte; API pubblica `GET /api/public/practitioners/{id}/services/{serviceId}/slots` (D-038). 259 test verdi (`mvnw clean test`, 7 container). Passi 5 e 6 uniti in `main` (merge di `feat/step-5-catalog`).

**Passo 7 in corso** (dal 2026-11-06, branch `feat/step-7-booking`), dal brief della chat di pianificazione. Sei pezzi: (1) migrazione V4 e ciclo tra `schedule` e `booking`; (2) richiesta di prenotazione del cliente; (3) stati e approvazione; (4) lavori programmati; (5) prenotazione dal pannello, pagata in studio e senza crediti (resta nel passo 7: punto aperto 10 chiuso, D-040); (6) liste e segnalazioni. Le notifiche email e push lato backend passano al passo 8 (D-039). Punti aperti 10, 11 e 12 chiusi (D-040, D-041, D-043); aperto il 16, link di pagamento, prima del passo 9. **Pezzo 1 fatto** (2026-11-06, guida 18, `passi/07-prenotazione.md`), codice scritto da Claude su richiesta di Nicholas: migrazione V4 (tabella `booking` con il vincolo di non sovrapposizione parziale, impostazioni di limite e approvazione con il `check` su due colonne, `service.evaluation`); interfaccia `OccupiedTime` di `schedule` implementata da `booking` (D-044), così gli orari liberi escludono le prenotazioni vere ma non i blocchi scaduti; primo test di concorrenza contro PostgreSQL. 321 test verdi (`mvnw clean test`).

**Pezzo 2 fatto** (2026-11-06, guida 19, D-045), codice scritto da Claude su richiesta di Nicholas, che ha chiesto di procedere senza conferme: `POST /api/customer/bookings` e `GET /api/customer/bookings/{id}`; controlli in ordine (servizio, lucchetto sul cliente, limite, orario libero con lo stesso calcolo degli orari liberi, scadenza pigra dei blocchi scaduti, `insert`); violazione del vincolo tradotta in `SLOT_NOT_AVAILABLE`; modulo `customer` con l'entità, il lucchetto (`MANDATORY`) e il cliente corrente provvisorio (`X-Customer-Id`). Test di concorrenza sul limite, verificato anche "al contrario": senza il lucchetto fallisce (2 prenotazioni su 1). 353 test verdi. **Prossimo: pezzo 3**, stati e approvazione: macchina a stati, "pagamento autorizzato" simulato, incasso e annullamento provvisori, eventi di dominio.

Risposte dello studio del 2026-11-02 e 2026-11-06 sul calendario (requisiti, "Calendario"): scatto di un'ora, ma valutazione da 30 minuti e seduta successiva possono iniziare alla mezz'ora; nessuna pausa; seduta sempre dentro la fascia; niente prenotazioni per il giorno stesso (punto 14 chiuso); orari lunedì–venerdì 9–13 e 14–19, modificabili anche per date future; **stanza unica**, ma la sovrapposizione con una valutazione si **segnala**, non si impedisce. Punti 3 e 13 chiusi con D-033 e D-034.

Decisioni del 2026-11-06:
- **approvazione** delle prenotazioni automatica o manuale, per professionista, con tempo di risposta scelto dal professionista e annullamento se non risponde, più un anticipo minimo di più di 24 ore (D-030);
- **importo bloccato** sulla carta e incassato solo all'approvazione, con il *manual capture* di Stripe: modifica D-007 (D-031);
- **notifiche push** con Web Push, dal pannello installabile come app (D-032).

Decisioni del 2026-10-29 (solo documentazione): niente IVA perché entrambi i professionisti sono forfettari (D-022, modifica D-011 e D-012); calendario non pubblico (requisiti, "Riservatezza del calendario"); orizzonte di prenotazione e limite di sedute future (D-023); prenotazione dal pannello per conto di un cliente (D-024).

Avvio del database in locale, dalla radice: `docker compose -f infra/docker-compose.dev.yml --env-file .env up -d`

## Percorso

| # | Passo | Chi scrive | Stato |
|---|---|---|---|
| 1 | Regole e requisiti | — | ✅ fatto → `requisiti.md` |
| 2 | Architettura e scelte tecniche | — | ✅ fatto → `architettura.md` |
| 3 | Modello dei dati | — | ✅ fatto → `modello-dati.md` |
| 4 | Fondamenta: repository, Spring Boot, PostgreSQL in Docker, Flyway, prime tabelle | Nicholas | ✅ fatto → `passi/04-fondamenta.md` |
| 5 | Catalogo e professionisti: servizi, prezzi, impostazioni | Claude, su richiesta di Nicholas | ✅ fatto → `passi/05-catalogo.md` |
| 6 | Calendario: orari di lavoro, indisponibilità, calcolo degli orari liberi | Claude, su richiesta di Nicholas | ✅ fatto → `passi/06-calendario.md` |
| 7 | Prenotazione: blocco dell'orario, approvazione (D-030), vincolo di non sovrapposizione, lavori programmati, eventi di dominio | Claude, su richiesta di Nicholas | 🔄 in corso (pezzi 1–2 di 6) → `passi/07-prenotazione.md` |
| 8 | Accesso: codice via email, sessioni, ruoli (Spring Security); notifiche email e push lato backend (D-032, D-039) | Nicholas, o Claude su sua richiesta | |
| 9 | Pagamenti: Stripe Checkout con *manual capture* (D-031) e webhook | Nicholas, o Claude su sua richiesta | |
| 10 | Crediti e pacchetti: lotti, movimenti, acquisto, prenotazione con credito | Nicholas, o Claude su sua richiesta | |
| 11 | Cancellazioni | Nicholas, o Claude su sua richiesta | |
| 12 | Frontend cliente: prenotazione e "le mie sedute" | Claude | |
| 13 | Frontend pannello professionisti, installabile come app con notifiche push (D-032) | Claude | |
| 14 | Messa online: Dockerfile, Compose, Caddy, GitHub Actions, backup su Hetzner | Nicholas, o Claude su sua richiesta | |

**Chi scrive il codice del backend** (CLAUDE.md, dal 2026-10-29): per ogni pezzo Claude scrive prima una guida di studio con le scelte da fare; Nicholas sceglie; il codice lo scrive Claude solo su **richiesta esplicita** di Nicholas, dopo che ha compreso le spiegazioni, altrimenti lo scrive Nicholas. Il passo 4 l'ha scritto Nicholas, i passi 5 e 6 Claude su sua richiesta.

Il frontend può partire prima, appena esistono le prime API (dopo il passo 7), se serve mostrare qualcosa allo studio.

## Punti aperti

Elencati in `requisiti.md`, sezione "Punti aperti". Da chiudere prima del passo indicato:
- Uno o due conti Stripe (quasi certamente due: due partite IVA distinte) → prima del passo 9
- ~~Scatto di inizio delle sedute~~ → chiuso il 2026-11-06 (D-033)
- ~~Approvazione: valore iniziale e conferma dentro il preavviso~~ → chiuso il 2026-11-06: manuale; la conferma tardiva va bene (D-030)
- ~~Orari di lavoro modificabili da una data futura~~ → chiuso il 2026-11-06 (D-034)
- ~~Stanza unica: segnalazione nel pannello~~ → chiuso il 2026-11-06: proprietà `evaluation` del servizio; la segnalazione mostra orario e "valutazione", mai il nome del cliente (D-043)
- ~~"Non per il giorno stesso" contro il preavviso di 24 ore~~ → chiuso il 2026-11-06 (va bene; credito a mano, punto aperto 6)
- Cancellazione da parte dello studio → prima del passo 11
- ~~Aliquota IVA~~ → chiuso il 2026-10-29: niente IVA (D-022)
- ~~Modo di pagamento delle prenotazioni dal pannello~~ → chiuso il 2026-11-06: in studio (D-040)
- ~~Valori iniziali di orizzonte e limite~~ → chiuso il 2026-11-06: 21 giorni, 4 prenotazioni future (D-041)
- Link di pagamento per le prenotazioni dal pannello (requisiti, punto aperto 16) → prima del passo 9
- Servizio email (orientamento: Brevo) → prima del passo 8
- Codice di accesso anche via SMS → al passo 8 o dopo la prima versione
- Concessioni discrezionali del professionista (crediti, omaggi) → prima del passo 10
- Cancellazione dei dati di un cliente (GDPR, anonimizzazione) → prima del passo 14

## Note per i passi futuri

- **Prima del passo 12 (frontend)**: decidere il contratto OpenAPI (springdoc, previsto dall'architettura). Verificarne la compatibilità con Spring Boot 4; i tipi del frontend si generano da lì.
- **Passo 7 (prenotazione), prima decisione**: `booking` chiederà a `schedule` se un orario è libero, ma il calcolo ha bisogno delle prenotazioni di `booking`: un ciclo tra moduli. Due uscite (guida 16, 6.1): un'interfaccia dichiarata da `schedule` e implementata da `booking` (inversione delle dipendenze, come `CurrentPractitioner`), oppure l'API pubblica degli orari liberi spostata in `booking`. `FreeSlots` riceve gli occupati come `List<TimeRange>` e non cambia in nessuno dei due casi. `booking` userà `CatalogService.serviceOnSale` per la durata (D-038) e `TimeRange` per gli intervalli.
- **Passi 7, 8, 9 e 13, approvazione** (D-030, D-031, D-032, D-039): al passo 7 nuovi stati della prenotazione, lavoro programmato per le approvazioni scadute, eventi di dominio senza ascoltatori; al passo 8 tabella `push_subscription`, chiavi VAPID in `.env.example`, notifiche push ed email agganciate agli eventi del passo 7; al passo 9 *manual capture* con incasso e annullamento verso Stripe; al passo 13 *service worker* e pagina delle richieste nel pannello. Validazione incrociata delle impostazioni: `approval_window_hours` ≤ `min_booking_notice_hours`. Al passo 9, verificare sulla documentazione di Stripe le commissioni sui blocchi annullati e la durata del blocco per le carte europee.
- **Passi 7 e 13, stanza unica**: la sovrapposizione tra una valutazione di un professionista e una seduta dell'altro si **segnala** nel pannello, non si impedisce (D-043). Il calcolo degli orari liberi e il vincolo nel database restano per professionista. La valutazione si riconosce da `service.evaluation` (V4), e serve una query sulle sovrapposizioni tra professionisti diversi che non riveli il nome del cliente (pezzo 6). Attenzione a D-002: la segnalazione la produce il modulo delle prenotazioni, il pannello la chiede a lui.
- **Passo 7 (prenotazione)**: la V4 aggiunge `max_future_bookings` a `practitioner` (default 4, D-041; `booking_horizon_days` c'è già dalla V3, D-035) e il servizio di prenotazione fa rispettare orizzonte e limite solo quando prenota il cliente. Attenzione: una `PENDING_PAYMENT` con blocco già scaduto non deve contare nel limite né far scattare il vincolo (D-042, guida 18, 3.5); e due richieste simultanee dello stesso cliente potrebbero superare il limite, perché è un controllo in Java e non un vincolo del database. Prenotazione dal pannello (D-024, D-040): `booked_by = PRACTITIONER`, `paid_with = IN_STUDIO`, creazione del cliente se non esiste, e lo **stesso lucchetto sul cliente** della richiesta (D-045), perché conta nel limite.
- **Passi 10 e 11, schema delle prenotazioni** (D-042): i `check` di `booking` contengono solo i valori del passo 7. Il passo 10 aggiunge `CREDIT` a `paid_with`; il passo 11 aggiunge `CANCELLED_IN_TIME`, `CANCELLED_LATE` (e poi `COMPLETED`, `NO_SHOW`) allo stato, ognuno con una migrazione nuova (`drop constraint` + `add constraint`) e il valore nuovo nell'`enum` Java.
- **Passo 8 (accesso), professionista corrente**: rimuovere `DevHeaderCurrentPractitioner` (D-028), che legge l'identità da un header dichiarato dal client, e sostituirlo con un'implementazione di `CurrentPractitioner` basata sull'utente autenticato (`practitioner.user_id`). **Criterio di "fatto" del passo 8.**
- **Passo 8 (accesso), cliente corrente**: il passo 7 introduce un sostituto provvisorio del cliente corrente, costruito come `DevHeaderCurrentPractitioner` (un solo punto, identità dichiarata dal client). Va rimosso e sostituito con il cliente dell'utente autenticato (`customer.user_id`). **Criterio di "fatto" del passo 8.**
- **Passo 8, notifiche** (D-032, D-039): email e push lato backend si agganciano agli eventi di dominio pubblicati dal passo 7 (es. "richiesta in attesa", "prenotazione confermata"). Email di riserva quando il professionista non ha dispositivi o l'invio push fallisce.
- **Passo 9 (pagamenti), "pagamento autorizzato" simulato**: il passo 7 introduce un modo provvisorio di simulare il webhook di Stripe, che non deve mai essere attivo in produzione. Va rimosso e sostituito dal webhook vero, insieme all'implementazione provvisoria di incasso e annullamento del blocco (D-031). **Criterio di "fatto" del passo 9.**
- **Passo 8 (accesso), API pubbliche e account disattivati**: le API senza accesso stanno tutte sotto `/api/public/**` (D-029), quelle del pannello sotto `/api/panel/**` (professionisti), quelle del cliente sotto `/api/customer/**` (D-045). La configurazione di Spring Security apre `/api/public/**`, assegna gli altri due prefissi al loro ruolo e chiude tutto il resto con una regola sola, così un'API dimenticata resta chiusa. Il login deve rifiutare un account disattivato: oggi `DevHeaderCurrentPractitioner` controlla solo che il professionista esista, quindi un professionista disattivato può ancora usare il pannello.
- **Passo 8 (accesso), riservatezza del calendario**: ogni regola di "Riservatezza del calendario" (requisiti) diventa un **test automatico**. Almeno: utente anonimo rifiutato su prenotazioni e sedute (vede solo gli orari liberi); professionista A rifiutato sulle prenotazioni di B; cliente rifiutato sulle sedute di altri. **Criterio di "fatto" del passo 8**: questi test esistono e sono verdi.
- **Passo 8 (accesso)**: sessione lunga che si rinnova a ogni visita (ordine di grandezza 30–90 giorni), per ridurre i codici via email senza introdurre password (D-014). Valutare il codice via SMS (requisiti, punto aperto 7): costo per messaggio, verifica del numero, limiti contro gli abusi (invio di SMS a raffica verso numeri a pagamento), consenso. Se il telefono diventa un modo per accedere, deve stare in `app_user`, unico e in un formato standard (internazionale, es. `+39...`), con la data di verifica.
- **Passi 10–11 (crediti, cancellazioni)**: concessioni discrezionali del professionista (requisiti, punto aperto 6). Il modello a lotti e movimenti (D-009, D-010) le regge senza stravolgimenti: un omaggio o un credito concesso su una cancellazione tardiva è un **lotto** con una nuova origine (es. `GRANT`), una correzione è un **movimento** con un nuovo motivo (es. `ADJUSTMENT`). Serve la traccia: chi l'ha concesso, quando, con una nota. Una concessione non cambia lo stato della prenotazione (resta `CANCELLED_LATE`): cambia solo il credito. Perimetro deciso: credito su cancellazione tardiva, sedute omaggio, proroga di una scadenza, ognuno per i propri clienti. Attenzione alla **proroga**: è l'unica che *modifica* qualcosa (la scadenza di un lotto) invece di aggiungere; per non perdere la storia va registrato anche l'evento (vecchia e nuova scadenza, chi, quando, perché).
- **Passo 8 (accesso), riattivazione**: con `unique` sull'email, un cliente disattivato che torna con la stessa email va **riattivato**, non registrato di nuovo.
- **Passo 13 (pannello)**: gestione dei professionisti da parte dell'admin (D-021), compresa la disattivazione. Prenotazione per conto di un cliente e creazione del cliente con nome, cognome ed email (D-024). Da chiarire prima: quali clienti può cercare e vedere un professionista.

