package com.posturestudio.booking.booking;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// "My sessions" split by time (guide 46, part 4): not over yet, or over. A session in progress is upcoming
enum BookingPeriod {
    UPCOMING,
    PAST;

    // the value of ?period=, in lower case as the other query parameters; anything else is a 400
    static BookingPeriod fromQuery(String value) {
        return switch (value) {
            case "upcoming" -> UPCOMING;
            case "past" -> PAST;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "period must be upcoming or past, not " + value);
        };
    }
}
