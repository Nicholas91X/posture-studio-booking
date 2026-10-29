# Verifica 05 — Colloquio simulato sul passo 4

2026-10-29 · modalità: colloquio (`/verifica colloquio passo 4`) · riferimenti: [passo 04](../../passi/04-fondamenta.md), guide [03](../03-configurazione-backend.md), [04](../04-flyway-e-migrazione-v1.md), [05](../05-entita-jpa-e-test.md), [06](../06-come-funziona-spring.md), [07](../07-costruttori.md)

> Premesse verificate prima del test su una copia del backend (Spring Boot 4.1.1, Hibernate 7.4): senza alcun `.env` i 21 test passano; un campo `lastLoginAt` aggiunto ad `AppUser` senza migrazione fa fallire l'avvio con `Schema validation: missing column [last_login_at] in table [app_user]`.
>
> Assorbe la verifica 04 (interrotta): la sua domanda 2 è ripresa qui in forma diversa (domanda 3).

## Esito in breve

| # | Domanda | Formato | Sicurezza | Esito |
|---|---|---|---|---|
| 1 | «Quindi non usa JDBC?» (ripasso PD-12, PD-11) | Colloquio, con trappola | | |
| 2 | Il campo aggiunto senza migrazione (ripasso PD-07) | Cosa succede se | | |
| 3 | | | | |
| 4 | | | | |
| 5 | | | | |
| 6 | | | | |

---

## Domanda 1 — «Quindi non usa JDBC?» (colloquio, ripasso PD-12 e PD-11)

**Domanda.** L'intervistatore guarda il suo `pom.xml`: «Vedo `spring-boot-starter-data-jpa`. Quindi nel suo progetto JDBC non lo usa, giusto? È roba vecchia.» Cosa risponde?

**Sicurezza:**

**Risposta di Nicholas:**

**Approfondimento:**

**Correzione.**

**Da ripassare:**

---

## Riepilogo

*(a fine test)*
