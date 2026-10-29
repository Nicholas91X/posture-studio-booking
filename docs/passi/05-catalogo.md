# Passo 05 — Catalogo e professionisti

> **Stato: in corso** (2026-10-29). Pezzo 1 di 5 fatto.

## Obiettivo
Il professionista gestisce cosa vende e come lavora: servizi, pacchetti in vendita e impostazioni. Il cliente legge il catalogo attivo. Sono le prime API REST del progetto: richiesta → validazione → regole → database → risposta.

I pezzi:
1. Migrazione V2 e test dei vincoli — ✅ fatto
2. Modulo `practitioner`: entità, repository chiuso nel modulo, `PractitionerService`, le tre impostazioni
3. Modulo `catalog`: entità `Service` e `PackageOffer`, `CatalogService` con le regole
4. "Professionista corrente" provvisorio e API del pannello, con validazione e regola di proprietà (404)
5. API pubbliche, `@RestControllerAdvice` con `ProblemDetail`, test delle API dall'esterno

## Cosa è stato costruito

| File | Chi | Cosa fa |
|---|---|---|
| `db/migration/V2__create_service_and_package_offer.sql` | Claude, su richiesta di Nicholas | Tabelle `service` e `package_offer` con vincoli con nome: nome non vuoto, durata, prezzo e validità > 0, sedute ≥ 2, foreign key composta (D-025), indici su `practitioner_id` |
| `test/.../catalog/CatalogSchemaTest.java` | Claude, su richiesta di Nicholas | 10 test di integrazione con SQL diretto (`JdbcTemplate`): due casi positivi, un valore limite (2 sedute), un caso negativo per ogni vincolo, verificato con il nome del vincolo |
| `test/.../auth/AppUserRepositoryTest.java` | Claude, su richiesta di Nicholas | Aggiunti i test rimasti dal passo 4: email in maiuscolo e ruolo inesistente inseriti con SQL, rifiutati da `app_user_email_ck` e `app_user_role_ck` |

## Concetti chiave

> Spiegazione completa: [`docs/studio/08-migrazione-v2-catalogo.md`](../studio/08-migrazione-v2-catalogo.md).

### Regole tra due tabelle
Un `check` vede solo la propria riga. Per garantire che il servizio di un pacchetto sia dello stesso professionista serve una **foreign key composta**: la coppia (`service_id`, `practitioner_id`) deve esistere in `service`. PostgreSQL la accetta solo verso colonne coperte da un `unique` su esattamente quelle colonne, quindi in `service` serve un `unique (id, practitioner_id)`, logicamente ridondante. Con colonne `NULL` la composta non viene controllata (`MATCH SIMPLE`): per questo entrambe sono `not null`.

### Cosa diventa un vincolo e cosa no
Nel database va ciò che è vero per ogni riga, sempre. Non diventano vincoli le regole che valgono al momento di un'azione ("si crea un pacchetto solo su un servizio attivo": domani il servizio può essere disattivato) o che dipendono da dati che cambiano in un'altra tabella (lo scatto del professionista). Nemmeno i filtri in lettura (il catalogo pubblico) sono vincoli.

### `not null` e `check` insieme
`check (btrim(name) <> '')` rifiuta `''` e `'   '`, ma con un `NULL` il risultato è "sconosciuto" e il `check` passa (PD-14). Servono entrambi.

### Testare il database con SQL diretto
Gli `insert` con `JdbcTemplate` saltano ogni validazione Java: provano che il database si difende da solo. `JdbcTemplate` traduce le violazioni in `DataIntegrityViolationException`. Per non far passare un test per il motivo sbagliato: il nome del vincolo nel messaggio, un solo valore sbagliato per test, almeno un caso positivo, un valore limite. Dopo l'errore atteso, nessun'altra istruzione: in PostgreSQL la transazione è ormai annullata.

Dettaglio: il driver PostgreSQL restituisce `timestamptz` come `OffsetDateTime`, non come `Instant`, quando lo si legge con `JdbcTemplate`.

## Decisioni
- D-025: foreign key composta per il servizio del pacchetto.
- D-022: prezzo unico `price_cents`, applicato in V2.

## Errori fatti e come sono stati corretti
*(da completare con i pezzi successivi)*

## Domande da colloquio
*(a fine passo; quelle sulla V2 sono nella guida 08)*
