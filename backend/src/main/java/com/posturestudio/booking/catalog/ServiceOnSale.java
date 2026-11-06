package com.posturestudio.booking.catalog;

/**
 * What the other modules need of a service on sale: which one, how long it lasts, and its price,
 * which booking copies into the booking (D-011, D-038).
 * The answer of CatalogService.serviceOnSale, never the entity (D-002, D-026).
 */
public record ServiceOnSale(long id, int durationMinutes, long priceCents) {

    static ServiceOnSale from(ServiceOffer service) {
        return new ServiceOnSale(service.getId(), service.getDurationMinutes(), service.getPriceCents());
    }
}
