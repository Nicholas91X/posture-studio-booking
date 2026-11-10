package com.posturestudio.booking.payment;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * Admin API of the payment accounts (D-057, guide 30, part 9). Only the ADMIN gets here (the rule of
 * /api/admin/** in SecurityConfiguration). In the payment module because the assignment is its data, even if
 * the address speaks of practitioners (as D-052: the area is security, the module is who owns the data).
 * No DELETE: nobody asked to take an account away (9.3).
 */
@RestController
@RequestMapping("/api/admin")
class PaymentAccountAdminController {

    private final PaymentAccountService service;

    PaymentAccountAdminController(PaymentAccountService service) {
        this.service = service;
    }

    @GetMapping("/payment-accounts")
    List<PaymentAccountView> accounts() {
        return service.listAccounts();
    }

    @GetMapping("/practitioners/{id}/payment-account")
    AssignedAccountView assigned(@PathVariable long id) {
        return service.assignedTo(id);
    }

    @PutMapping("/practitioners/{id}/payment-account")
    AssignedAccountView assign(@PathVariable long id, @Valid @RequestBody AccountAssignment request) {
        return service.assign(id, request.account());
    }
}
