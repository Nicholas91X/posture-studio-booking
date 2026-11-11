package com.posturestudio.booking.credit;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.catalog.ServiceOnSale;
import com.posturestudio.booking.customer.CustomerService;
import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotFoundException;

/**
 * The concessions of a practitioner (D-067, guide 40): a gift, an extension, a correction. Always on own lots and
 * own services, always with who (the account acting), when (the Clock) and why (a note). No limits beyond these
 * and a balance never below zero (D-067): the trace is the control.
 *
 * practitionerId: whose lots and services; userId: the account that signs the trace (guide 40, part 2).
 * Dependencies: credit -> catalog, customer, practitioner; none of them depends on credit.
 */
@Service
class CreditConcessions {

    private final CreditLotRepository lots;
    private final CreditMovementRepository movements;
    private final CreditLotExtensionRepository extensions;
    private final CatalogService catalog;
    private final CustomerService customers;
    private final PractitionerService practitioners;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    CreditConcessions(CreditLotRepository lots, CreditMovementRepository movements,
                      CreditLotExtensionRepository extensions, CatalogService catalog, CustomerService customers,
                      PractitionerService practitioners, ApplicationEventPublisher events, Clock clock) {
        this.lots = lots;
        this.movements = movements;
        this.extensions = extensions;
        this.catalog = catalog;
        this.customers = customers;
        this.practitioners = practitioners;
        this.events = events;
        this.clock = clock;
    }

    // A gift (guide 40, part 3): a new lot of its own source, for a customer found or created by email (D-066).
    // No lock: nobody else can touch a lot that does not exist yet
    @Transactional
    public PanelCreditLotView grant(long practitionerId, long userId, NewCreditGrant request) {
        Instant now = clock.instant();
        // an own service on sale, or 404: a gift nobody could book is no gift
        ServiceOnSale service = catalog.serviceOnSale(practitionerId, request.serviceId());
        ValidUntil validUntil = request.validUntil() != null ? new ValidUntil(request.validUntil())
                : ValidUntil.daysAfter(now, practitioners.getSettings(practitionerId).singleCreditValidityDays());
        requireNotInThePast(validUntil.lastDay(), now);
        long customerId = customers.findOrCreate(request.customer());
        CreditLot lot = lots.save(CreditLot.grant(customerId, practitionerId, service.id(), userId, request.note(),
                validUntil, now));
        movements.save(CreditMovement.lotCreated(lot.getId(), request.credits(), now));
        events.publishEvent(new CreditEvent.CreditGranted(lot.getId(), customerId, practitionerId, service.id(),
                request.credits(), validUntil.lastDay()));
        return PanelCreditLotView.of(lot, request.credits());
    }

    // An extension (guide 40, part 4): forward only, never to a day already gone, also of an expired lot. The lot
    // with its lock, so two extensions queue; the row of the history saved first, as the trigger of V15 demands
    @Transactional
    public PanelCreditLotView extend(long practitionerId, long userId, long lotId, NewCreditExtension request) {
        Instant now = clock.instant();
        CreditLot lot = ownLocked(practitionerId, lotId);
        LocalDate oldValidUntil = lot.getValidUntil().lastDay();
        if (!request.validUntil().isAfter(oldValidUntil)) {
            throw new BusinessRuleException(ErrorCode.CREDIT_EXTENSION_NOT_LATER,
                    "Lot " + lotId + " is valid until " + oldValidUntil + ": an extension moves it forward");
        }
        requireNotInThePast(request.validUntil(), now);
        extensions.save(lot.extendTo(new ValidUntil(request.validUntil()), userId, request.note(), now));
        events.publishEvent(new CreditEvent.CreditExtended(lot.getId(), lot.getCustomerId(), practitionerId,
                lot.getServiceId(), oldValidUntil, request.validUntil()));
        return PanelCreditLotView.of(lot, movements.balanceOf(lot.getId()));
    }

    // A correction (guide 40, part 5): with the lock on the lot, as every debit (D-069), the balance read under it.
    // Never below zero: a readable 409 here, the trigger of V15 as the last defense
    @Transactional
    public PanelCreditLotView adjust(long practitionerId, long userId, long lotId, NewCreditAdjustment request) {
        Instant now = clock.instant();
        CreditLot lot = ownLocked(practitionerId, lotId);
        long balance = movements.balanceOf(lot.getId());
        if (balance + request.amount() < 0) {
            throw new BusinessRuleException(ErrorCode.CREDIT_BALANCE_INSUFFICIENT,
                    "Lot " + lotId + " has " + balance + " credits: a correction of " + request.amount()
                            + " would go below zero");
        }
        movements.save(CreditMovement.adjustment(lot.getId(), request.amount(), userId, request.note(), now));
        long after = balance + request.amount();
        events.publishEvent(new CreditEvent.CreditAdjusted(lot.getId(), lot.getCustomerId(), practitionerId,
                lot.getServiceId(), request.amount(), after, lot.getValidUntil().lastDay()));
        return PanelCreditLotView.of(lot, after);
    }

    @Transactional(readOnly = true)
    public PanelCreditLotView find(long practitionerId, long lotId) {
        CreditLot lot = lots.findByIdAndPractitionerId(lotId, practitionerId).orElseThrow(() -> notFound(lotId));
        return PanelCreditLotView.of(lot, movements.balanceOf(lot.getId()));
    }

    // the practitioner is part of the query: the lot of another is not found (404), as everything of another
    private CreditLot ownLocked(long practitionerId, long lotId) {
        return lots.findLockedByIdAndPractitionerId(lotId, practitionerId).orElseThrow(() -> notFound(lotId));
    }

    // a last valid day already gone gives the customer nothing; today is fine: it lasts until midnight in Rome
    private static void requireNotInThePast(LocalDate lastDay, Instant now) {
        if (lastDay.isBefore(ValidUntil.dayOf(now))) {
            throw new BusinessRuleException(ErrorCode.CREDIT_VALID_UNTIL_IN_THE_PAST,
                    "The last valid day " + lastDay + " is already gone");
        }
    }

    private static NotFoundException notFound(long lotId) {
        return new NotFoundException("Credit lot " + lotId + " not found");
    }
}
