package com.posturestudio.booking.schedule;

import java.time.LocalDate;
import java.util.List;

/**
 * A schedule version as the practitioner sees it in the panel.
 * current and editable are rules, so the backend computes them: the panel only shows them
 * (the frontend holds no business rules).
 */
public record ScheduleVersionView(
        long id,
        LocalDate validFrom,
        // the version that applies today
        boolean current,
        // can still be replaced or deleted: it starts today or later
        boolean editable,
        List<WorkingBand> bands) {

    static ScheduleVersionView from(WorkingSchedule schedule, boolean current, LocalDate today) {
        return new ScheduleVersionView(schedule.getId(), schedule.getValidFrom(), current,
                schedule.isEditableOn(today), schedule.bands());
    }
}
