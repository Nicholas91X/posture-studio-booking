package com.posturestudio.booking.schedule;

import java.time.OffsetDateTime;

import com.posturestudio.booking.shared.StudioTime;

/**
 * An absence as the practitioner sees it in the panel, with the reason.
 * Never used by public APIs: the reason is for the practitioner only.
 */
public record UnavailabilityView(long id, OffsetDateTime startsAt, OffsetDateTime endsAt, String reason) {

    static UnavailabilityView from(Unavailability unavailability) {
        return new UnavailabilityView(unavailability.getId(),
                StudioTime.inStudioZone(unavailability.getStartsAt()),
                StudioTime.inStudioZone(unavailability.getEndsAt()),
                unavailability.getReason());
    }
}
