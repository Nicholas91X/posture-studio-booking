package com.posturestudio.booking.practitioner;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * Panel API for the settings of the practitioner who is acting. No logic here:
 * who is acting, validation of the shape, then the service.
 */
@RestController
@RequestMapping("/api/panel/settings")
class PractitionerPanelController {

    private final PractitionerService practitioners;
    private final CurrentPractitioner current;

    PractitionerPanelController(PractitionerService practitioners, CurrentPractitioner current) {
        this.practitioners = practitioners;
        this.current = current;
    }

    @GetMapping
    PractitionerSettings get() {
        return practitioners.getSettings(current.id());
    }

    @PutMapping
    PractitionerSettings update(@Valid @RequestBody PractitionerSettings settings) {
        return practitioners.updateSettings(current.id(), settings);
    }
}
