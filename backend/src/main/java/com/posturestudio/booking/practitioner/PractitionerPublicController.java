package com.posturestudio.booking.practitioner;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public API, no sign-in: everything under /api/public, so that in step 8 one rule
 * opens this prefix and keeps everything else closed.
 */
@RestController
@RequestMapping("/api/public/practitioners")
class PractitionerPublicController {

    private final PractitionerService practitioners;

    PractitionerPublicController(PractitionerService practitioners) {
        this.practitioners = practitioners;
    }

    @GetMapping
    List<PublicPractitioner> listActive() {
        return practitioners.listActive();
    }
}
