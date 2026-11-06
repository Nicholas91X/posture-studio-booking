package com.posturestudio.booking.catalog;

/**
 * A package as the practitioner sees it in the panel, with the name of its service.
 */
public record PackageOfferView(
        long id,
        long serviceId,
        String serviceName,
        String name,
        int sessionsCount,
        long priceCents,
        int validityDays,
        boolean active) {

    // reads the service: call it inside the transaction, with the service already loaded
    static PackageOfferView from(PackageOffer offer) {
        ServiceOffer service = offer.getService();
        return new PackageOfferView(
                offer.getId(), service.getId(), service.getName(), offer.getName(),
                offer.getSessionsCount(), offer.getPriceCents(), offer.getValidityDays(), offer.isActive());
    }
}
