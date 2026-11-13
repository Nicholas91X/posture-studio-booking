# Le mie sedute a calendario: vista settimanale e mensile

Data: 2026-11-13 · Richiesta di Nicholas · Approvato a parole lo stesso giorno · Decisione: D-087

## Cosa si costruisce

In "Le mie sedute" (`/prenotazioni`), oltre all'elenco di oggi, due viste a calendario delle **proprie** sedute:

- la **settimana** come griglia giorni × ore, con le sedute come blocchi colorati nello stato;
- il **mese** come griglia classica, con un puntino per seduta e il giorno scelto elencato sotto.

Toccando una seduta si apre la sua pagina (`/prenotazioni/{id}`), dove ci sono già tutte le azioni. Le griglie non fanno niente: mostrano e portano.

**Nessuna modifica al backend.** Nessuna libreria nuova.

### Cosa non si fa (scelte del 2026-11-13)

- Niente azioni dalla griglia (cancellare, pagare): stanno nella scheda e nella pagina della seduta.
- Niente sedute annullate nelle griglie (vedi "I dati"): restano nell'elenco.
- Niente preferenza ricordata sul telefono: la scheda di default resta "Prossime".
- Niente libreria di calendario: pesa, porta i suoi stili e disegna nel fuso del telefono.
- Niente pagine separate (`/prenotazioni/settimana`): un selettore nella stessa pagina.

## 1. Dove sta e come ci si muove

Una sola barra di schede in cima alla pagina, al posto delle due di oggi:
**Prossime · Passate · Settimana · Mese**. Le prime due sono l'elenco di adesso, identico; le altre due le griglie.

La vista e il periodo stanno nell'indirizzo:

| Vista | Indirizzo | Note |
|---|---|---|
| Prossime (default) | `/prenotazioni` oppure `?vista=prossime` | come oggi |
| Passate | `?vista=passate` | come oggi |
| Settimana | `?vista=settimana&da=2026-11-10` | `da` è un giorno della settimana: si aggancia al suo lunedì |
| Mese | `?vista=mese&da=2026-11-01&giorno=2026-11-13` | `da` è un giorno del mese: si aggancia al primo; `giorno` è il giorno scelto, facoltativo |

- Senza `da` si vede la settimana (o il mese) di **oggi a Roma**.
- Un `da` o un `giorno` che non è una data valida (`YYYY-MM-DD`) vale come assente. Un `vista` sconosciuto vale "prossime".
- Schede, frecce, "Oggi" e caselle del mese sono **link** (`<Link>`) che cambiano la query: ricaricando, tornando indietro dalla pagina di una seduta o salvando il link si resta dove si era.
- Sopra ogni griglia la riga di navigazione: `◂` **titolo del periodo** `▸`, e a destra un piccolo "Oggi" (pulsante secondario, pillola) che riporta al periodo corrente, **nascosto** quando ci si è già.

Titoli: settimana "10 – 16 novembre 2026", a cavallo di mesi "27 ottobre – 2 novembre 2026", a cavallo di anni "29 dicembre 2025 – 4 gennaio 2026"; mese "Novembre 2026".

## 2. I dati

- **Elenco**: come oggi, `GET /api/customer/bookings?period=upcoming|past`; il taglio passato/futuro lo fa il backend (D-082). Non si tocca.
- **Griglie**: una chiamata a `GET /api/customer/bookings` **senza** `period`, che il backend già risponde con tutte le sedute del cliente (le più lontane nel futuro prima). Letta una volta per visita alla pagina e tenuta finché la pagina è aperta, anche passando da settimana a mese o all'elenco e ritorno. (Nota futura: se la lista diventasse lunga, centinaia di sedute, si aggiunge `from/to` al backend. Non ora.)
- **Quali sedute compaiono**: solo gli stati "veri", cioè `PENDING_PAYMENT`, `PENDING_APPROVAL`, `CONFIRMED`, `COMPLETED`, `NO_SHOW`. Le altre (`CANCELLED_IN_TIME`, `CANCELLED_LATE`, `CANCELLED_BY_STUDIO`, `EXPIRED`, `REJECTED`, `APPROVAL_EXPIRED`, `WITHDRAWN`) no: in un calendario un appuntamento che non c'è più confonde. È una scelta di **presentazione** (quali righe mostra una vista), non una regola di prodotto: gli stati li decide il backend, e l'elenco continua a mostrare tutto. La lista degli stati mostrati è un `Record<BookingStatus, boolean>`: quando il contratto guadagna uno stato, il frontend smette di compilare finché non si decide (come `labels.ts`).
- **Giorno e ora** di ogni seduta sono quelli di **Roma**, calcolati con `Intl.DateTimeFormat` e `timeZone: "Europe/Rome"` come già fa `format.ts`; mai con l'orologio del telefono. Durata del blocco = `endsAt − startsAt` in minuti.
- **Nomi** di servizio e professionista da `useDirectory`, come nell'elenco.

## 3. La settimana

Una griglia CSS (`display: grid`):

- **Colonne**: una stretta per le ore (circa 2,2 rem) e sette uguali, da lunedì a domenica.
- **Testa**: "lun 10 … dom 16"; il numero di oggi in un cerchio indaco.
- **Righe**: unità di **5 minuti**, 4 px ciascuna (48 px per ora). Un blocco occupa dalla riga del suo inizio a quella della sua fine: posizione e altezza seguono orario e durata per qualsiasi servizio (30, 45, 60 minuti…). Una riga sottile ogni ora, con l'etichetta "9:00, 10:00…" nella colonna delle ore.
- **Ore mostrate**: dalla prima all'ultima seduta della settimana, **e almeno dalle 9 alle 19**. 9–19 è un'ampiezza di disegno, non una regola: se una seduta cadesse alle 8 la griglia si allarga da sola. Precisamente: si parte dall'ora piena in cui inizia la prima seduta (8:30 → 8:00) o dalle 9 se è dopo; si arriva all'ora piena in cui finisce l'ultima, arrotondata in su (19:30 → 20:00), o alle 19 se è prima.
- **Altezza**: circa 500 px per 10 ore; scorre con la pagina, nessuno scorrimento interno.
- **Oggi**: la colonna ha un velo pervinca (`--color-periwinkle-tint`) e una riga indaco sottile all'ora attuale (posizionata al momento del disegno; nessun timer).

**Un blocco** è un `<Link href="/prenotazioni/{id}">`:

- colori dai token dello stato già usati per i badge: fondo `--color-<tono>-bg`, testo e bordo sinistro (3 px) `--color-<tono>`; tono `neutral` (svolta) → `--color-surface-muted` e `--color-text-muted`;
- contenuto sul telefono: solo l'**orario d'inizio** ("10:00"); da 48 rem in su anche il nome del servizio e, se ci sta, il professionista (una riga, `text-overflow: ellipsis`);
- `aria-label` completa: "Lunedì 10 novembre, 10:00–11:00, Seduta con Anna Rossi, Confermata".

**Sovrapposizioni** nello stesso giorno (raro: due professionisti alla stessa ora): le sedute si dividono la colonna in corsie uguali, fianco a fianco (partizione degli intervalli: a ogni seduta la prima corsia libera; larghezza = 100 % / numero di corsie di quel gruppo).

## 4. Il mese

Griglia classica:

- sette colonne lun–dom; cinque o sei righe secondo il mese (tutte le settimane che toccano il mese, da lunedì); i giorni dei mesi vicini **sbiaditi ma toccabili**;
- in ogni casella (un `<Link>` con `aria-label` "Giovedì 13 novembre, 2 sedute") il numero del giorno e un **puntino per seduta** nel colore dello stato, al massimo quattro, poi "+n";
- **oggi**: il numero nel cerchio indaco; il **giorno scelto**: la casella piena d'indaco con il testo chiaro, come il giorno scelto nella pagina degli orari ("il blu è la scelta", D-086).

Sotto la griglia, il giorno scelto per intero: titolo "Giovedì 13 novembre" e le sue sedute come **righe compatte**, ognuna un `<Link>` alla pagina della seduta: orario d'inizio e fine, "Seduta con Anna Rossi", badge dello stato. Senza sedute: "Nessuna seduta".

Giorno scelto di default: nel mese corrente **oggi**; negli altri mesi **nessuno**, e sotto la griglia "Tocca un giorno per vedere le sue sedute".

## 5. Casi limite

| Caso | Comportamento |
|---|---|
| Telefono in un altro fuso | Tutto passa per `Europe/Rome`; "oggi" è l'oggi di Roma (`todayInRome`); il lunedì si calcola sui giorni di calendario come stringhe (`addDays`, `Date.UTC`), senza orologio |
| Settimana a cavallo di mesi o anni | Il titolo si adatta (vedi parte 1); il mese della casella non conta |
| Cambio dell'ora (marzo, ottobre) | Le posizioni usano l'orologio di Roma di ogni istante: le 10:00 restano alle 10:00. Nessun "istante + 24 ore" |
| Seduta oltre mezzanotte | Non esiste con gli orari dello studio; per sicurezza il blocco si ferma al fondo della sua colonna |
| Settimana o mese senza sedute | La griglia resta; sotto, in grigio: "Nessuna seduta questa settimana." / "Nessuna seduta in questo mese." con il link "Prenotane una" alla home |
| Più di quattro sedute in un giorno (mese) | Quattro puntini e "+n" |
| Caricamento ed errori | Come l'elenco: `Loading` finché non ci sono sia le sedute sia i nomi, `ErrorNotice` per un errore |
| Accessibilità | Schede con `role="tab"`/`aria-selected`; blocchi, righe e caselle sono link con etichetta completa; le righe delle ore sono decorazione (`aria-hidden`); il focus è l'anello indaco di sempre. Un blocco di 30 minuti è alto 24 px, meno dei 44 px del tocco minimo: accettato perché è la natura di una griglia a ore (il calendario del telefono fa lo stesso), e la stessa seduta si raggiunge anche dal mese e dall'elenco |
| Modalità scura | I token degli stati e il velo pervinca esistono già nelle due versioni; nessun colore nuovo |

## 6. Struttura del codice

| File | Cosa fa |
|---|---|
| `src/lib/calendar.ts` (nuovo) | Conti di calendario, funzioni pure senza React: `mondayOf(day)`, `weekOf(monday)` (7 giorni), `monthGridOf(firstOfMonth)` (35 o 42 giorni), `firstOfMonth(day)`, `addMonths`, `weekdayOf(day)`, `romeClock(instant)` (ora e minuto a Roma), `minutesBetween`, `lanesOf(intervals)` (corsie), `weekTitle`, `monthTitle`, `isDay(text)` (una data valida `YYYY-MM-DD`) |
| `src/lib/format.ts` | Aggiunte piccole se servono (es. il nome del mese) |
| `src/components/calendar/WeekGrid.tsx` (nuovo) | La griglia settimanale: riceve le sedute della settimana (già filtrate), la mappa dei nomi e il lunedì; disegna |
| `src/components/calendar/MonthGrid.tsx` (nuovo) | La griglia mensile e l'elenco del giorno scelto |
| `src/components/calendar/PeriodNav.tsx` (nuovo) | La riga `◂ titolo ▸` + "Oggi", con i link |
| `src/app/prenotazioni/BookingsClient.tsx` | Legge `vista`, `da`, `giorno` dalla query; la barra a quattro schede; carica la lista con `period` (elenco) o completa (griglie); filtra per stato e periodo; passa alle griglie |
| `src/app/globals.css` | Gli stili di griglia, blocchi, puntini, navigazione (solo token di `tokens.css`) |
| `src/lib/calendar.test.ts` (nuovo) | Prove dei conti di calendario |
| `playwright.unit.config.ts` (nuovo) | Seconda configurazione del runner, senza server, `testDir: "src"`, `testMatch: "**/*.test.ts"`, con `process.env.TZ = "America/New_York"` |
| `package.json` | `"test:unit": "playwright test -c playwright.unit.config.ts"` |
| `e2e/customer.spec.ts` | Il test nuovo; il test "ogni pagina sta nello schermo" include le due viste |

Il filtro per periodo (quali sedute cadono nella settimana o nel mese mostrato) e per stato sta in `BookingsClient` o in `calendar.ts`, non nelle griglie: le griglie ricevono solo quello che devono disegnare.

## 7. Le prove

- **Unitarie** (`npm run test:unit`, pochi secondi, senza backend): il lunedì di ogni giorno della settimana; una settimana a cavallo di mese e di anno; un mese che inizia di domenica (sei righe) e uno che inizia di lunedì (cinque); febbraio 2028 (bisestile); le due domeniche del cambio dell'ora 2026 (29 marzo, 25 ottobre) per `romeClock`; `lanesOf` con zero, una, due e tre sovrapposizioni; i titoli; `isDay` con date valide e non ("2026-13-01", "ieri", ""). Il processo è nel fuso di New York: se un conto usasse l'orologio del processo, fallirebbe.
- **End-to-end** (`npm run e2e`, nella storia seriale di `customer.spec.ts`): Maria ha una seduta confermata e una cancellata; apre `?vista=settimana&da=<giorno della seduta>` e vede il blocco della confermata (link con etichetta che contiene l'orario e "Confermata") e **non** la cancellata; lo tocca e arriva a `/prenotazioni/{id}`; apre `?vista=mese&da=<giorno>&giorno=<giorno>`, il puntino c'è e la riga sotto c'è. Il test "ogni pagina sta nello schermo del telefono" visita anche le due viste (nessuno scorrimento orizzontale).
- **Occhio**: screenshot in chiaro e scuro con il browser (Pixel 7, 412 × 915), settimana e mese, come per l'identità visiva.

## 8. Documentazione

- `docs/decisioni.md`: scheda **D-087** (le viste a calendario di "le mie sedute": quali sedute, nessuna modifica al backend, Roma e non il telefono, l'indirizzo come stato, niente libreria).
- `docs/passi/12-frontend-cliente.md`: una sezione "Dopo il passo: le viste a calendario" con i file, i concetti (CSS grid con righe di 5 minuti, `Intl` e fusi, funzioni pure provate da sole con il runner che c'è), gli errori fatti e un paio di domande da colloquio.
- `docs/stato.md`: la riga nel paragrafo del passo 12.
- Commit in Conventional Commits, dopo la suite verde (unitarie + end-to-end + `npm run typecheck`).
