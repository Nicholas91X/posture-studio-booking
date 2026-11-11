package com.posturestudio.booking.credit;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.customer.CurrentCustomer;

/**
 * The credits of the customer (guide 41), under /api/customer: only customers (D-051), nothing under /api/public
 * (D-029). Only their own: the lot of another is not found.
 */
@RestController
@RequestMapping("/api/customer/credits")
class CustomerCreditController {

    private final CreditViews views;
    private final CurrentCustomer current;

    CustomerCreditController(CreditViews views, CurrentCustomer current) {
        this.views = views;
        this.current = current;
    }

    @GetMapping
    List<CreditLotSummary> list() {
        return views.forCustomer(current.id());
    }

    @GetMapping("/{id}")
    CreditLotDetail find(@PathVariable long id) {
        return views.lotOfCustomer(current.id(), id);
    }
}
