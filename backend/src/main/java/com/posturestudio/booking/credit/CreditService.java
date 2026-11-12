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

    // ***** CANCELLATIONS (step 11, guide 43, part 4) *****

    // A session paid with a credit, cancelled in time or by the studio: its credit comes back, valid at least
    // validityDays (single_credit_validity_days) from the day of the cancellation in Rome (D-076):
    // 1. its lot lasts at least that long: +1 on the same lot (CANCELLATION_RESTORE);
    // 2. it ends sooner, but it is a CANCELLATION lot ("already extended") and the customer cancelled: +1 on the
    //    same lot all the same, no new extension. Without this, "book, cancel in time" would extend a credit
    //    forever, and from a card too (guide 42, part 8);
    // 3. otherwise (a package or a gift that ends sooner; or the studio cancelled): a new lot of 1 until that day.
    // Once only: the caller holds the lock on the booking and checks its state; the index "one return per booking"
    // refuses a second credit anyway, whichever of the two ways (V16). A positive movement takes no lock on the lot:
    // it cannot take it below zero (D-069). MANDATORY: in the transaction of the cancellation
    @Transactional(propagation = Propagation.MANDATORY)
    public ReturnedCredit restoreOnCancellation(long bookingId, int validityDays, CancelledBy who) {
        Instant now = clock.instant();
        CreditMovement taken = movements.findByBookingIdAndReason(bookingId, MovementReason.BOOKING)
                .orElseThrow(() -> new IllegalStateException("Booking " + bookingId + " took no credit"));
        CreditLot lot = lots.findById(taken.getCreditLotId()).orElseThrow();
        ValidUntil minimum = ValidUntil.daysAfter(now, validityDays);
        boolean longEnough = !minimum.isAfter(lot.getValidUntil());
        boolean alreadyExtended = lot.getSource() == LotSource.CANCELLATION && who == CancelledBy.CUSTOMER;
        if (longEnough || alreadyExtended) {
            movements.save(CreditMovement.cancellationRestore(lot.getId(), bookingId, now));
            return new ReturnedCredit(lot.getId(), lot.getValidUntil().lastDay());
        }
        return newLot(lot.getCustomerId(), lot.getPractitionerId(), lot.getServiceId(), bookingId, minimum, now);
    }

    // A session paid by card, cancelled in time or by the studio: a credit of 1 valid validityDays from the day of
    // the cancellation (D-010, D-076). The money stays with the studio (D-015)
    @Transactional(propagation = Propagation.MANDATORY)
    public ReturnedCredit lotFromCancellation(long customerId, long practitionerId, long serviceId, long bookingId,
                                              int validityDays) {
        Instant now = clock.instant();
        return newLot(customerId, practitionerId, serviceId, bookingId, ValidUntil.daysAfter(now, validityDays), now);
    }

    // The credits that came back to these bookings (guide 43, part 9), by booking: one query for a list (D-029)
    @Transactional(readOnly = true)
    public Map<Long, ReturnedCredit> returnedTo(Collection<Long> bookingIds) {
        if (bookingIds.isEmpty()) {
            return Map.of();
        }
        return movements.findReturnsTo(bookingIds).stream()
                .collect(Collectors.toUnmodifiableMap(BookingReturn::bookingId,
                        row -> new ReturnedCredit(row.lotId(), row.validUntil())));
    }

    // The one place that creates a CANCELLATION lot (D-078): with its LOT_CREATED carrying the booking, the rule
    // across two tables that the database cannot check by itself
    private ReturnedCredit newLot(long customerId, long practitionerId, long serviceId, long bookingId,
                                  ValidUntil validUntil, Instant now) {
        CreditLot lot = lots.save(CreditLot.fromCancellation(customerId, practitionerId, serviceId, validUntil, now));
        movements.save(CreditMovement.lotCreatedByCancellation(lot.getId(), bookingId, now));
        return new ReturnedCredit(lot.getId(), validUntil.lastDay());
    }

    // The customers with at least one lot with this practitioner (D-066): booking joins them with the customers of
    // its bookings, for "my customers" of the panel (guide 41, part 4)
    @Transactional(readOnly = true)
    public java.util.Set<Long> customerIdsOf(long practitionerId) {
        return java.util.Set.copyOf(lots.findCustomerIdsOf(practitionerId));
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
