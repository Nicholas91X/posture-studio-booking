# Verifica 04 — Entità JPA e Testcontainers

2026-10-22 · modalità: argomento indicato ("entità jpa e testcontainers") · guide di riferimento: [05](../05-entita-jpa-e-test.md), [06](../06-come-funziona-spring.md)

> Premesse verificate prima del test su una copia del progetto (Hibernate 7.4, Testcontainers 2). La verifica ha anche corretto tre punti della guida 05 (`@Generated` da sola basta; `getId()` deve essere pubblico perché il proxy risponda senza query; l'errore dopo un vincolo violato è un `AssertionFailure` di Hibernate).

## Esito in breve

| # | Domanda | Formato | Sicurezza | Esito |
|---|---|---|---|---|
| 1 | La giornata di una connessione (ripasso PD-11, PD-12) | Punto di vista di una cosa | — | ⏭️ saltata |
| 2 | Tre modi di mappare `createdAt` | Cosa succede se | — | ⏸️ posta, non risposta |
| 3–6 | Da porre alla ripresa | — | — | ⏸️ |

---

## Domanda 1 — La giornata di una connessione (punto di vista di una cosa, ripasso PD-11 e PD-12)

**Domanda.** Sei una connessione del pool HikariCP nel test `@DataJpaTest` `saveAndRead` (salva un utente e lo rilegge). (1) All'avvio del contesto, prima dei test, chi ti usa e per cosa? (2) Quando parte il test, chi ti prende in prestito e perché in quel momento? (3) Come ti trova il repository durante `save`? (4) Chi ti usa per mandare l'`insert`, attraverso quale API? (5) A fine test cosa succede ai dati, e dove finisci?

**Sicurezza:** —

**Risposta di Nicholas:**
> Voglio saltare la domanda 1. Dammi le soluzioni

**Soluzione.**
1. All'avvio Hikari apre le connessioni verso il container; una viene usata da **Flyway** (storico, applicazione di V1) e da **Hibernate** (lettura della struttura del database, `validate`), poi torna nel pool. Ordine: `dataSource` → `flyway` → `entityManagerFactory` (guida 06, parte 4).
2. `@DataJpaTest` rende il test `@Transactional`: **prima** della prima riga, il `JpaTransactionManager` apre un `EntityManager` e prende in prestito la connessione, perché la transazione deve coprire tutto il test (`Creating new transaction`, `Opened new EntityManager`).
3. `EntityManager` e connessione sono **legati al thread** (`ThreadLocal`): il proxy di `save` li trova e **partecipa** alla transazione esistente (`Found thread-bound EntityManager`, `Participating in existing transaction`: propagazione `REQUIRED`).
4. **Hibernate**, con un `PreparedStatement` di **JDBC**; il **driver** PostgreSQL implementa JDBC e traduce nel protocollo del database (`insert ... returning id,created_at`).
5. `rollback`: i dati spariscono; l'`EntityManager` viene chiuso; la connessione **non** viene chiusa ma **torna nel pool**, pronta per il test successivo.

```
avvio:   Hikari apre le connessioni → Flyway (V1) → Hibernate (validate) → tornano nel pool
test:    TransactionManager presta la connessione + EntityManager → legati al THREAD
         save() la trova sul thread → Hibernate → JDBC → driver → PostgreSQL
fine:    rollback → EntityManager chiuso → la connessione torna nel pool
```

**Da ripassare:** guida 06, 6.2, 7.1, 10.2; guida 03, 1.2.

---

## Domanda 2 — Tre modi di mappare `createdAt` (cosa succede se)

**Domanda.** In V1 `created_at` è `timestamptz not null default now()`. Tre mappature di `createdAt`, una alla volta: (a) nessuna annotazione; (b) solo `@Column(insertable = false, updatable = false)`; (c) solo `@Generated` di Hibernate. Per ciascuna: il `save` riesce o fallisce, e perché? Cosa contiene `createdAt` subito dopo il `save`?

**Risposta di Nicholas:**
> Vorrei saltare il test (e riprenderlo poi in un altro momento) e procedere

*(Non risposta: la soluzione non viene scritta qui, perché la domanda si ripropone alla ripresa. Comportamento verificato il 2026-10-22 su Hibernate 7.4.)*

---

## Riepilogo

**Test interrotto** il 2026-10-27 dopo la domanda 2, su richiesta di Nicholas, per procedere con il codice. Da riprendere con `/verifica entità jpa e testcontainers`.

**Alla ripresa:**
- riproporre la domanda 2 (ancora non risposta);
- argomenti preparati per le domande 3–6, con premesse già verificate:
  - il proxy di una relazione *lazy* letto fuori dalla transazione, OSIV, problema N+1;
  - un test da correggere (scelta dell'immagine, vincolo giusto da verificare, operazione dopo l'errore atteso);
  - una proposta di scrivere l'entità come `record` e restituirla dal controller;
  - cosa succede ai test su GitHub Actions, senza `.env`;
- la domanda 1 (saltata, con soluzione sopra) va ripresa **in forma diversa** per i punti PD-11 e PD-12.

**Punti deboli:** nessuno cambiato di stato (domande senza risposta). PD-11 e PD-12 restano 🔴.
