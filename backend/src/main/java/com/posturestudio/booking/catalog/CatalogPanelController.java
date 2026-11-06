package com.posturestudio.booking.catalog;

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

import com.posturestudio.booking.practitioner.CurrentPractitioner;

import jakarta.validation.Valid;

/**
 * Panel API for the services and packages of the practitioner who is acting.
 * No logic here: who is acting, validation of the shape, the service, the HTTP status.
 * Someone else's service or package answers 404 like a missing one (the service decides).
 * Deactivate and activate are domain actions (POST), not a field to patch.
 */
@RestController
@RequestMapping("/api/panel")
class CatalogPanelController {

    private final CatalogService catalog;
    private final CurrentPractitioner current;

    CatalogPanelController(CatalogService catalog, CurrentPractitioner current) {
        this.catalog = catalog;
        this.current = current;
    }

    // ***** SERVICES *****

    @GetMapping("/services")
    List<ServiceOfferView> listServices() {
        return catalog.listServices(current.id());
    }

    @PostMapping("/services")
    ResponseEntity<ServiceOfferView> createService(@Valid @RequestBody ServiceOfferDetails details) {
        ServiceOfferView created = catalog.createService(current.id(), details);
        return ResponseEntity.created(URI.create("/api/panel/services/" + created.id())).body(created);
    }

    @PutMapping("/services/{id}")
    ServiceOfferView updateService(@PathVariable long id, @Valid @RequestBody ServiceOfferDetails details) {
        return catalog.updateService(current.id(), id, details);
    }

    @PostMapping("/services/{id}/deactivate")
    ServiceOfferView deactivateService(@PathVariable long id) {
        return catalog.deactivateService(current.id(), id);
    }

    @PostMapping("/services/{id}/activate")
    ServiceOfferView activateService(@PathVariable long id) {
        return catalog.activateService(current.id(), id);
    }

    // ***** PACKAGES *****

    @GetMapping("/packages")
    List<PackageOfferView> listPackages() {
        return catalog.listPackages(current.id());
    }

    @PostMapping("/packages")
    ResponseEntity<PackageOfferView> createPackage(@Valid @RequestBody NewPackageOffer request) {
        PackageOfferView created = catalog.createPackage(current.id(), request.serviceId(), request.details());
        return ResponseEntity.created(URI.create("/api/panel/packages/" + created.id())).body(created);
    }

    @PutMapping("/packages/{id}")
    PackageOfferView updatePackage(@PathVariable long id, @Valid @RequestBody PackageOfferDetails details) {
        return catalog.updatePackage(current.id(), id, details);
    }

    @PostMapping("/packages/{id}/deactivate")
    PackageOfferView deactivatePackage(@PathVariable long id) {
        return catalog.deactivatePackage(current.id(), id);
    }

    @PostMapping("/packages/{id}/activate")
    PackageOfferView activatePackage(@PathVariable long id) {
        return catalog.activatePackage(current.id(), id);
    }
}
