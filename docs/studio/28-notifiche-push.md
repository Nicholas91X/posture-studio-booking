# Notifiche push — guida di studio

Questa guida accompagna il **pezzo 5 del passo 8**. Dal pezzo 4 una richiesta da approvare arriva al professionista via email. Un'email però si legge quando capita, e la richiesta ha una scadenza (D-030). D-032 ha deciso la strada: una **notifica push** sul telefono, con lo standard Web Push, e l'email come **riserva** quando la push non può partire.

```
GET  /api/panel/push-subscriptions/vapid-key      → 200 {"publicKey": "BP4z9..."}
POST /api/panel/push-subscriptions                {"endpoint", "keys": {"p256dh", "auth"}} → 204
POST /api/panel/push-subscriptions/unsubscribe    {"endpoint"} → 204
```

Questo pezzo fa il **lato backend**: dove si registra un dispositivo, come si cifra e si firma un messaggio, a chi lo si manda, quando parte l'email al suo posto. Il lato frontend (la pagina che chiede il permesso, il *service worker* che mostra la notifica) è il passo 13. Per questo oggi la push non si può vedere arrivare su un telefono: i test la ricevono con un finto servizio push, e la decifrano.

Teoria di riferimento: D-032 (Web Push, VAPID, tabella dei dispositivi, email di riserva, niente nome del cliente), D-039 (lato backend al passo 8), D-053 (l'ascoltatore dopo il commit su virtual thread, la funzione pura per i testi), D-018 (segreti solo nel `.env`), D-050 (fail fast su una chiave mancante, come `AUTH_CODE_HMAC_KEY`).

---

## Indice

- Parte 1 — Come funziona Web Push
- Parte 2 — Prima scelta: una libreria, o il JDK
- Parte 3 — La cifratura del messaggio (RFC 8291), passo per passo
- Parte 4 — La firma del server (VAPID, RFC 8292)
- Parte 5 — Le chiavi VAPID
- Parte 6 — Seconda scelta: la tabella dei dispositivi e la registrazione
- Parte 7 — Terza scelta: a quali indirizzi il server accetta di scrivere (SSRF)
- Parte 8 — Quando parte la push, e quando l'email al suo posto
- Parte 9 — Le risposte del servizio push
- Parte 10 — Il testo della push
- Parte 11 — I test
- Parte 12 — Cosa c'è da decidere
- Parte 13 — Com'è andata (implementazione)
- Domande da colloquio

---

## Parte 1 — Come funziona Web Push

### 1.1 Tre attori

1. **Il browser** del professionista (il pannello installato sul telefono, dal passo 13).
2. **Il servizio push** del produttore del browser: Google per Chrome e Android, Mozilla per Firefox, Apple per Safari e iPhone, Microsoft per Edge su Windows. È lui che tiene aperta una connessione con il telefono e gli consegna i messaggi.
3. **Il nostro backend**, che nello standard si chiama *application server*.

Il backend non parla mai con il telefono. Parla con il servizio push, con una semplice richiesta HTTP `POST`.

### 1.2 L'iscrizione (*subscription*)

Quando il professionista dice "sì, voglio le notifiche", il browser chiede al suo servizio push un'iscrizione e la restituisce alla pagina. È un oggetto con tre valori:

```json
{
  "endpoint": "https://fcm.googleapis.com/fcm/send/dXJ...",
  "keys": {
    "p256dh": "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcx...",
    "auth": "BTBZMqHH6r4Tts7J_aSIgg"
  }
}
```

- **`endpoint`**: l'indirizzo, presso il servizio push, che corrisponde a **quel browser su quel dispositivo**. Chi fa `POST` lì manda un messaggio a quel dispositivo.
- **`p256dh`**: la **chiave pubblica** del browser, sulla curva ellittica P-256. Serve a cifrare il messaggio in modo che solo quel browser lo possa leggere.
- **`auth`**: 16 byte casuali, un segreto condiviso tra browser e backend, che entra nella cifratura.

La pagina manda questo oggetto al backend, che lo salva. Un professionista con telefono e computer ha due iscrizioni.

### 1.3 Il messaggio

Per mandare una push il backend:

1. **cifra** il testo con la chiave pubblica del browser (parte 3): il servizio push lo trasporta senza poterlo leggere (D-032, "i messaggi transitano cifrati");
2. **firma** la richiesta con la sua chiave privata **VAPID** (parte 4): il servizio push sa che arriva dallo stesso server a cui il browser ha dato il permesso;
3. fa `POST` all'`endpoint`, con il messaggio cifrato nel corpo.

Il servizio push risponde subito (201, "preso in carico") e consegna il messaggio quando il telefono è raggiungibile. Il browser lo decifra e lo passa al *service worker* del pannello, che mostra la notifica.

---

## Parte 2 — Prima scelta: una libreria, o il JDK

### 2.1 Strada A — `nl.martijndwars:web-push` (webpush-java)

La libreria Java più citata nei tutorial. Usa **Bouncy Castle** per la crittografia e Apache HttpAsyncClient per la rete, ed è scritta per Java 8. È ferma da anni senza nuove versioni, e trascina una versione vecchia di Bouncy Castle (`bcprov-jdk15on`) con vulnerabilità note: aggiungerla oggi vorrebbe dire importare un debito.

### 2.2 Strada B — Una libreria più recente (`zerodep-web-push-java`, `push2u`, il fork di webpush-java per Java 17)

Esistono librerie più moderne, alcune senza dipendenze. Sono progetti di una sola persona, con pochi utenti: una dipendenza è un pezzo di codice di cui ci si fida, e questi non hanno ancora la storia che giustifica la fiducia. Possono comunque essere una buona scelta: vanno valutate leggendone il codice e la frequenza dei rilasci.

### 2.3 Strada C — Implementare lo standard con le sole classi del JDK

Web Push usa quattro operazioni crittografiche, e **tutte e quattro sono già nel JDK 21**, senza dipendenze:

| Operazione | Classe del JDK |
|---|---|
| Accordo di chiavi ECDH su P-256 | `KeyAgreement.getInstance("ECDH")` |
| HKDF, costruita su HMAC-SHA-256 | `Mac.getInstance("HmacSHA256")` |
| Cifratura AES-128-GCM | `Cipher.getInstance("AES/GCM/NoPadding")` |
| Firma ES256 per il JWT di VAPID | `Signature.getInstance("SHA256withECDSAinP1363Format")` |

Il codice è un centinaio di righe. **La regola "non scrivere la tua crittografia"** vale per chi inventa algoritmi o li implementa da zero (una AES scritta a mano, un generatore casuale fatto in casa). Qui non si inventa niente: si **compongono** primitive del JDK nell'ordine scritto in un RFC. Il rischio vero è sbagliare l'ordine o un byte, e lo si elimina con i **vettori di prova**. L'RFC 8291, nell'appendice A, pubblica un esempio completo: chiavi, sale, testo, e il risultato cifrato byte per byte. Se il nostro codice produce esattamente quei byte, è giusto.

### 2.4 Raccomandazione: C

Nessuna dipendenza da mantenere, nessuna vulnerabilità ereditata, e un test che confronta il nostro risultato con quello dell'RFC. In più, il progetto è anche un caso di studio: capire cosa c'è dentro una push è materiale da colloquio (ECDH, HKDF, AES-GCM, JWT).

**Cosa non si può provare oggi:** che i servizi push veri accettino le nostre richieste. Lo si vede al passo 13, con il primo telefono iscritto. Il rischio è basso, perché cifratura e firma sono verificate contro gli RFC, ma va annotato come prova da fare (parte 13).

---

## Parte 3 — La cifratura del messaggio (RFC 8291), passo per passo

### 3.1 L'idea

Il backend e il browser devono arrivare alla **stessa chiave AES** senza mai trasmetterla. Si usa l'**ECDH** (*Elliptic Curve Diffie-Hellman*): ognuno ha una coppia di chiavi, e la propria chiave privata combinata con la chiave pubblica dell'altro dà lo **stesso segreto** da tutte e due le parti. Chi vede solo le due chiavi pubbliche non lo può calcolare.

Per ogni messaggio il backend genera una coppia di chiavi **nuova** (*effimera*): due messaggi non condividono mai la chiave AES.

### 3.2 I passi

```
1. coppia effimera del server:           as_private, as_public (65 byte)
2. segreto ECDH:                         ecdh = ECDH(as_private, ua_public)               32 byte
3. combinare con il segreto auth:        PRK_key = HMAC(auth, ecdh)
                                         IKM     = HMAC(PRK_key, "WebPush: info" 00 ua_public as_public 01)
4. sale casuale:                         salt (16 byte)
5. chiave e nonce:                       PRK   = HMAC(salt, IKM)
                                         CEK   = HMAC(PRK, "Content-Encoding: aes128gcm" 00 01)  primi 16 byte
                                         NONCE = HMAC(PRK, "Content-Encoding: nonce" 00 01)      primi 12 byte
6. cifrare:                              AES-128-GCM(CEK, NONCE, testo 02)
7. corpo della richiesta:                salt | 4096 | 65 | as_public | testo cifrato
```

- I passi 3 e 5 sono **HKDF** (RFC 5869), in due tempi: *extract* (`HMAC(sale, materiale)`, "concentra" il segreto in una chiave uniforme) ed *expand* (`HMAC(chiave, info 01)`, ne ricava chiavi diverse per usi diversi, distinte dalla stringa `info`). Quando serve al massimo un blocco di 32 byte, l'*expand* è un solo HMAC con il byte `01` in fondo.
- **`auth` entra nella chiave**: chi conoscesse solo la chiave pubblica del browser non potrebbe produrre un messaggio che il browser accetta.
- **AES-GCM** cifra **e** autentica: aggiunge 16 byte (il *tag*) che il browser controlla. Un messaggio modificato in viaggio viene scartato.
- Il byte **`02`** dopo il testo dice "ultimo blocco" (RFC 8188). Si possono aggiungere zeri dopo, per nascondere la lunghezza del testo: qui non serve.
- L'**intestazione** (86 byte): il sale, la dimensione del blocco (4096), la lunghezza della chiave (65) e `as_public`. Il browser ci trova tutto ciò che gli serve per rifare i passi 2–5 con la **sua** chiave privata.

### 3.3 Una chiave pubblica che non sta sulla curva

Una chiave pubblica P-256 è un punto `(x, y)` che deve soddisfare l'equazione della curva, `y² = x³ + ax + b`. Un punto scelto apposta **fuori** dalla curva può far rivelare a un ECDH ingenuo pezzi della chiave privata (*invalid curve attack*). Qui la chiave privata è effimera e si butta dopo un messaggio, quindi il danno sarebbe nullo. Ma il controllo costa una riga, e serve comunque a rifiutare chiavi sbagliate al momento dell'iscrizione: lo si fa (parte 6).

---

## Parte 4 — La firma del server (VAPID, RFC 8292)

### 4.1 Perché

Senza VAPID chiunque conosca un `endpoint` potrebbe mandarci messaggi. Con VAPID, il browser al momento dell'iscrizione riceve la **chiave pubblica** del nostro server (`applicationServerKey`), e il servizio push accetta su quell'`endpoint` solo richieste firmate con la **chiave privata** corrispondente.

### 4.2 Il token

Ogni richiesta porta un **JWT** (*JSON Web Token*: tre parti in Base64url separate da punti, intestazione, dati, firma):

```
intestazione:  {"typ":"JWT","alg":"ES256"}
dati:          {"aud":"https://fcm.googleapis.com","exp":1762740000,"sub":"mailto:studio@example.com"}
firma:         ECDSA P-256 con SHA-256 di "intestazione.dati"
```

- **`aud`** (*audience*): l'origine dell'`endpoint` (schema, host, porta). Un token per Google non vale per Apple.
- **`exp`**: la scadenza, al massimo 24 ore. Qui 12.
- **`sub`**: un contatto (`mailto:` o `https:`) per il servizio push, se il server si comporta male. Apple lo controlla.

L'intestazione HTTP è `Authorization: vapid t=<jwt>, k=<chiave pubblica>`.

### 4.3 Un dettaglio che fa perdere ore: il formato della firma

`Signature.getInstance("SHA256withECDSA")` del JDK produce la firma in formato **DER** (una struttura ASN.1, 70–72 byte di lunghezza variabile). Il JWT vuole invece i due numeri della firma, `r` e `s`, **attaccati**: esattamente 64 byte (formato *IEEE P1363*). Il JDK lo fa da solo con `"SHA256withECDSAinP1363Format"`. Con il nome sbagliato il codice funziona, la firma è valida, e il servizio push la rifiuta.

---

## Parte 5 — Le chiavi VAPID

### 5.1 Dove stanno

- **`VAPID_PRIVATE_KEY`**: un **segreto** (D-018). Chi la ha può mandare push ai nostri professionisti, con le iscrizioni. Solo nel `.env`.
- **`VAPID_PUBLIC_KEY`**: pubblica per natura. Il frontend la chiede all'API per iscriversi.
- **`VAPID_SUBJECT`**: il contatto, per default `mailto:no-reply@posture-studio.local`; in produzione l'email dello studio.

Il formato è quello di tutte le librerie Web Push: **Base64url** della chiave privata grezza (32 byte) e della chiave pubblica non compressa (65 byte, `04 | x | y`). Si tengono tutte e due perché il JDK non offre un modo semplice per ricavare la pubblica dalla privata.

### 5.2 Come si generano

Un programma Java di un solo file, `backend/tools/GenerateVapidKeys.java`, che si lancia **senza compilarlo**:

```
java tools/GenerateVapidKeys.java
```

Da Java 11 il comando `java` accetta un file sorgente e lo compila in memoria (*launch single-file source-code programs*, JEP 330). Va bene per piccoli strumenti come questo: niente Maven, niente classi nel prodotto.

### 5.3 Fail fast

Come per `AUTH_CODE_HMAC_KEY`: senza chiavi, o con chiavi che non sono una coppia, **l'applicazione non parte**. All'avvio `Vapid` firma un testo con la privata e lo verifica con la pubblica. Un errore di copia nel `.env` si scopre all'avvio, non quando nessuna notifica arriva.

### 5.4 Non si cambiano a cuor leggero

Le iscrizioni sono legate alla chiave pubblica con cui il browser si è iscritto. **Cambiare la coppia VAPID invalida tutte le iscrizioni**: ogni dispositivo deve iscriversi di nuovo. Le chiavi di sviluppo e di produzione sono diverse (passo 14), e quelle di produzione si generano una volta.

---

## Parte 6 — Seconda scelta: la tabella dei dispositivi e la registrazione

### 6.1 La tabella (V7)

```sql
create table push_subscription (
    id              bigint      generated always as identity,
    practitioner_id bigint      not null,      -- foreign key verso practitioner
    endpoint        text        not null,      -- unique
    p256dh          text        not null,
    auth            text        not null,
    registered_at   timestamptz not null default now()
);
```

La tabella appartiene a `notification` (D-032: "una tabella dei dispositivi registrati"). Il professionista è riferito per id, con la *foreign key* nel database come per le altre tabelle (D-026).

### 6.2 La stessa iscrizione due volte

Il frontend manderà l'iscrizione a ogni apertura del pannello, perché il browser la può rinnovare quando vuole (cambia l'`endpoint` o le chiavi). Quindi la registrazione è **idempotente**: lo stesso `endpoint` aggiorna la riga, non ne crea una seconda. E se lo stesso browser ora è usato da un altro professionista (un tablet dello studio), la riga passa a lui.

È l'`insert` del pezzo 3 con l'altra metà della clausola:

```sql
insert into push_subscription (practitioner_id, endpoint, p256dh, auth)
values (:practitionerId, :endpoint, :p256dh, :auth)
on conflict (endpoint) do update
    set practitioner_id = excluded.practitioner_id, p256dh = excluded.p256dh,
        auth = excluded.auth, registered_at = now()
```

`excluded` è la riga che si voleva inserire. Il database decide in un'operazione sola se inserire o aggiornare: due registrazioni contemporanee dello stesso `endpoint` non possono creare due righe.

### 6.3 Quanti dispositivi

Un frontend difettoso potrebbe registrare un'iscrizione nuova a ogni apertura. Un limite: al massimo **10 dispositivi** per professionista; registrandone un undicesimo si cancella quello registrato da più tempo. Dieci è largo per una persona, e ogni push è una richiesta HTTP per dispositivo.

### 6.4 Togliere un dispositivo

`POST /api/panel/push-subscriptions/unsubscribe` con l'`endpoint`: il frontend lo chiamerà quando il professionista spegne le notifiche o esce dal pannello. Toglie solo un'iscrizione **propria**: lo stesso `endpoint` di un altro professionista non si tocca (stessa regola di proprietà delle altre API, guida 11). Sempre 204, anche se non c'era niente.

### 6.5 L'`endpoint` è un dato delicato

Non è un segreto come la chiave VAPID (senza la nostra chiave privata non basta), ma è un indirizzo personale di un dispositivo. Nei log si scrive l'**host** (es. `fcm.googleapis.com`) e l'id della riga, mai l'indirizzo intero.

---

## Parte 7 — Terza scelta: a quali indirizzi il server accetta di scrivere (SSRF)

### 7.1 Il problema

Con la push il backend fa `POST` a un indirizzo **che gli ha dato il client**. Se si accettasse qualunque URL, un utente del pannello potrebbe registrare `http://localhost:8080/...`, `http://169.254.169.254/` (i metadati del server in molti cloud) o un indirizzo della rete interna di Docker, e il backend ci scriverebbe a suo nome a ogni richiesta da approvare. È la **SSRF** (*Server-Side Request Forgery*): far fare al server richieste che dall'esterno non si potrebbero fare.

I professionisti sono persone di fiducia. Ma un account rubato è un attaccante con il ruolo del professionista, e la difesa costa poco.

### 7.2 Strada A — Accettare qualunque `https`

Ferma `http://localhost`, ma non `https://` verso un host interno o qualunque sito.

### 7.3 Strada B — Solo i servizi push conosciuti

I servizi push sono pochi e noti. Si accettano solo `https` sulla porta standard, con host:

| Host (o suoi sottodomini) | Di chi |
|---|---|
| `fcm.googleapis.com` | Google (Chrome, Android, Opera, Samsung Internet) |
| `push.services.mozilla.com` | Mozilla (Firefox) |
| `push.apple.com` | Apple (Safari, iPhone, iPad) |
| `notify.windows.com` | Microsoft (Edge) |

Un vincolo Bean Validation nostro, `@PushEndpoint`, come `@ValidEmail` (D-037, D-048): un indirizzo fuori elenco è un **400** sul campo `endpoint`. Se domani nasce un servizio push nuovo, si aggiunge una riga e un test.

### 7.4 Raccomandazione: B

Il controllo si fa **all'ingresso**, alla registrazione. Le righe nel database sono quindi già controllate, e il codice che spedisce non le ricontrolla: per questo i test possono inserire con SQL un'iscrizione verso un finto servizio push su `http://localhost`.

---

## Parte 8 — Quando parte la push, e quando l'email al suo posto

### 8.1 Quali eventi

Solo **`ApprovalRequested`**: è l'unico evento che chiede al professionista di **fare** qualcosa, entro una scadenza (guida 27, parte 2.2). Ritiri, scadenze e conferme non gli chiedono niente. Se un giorno servirà ("una richiesta in meno"), è un caso nuovo nello `switch` della funzione pura.

### 8.2 La regola della riserva

D-032: l'email arriva se il professionista non ha dispositivi, o se l'invio fallisce. Quindi, per ogni `ApprovalRequested`:

1. si prova la push su **tutti** i dispositivi del professionista;
2. se **almeno uno** l'ha accettata, l'email al professionista **non parte**;
3. altrimenti (nessun dispositivo, tutti rifiutati, servizio push irraggiungibile, un errore qualunque) parte l'email del pezzo 4.

L'email al cliente parte sempre: con la push non c'entra.

### 8.3 Accettata non vuol dire consegnata

Il 201 del servizio push vuol dire "l'ho preso in carico", non "il telefono l'ha mostrato". Un telefono spento la riceve alla riaccensione. Due intestazioni rendono questo ritardo innocuo:

- **`TTL`** (*time to live*): quanti secondi il servizio push tiene il messaggio se il telefono non è raggiungibile. Qui **i secondi che mancano alla scadenza della risposta**: dopo, la richiesta è chiusa, e una notifica che dice "approva entro le 22" alle 23 è solo rumore. Il servizio la butta da solo.
- **`Urgency: high`**: dice al servizio push di consegnare subito anche a un telefono in risparmio energetico.

Resta un caso: il telefono è acceso, la push arriva, ma il professionista non la guarda. Non è un problema tecnico, e un'email in più non lo risolverebbe.

### 8.4 Dove gira

Nello stesso ascoltatore del pezzo 4 (`BookingNotifications`), dopo il commit, nel virtual thread dell'email. La push è una richiesta HTTP che aspetta la rete come l'email: il caso perfetto per un virtual thread (guida 27, parte 4). Nessuna transazione è legata a quel thread: cancellare un'iscrizione scaduta è una transazione nuova e indipendente, senza bisogno di `REQUIRES_NEW` (guida 27, 3.2).

---

## Parte 9 — Le risposte del servizio push

| Risposta | Significa | Cosa si fa |
|---|---|---|
| 201 (o un altro 2xx) | Presa in carico | Conta come consegnata |
| 404, 410 | L'iscrizione non esiste più (l'utente ha tolto il permesso, ha disinstallato, il browser l'ha rinnovata) | **Si cancella la riga** (D-032, conseguenze) |
| 400, 403, 413 | Richiesta sbagliata, firma rifiutata, messaggio troppo grande | Un errore nostro: log a livello `ERROR` |
| 429, 5xx, timeout, rete | Il servizio è sovraccarico o irraggiungibile | Log a livello `WARN`; la riga resta |

In tutti i casi tranne il primo, quel dispositivo **non conta** come raggiunto: se nessuno l'ha accettata, parte l'email. Nessun nuovo tentativo, come per l'email (guida 27, 3.5): la riserva è l'email stessa.

---

## Parte 10 — Il testo della push

### 10.1 Cosa dice

```
Nuova prenotazione da approvare
lunedì 9 novembre, 9:00 · rispondi entro venerdì 6 novembre, 22:00
```

Una notifica compare sullo **schermo bloccato**, dove la vede chiunque abbia il telefono davanti. Quindi: **niente nome del cliente** (D-032) e, come per gli oggetti delle email (guida 27, 6.1), **niente tipo di trattamento**. Data, ora e scadenza bastano per capire che c'è da fare, e toccandola si apre il pannello.

### 10.2 Il formato: un contratto con il passo 13

Il messaggio è un piccolo JSON, che il *service worker* del passo 13 leggerà:

```json
{
  "title": "Nuova prenotazione da approvare",
  "body": "lunedì 9 novembre, 9:00 · rispondi entro venerdì 6 novembre, 22:00",
  "url": "http://localhost:3000",
  "tag": "booking-42"
}
```

- `url`: dove portare il professionista quando tocca la notifica. Oggi l'indirizzo base (`APP_URL`, come le email); al passo 13, la pagina della richiesta.
- `tag`: le notifiche con lo stesso `tag` si sostituiscono invece di accumularsi.

È un `record` (`PushPayload`), trasformato in JSON con Jackson: niente stringhe JSON scritte a mano, dove un apostrofo o un accento rompono il formato.

### 10.3 Ancora una funzione pura

Come `BookingEmails`: `PushMessages.pushFor(event, now)` restituisce il messaggio da mandare, o niente. Uno `switch` sulla `sealed interface` senza `default`: un evento nuovo non compila finché qualcuno non decide se ha una push.

---

## Parte 11 — I test

| Test | Cosa prova |
|---|---|
| `PushEncryptionTest` | **Il vettore dell'RFC 8291**: con le chiavi e il sale dell'appendice A, il risultato è identico byte per byte. Una chiave fuori dalla curva o di lunghezza sbagliata è rifiutata. Un messaggio troppo grande è rifiutato |
| `VapidTest` | L'intestazione `Authorization`: `aud` con l'origine dell'`endpoint` (anche con la porta), `exp` 12 ore dopo, `sub`, e la firma che si verifica con la chiave pubblica. Chiavi che non sono una coppia: errore all'avvio |
| `PushMessagesTest` | Solo `ApprovalRequested` ha una push; nel testo né nomi né servizio; `TTL` fino alla scadenza, zero se è passata |
| `PushSubscriptionApiTest` | Registrazione (204, una riga), di nuovo lo stesso `endpoint` (sempre una riga, chiavi nuove), da un altro professionista (la riga passa a lui), l'undicesimo dispositivo, gli `endpoint` rifiutati (`http`, host sconosciuto, porta), una chiave non valida, la cancellazione solo della propria, la chiave pubblica; cliente 403, anonimo 401 |
| `BookingNotificationsTest` (nuovi casi) | Con un **finto servizio push** (un server HTTP del JDK su `localhost`) che **decifra** ciò che riceve con la chiave privata del finto browser: la push arriva, con il testo giusto e senza nomi, e l'email ad Anna non parte; un dispositivo 410 viene cancellato e parte l'email; un dispositivo 500 resta e parte l'email; due dispositivi, uno scaduto e uno vivo: niente email |

Il finto servizio push decifra con un codice scritto **nel test**, separato da quello di produzione. Così il test del giro completo prova davvero che il messaggio è leggibile da un browser, e non che il codice di produzione sia d'accordo con se stesso.

Le chiavi VAPID dei test sono quelle dell'appendice A dell'RFC: pubbliche apposta, come la chiave HMAC dei test (guida 24).

---

## Parte 12 — Cosa c'è da decidere

| Scelta | Raccomandazione | Dove |
|---|---|---|
| Libreria o JDK | JDK, verificato con i vettori dell'RFC 8291 | parte 2 |
| Chiavi | `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` nel `.env`; generate con `tools/GenerateVapidKeys.java`; senza, l'avvio si ferma | parte 5 |
| Registrazione | `on conflict (endpoint) do update`; al massimo 10 dispositivi; cancellazione solo della propria | parte 6 |
| SSRF | Solo `https` verso i servizi push conosciuti, con `@PushEndpoint` | parte 7 |
| Quando | Solo `ApprovalRequested`; email al professionista solo se nessun dispositivo ha accettato; `TTL` fino alla scadenza, `Urgency: high` | parte 8 |
| Risposte | 404/410 cancellano la riga; il resto conta come non consegnato | parte 9 |
| Testo | Data, ora, scadenza; né nomi né trattamento; JSON da un `record` | parte 10 |

Una migrazione nuova: **V7**.

---

## Parte 13 — Com'è andata (implementazione)

Costruito come raccomandato nella parte 12 (D-054). 601 test verdi con `mvnw clean test`, 42 nuovi: 6 in `PushEncryptionTest`, 5 in `VapidTest`, 3 in `PushMessagesTest`, 15 in `PushEndpointTest` (uno per indirizzo), 9 in `PushSubscriptionApiTest`, 4 in `BookingNotificationsTest`.

**Il vettore dell'RFC al primo colpo.** Il test che confronta la nostra cifratura con l'appendice A dell'RFC 8291 è passato alla prima esecuzione. Un verde al primo colpo va messo alla prova (parte sotto): togliendo il solo byte `00` in fondo a `"WebPush: info"`, il test diventa rosso.

**Il giro completo.** In `BookingNotificationsTest` il finto browser decifra la push ricevuta dal finto servizio push e ottiene esattamente:

```json
{"title":"Nuova prenotazione da approvare","body":"lunedì 9 novembre, 9:00 · rispondi entro venerdì 6 novembre, 22:00","url":"http://localhost:3000","tag":"booking-6"}
```

Accenti e `·` arrivano intatti (UTF-8). Le intestazioni sono `TTL: 43200` (12 ore, fino alla scadenza), `Urgency: high`, `Content-Encoding: aes128gcm`, `Authorization: vapid t=...`.

**I test sanno fallire.** Dieci difese tolte una alla volta, ognuna con `mvnw clean`:

| Tolto | Test che se ne accorge |
|---|---|
| il byte `00` di `key_info` | il vettore RFC, e il giro con il finto browser |
| `SHA256withECDSAinP1363Format` (al suo posto la firma DER) | `VapidTest`: la firma non è più di 64 byte |
| il controllo che il punto stia sulla curva | `aKeyOffTheCurveIsRefused`, e il 400 dell'API sulla chiave |
| il controllo `https` | `PushEndpointTest` (`http://fcm.googleapis.com`) |
| la regola della riserva (email sempre) | i due test in cui una push è stata accettata |
| la cancellazione dei dispositivi scaduti | `aDeviceThatNoLongerExistsIsDeleted...`, `oneDeviceReachedOutOfTwo...` |
| il 410 come "scaduto" (solo il 404) | gli stessi due |
| il limite di 10 dispositivi | `theEleventhDeviceRemovesTheOldest` |
| "solo i propri" nella cancellazione | `unsubscribingRemovesOnlyAnOwnDevice` |
| il `TTL` fino alla scadenza (28 giorni fissi) | `itLivesUntilTheDeadline`, e l'intestazione `TTL` del giro completo |

**Errore 1 (mio): mutazioni che si contaminavano.** Il primo giro di mutazioni ripristinava il codice **spostando** la copia di backup al posto del file. Una copia spostata conserva la sua data di modifica, più vecchia della classe compilata dalla mutazione, e Maven poteva tenere in `target` la classe mutata. Il sintomo era strano: togliendo il controllo della curva falliva anche il test del vettore RFC, che non c'entra. Il confronto lo diceva: intestazione giusta, testo cifrato diverso. Era ancora la mutazione precedente, quella su `key_info`. Con `clean` falliva solo il test giusto. Ho corretto lo script (il ripristino riscrive il file) e rifatto tutte e dieci le mutazioni con `clean`: la tabella qui sopra è il secondo giro. La lezione: un fallimento che non si spiega va capito prima di essere contato, anche quando è il risultato che si voleva.

**Errore 2: un *text block* dentro uno script.** Lo script Python che aggiungeva i test al file usava le stringhe `"""`, e il `"""` di un *text block* Java le chiudeva a metà. Python si è fermato con un errore di sintassi **prima** di scrivere, quindi senza danni (i test erano ancora i 10 vecchi). Riscritto con `'''`.

**Un ripensamento: `@Immutable`.** Avevo segnato l'entità `PushSubscription` con `@Immutable` di Hibernate, visto che JPA non la salva mai. Ma con Hibernate 7 un'entità `@Immutable` può rifiutare le `delete` in blocco, come `deleteOwn`. Invece di scoprirlo in produzione l'ho tolta: il "mai salvata con JPA" resta scritto nel commento.

**Uno scostamento dalla progettazione.** `modello-dati.md` prevedeva `push_subscription.user_id` verso `app_user`. Ho usato `practitioner_id` verso `practitioner`: solo i professionisti ricevono push (D-032), e così la *foreign key* lo garantisce. `modello-dati.md` è aggiornato, e lo scostamento è in D-054.

**Non fatto:** una push vera su un telefono. Serve il *service worker* del passo 13. È l'unica parte non provata: che i servizi push di Google, Mozilla e Apple accettino le nostre richieste. Cifratura e firma sono verificate contro gli RFC, quindi un rifiuto lì farebbe pensare prima di tutto alla configurazione (`VAPID_SUBJECT`, chiavi diverse da quelle dell'iscrizione).

---

## Domande da colloquio

### Domanda 1: Come funziona una notifica Web Push, dal server al telefono?

**Risposta modello.** Il browser si iscrive presso il servizio push del suo produttore e dà alla pagina un `endpoint`, la sua chiave pubblica e un segreto `auth`; la pagina li manda al server. Per notificare, il server cifra il messaggio per quel browser (ECDH più HKDF, poi AES-GCM, RFC 8291), firma la richiesta con la sua chiave VAPID (un JWT ES256, RFC 8292), e fa `POST` all'`endpoint`. Il servizio push consegna senza poter leggere il contenuto, e il *service worker* della pagina mostra la notifica.

### Domanda 2: Cos'è l'ECDH e perché si usa una coppia di chiavi nuova per ogni messaggio?

**Risposta modello.** È un accordo di chiavi: la mia chiave privata con la tua chiave pubblica dà lo stesso segreto che la tua privata dà con la mia pubblica, e chi vede solo le pubbliche non lo può calcolare. Con una coppia effimera per messaggio, ogni messaggio ha una chiave AES diversa: scoprirne una non apre gli altri, e la chiave privata del server non resta da nessuna parte.

### Domanda 3: Il tuo server fa richieste HTTP verso URL che arrivano dai client. Che rischio c'è?

**Risposta modello.** La SSRF: un client può far chiamare al server indirizzi interni, come `localhost`, la rete dei container o i metadati del cloud, a cui dall'esterno non arriverebbe. La difesa più solida è un elenco degli host ammessi, controllato all'ingresso: qui solo `https` verso i servizi push noti. Un controllo per prefisso o solo sullo schema non basta.

### Domanda 4: Perché hai implementato tu la cifratura invece di usare una libreria? Non è "roll your own crypto"?

**Risposta modello.** Non ho inventato né implementato primitive: ECDH, HMAC, AES-GCM ed ECDSA sono del JDK. Ho composto quelle primitive nell'ordine scritto nell'RFC, e un test confronta il risultato con il vettore di prova pubblicato nell'RFC, byte per byte. L'alternativa era una libreria ferma da anni con una versione vulnerabile di Bouncy Castle. Con una libreria mantenuta e diffusa avrei scelto la libreria.

### Domanda 5: Una firma ECDSA del JDK è valida ma il destinatario la rifiuta. Cosa controlli?

**Risposta modello.** Il formato. `SHA256withECDSA` produce una firma DER (ASN.1, lunghezza variabile), mentre JWT, WebAuthn e molti protocolli vogliono `r` e `s` concatenati, 64 byte fissi (IEEE P1363). Il JDK li produce con `SHA256withECDSAinP1363Format`. Lo si scopre verificando la firma con lo stesso formato che userà il destinatario.

### Domanda 6: Come fai a non perdere una notifica importante se il canale principale fallisce?

**Risposta modello.** Un canale di riserva, deciso per esito e non per ipotesi: provo la push su tutti i dispositivi e, se nessuno la accetta, mando l'email. Distinguo "accettata dal servizio" da "consegnata", e lo gestisco con il `TTL` fino alla scadenza e l'urgenza alta. Gli errori che dicono "iscrizione scaduta" (404, 410) cancellano il dispositivo, così il prossimo messaggio non ci riprova.

### Domanda 7: Una mutazione fa fallire un test che con quella modifica non c'entra. Cosa pensi?

**Risposta modello.** Che lo stato non è quello che credo: una classe compilata vecchia, un file non ripristinato, dati lasciati da un test precedente. Prima di contare il risultato lo spiego: qui il testo cifrato diverso con l'intestazione giusta indicava un'altra modifica ancora attiva. Rifaccio da zero, con `clean`, e controllo che i file siano tornati com'erano.

---

## Documentazione ufficiale

- RFC 8030, Generic Event Delivery Using HTTP Push: https://www.rfc-editor.org/rfc/rfc8030
- RFC 8291, Message Encryption for Web Push (appendice A: i vettori di prova): https://www.rfc-editor.org/rfc/rfc8291
- RFC 8292, VAPID: https://www.rfc-editor.org/rfc/rfc8292
- RFC 8188, Encrypted Content-Encoding for HTTP (aes128gcm): https://www.rfc-editor.org/rfc/rfc8188
- RFC 5869, HKDF: https://www.rfc-editor.org/rfc/rfc5869
- MDN, Push API: https://developer.mozilla.org/en-US/docs/Web/API/Push_API
- Apple, notifiche web su iPhone (iOS 16.4+): https://developer.apple.com/documentation/usernotifications/sending-web-push-notifications-in-web-apps-and-browsers
- JDK, nomi standard degli algoritmi (`SHA256withECDSAinP1363Format`, `ECDH`): https://docs.oracle.com/en/java/javase/21/docs/specs/security/standard-names.html
- JEP 330, Launch Single-File Source-Code Programs: https://openjdk.org/jeps/330
- OWASP, Server-Side Request Forgery: https://owasp.org/www-community/attacks/Server_Side_Request_Forgery
