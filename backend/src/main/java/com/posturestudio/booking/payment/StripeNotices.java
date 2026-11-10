package com.posturestudio.booking.payment;

import org.springframework.stereotype.Component;

import com.stripe.model.Event;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * From a verified Stripe Event to a StripeNotice (guide 32, part 6). The fields come from the RAW JSON of the
 * object, not from stripe-java's typed model: when the API version of an event differs from the library's,
 * the typed model is an empty Optional, and the event would be ignored in silence. The few fields used here
 * (id, status, payment_intent) are the same in every version.
 */
@Component
class StripeNotices {

    static final String SESSION_COMPLETED = "checkout.session.completed";
    static final String SESSION_EXPIRED = "checkout.session.expired";

    private final JsonMapper json;

    StripeNotices(JsonMapper json) {
        this.json = json;
    }

    StripeNotice from(Event event) {
        return switch (event.getType()) {
            case SESSION_COMPLETED -> {
                JsonNode session = objectOf(event);
                yield new StripeNotice.SessionCompleted(event.getId(), text(session, "id"), text(session, "status"),
                        text(session, "payment_intent"));
            }
            case SESSION_EXPIRED -> new StripeNotice.SessionExpired(event.getId(), text(objectOf(event), "id"));
            default -> new StripeNotice.Ignored(event.getId(), event.getType());
        };
    }

    private JsonNode objectOf(Event event) {
        return json.readTree(event.getDataObjectDeserializer().getRawJson());
    }

    // null when missing or not a string (payment_intent is null on an expired session)
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }
}
