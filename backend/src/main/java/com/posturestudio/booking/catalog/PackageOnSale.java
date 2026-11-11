package com.posturestudio.booking.catalog;

/**
 * What payment needs of a package on sale to sell it (step 10, guide 37): whose it is, on which service, and
 * what the customer sees on the Stripe page. payment copies sessions, price and validity into the payment
 * (D-069): the package can change before the webhook arrives.
 * The answer of CatalogService.packageOnSale, never the entity (D-002, D-026).
 */
public record PackageOnSale(long id, long practitionerId, long serviceId, String name, String serviceName,
                            int sessionsCount, long priceCents, int validityDays) {

    static PackageOnSale from(PackageOffer offer) {
        return new PackageOnSale(offer.getId(), offer.getPractitionerId(), offer.getService().getId(),
                offer.getName(), offer.getService().getName(), offer.getSessionsCount(), offer.getPriceCents(),
                offer.getValidityDays());
    }
}
