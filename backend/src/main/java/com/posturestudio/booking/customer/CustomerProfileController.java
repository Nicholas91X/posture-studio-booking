package com.posturestudio.booking.customer;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.auth.CurrentUser;

import jakarta.validation.Valid;

/**
 * The profile of the signed-in customer (guide 25, 7.3). It asks CurrentUser, not CurrentCustomer: the
 * profile must work before the customer row exists, or PUT could never create it.
 */
@RestController
@RequestMapping("/api/customer/profile")
class CustomerProfileController {

    private final CustomerService customers;
    private final CurrentUser user;

    CustomerProfileController(CustomerService customers, CurrentUser user) {
        this.customers = customers;
        this.user = user;
    }

    // 409 PROFILE_INCOMPLETE until first and last name are given
    @GetMapping
    CustomerProfile profile() {
        return customers.profileOf(user.id());
    }

    // PUT: creates the profile the first time, corrects it afterwards; repeated, same result (idempotent)
    @PutMapping
    CustomerProfile save(@Valid @RequestBody ProfileNames names) {
        return customers.saveProfile(user.id(), names);
    }
}
