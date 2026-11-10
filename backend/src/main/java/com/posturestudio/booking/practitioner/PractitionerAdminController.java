package com.posturestudio.booking.practitioner;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * Admin API for the practitioners (D-021, guide 26). Only the ADMIN gets here: the rule of the area is in
 * SecurityConfiguration. In the practitioner module because the practitioners are its data: the area is a
 * matter of security, the module a matter of who owns the data (guide 26, part 3).
 * No logic here: validation of the shape, the service, the HTTP status.
 */
@RestController
@RequestMapping("/api/admin/practitioners")
class PractitionerAdminController {

    private final PractitionerService practitioners;

    PractitionerAdminController(PractitionerService practitioners) {
        this.practitioners = practitioners;
    }

    @GetMapping
    List<AdminPractitionerView> list() {
        return practitioners.listForAdmin();
    }

    // there because the 201 of the creation points here with its Location
    @GetMapping("/{id}")
    AdminPractitionerView get(@PathVariable long id) {
        return practitioners.getForAdmin(id);
    }

    @PostMapping
    ResponseEntity<AdminPractitionerView> create(@Valid @RequestBody NewPractitioner request) {
        AdminPractitionerView created = practitioners.create(request);
        return ResponseEntity.created(URI.create("/api/admin/practitioners/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    AdminPractitionerView rename(@PathVariable long id, @Valid @RequestBody PractitionerNames names) {
        return practitioners.rename(id, names);
    }

    // domain actions, as for services and packages: idempotent, 200 with the state after the action
    @PostMapping("/{id}/deactivate")
    AdminPractitionerView deactivate(@PathVariable long id) {
        return practitioners.deactivate(id);
    }

    @PostMapping("/{id}/activate")
    AdminPractitionerView activate(@PathVariable long id) {
        return practitioners.activate(id);
    }
}
