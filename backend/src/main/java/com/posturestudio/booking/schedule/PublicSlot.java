package com.posturestudio.booking.schedule;

import java.time.OffsetDateTime;

import com.posturestudio.booking.shared.StudioTime;
import com.posturestudio.booking.shared.TimeRange;

/**
 * A free slot as anyone can see it: when the session would start and end, in Rome time (D-035).
 * Nothing else, on purpose: no reasons, no ids, nothing about what makes other times busy
 * (requisiti, "Riservatezza del calendario").
 */
public record PublicSlot(OffsetDateTime start, OffsetDateTime end) {

    static PublicSlot from(TimeRange slot) {
        return new PublicSlot(StudioTime.inStudioZone(slot.start()), StudioTime.inStudioZone(slot.end()));
    }
}
