package com.posturestudio.booking.schedule;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * Public API of the free slots, under /api/public (D-029): no access needed, only free times
 * come out (requisiti, "Riservatezza del calendario"). No logic here.
 */
@RestController
@RequestMapping("/api/public/practitioners")
class SchedulePublicController {

    private final ScheduleService schedule;

    SchedulePublicController(ScheduleService schedule) {
        this.schedule = schedule;
    }

    // ?from=2026-11-09&to=2026-11-15: SlotRange is built from the query string and validated (400)
    @GetMapping("/{id}/services/{serviceId}/slots")
    List<PublicSlot> freeSlots(@PathVariable long id, @PathVariable long serviceId, @Valid SlotRange range) {
        return schedule.freeSlots(id, serviceId, range.from(), range.to());
    }
}
