package com.posturestudio.booking.credit;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.auth.CurrentUser;
import com.posturestudio.booking.practitioner.CurrentPractitioner;

import jakarta.validation.Valid;

/**
 * The credits in the panel, under /api/panel: only practitioners (D-051). The concessions (D-067, guide 40) and
 * what can be read of the own lots (guide 41).
 * Who acts comes from the session, never from the request: the practitioner (whose lots) and the account (who
 * signs the trace, guide 40, part 2).
 */
@RestController
@RequestMapping("/api/panel")
class CreditPanelController {

    private final CreditConcessions concessions;
    private final CreditViews views;
    private final CurrentPractitioner practitioner;
    private final CurrentUser user;

    CreditPanelController(CreditConcessions concessions, CreditViews views, CurrentPractitioner practitioner,
                          CurrentUser user) {
        this.concessions = concessions;
        this.views = views;
        this.practitioner = practitioner;
        this.user = user;
    }

    @PostMapping("/credit-grants")
    ResponseEntity<PanelCreditLotView> grant(@Valid @RequestBody NewCreditGrant request) {
        PanelCreditLotView lot = concessions.grant(practitioner.id(), user.id(), request);
        return ResponseEntity.created(URI.create("/api/panel/credit-lots/" + lot.id())).body(lot);
    }

    @PostMapping("/credit-lots/{id}/extensions")
    PanelCreditLotView extend(@PathVariable long id, @Valid @RequestBody NewCreditExtension request) {
        return concessions.extend(practitioner.id(), user.id(), id, request);
    }

    @PostMapping("/credit-lots/{id}/adjustments")
    PanelCreditLotView adjust(@PathVariable long id, @Valid @RequestBody NewCreditAdjustment request) {
        return concessions.adjust(practitioner.id(), user.id(), id, request);
    }

    // one own lot with its history and the notes (guide 41, part 3)
    @GetMapping("/credit-lots/{id}")
    CreditLotDetail find(@PathVariable long id) {
        return views.lotOfPractitioner(practitioner.id(), id);
    }

    // the lots of a customer with this practitioner only (guide 41, part 4); in the credit module, which owns
    // them, even if the address speaks of customers (as the payment account under practitioners, D-060)
    @GetMapping("/customers/{customerId}/credits")
    java.util.List<CreditLotSummary> creditsOf(@PathVariable long customerId) {
        return views.forCustomerOfPractitioner(practitioner.id(), customerId);
    }
}
