package com.posturestudio.booking.credit;

import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only door of the credit module (D-002): records in, records out, never the entities.
 * Dependencies come into this module (payment -> credit, later booking -> credit), never go out of it.
 */
@Service
public class CreditService {

    private final CreditLotRepository lots;
    private final CreditMovementRepository movements;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    CreditService(CreditLotRepository lots, CreditMovementRepository movements, ApplicationEventPublisher events,
                  Clock clock) {
        this.lots = lots;
        this.movements = movements;
        this.events = events;
        this.clock = clock;
    }

    // The lot of a package just paid (guide 37, parts 7 and 9): the lot, its LOT_CREATED movement, the event.
    // MANDATORY: it joins the transaction of the webhook, so lot, payment and seen event commit together or not
    // at all; called without a transaction it fails at once instead of committing a lot without its payment.
    // Once per payment: the webhook guarantees it (the payment moves PENDING -> SUCCEEDED once, with its lock),
    // and credit_lot_payment_uq refuses a second lot anyway (D-069)
    @Transactional(propagation = Propagation.MANDATORY)
    public PackageLot lotFromPackage(PackagePurchase purchase) {
        ValidUntil validUntil = ValidUntil.daysAfter(purchase.paidAt(), purchase.validityDays());
        CreditLot lot = lots.save(CreditLot.fromPackage(purchase.customerId(), purchase.practitionerId(),
                purchase.serviceId(), purchase.paymentId(), purchase.packageOfferId(), purchase.priceCents(),
                validUntil, clock.instant()));
        movements.save(CreditMovement.lotCreated(lot.getId(), purchase.credits(), clock.instant()));
        events.publishEvent(new CreditEvent.PackagePurchased(lot.getId(), purchase.customerId(),
                purchase.practitionerId(), purchase.serviceId(), purchase.credits(), validUntil.lastDay()));
        return new PackageLot(purchase.paymentId(), lot.getId(), purchase.credits(), validUntil.lastDay());
    }

    // The lots of these payments, by payment id (guide 37, part 9): one question for a whole list (D-029).
    // A payment with no lot (still pending, expired) is simply not in the map
    @Transactional(readOnly = true)
    public Map<Long, PackageLot> packageLotsOf(Collection<Long> paymentIds) {
        if (paymentIds.isEmpty()) {
            // "in ()" is not valid SQL: do not depend on how Hibernate translates an empty list
            return Map.of();
        }
        return lots.findPackageLotsOf(paymentIds).stream()
                .collect(Collectors.toUnmodifiableMap(PackageLot::paymentId, Function.identity()));
    }
}
