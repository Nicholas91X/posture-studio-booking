package com.posturestudio.booking.catalog;

/**
 * What the other modules need of a service on sale: which one, and how long it lasts.
 * The answer of CatalogService.serviceOnSale, never the entity (D-002, D-026).
 */
public record ServiceOnSale(long id, int durationMinutes) {

    static ServiceOnSale from(ServiceOffer service) {
        return new ServiceOnSale(service.getId(), service.getDurationMinutes());
    }
}
