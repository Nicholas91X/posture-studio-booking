package com.posturestudio.booking.catalog;

/**
 * A service as the practitioner sees it in the panel, deactivated ones included.
 * evaluation stays in the panel: the public catalog does not show it (D-043).
 */
public record ServiceOfferView(long id, String name, int durationMinutes, long priceCents, boolean active,
                               boolean evaluation) {

    // package-private: outside the module the record is visible, the entity is not
    static ServiceOfferView from(ServiceOffer service) {
        return new ServiceOfferView(
                service.getId(), service.getName(), service.getDurationMinutes(),
                service.getPriceCents(), service.isActive(), service.isEvaluation());
    }
}
