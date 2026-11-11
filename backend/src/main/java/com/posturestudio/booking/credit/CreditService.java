package com.posturestudio.booking.credit;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only door of the credit module (D-002): records in, records out, never the entities.
 * Dependencies come into this module (payment -> credit, booking -> credit), never go out of it.
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

    // ***** BOOKING WITH A CREDIT (step 10, piece 3, guide 38) *****

    // A quick look, without locks (guide 38, part 4): has this customer a credit for this session? It decides
    // nothing, it only lets the request answer 409 early when there is neither a credit nor a card account
    @Transactional(readOnly = true)
    public boolean hasUsableCredit(long customerId, long practitionerId, long serviceId, Instant sessionStart) {
        return !lots.findUsableIds(customerId, practitionerId, serviceId, ValidUntil.dayOf(sessionStart)).isEmpty();
    }

    // The choice that counts, under the locks (guide 38, parts 5 and 6): the usable lot that ends first, LOCKED,
    // its balance read again after the lock. Usable for a session = its last valid day covers the day of the
    // session (guide 38, part 2: ValidUntil.covers(sessionStart)), not only today. The caller already holds the
    // lock on the customer (D-045), which is enough for two requests of the same customer; the lock on the lot
    // is the rule of D-069 for every debit, and it also covers a correction of the practitioner, which does not
    // go through the customer. Lock order: customer, then lot. MANDATORY: the lock lives until the caller commits
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> lockUsableLot(long customerId, long practitionerId, long serviceId, Instant sessionStart) {
        for (long lotId : lots.findUsableIds(customerId, practitionerId, serviceId, ValidUntil.dayOf(sessionStart))) {
            lots.findLockedById(lotId);
            // read again after the lock: a correction may have taken the last credit while we waited
            if (movements.balanceOf(lotId) > 0) {
                return Optional.of(lotId);
            }
        }
        return Optional.empty();
    }

    // The credit is committed to the booking: -1 on the lot locked by lockUsableLot. The trigger of the balance
    // refuses it anyway if the lot were empty (D-069)
    @Transactional(propagation = Propagation.MANDATORY)
    public void commit(long lotId, long bookingId) {
        movements.save(CreditMovement.booking(lotId, bookingId, clock.instant()));
    }

    // The booking let its credit go (rejected, unanswered, withdrawn: D-030): +1 on THE SAME lot, which keeps its
    // last valid day (D-010). Once only: false when the booking took no credit or already gave it back; the
    // unique index of V15 refuses a second one anyway. The caller holds the lock on the booking (D-046)
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean release(long bookingId) {
        Optional<CreditMovement> taken = movements.findByBookingIdAndReason(bookingId, MovementReason.BOOKING);
        if (taken.isEmpty() || movements.existsByBookingIdAndAmountGreaterThan(bookingId, 0)) {
            return false;
        }
        movements.save(CreditMovement.bookingRelease(taken.get().getCreditLotId(), bookingId, clock.instant()));
        return true;
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
