package com.posturestudio.booking.payment;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.customer.CurrentCustomer;

import jakarta.validation.Valid;

/**
 * Customer API for buying packages (step 10, guide 37), under /api/customer: only customers (D-051), nothing
 * under /api/public (D-029). In the payment module, which owns the payments, as the payment link (D-065).
 */
@RestController
@RequestMapping("/api/customer/package-purchases")
class PackagePurchaseController {

    private final PackagePurchases purchases;
    private final CurrentCustomer current;

    PackagePurchaseController(PackagePurchases purchases, CurrentCustomer current) {
        this.purchases = purchases;
        this.current = current;
    }

    // 201 with the Stripe page to send the customer to
    @PostMapping
    ResponseEntity<PackagePurchaseView> start(@Valid @RequestBody NewPackagePurchase request) {
        PackagePurchaseView created = purchases.start(current.id(), request);
        return ResponseEntity.created(URI.create("/api/customer/package-purchases/" + created.id())).body(created);
    }

    @GetMapping
    List<PackagePurchaseView> list() {
        return purchases.list(current.id());
    }

    @GetMapping("/{id}")
    PackagePurchaseView find(@PathVariable long id) {
        return purchases.find(current.id(), id);
    }
}
