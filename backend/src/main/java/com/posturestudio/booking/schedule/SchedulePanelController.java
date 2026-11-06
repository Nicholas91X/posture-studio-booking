package com.posturestudio.booking.schedule;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.practitioner.CurrentPractitioner;

import jakarta.validation.Valid;

/**
 * Panel API for the schedule versions and the absences of the practitioner who is acting.
 * No logic here: who is acting, validation of the shape, the service, the HTTP status.
 * Absences have no PUT: to correct one, delete it and create it again.
 */
@RestController
@RequestMapping("/api/panel")
class SchedulePanelController {

    private final ScheduleService schedule;
    private final CurrentPractitioner current;

    SchedulePanelController(ScheduleService schedule, CurrentPractitioner current) {
        this.schedule = schedule;
        this.current = current;
    }

    // ***** SCHEDULE VERSIONS *****

    @GetMapping("/schedules")
    List<ScheduleVersionView> listVersions() {
        return schedule.listVersions(current.id());
    }

    @PostMapping("/schedules")
    ResponseEntity<ScheduleVersionView> createVersion(@Valid @RequestBody ScheduleVersionRequest request) {
        ScheduleVersionView created = schedule.createVersion(current.id(), request);
        return ResponseEntity.created(URI.create("/api/panel/schedules/" + created.id())).body(created);
    }

    @PutMapping("/schedules/{id}")
    ScheduleVersionView replaceVersion(@PathVariable long id, @Valid @RequestBody ScheduleVersionRequest request) {
        return schedule.replaceVersion(current.id(), id, request);
    }

    @DeleteMapping("/schedules/{id}")
    ResponseEntity<Void> deleteVersion(@PathVariable long id) {
        schedule.deleteVersion(current.id(), id);
        return ResponseEntity.noContent().build();
    }

    // ***** UNAVAILABILITIES *****

    @GetMapping("/unavailabilities")
    List<UnavailabilityView> listUnavailabilities() {
        return schedule.listUpcomingUnavailabilities(current.id());
    }

    @PostMapping("/unavailabilities")
    ResponseEntity<UnavailabilityView> createUnavailability(@Valid @RequestBody NewUnavailability request) {
        UnavailabilityView created = schedule.createUnavailability(current.id(), request);
        return ResponseEntity.created(URI.create("/api/panel/unavailabilities/" + created.id())).body(created);
    }

    @DeleteMapping("/unavailabilities/{id}")
    ResponseEntity<Void> deleteUnavailability(@PathVariable long id) {
        schedule.deleteUnavailability(current.id(), id);
        return ResponseEntity.noContent().build();
    }
}
