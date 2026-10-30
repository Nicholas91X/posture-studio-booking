package com.posturestudio.booking.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public API, no sign-in: what a practitioner sells. A missing or deactivated practitioner
 * answers 404 in the same way (the service decides).
 */
@RestController
@RequestMapping("/api/public/practitioners")
class CatalogPublicController {

    private final CatalogService catalog;

    CatalogPublicController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/{id}/catalog")
    PublicCatalog catalog(@PathVariable long id) {
        return catalog.publicCatalog(id);
    }
}
