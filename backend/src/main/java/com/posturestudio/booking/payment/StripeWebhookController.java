package com.posturestudio.booking.payment;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.shared.NotFoundException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;

/**
 * Stripe calling us (D-062, guide 32). One address per payment account (part 4): the name in the path picks
 * the secret that verifies the signature. Outside CSRF and open to anyone (/api/webhooks/**): the signature
 * is the protection (part 10).
 *
 * 200: received, also when already seen or of a type we do not use. 400: signature wrong, too old or missing,
 * said without details. 404: no such account. 500 (any other failure): Stripe will send it again.
 */
@RestController
@RequestMapping("/api/webhooks/stripe")
class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final PaymentAccounts accounts;
    private final StripeNotices notices;
    private final StripeWebhookService webhooks;
    private final Clock clock;

    StripeWebhookController(PaymentAccounts accounts, StripeNotices notices, StripeWebhookService webhooks, Clock clock) {
        this.accounts = accounts;
        this.notices = notices;
        this.webhooks = webhooks;
        this.clock = clock;
    }

    // byte[]: the RAW body, decoded here as UTF-8 (part 10). A String would be decoded by Spring with a charset
    // taken from a header, an object would be read and written again: either could break the signature
    @PostMapping("/{account}")
    ResponseEntity<ProblemDetail> receive(@PathVariable String account,
                                          @RequestHeader(name = "Stripe-Signature", required = false) String signature,
                                          @RequestBody byte[] body) {
        PaymentAccount paymentAccount = accounts.byName(account)
                .orElseThrow(() -> new NotFoundException("No payment account " + account));
        if (signature == null) {
            return invalidSignature(account, "missing");
        }
        Event event;
        try {
            // HMAC-SHA256 of timestamp + "." + body with the secret of THIS account, compared in constant time;
            // a timestamp more than 5 minutes from our Clock is refused (part 3)
            event = Webhook.constructEvent(new String(body, StandardCharsets.UTF_8), signature,
                    paymentAccount.webhookSecret(), Webhook.DEFAULT_TOLERANCE, clock);
        } catch (SignatureVerificationException e) {
            return invalidSignature(account, "not verified");
        }
        webhooks.handle(paymentAccount, notices.from(event), event.getType());
        return ResponseEntity.ok().build();
    }

    // logged with the account, never with the header or the body
    private static ResponseEntity<ProblemDetail> invalidSignature(String account, String why) {
        log.warn("Stripe webhook for account {} refused: signature {}", account, why);
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid signature"));
    }
}
