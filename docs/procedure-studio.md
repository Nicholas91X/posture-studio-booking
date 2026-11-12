# Procedure per lo studio

Cose che il sistema non fa da solo, apposta, e che il professionista fa a mano. Scritte per i professionisti, non per chi sviluppa.

---

## Rimborsare un cliente che non vuole il credito

**Quando serve.** Il sistema non restituisce mai soldi (D-015): quando una seduta pagata viene cancellata in tempo dal cliente, o in qualunque momento dallo studio, il cliente riceve un **credito** (D-075, D-076). Se il cliente preferisce i soldi, il professionista lo rimborsa a mano.

**Cosa serve.** L'accesso al pannello e alla dashboard di Stripe del **proprio** conto di incasso (se il conto è comune, a quello comune).

**I passi, in quest'ordine.**

1. **Azzera il credito nel pannello.** Apri il cliente, poi i suoi crediti, poi il credito nato dalla cancellazione (lo riconosci dalla data e dalla seduta cancellata). Fai una **correzione di −1**, con una nota, per esempio: *"Rimborso richiesto dal cliente per la seduta del 14/11, rimborsato da Stripe."*
   - Se il pannello rifiuta la correzione (*crediti insufficienti*), il cliente **ha già usato** quel credito per un'altra seduta: **non rimborsare**, ne riceverebbe due volte il valore. Parlane con il cliente.
2. **Rimborsa da Stripe.** Nella dashboard: *Pagamenti*, cerca il pagamento (per email del cliente o per data), apri il pagamento, *Rimborsa*. Scegli l'importo:
   - seduta pagata con carta: l'importo della seduta;
   - seduta pagata con un credito di un pacchetto: la parte di quel pacchetto, di solito il prezzo del pacchetto diviso per le sue sedute. Decide il professionista.
3. **Se il rimborso non riesce** (Stripe lo rifiuta, o il pagamento è troppo vecchio per essere rimborsato), rimetti il credito con una **correzione di +1**, con la nota *"Rimborso non riuscito, credito restituito"*, e avvisa il cliente.

**Perché prima la correzione e poi il rimborso.** Finché il credito c'è, il cliente può prenotarci sopra. Se rimborsi prima e correggi dopo, in mezzo il cliente potrebbe usare il credito e avere anche i soldi. Azzerato prima, il credito non si può più usare; e se il rimborso poi non va, rimetterlo è un clic.

**Cosa resta registrato.** Nel sistema, la correzione con chi l'ha fatta, quando e perché (D-067); in Stripe, il rimborso. Il cliente riceve un'email per la correzione (D-073) e la ricevuta del rimborso da Stripe.

**Commissioni.** Su un rimborso Stripe di norma non restituisce la commissione del pagamento (da riverificare sulla dashboard del conto prima della messa online, passo 14): il costo resta allo studio. È il motivo per cui il sistema offre il credito e non il rimborso.

> Ordine dei passi: proposta di Claude (D-075), da confermare con Nicholas. Il brief del passo 11 diceva "rimborsa, poi azzera".
