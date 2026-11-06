package com.posturestudio.booking.catalog;

import java.util.List;

/**
 * What a customer sees of a practitioner without signing in: only what is on sale.
 * No "active" flag (everything here is active), no dates, nothing about bookings or customers.
 */
public record PublicCatalog(List<ServiceItem> services, List<PackageItem> packages) {

    public record ServiceItem(long id, String name, int durationMinutes, long priceCents) {

        static ServiceItem from(ServiceOffer service) {
            return new ServiceItem(service.getId(), service.getName(), service.getDurationMinutes(), service.getPriceCents());
        }
    }

    public record PackageItem(long id, long serviceId, String name, int sessionsCount, long priceCents, int validityDays) {

        static PackageItem from(PackageOffer offer) {
            return new PackageItem(offer.getId(), offer.getService().getId(), offer.getName(),
                    offer.getSessionsCount(), offer.getPriceCents(), offer.getValidityDays());
        }
    }
}
