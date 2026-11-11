package com.posturestudio.booking.credit;

import java.time.LocalDate;

/**
 * A lot as its practitioner sees it after a concession (guide 40): its balance now and its last valid day.
 * The lists of the panel, with the customer's name and the history, arrive with piece 6. No names here: a gift by
 * email may reach an existing customer, and the answer must not tell more than the practitioner wrote (D-066)
 */
record PanelCreditLotView(long id, long customerId, long serviceId, LotSource source, long balance,
                          LocalDate validUntil) {

    static PanelCreditLotView of(CreditLot lot, long balance) {
        return new PanelCreditLotView(lot.getId(), lot.getCustomerId(), lot.getServiceId(), lot.getSource(), balance,
                lot.getValidUntil().lastDay());
    }
}
