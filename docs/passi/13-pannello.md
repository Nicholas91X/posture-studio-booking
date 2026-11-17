# Passo 13 — Il pannello

> **Stato: chiuso** (2026-11-17). Unito in `main` con un merge (PR #10, branch `feat/step-13-panel`). 1137 test del backend, 15 test end-to-end e 16 prove unitarie del frontend verdi. Push vera ricevuta in sviluppo dal Chrome del computer (Nicholas, 2026-11-17). La prova su iPhone e Android veri è un criterio del passo 14.

## Obiettivo

L'app dei professionisti e dell'admin, nella stessa applicazione Next.js del sito dei clienti, installabile sul telefono e con le notifiche push. Le regole restano nel backend: il pannello mostra e invia (D-005).

Il codice del frontend l'ha scritto Claude. Le tre API mancanti le ha scritte Claude su richiesta esplicita di Nicholas (D-088). Niente guide di studio, come chiedeva il brief.

## Criteri di "fatto" (dal brief)

- [x] **I sei pezzi funzionano da telefono (simulato)**: 5 test end-to-end su un Pixel 7 simulato. In più, un giro di schermate di tutte le pagine (non versionato), guardate una per una, con il controllo "niente più largo dello schermo".
- [x] **Le frasi dei codici d'errore del pannello sono nel dizionario**: `errors.ts`, nessun "Operazione non possibile" rimasto. In più `PANEL_WORDING` per i codici che il professionista deve leggere in un altro modo rispetto al cliente.
- [x] **Push ricevuta in sviluppo dal browser del computer**: **fatta da Nicholas il 2026-11-17** con il suo Chrome, come Anna, su `http://127.0.0.1:3000` (errore 8): popup, permesso, notifica di prova arrivata. "Funziona tutto perfettamente". Prima, la catena era stata provata a pezzi (sotto), perché il Chrome avviato da Playwright non si collega al servizio push di Google.
- [x] **Test end-to-end verdi; suite completa verde**: 15 su 15 (10 del cliente, 5 del pannello); 1137 test del backend; 16 prove unitarie.
- [x] **stato.md aggiornato** (prova su iPhone e Android al passo 14); PR verso main; riepilogo.

## Cosa è stato costruito

### Backend: tre API (D-088)

| File | Cosa fa |
|---|---|
| `customer/FoundCustomer` | `record` (id, creato adesso): la risposta di `CustomerService.findOrCreate` |
| `booking/PanelBookingCreated` | La risposta di `POST /api/panel/bookings`: i campi di `PanelBookingView` più `customerCreated` |
| `BookingService.roomOverlapsForPanel` + `GET /api/panel/bookings/room-overlaps` | La stanza prima di prenotare: stessa query e stessa regola delle liste (D-049) |
| `PushMessages.test`, `PushSubscriptionService.sendTest`, `POST /api/panel/push-subscriptions/test` | La notifica di prova, con `{delivered}` |

Test: 2 in `BookingPanelApiTest` per il cliente creato, 4 per la stanza, 4 in `PushSubscriptionApiTest` per la prova (con il servizio push finto), e `CustomerServiceTest` adattato.

### Frontend

| File | Cosa fa |
|---|---|
| `components/panel/RequirePanel.tsx`, `PanelPage.tsx` | La guardia per ruolo: chi non è entrato va all'accesso, l'admin sulle pagine del professionista va alle sue, un cliente legge che il pannello non è suo |
| `components/AppShell.tsx` | Le voci in basso secondo il ruolo: Agenda, Richieste, Prenota, Clienti, Altro; per l'admin Professionisti, Conti, Altro |
| `app/HomeClient.tsx`, `lib/session.tsx` (`homeOf`) | La home manda un professionista al pannello e l'admin ai professionisti, anche quando si arriva dal link di una push |
| `app/pannello/page.tsx` (`AgendaClient`) | Giorno e settimana con la griglia del passo 12 resa generica (`WeekGrid` riceve blocchi, non prenotazioni), le segnalazioni, l'avviso "N richieste da approvare" |
| `app/pannello/richieste` | Approva, rifiuta (con la domanda prima), la scadenza |
| `app/pannello/sedute/[id]` | Una seduta: cancella come studio (D-075, cosa resta al cliente detto prima), "non presentato" e correzione, link di pagamento con "Condividi su WhatsApp" e "Copia il link" (D-059) |
| `app/pannello/prenota` | Prenotazione per un cliente per email, nome e cognome (D-048); la stanza controllata prima della conferma; l'avviso "Cliente nuovo: controlla l'email" quando il backend dice `customerCreated` |
| `app/pannello/orari` | Versioni settimanali con data di inizio (D-034): nuova, modifica, elimina solo se `editable`; assenze |
| `app/pannello/catalogo` | Servizi (con "valutazione") e pacchetti: nuovi, modifica, fuori e dentro la vendita |
| `app/pannello/impostazioni` | Le impostazioni e la scheda delle notifiche |
| `app/pannello/clienti`, `clienti/[id]` | "I miei clienti" (D-066) con la ricerca; i crediti di un cliente con storia e note; omaggio, proroga e correzione con la nota obbligatoria (D-067) |
| `app/pannello/professionisti`, `professionisti/[id]`, `conti` | Admin: crea, rinomina, conto di incasso (D-057), sedute future e cancellazione per conto (D-081), disattivazione con il 409 e l'avviso dei crediti aperti (D-077) |
| `app/pannello/altro` | Le sezioni meno usate e "Esci" |
| `lib/panel.ts` | `useApi` e `useAction` (dati e azioni delle pagine), i servizi propri, gli stati detti dal punto di vista del professionista |
| `lib/format.ts` | `romeDateTime` (giorno e ora di Roma → istante con lo scarto giusto), `parseEuro` (euro scritti → centesimi interi); 3 prove in `format.test.ts` |
| `public/sw.js` | Il service worker: solo `push` e `notificationclick`, nessun `fetch` |
| `lib/push.ts`, `components/panel/Notifications.tsx` | Stato del permesso, iPhone non installato, iscrizione con la chiave VAPID, "più tardi" ricordato per una settimana, la notifica di prova, il distacco all'uscita |
| `public/pannello.webmanifest`, `public/icons/`, `app/pannello/layout.tsx` | L'app installabile: manifest solo sulle pagine del pannello, `apple-mobile-web-app-capable` per iPhone, icone dal logo |
| `next.config.ts` | `sw.js` mai in cache, il tipo del manifest |
| `e2e/panel.spec.ts` | I 5 percorsi del brief |

## Prova della push in sviluppo (fatta il 2026-11-17)

**Già provato**, a pezzi:
- il backend manda la push di prova e la cifra come vuole un browser: `PushSubscriptionApiTest`, decifrata dal browser finto dei test;
- il service worker la mostra con titolo, testo, icona e indirizzo giusti: push consegnata a Chrome con il protocollo di debug (`ServiceWorker.deliverPushMessage`), notifica letta con `getNotifications()`;
- popup, permesso concesso, registrazione del service worker: provati in un Chrome avviato da Playwright, che però non riceve una sottoscrizione da Google e resta in attesa (da qui il limite di 20 secondi, errore 4).

**Procedura**, con il Chrome o l'Edge normale del computer (esito: tutto come previsto):
1. Backend con il codice di questo branch (`.\mvnw.cmd spring-boot:run`), frontend (`npm run dev`), database e Mailpit accesi.
2. `http://127.0.0.1:3000/pannello` (o `localhost`, se i suoi cookie non sono troppi: errore 8), accedi come professionista: in sviluppo Anna Rossi, `anna@example.com`, codice da Mailpit. L'account di `ADMIN_EMAIL` è l'admin, che non ha notifiche.
3. Nel popup "Attiva le notifiche": **Attiva**, poi **Consenti** nella domanda del browser.
4. Altro › Impostazioni e notifiche › **Manda una notifica di prova**: la pagina dice "Notifica di prova inviata" e compare la notifica di Windows "Notifiche attive".
5. Toccandola si apre il pannello.
6. Se dice "Nessun dispositivo ha ricevuto la notifica": nel log del backend la riga di `WebPushClient` dice perché. Un 403 vuol dire chiavi VAPID diverse da quelle con cui il browser si è iscritto.

## Concetti chiave

**Nascondere non è vietare.** Il pannello mostra a ogni ruolo solo le sue voci, e la guardia manda ognuno nella sua parte. Ma la sicurezza è nel backend: `/api/panel/**` solo ai professionisti, `/api/admin/**` solo all'admin (D-051). Un professionista che scrive a mano l'indirizzo di una pagina dell'admin vede un avviso; se chiamasse l'API riceverebbe 403.

**Il service worker che non vede le richieste.** Un service worker è un piccolo programma che il browser tiene vivo anche a pagina chiusa. Di solito intercetta le richieste (evento `fetch`) per lavorare offline, e così può tenere copie delle risposte. Il nostro non ha `fetch`: riceve solo le push e i tocchi sulle notifiche. Niente dati di clienti salvati sul telefono, niente pagine vecchie servite per errore.

**Il giro di una push.** Il browser chiede al suo servizio push (Google, Apple, Mozilla) un indirizzo personale, firmato con la nostra chiave pubblica VAPID. Il pannello lo manda al backend. Il backend cifra il messaggio con le chiavi del browser e lo spedisce a quell'indirizzo. Il servizio push lo consegna al telefono, e il service worker lo mostra. Il servizio push vede passare il messaggio ma non può leggerlo.

**iPhone.** Su iPhone le push web arrivano solo se il pannello è aggiunto alla schermata Home (iOS 16.4 e successivi), e la domanda del permesso parte solo da un tocco. Per questo il popup spiega e non chiede niente: è "Attiva" che fa partire la domanda del sistema. In Safari non installato, il popup spiega come aggiungere il pannello alla schermata Home.

**Un "no" non si richiede.** Se il permesso è negato, il sito non può più chiederlo: solo le impostazioni del telefono o del browser. Per questo il pannello non insiste e spiega dove andare. "Più tardi" invece è nostro: si ricorda sul dispositivo (`localStorage`) per una settimana.

**Giorno e ora di Roma da un modulo.** Un campo data e un campo ora danno "2026-11-10" e "09:30", senza fuso. Il backend vuole un istante. `romeDateTime` cerca lo scarto di Roma in quel momento (+01:00 d'inverno, +02:00 d'estate) e lo ricalcola una seconda volta, così è giusto anche nei giorni del cambio dell'ora. È una conversione, non una regola: se l'orario si può prenotare lo dice il backend.

**Un `record` invece di un flag nascosto (Java).** `findOrCreate` restituiva un `long`. Per sapere se il cliente era nuovo, invece di un secondo metodo o di un parametro da riempire, restituisce un `record FoundCustomer(long id, boolean created)`: due valori con un nome, immutabili, con `equals` e `toString` già fatti (Java 16+). Il compilatore ha indicato tutti i chiamanti da adattare.

**JPA e JDBC nella stessa transazione.** Nel test, la cancellazione del dispositivo morto era ancora nella coda di Hibernate quando il test contava le righe con `JdbcTemplate`: JDBC non svuota quella coda. Si prova dall'esterno, con una seconda richiesta (errore 2).

## Decisioni

- **D-088**: le tre API del pannello (il cliente creato adesso, la stanza prima di prenotare, la notifica di prova), con la nota sulla riservatezza.
- Applicate senza schede nuove: D-024, D-030, D-032, D-034, D-039, D-043, D-048, D-049, D-054, D-056, D-057, D-059, D-066, D-067, D-075, D-077, D-079, D-081, D-087.

## Errori fatti e come sono stati corretti

1. **Tre API mancanti, trovate prima di scrivere il codice.** Confrontando ogni pezzo del brief con il contratto OpenAPI: niente "cliente nuovo" nella risposta, niente segnalazione della stanza per un orario non ancora prenotato, niente notifica di prova. Segnalato, e Nicholas ha chiesto di aggiungerle (D-088). Indovinarle nel frontend avrebbe messo regole fuori dal backend.
2. **Il dispositivo cancellato "c'era ancora"** nel test della push di prova: vedi "JPA e JDBC" sopra. Corretto il test, non il codice.
3. **Il popup delle notifiche non compariva nei test end-to-end.** Chromium senza finestra risponde sempre "negato" alle notifiche, e con "negato" il popup giustamente non si mostra. I test segnano "più tardi" all'avvio; il popup è stato provato in un browser con la finestra.
4. **"Attivazione…" per sempre.** Nel Chrome avviato da Playwright, `pushManager.subscribe()` non risponde mai: è avviato senza il collegamento di rete in background che il servizio push di Google usa. Su un telefono senza rete potrebbe succedere lo stesso. Ora c'è un limite di 20 secondi e una frase chiara, mai un testo tecnico.
5. **"Conto di incasso" fermo su "Caricamento…"** per un professionista senza conto. Il 404 diventava `null`, e `useApi` usa `null` per "non ancora caricato". Visto nelle schermate; ora la risposta è `{ account: null }`.
6. **Il test dell'admin falliva solo nella suite completa.** L'admin chiedeva il quarto codice in un minuto: il limite di D-050 è 3 ogni 15 minuti per indirizzo, quindi il backend risponde 202 e non manda niente, apposta. Ora l'admin entra una volta sola dal browser, e la preparazione usa la stessa sessione per le API (`Actor.of`).
7. **La vista giorno nascondeva i nomi** nei blocchi: la regola del passo 12 li toglie sul telefono, pensata per sette colonne strette. Con una colonna sola c'è spazio: `week-single`.
8. **Il sito non faceva accedere: 400 a ogni chiamata** (trovato da Nicholas nella prova della push). Il browser manda a `localhost` i cookie di tutti i progetti aperti su `localhost`, qualunque porta; insieme superavano gli 8 KB di intestazioni che Tomcat accetta, e Tomcat risponde 400 prima di arrivare all'applicazione. Riprodotto con `curl` (9 KB di cookie: 400; 3 KB: 200). Non è un difetto del codice, e in produzione il dominio ha solo i suoi cookie: in sviluppo si usa `127.0.0.1`, che per il browser è un altro sito (`avvio.md`, problemi già incontrati). Prima ancora, il backend nuovo non partiva perché quello vecchio, acceso da giorni, teneva la 8080.
9. **Errore di hydration sulla barra in basso** (trovato da Nicholas nella console). Il numero di colonne della barra stava in uno stile scritto nell'HTML (`style`), calcolato dalle voci del ruolo. L'HTML del server e quello che React si aspettava nel browser non coincidevano, e React non ricuce gli attributi diversi. Ora le colonne le decide il CSS (`grid-auto-flow: column`), e non c'è più niente da far coincidere. Nella stessa prova: su `127.0.0.1` Next rifiutava il ricaricamento automatico, perché in sviluppo accetta solo `localhost`. Aggiunto `allowedDevOrigins: ["127.0.0.1"]`, che vale solo in sviluppo. Verificato aprendo quattro pagine su `127.0.0.1`, da telefono e da computer: nessun errore nella console.

## Domande da colloquio

### Domanda 1: Il pannello nasconde le pagine dell'admin a un professionista. È sicurezza?
**Risposta modello.** No, è comodità. La sicurezza è nel backend: ogni API dell'admin richiede il ruolo ADMIN in Spring Security, con `denyAll` per tutto il resto. Il frontend può essere modificato da chi lo usa, quindi non decide niente. Nasconde solo per non mostrare pulsanti che darebbero 403.

### Domanda 2: Come funziona una notifica Web Push, e chi può leggerne il contenuto?
**Risposta modello.** Il browser si iscrive al proprio servizio push con la nostra chiave pubblica VAPID e ottiene un indirizzo, che manda al backend. Il backend cifra il messaggio con le chiavi del browser (aes128gcm), firma la richiesta con la chiave privata VAPID e la spedisce. Il servizio push consegna senza poter leggere; il service worker decifra e mostra. Il testo compare anche a schermo bloccato: per questo dentro c'è solo giorno e ora, mai il nome del cliente.

### Domanda 3: Perché il vostro service worker non gestisce l'evento `fetch`?
**Risposta modello.** Perché non ci serve il funzionamento offline, e un service worker con `fetch` può tenere risposte in cache. Vorrebbe dire dati dei clienti sul telefono e il rischio di mostrare un'agenda vecchia. Il pannello deve sempre leggere lo stato dal backend; il service worker serve solo a ricevere le push.

### Domanda 4: Come gestisci il permesso delle notifiche senza infastidire?
**Risposta modello.** Prima spiego in un popup nostro, e solo il tocco su "Attiva" fa partire la domanda del sistema: su iPhone è obbligatorio, e altrove evita un "no" di riflesso, che non si può più richiedere. "Più tardi" si ricorda sul dispositivo per una settimana. Con "negato" spiego dove riattivarlo nelle impostazioni. Su iPhone non installato spiego come aggiungere il pannello alla schermata Home.

### Domanda 5: Il backend deve dire al frontend se il cliente è stato creato. Come cambi la firma di `findOrCreate`?
**Risposta modello.** Restituisco un `record` con l'id e un booleano `created`, invece di un `long`: un tipo piccolo, immutabile, con nomi che dicono cosa sono. Il compilatore segnala tutti i chiamanti. La risposta HTTP diventa un `record` nuovo, piatto, con lo stesso nome dei campi di prima più quello nuovo, così i client esistenti continuano a funzionare. E valuto cosa rivela: "non creato" dice che quell'email era già cliente dello studio.

### Domanda 6: Un test end-to-end passa da solo e fallisce nella suite completa. Come lo affronti?
**Risposta modello.** Cerco quello che i test condividono: dati, orologio, limiti. Qui era il limite dei codici di accesso, 3 ogni 15 minuti per indirizzo: l'admin entrava quattro volte in un minuto, e il quarto codice non partiva, apposta. Non si alza il limite per far passare il test: si fa entrare l'admin una volta e si riusa la sua sessione.
