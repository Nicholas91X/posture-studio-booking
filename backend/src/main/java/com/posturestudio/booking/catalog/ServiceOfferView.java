package com.posturestudio.booking.catalog;

/**
 * A service as the practitioner sees it in the panel, deactivated ones included.
 */
public record ServiceOfferView(long id, String name, int durationMinutes, long priceCents, boolean active) {

    // package-private: outside the module the record is visible, the entity is not
    static ServiceOfferView from(ServiceOffer service) {
        return new ServiceOfferView(
                service.getId(), service.getName(), service.getDurationMinutes(),
                service.getPriceCents(), service.isActive());
    }
}
