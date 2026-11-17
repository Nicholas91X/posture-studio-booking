package com.posturestudio.booking.credit;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.catalog.PackageOnSale;
import com.posturestudio.booking.customer.CustomerService;
import com.posturestudio.booking.shared.NotFoundException;

/**
 * Packages sold in the studio and recorded from the panel (D-089). The lot is born as for a package bought online,
 * from a photograph of the package (D-069), with STUDIO_SALE as source and who sold it; there is no payment, because
 * the system does not record cash. No event: the practitioner is the one who did it, in front of the customer.
 */
@Service
class StudioSales {

    private final CreditLotRepository lots;
    private final CreditMovementRepository movements;
    private final CatalogService catalog;
    private final CustomerService customers;
    private final Clock clock;

    StudioSales(CreditLotRepository lots, CreditMovementRepository movements, CatalogService catalog,
                CustomerService customers, Clock clock) {
        this.lots = lots;
        this.movements = movements;
        this.catalog = catalog;
        this.customers = customers;
        this.clock = clock;
    }

    // An own package on sale, or 404 (another practitioner's package is "not found", D-026). Valid N days from
    // today in Rome, as a package bought online is from the day of the payment (D-070)
    @Transactional
    public PanelCreditLotView sell(long practitionerId, long userId, NewStudioSale request) {
        Instant now = clock.instant();
        PackageOnSale sold = catalog.packageOnSale(request.packageOfferId());
        if (sold.practitionerId() != practitionerId) {
            throw new NotFoundException("Package " + request.packageOfferId() + " not found");
        }
        long customerId = customers.findOrCreate(request.customer()).id();
        CreditLot lot = lots.save(CreditLot.studioSale(customerId, practitionerId, sold.serviceId(), sold.id(),
                sold.priceCents(), userId, ValidUntil.daysAfter(now, sold.validityDays()), now));
        movements.save(CreditMovement.lotCreated(lot.getId(), sold.sessionsCount(), now));
        return PanelCreditLotView.of(lot, sold.sessionsCount());
    }
}
