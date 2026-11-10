# Codice di accesso via SMS — guida di studio

Questa guida accompagna il **pezzo 6 del passo 8**, l'ultimo. Dal pezzo 1 si entra con un codice di 6 cifre via email. La proposta di Nicholas (requisiti, punto aperto 7): se l'utente ha un **numero di telefono verificato**, il codice può arrivare via SMS. È più comodo dal telefono, dove si prenota quasi sempre.

```
POST   /api/public/auth/login-code/sms    {"phone"}            → 202 (sempre uguale)
POST   /api/public/auth/session/sms       {"phone", "code"}    → 200, la sessione
GET    /api/account/phone                                      → 200 {"phone", "verifiedAt"}
POST   /api/account/phone/code            {"phone"}            → 202 (un codice per verificarlo)
POST   /api/account/phone                 {"phone", "code"}    → 200 (numero verificato)
DELETE /api/account/phone                                      → 204
```

Sembra il pezzo 1 con un altro canale. Non lo è, per due differenze:
- **un'email nuova crea un account, un numero no.** L'SMS serve solo a chi ha già un account e ci ha associato un numero, verificandolo;
- **un SMS costa.** Un'email è gratis; un SMS costa qualche centesimo, e chi riesce a far spedire SMS a raffica dal nostro server ci fa pagare (parte 5).

Teoria di riferimento: D-014 (6 cifre, 10 minuti, 5 tentativi), D-050 (tabella `login_code` con `channel` e `destination`, HMAC, `CodeCheck`, limiti nel database, nessuna enumerazione, `LoginCodeSender`), D-051 (sessione, regole per area), D-020 (l'email normalizzata in un `record`), D-042 (`check` con i soli valori prodotti), requisiti punto aperto 7.

---

## Indice

- Parte 1 — Cosa c'è già, e cosa manca
- Parte 2 — Prima scelta: il numero, dove sta e com'è scritto
- Parte 3 — Seconda scelta: come si verifica un numero
- Parte 4 — Terza scelta: l'accesso via SMS senza rivelare chi è cliente
- Parte 5 — Quarta scelta: abusi e costi
- Parte 6 — Quinta scelta: chi spedisce l'SMS
- Parte 7 — Il testo dell'SMS
- Parte 8 — Le API e i loro errori
- Parte 9 — Lo schema (V8)
- Parte 10 — I test
- Parte 11 — Cosa c'è da decidere
- Parte 12 — Com'è andata (implementazione)
- Domande da colloquio

---

## Parte 1 — Cosa c'è già, e cosa manca

**C'è** (pezzi 1 e 2):
- `login_code` è già pensata per più canali: `channel` + `destination`, con il `check` che oggi accetta solo `EMAIL` (D-050). La guida 24 (parte 2.4) aveva anticipato che il pezzo 6 avrebbe aggiunto anche **lo scopo** del codice;
- impronta HMAC, 5 tentativi con il lucchetto sulla riga, scadenza di 10 minuti, limiti per destinazione e per IP, `CodeCheck` come risultato, risposta 202 sempre uguale;
- la sessione: `SessionController` apre la sessione con un `CodeCheck.Valid`;
- `app_user.phone`: una colonna della V1, facoltativa e **mai scritta da nessun codice**. Non è unica, non ha un formato, non è verificata.

**Manca:**
- un numero scritto in un modo solo, unico, e verificato;
- un modo per verificarlo;
- l'accesso via SMS, con le sue regole contro gli abusi;
- chi spedisce gli SMS, e come vederli in sviluppo.

---

## Parte 2 — Prima scelta: il numero, dove sta e com'è scritto

### 2.1 Dove: in `app_user`

Il numero diventa un **modo per accedere**, come l'email: appartiene all'account, quindi ad `auth`, e vale per ogni ruolo (un professionista può entrare via SMS come un cliente). La nota del passo 8 in `stato.md` l'aveva già detto: in `app_user`, unico, in un formato standard, con la data di verifica.

### 2.2 Un solo modo di scriverlo: E.164

`333 123 4567`, `+39 333 1234567`, `0039-333-123-4567` sono lo stesso numero. Come per l'email (D-020), lo si **normalizza** prima di salvarlo o cercarlo, nel formato internazionale **E.164**: `+` seguito da prefisso del paese e numero, senza spazi, al massimo 15 cifre. Qui: `+393331234567`.

Un `record` `PhoneNumber` in `auth`, come `Email`: il costruttore normalizza e rifiuta ciò che non va. Un numero dentro il sistema è sempre già giusto.

- toglie spazi, trattini, punti e parentesi;
- `00` all'inizio diventa `+`;
- senza prefisso, un numero di 10 cifre che comincia per `3` è un cellulare italiano: diventa `+39...`.

### 2.3 Solo cellulari italiani

Questa è una scelta di **sicurezza**, non di comodità (parte 5). Si accettano solo numeri `+39 3...` (9 o 10 cifre dopo il `3`): i cellulari italiani. Un numero fisso non riceve SMS, e un numero estero è la porta della frode più comune sugli SMS (parte 5.1).

Un cliente straniero accede via email, come tutti oggi. Se lo studio avrà clienti all'estero con il bisogno dell'SMS, si allarga l'elenco dei prefissi: una riga, con il suo test.

Si poteva usare **libphonenumber** di Google, la libreria di riferimento per i numeri di tutto il mondo: conosce prefissi, lunghezze e tipi (fisso, mobile) di ogni paese. È la scelta giusta per chi accetta numeri di tutto il mondo. Per accettare solo `+39 3`, una regola di due righe basta e si legge.

### 2.4 Verificato, o niente

Il numero si scrive in `app_user` **solo** quando è verificato (parte 3): non esiste un numero "inserito ma non verificato" nella tabella. Due colonne, e un `check` che le tiene insieme:

```sql
phone             text,          -- E.164, unico
phone_verified_at timestamptz,
check ((phone is null) = (phone_verified_at is null))
```

Vantaggi: `unique (phone)` vale solo per numeri verificati, quindi nessuno può "occupare" il numero di un altro scrivendolo senza riceverne il codice; e "accesso via SMS" diventa semplicemente "esiste un account con questo `phone`".

---

## Parte 3 — Seconda scelta: come si verifica un numero

### 3.1 Il giro

1. L'utente, **già entrato** (via email), scrive il suo numero nel profilo: `POST /api/account/phone/code`.
2. Il backend manda un codice di 6 cifre **a quel numero**.
3. L'utente lo scrive: `POST /api/account/phone` con numero e codice.
4. Il codice è giusto: il numero è suo, e si salva con l'istante della verifica.

Chi ha il codice ha il telefono: è la prova del possesso.

### 3.2 Lo scopo del codice: `purpose`

Un codice di verifica e un codice di accesso hanno le stesse regole (6 cifre, 10 minuti, 5 tentativi, HMAC), quindi la stessa tabella. Ma **non devono mai valere uno per l'altro**:
- un codice di verifica usato per accedere aprirebbe una sessione a chi non ha chiesto di accedere;
- un codice di accesso usato per verificare permetterebbe di associare un numero partendo da un altro giro.

Una colonna nuova, `purpose`: `SIGN_IN` o `VERIFY_PHONE`. "L'ultimo codice" si cerca per canale, destinazione **e** scopo.

### 3.3 Un codice di verifica è di chi l'ha chiesto

Il codice di verifica arriva al numero, ma conferma un numero **per un account**. Se fosse legato solo al numero, il codice chiesto da Anna per il suo numero potrebbe essere usato da Bruno, se Bruno lo leggesse. Quindi la riga ricorda **chi** l'ha chiesto (`requested_by`, l'id dell'account), e la conferma cerca il codice di quel numero, per quello scopo, chiesto da quell'account.

```sql
check ((purpose = 'VERIFY_PHONE') = (requested_by is not null))
```

### 3.4 Un numero che era di un altro

I numeri di telefono si **riciclano**: un operatore riassegna dopo qualche mese il numero di chi l'ha disdetto. Se Carla verifica un numero che nel sistema è ancora associato all'account di un vecchio cliente, chi ha ragione? Chi **ha il telefono adesso**: Carla ha appena ricevuto il codice. Il numero passa a lei, e il vecchio account lo perde (continua a entrare via email).

L'alternativa, rifiutare ("numero già usato"), lascerebbe Carla senza SMS per colpa di un account che forse non esiste più, e le direbbe che quel numero è di un cliente dello studio.

Nell'ordine giusto: prima si toglie il numero all'altro account, poi lo si scrive su quello di Carla, nella stessa transazione. Il vincolo `unique` si controlla a ogni istruzione, quindi l'ordine conta (parte 12).

### 3.5 Togliere il numero

`DELETE /api/account/phone`: il numero sparisce dall'account, e con lui l'accesso via SMS. Cambiare numero è togliere il vecchio e verificare il nuovo (o verificare il nuovo direttamente: sostituisce il vecchio).

---

## Parte 4 — Terza scelta: l'accesso via SMS senza rivelare chi è cliente

### 4.1 Il problema

Il pezzo 1 risponde sempre 202, nello stesso tempo, che l'email esista o no (D-050), perché sapere "questa persona è cliente di uno studio di posturologia" è già un'informazione sulla sua salute. Con l'email era facile: si spedisce **sempre** un'email (il codice, o "account disattivato").

Con l'SMS no. A un numero che non è di nessuno **non si spedisce niente**: costerebbe, e sarebbe un modo per far mandare SMS a numeri qualunque (parte 5). Ma allora il lavoro è diverso, e le differenze si vedono in tre posti:

| Dove si vede | Numero di un cliente | Numero di nessuno | Come si pareggia |
|---|---|---|---|
| La risposta | 202 | 202 | Già uguale |
| I limiti (429 dopo 3 richieste) | La riga c'è e conta | Se non ci fosse la riga, mai 429 | **Si scrive la riga anche per il numero di nessuno**, con un codice che nessuno riceverà |
| Il tempo di risposta | Aspetta il fornitore SMS (centinaia di millisecondi) | Non aspetta niente | **Si spedisce in un altro thread**: la risposta non aspetta mai il fornitore |

### 4.2 L'invio in un altro thread

Come le email delle prenotazioni (guida 27, parte 3): dopo il commit, l'SMS parte in un virtual thread del modulo `notification`. Il prezzo è lo stesso di allora: se il fornitore non risponde, la persona non riceve niente e non riceve nemmeno un 503. Lo scrive il log; la persona chiede un altro codice o usa l'email. Il codice via email resta sincrono, con il suo 503 (D-050): lì il problema dei tempi non c'è, perché un'email parte sempre.

### 4.3 La riga di un numero di nessuno

Scrivere la riga fa scattare i limiti nello stesso modo. Ma un dettaglio: il **tetto giornaliero** degli SMS (parte 5.3) deve contare gli SMS **spediti**, non le righe, altrimenti chiunque potrebbe esaurirlo chiedendo codici per numeri inventati, senza farci spendere niente ma lasciando tutti senza SMS. Quindi la riga dice se un messaggio è partito: `message_sent`. Per le email è sempre vero.

### 4.4 Né un account nuovo, né un account disattivato

- Un numero di nessuno **non crea un account**, a differenza dell'email: un account nasce solo con un'email verificata (D-050), e il numero si aggiunge dopo.
- Un account **disattivato** con un numero verificato non riceve l'SMS (con l'email riceveva "account disattivato": un SMS costa, e il messaggio non serve).
- Alla verifica, un codice giusto per un numero che nel frattempo non è più di nessuno (o di un account disattivato) non apre niente.

---

## Parte 5 — Quarta scelta: abusi e costi

### 5.1 La frode sugli SMS (*SMS pumping*, *toll fraud*)

È la frode più diffusa contro i servizi che mandano codici via SMS. Chi la fa si accorda con un operatore telefonico (spesso estero) che gli gira una parte di quanto incassa per ogni SMS ricevuto dai suoi numeri. Poi fa chiedere al nostro server codici verso migliaia di quei numeri. Noi paghiamo ogni SMS, lui incassa la sua parte. Non gli serve entrare da nessuna parte: gli basta un modulo che mandi SMS.

Per noi i moduli che mandano SMS sono due:
- **l'accesso**: manda SMS solo a numeri **già verificati** di account attivi. Per farne partire uno verso un numero suo, l'attaccante dovrebbe prima verificarlo, cioè ricevere un codice: torna al punto sotto;
- **la verifica**: manda un SMS al numero che l'utente scrive. È il punto da difendere. Si arriva solo da entrati, ma un account si crea gratis con un'email.

### 5.2 Le difese, una sopra l'altra

| Difesa | Ferma | Dove |
|---|---|---|
| Solo cellulari italiani `+39 3` | I numeri esteri a tariffa speciale, il bersaglio della frode | `PhoneNumber`, parte 2.3 |
| Verifica solo da entrati | Chi non ha nemmeno un account | `/api/account/**`, autenticato |
| Per destinazione: 3 in 15 minuti, 10 al giorno | Gli SMS a raffica verso lo stesso numero (anche come molestia) | I limiti di D-050, contati sul canale `SMS` |
| Per IP: 30 l'ora, email e SMS insieme | Chi chiede da un solo indirizzo | Il limite di D-050 |
| **Per account: 5 verifiche al giorno** | Un account che prova numero dopo numero | Nuovo |
| **Tetto giornaliero: 100 SMS spediti** | Il caso peggiore, qualunque cosa sia passata sopra: la spesa ha un massimo | Nuovo |

Il tetto è il **paracadute**: con circa 5–9 centesimi a SMS in Italia (prezzi di mercato, da verificare sul listino del fornitore scelto), il caso peggiore costa qualche euro al giorno. La stima del volume vero (requisiti, punto 5) è di poche decine di codici al giorno, e la sessione dura 30–90 giorni (D-051). Oltre il tetto: 429 per tutti, e resta l'email. Che un attaccante possa esaurire il tetto e lasciare tutti senza SMS è il prezzo di un massimo di spesa: si vede nei log, e il tetto si alza senza ricompilare.

---

## Parte 6 — Quinta scelta: chi spedisce l'SMS

### 6.1 Lo stesso trucco del pezzo 1

`auth` dichiara di cosa ha bisogno, `notification` lo fornisce (D-044, D-046, D-050):

```java
// auth
public interface SmsCodeSender {
    void sendCode(PhoneNumber to, String code, Duration validity, CodePurpose purpose);
}
```

### 6.2 E se non c'è nessuno che lo spedisce?

Oggi **non c'è un fornitore SMS**: va scelto (Brevo, dai requisiti, offre anche SMS transazionali; da confrontare per prezzo, nome mittente, affidabilità in Italia) e configurato con un account e una chiave. È un punto aperto nuovo, prima del passo 14.

Fino ad allora, `auth` riceve un `SmsCodeSender` **facoltativo** (`ObjectProvider` di Spring: "dammelo se esiste"). Se non esiste, le API SMS rispondono subito **503 `SMS_UNAVAILABLE`**, prima di scrivere qualunque riga: uguale per ogni numero, quindi non rivela niente. Il frontend nasconde il pulsante "accedi con SMS". In produzione, finché il fornitore non è configurato, l'SMS è semplicemente spento.

### 6.3 In sviluppo: gli SMS in Mailpit

Per provare il giro a mano gli SMS devono arrivare da qualche parte. La guida 24 (parte 7.4) ha escluso il log: un codice nel log di produzione, per un errore di configurazione, è un buco vero. La stessa soluzione di allora: **Mailpit**. Un `SmsCodeSender` di sviluppo, `MailpitSmsCodeSender`, spedisce il testo dell'SMS come email all'indirizzo `sms-393331234567@sms.local`, e lo si legge su `http://localhost:8025` insieme alle altre email.

Si accende solo con `notification.sms.mailpit=true` (`SMS_TO_MAILPIT=true` nel `.env` di sviluppo), spento per default, come il pagamento simulato (D-046). In produzione, dimenticarlo acceso manderebbe gli SMS al fornitore email verso un dominio che non esiste: nessun SMS arriva, nessun codice è esposto.

Il fornitore vero sarà un'altra implementazione della stessa interfaccia, accesa dalla sua configurazione: `auth` non cambia.

---

## Parte 7 — Il testo dell'SMS

```
Codice di accesso: 123456
Vale 10 minuti. Non darlo a nessuno, nemmeno allo studio.

@localhost #123456
```

- **Un SMS sono 160 caratteri** dell'alfabeto GSM-7. Un carattere fuori alfabeto (molti accenti, gli emoji) fa passare l'intero messaggio a un'altra codifica da **70** caratteri: lo stesso testo diventa due o tre SMS, e costa due o tre volte. Il testo usa solo lettere senza accenti, cifre e pochi segni, e un test controlla lunghezza e caratteri.
- **"Non darlo a nessuno, nemmeno allo studio"**: la difesa contro chi telefona fingendosi lo studio e chiede "il codice che le è appena arrivato". È l'attacco più semplice contro i codici via SMS (*social engineering*), e una riga lo rende molto più difficile.
- **L'ultima riga** `@localhost #123456` è il formato degli *origin-bound one-time codes*: Chrome su Android (WebOTP) e Safari su iPhone propongono di compilare da soli il campo del codice, **solo** sul sito con quel nome. Un sito falso con un altro nome non riceve il suggerimento. L'host è quello di `APP_URL`.
- Nessun nome, nessun riferimento alla salute: lo schermo bloccato lo mostra (guida 27, 6.1).

Il codice di verifica ha la sua prima riga: "Codice per verificare il numero: 123456".

---

## Parte 8 — Le API e i loro errori

### 8.1 Accedere

`POST /api/public/auth/login-code/sms` e `POST /api/public/auth/session/sms`, accanto a quelle dell'email, con le stesse risposte: 202; poi 200 con la sessione, 401 `CODE_WRONG` o `CODE_EXPIRED`, 403 `ACCOUNT_DEACTIVATED`. Due indirizzi invece di un campo in più negli stessi: ognuno ha un corpo semplice e una validazione semplice (`@NotBlank @ValidPhone`), invece di "uno dei due, non entrambi".

Un numero scritto male è un **400**: riguarda il formato, non dice niente su chi ha un account (come l'email, guida 24, parte 8).

### 8.2 Il numero dell'account: `/api/account/**`

Il numero è dell'account, di **qualunque** ruolo. Le aree di D-051 sono per ruolo (`/api/panel`, `/api/customer`, `/api/admin`): serve un'area nuova, **`/api/account/**`, per chiunque sia entrato** (`authenticated()`). Una regola in più nella catena, prima di `denyAll()`.

### 8.3 Un codice sbagliato nella verifica: 409, non 401

All'accesso un codice sbagliato è un **401**: chi chiede non è entrato. Nella verifica del numero invece la persona **è** entrata: un 401 farebbe credere al frontend che la sessione sia scaduta, e lo rimanderebbe alla pagina di accesso. Quindi **409** con `CODE_WRONG` o `CODE_EXPIRED`: "la richiesta è giusta, ma lo stato (il codice) non la permette".

Come all'accesso, la verifica **non lancia** eccezioni per un codice sbagliato: restituisce un risultato (`PhoneCheck`, una `sealed interface` come `CodeCheck`), perché un'eccezione annullerebbe il tentativo appena contato (guida 24, 4.1).

---

## Parte 9 — Lo schema (V8)

```sql
-- app_user: il numero, solo verificato
update app_user set phone = null;      -- mai scritto da nessun codice: nessun valore è verificato
alter table app_user add column phone_verified_at timestamptz;
alter table app_user add constraint app_user_phone_e164_ck check (phone ~ '^\+[1-9][0-9]{7,14}$');
alter table app_user add constraint app_user_phone_verified_ck check ((phone is null) = (phone_verified_at is null));
alter table app_user add constraint app_user_phone_uq unique (phone);

-- login_code: il canale SMS, lo scopo, chi chiede la verifica, se un messaggio è partito
alter table login_code drop constraint login_code_channel_ck;
alter table login_code add constraint login_code_channel_ck check (channel in ('EMAIL', 'SMS'));
alter table login_code add column purpose text not null default 'SIGN_IN';
alter table login_code add column requested_by bigint references app_user (id);
alter table login_code add column message_sent boolean not null default true;
...
```

- Il `check` del database sul formato è più largo della regola di Java (E.164 di qualunque paese): il database tiene il formato, Java la politica "solo cellulari italiani", che può cambiare senza migrazione.
- `update app_user set phone = null`: una migrazione che cancella dati va giustificata. Qui nessun codice ha mai scritto `phone`, il sistema non è online, e un numero non verificato non può restare in una colonna che da adesso significa "verificato".
- I default di `purpose` e `message_sent` servono alle righe esistenti; poi si tolgono, perché Java li scrive sempre.
- Un codice di verifica è solo SMS: `check (purpose = 'SIGN_IN' or channel = 'SMS')`.

---

## Parte 10 — I test

| Test | Cosa prova |
|---|---|
| `PhoneNumberTest` | Le scritture dello stesso numero diventano una; fissi, esteri, troppo corti o lunghi rifiutati |
| `SmsTextsTest` (`notification`) | Al massimo 160 caratteri, solo GSM-7 di base, la riga `@host #codice`, "non darlo a nessuno" |
| `LoginCodeServiceTest` (casi nuovi) | Numero verificato: l'SMS parte e il codice apre; numero di nessuno: nessun SMS, ma la riga c'è e i limiti scattano uguali, e nessun codice apre niente; account disattivato: nessun SMS; il tetto conta solo gli SMS spediti; senza mittente SMS: `SMS_UNAVAILABLE`, nessuna riga |
| | Verifica: il codice giusto associa il numero; uno sbagliato conta il tentativo; il codice di Anna non vale per Bruno; un codice di verifica non apre una sessione, e viceversa; il numero passa da un account all'altro; 5 verifiche al giorno per account |
| `LoginCodeSchemaTest` | I `check` nuovi: formato, coppia numero/verifica, numero unico, `purpose` con `requested_by`, verifica solo via SMS |
| `SmsAccessApiTest` | Il giro completo con sessioni vere: entra via email, verifica il numero, esce, rientra via SMS; 400 su un numero sbagliato; `/api/account/phone` senza sessione è 401; 409 su un codice di verifica sbagliato |
| `MailpitDeliveryTest` (un caso nuovo) | Con `notification.sms.mailpit=true` un SMS arriva in Mailpit, con il testo esatto |

---

## Parte 11 — Cosa c'è da decidere

| Scelta | Raccomandazione | Dove |
|---|---|---|
| Dove sta il numero | `app_user.phone`, E.164, unico, scritto solo dopo la verifica, con `phone_verified_at` | parte 2 |
| Quali numeri | Solo cellulari italiani `+39 3`; regola nostra, non libphonenumber | parte 2.3 |
| Verifica | Da entrati, codice `VERIFY_PHONE` legato all'account che lo chiede; il numero passa a chi lo verifica | parte 3 |
| Accesso | Nessun account nuovo; riga anche per i numeri di nessuno; invio in un altro thread | parte 4 |
| Abusi | I limiti di D-050 sul canale SMS, più 5 verifiche al giorno per account e 100 SMS spediti al giorno in tutto | parte 5 |
| Chi spedisce | `SmsCodeSender` facoltativo; senza, 503 `SMS_UNAVAILABLE`; in sviluppo Mailpit; il fornitore vero è un punto aperto prima del passo 14 | parte 6 |
| Testo | ≤ 160 caratteri GSM-7, "non darlo a nessuno", riga WebOTP | parte 7 |
| API | Indirizzi `/sms` accanto a quelli dell'email; area `/api/account/**` per chiunque sia entrato; 409 nella verifica | parte 8 |

Una migrazione nuova: **V8**. Chiude il punto aperto 7 dei requisiti, e ne apre uno: il fornitore SMS.

---

## Parte 12 — Com'è andata (implementazione)

Costruito come raccomandato nella parte 11 (D-055). 660 test verdi con `mvnw clean test`, 59 nuovi: 21 in `PhoneNumberTest`, 5 in `SmsTextsTest`, 12 in `SmsCodeServiceTest`, 7 in `SmsAccessApiTest`, 12 nuovi in `LoginCodeSchemaTest`, uno in `LoginCodeApiTest` (il 503) e uno in `MailpitDeliveryTest` (un SMS in Mailpit).

**I test sanno fallire.** Undici difese tolte una alla volta, ognuna con `mvnw clean`:

| Tolto | Test che se ne accorge |
|---|---|
| la regola dei cellulari italiani (qualunque E.164) | `PhoneNumberTest`: fissi ed esteri accettati |
| "SMS solo al numero verificato di un account attivo" | il numero di nessuno riceve l'SMS; l'account disattivato pure |
| la riga per il numero di nessuno | i limiti non scattano più uguali |
| il tetto giornaliero | `theDailyCapCountsOnlyTheSmsThatLeft` |
| "solo gli SMS spediti" nel conteggio del tetto | lo stesso: 100 codici per numeri di nessuno lo esauriscono |
| il legame del codice di verifica all'account | `anotherAccountCannotUseTheCode`: Bruno usa il codice di Anna |
| il `flush` prima di spostare il numero | `theNumberMovesToWhoVerifiesIt`: `duplicate key ... app_user_phone_uq` (vedi sotto) |
| il limite di 5 verifiche per account | `fiveVerificationsADayPerAccount` |
| l'alfabeto GSM-7 (una `ó` nel testo) | `SmsTextsTest` |
| il 409 nella verifica (al suo posto 401) | `aWrongVerificationCodeIs409` |
| il controllo "c'è un fornitore SMS?" | `withoutAnSmsProviderTheSmsCodeIsUnavailable`: 500 invece di 503 |

**La mutazione sopravvissuta: il `flush`.** Al primo giro, togliere il `flush()` prima di spostare il numero lasciava il test verde. Il motivo: al momento del `flush` Hibernate scrive le `update` nell'ordine in cui ha caricato le entità, e il codice caricava **prima** il vecchio proprietario. Così la `update` che libera il numero partiva per prima, per caso. Bastava caricare le entità nell'altro ordine, o attivare `hibernate.order_updates` (che ordina per id), e il vincolo `unique` avrebbe fatto fallire la verifica. Ho riordinato il codice in modo naturale: prima l'account che conferma, poi il vecchio proprietario. Adesso il `flush` è necessario, e la mutazione fa fallire il test con l'errore esatto. Una mutazione che sopravvive dice una di due cose: il test non prova quella riga, o la riga non serve. Qui serviva, ma solo per un caso che il test non vedeva.

**Errore 1: più violazioni di vincolo nello stesso test.** I primi test dello schema V8 provavano quattro `insert` sbagliati di fila in un solo test. Dopo il primo rifiuto PostgreSQL abbandona l'intera transazione (`25P02`, *current transaction is aborted*), e gli altri tre fallivano per quello, con un'eccezione di un altro tipo. Divisi in un test per violazione, come quelli della V5.

**Errore 2: un SMS identico, ma con `\r\n`.** Nel test con Mailpit il testo sembrava diverso da quello atteso. Le righe tornano con `\r\n`, perché l'SMTP vuole il ritorno a capo in quella forma. Il test ora normalizza i ritorni a capo prima di confrontare.

**Errore 3: ancora la cache di Hibernate.** Dopo `DELETE /api/account/phone`, la riga riletta con SQL aveva ancora il numero. La richiesta era entrata nella transazione del test, che non arriva mai al commit, e Hibernate non aveva scritto niente. Un `flush()` prima di rileggere (passo 8, errore 4).

**Errore 4 (mio): la shell nella cartella sbagliata, due volte.** Due comandi sono partiti dalla radice del repository invece che da `backend/`. Maven non c'era (codice di uscita 127), e lo script delle mutazioni non ha trovato i file, quindi non ha toccato niente. L'ho visto dal codice di uscita stampato (passo 8, errore 10). Da allora ogni comando comincia con `cd` sulla cartella completa e si ferma se fallisce.

**Il test con Mailpit** spedisce un SMS di sviluppo e lo ritrova nella casella `sms-393331234567@sms.local`, con il testo esatto, compresa la riga `@localhost #042517`.

**Non fatto:** un SMS vero, perché il fornitore non è scelto (requisiti, punto 17). Fino al passo 14, in produzione l'SMS resta spento e le API rispondono 503.

---

## Domande da colloquio

### Domanda 1: Perché non si può semplicemente "aggiungere l'SMS" a un login via email?

**Risposta modello.** Per due differenze. L'email può creare un account, il numero no: serve un numero già associato e verificato, altrimenti chiunque potrebbe entrare con un numero inventato o riciclato. E un SMS costa: il modulo che spedisce SMS va difeso dalla frode e ha bisogno di un tetto di spesa. In più, non potendo spedire a numeri sconosciuti, bisogna pareggiare a mano i segnali che rivelerebbero chi è cliente: i limiti (si scrive la riga comunque) e il tempo (si spedisce in un altro thread).

### Domanda 2: Cos'è l'SMS pumping e come lo difendi?

**Risposta modello.** È la frode in cui un attaccante fa mandare dal tuo server SMS verso numeri suoi a tariffa speciale, e incassa una parte di quanto paghi. Si difende a strati: solo i paesi e i tipi di numero che servono (qui cellulari italiani), invio solo da utenti entrati, limiti per numero, per IP e per account, e un tetto giornaliero di SMS spediti che mette un massimo alla spesa qualunque cosa passi sopra.

### Domanda 3: Come verifichi che un numero di telefono è davvero di un utente?

**Risposta modello.** Con un codice mandato a quel numero, che l'utente deve scrivere: chi ha il codice ha il telefono. Il codice è legato all'account che l'ha chiesto e allo scopo "verifica", così non vale per un altro account né per accedere. Il numero si salva solo dopo, con la data della verifica, e un vincolo `unique` impedisce di occupare il numero di un altro senza averne ricevuto il codice. I numeri si riciclano, quindi chi lo verifica adesso lo prende a chi l'aveva prima.

### Domanda 4: L'invio è asincrono. Come lo giustifichi, se all'email hai detto che sincrono è meglio?

**Risposta modello.** Per l'email la spedizione avviene per ogni richiesta, quindi i tempi sono uguali e posso aspettare e rispondere 503. Per l'SMS spedisco solo ai numeri verificati: se aspettassi il fornitore, la risposta sarebbe più lenta proprio per i numeri dei clienti, e il tempo direbbe chi è cliente. Spedendo in un altro thread la risposta è uguale per tutti; perdo il 503, e lo compenso con il log e con la possibilità di usare l'email.

### Domanda 5: Perché lo stesso codice sbagliato dà 401 in un caso e 409 in un altro?

**Risposta modello.** Lo stato HTTP descrive la richiesta, non il codice. All'accesso chi chiede non è autenticato, e un codice sbagliato significa "non ti riconosco": 401. Nella verifica del numero l'utente è autenticato, e un 401 farebbe credere al client che la sessione sia scaduta; il problema è lo stato del codice, quindi 409, con un codice leggibile che il frontend traduce.

### Domanda 6: Come normalizzi un numero di telefono, e quando useresti libphonenumber?

**Risposta modello.** In E.164: `+`, prefisso del paese, numero, senza separatori. Lo faccio nel costruttore di un `record`, così un numero nel sistema è sempre già giusto, come l'email. Con una politica stretta (solo cellulari italiani) una regola breve basta e si legge; se dovessi accettare numeri di tutto il mondo userei libphonenumber, che conosce lunghezze e tipi di numero di ogni paese e cambia con i piani di numerazione.

---

## Documentazione ufficiale

- ITU-T E.164, il formato internazionale dei numeri: https://www.itu.int/rec/T-REC-E.164
- OWASP, Forgot Password e codici via SMS, enumerazione: https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html
- OWASP, Multifactor Authentication (SMS e i suoi limiti): https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html
- Origin-bound one-time codes delivered via SMS (il formato `@host #codice`): https://wicg.github.io/sms-one-time-codes/
- WebOTP API: https://developer.mozilla.org/en-US/docs/Web/API/WebOTP_API
- GSM 03.38, l'alfabeto degli SMS: https://en.wikipedia.org/wiki/GSM_03.38
- Spring, `ObjectProvider`: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/beans/factory/ObjectProvider.html
- libphonenumber: https://github.com/google/libphonenumber
