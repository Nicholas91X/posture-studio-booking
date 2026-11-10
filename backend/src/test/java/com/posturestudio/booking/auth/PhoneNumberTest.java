package com.posturestudio.booking.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// The phone numbers that can receive codes (guide 29, part 2): many ways of writing one number become one,
// E.164; only Italian mobiles are accepted (guide 29, 2.3 and 5.1). No Spring
class PhoneNumberTest {

    @ParameterizedTest
    @ValueSource(strings = {"+393331234567", "+39 333 123 4567", "0039 333 1234567", "333-123-4567",
            "(333) 123.4567", "3331234567", "  +39 333 1234567  "})
    void theWaysOfWritingANumberBecomeOne(String written) {
        assertThat(new PhoneNumber(written).value()).isEqualTo("+393331234567");
    }

    // older Italian mobiles have 9 digits after the 3 instead of 10... or one fewer: both exist
    @ParameterizedTest
    @ValueSource(strings = {"+39333123456", "333 123456"})
    void aShorterItalianMobileIsAMobileToo(String written) {
        assertThat(new PhoneNumber(written).value()).isEqualTo("+39333123456");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // a landline of Rome: no SMS
            "06 1234567", "+39 06 12345678",
            // abroad: where SMS pumping makes its money
            "+44 7911 123456", "+1 202 555 0143", "0044 7911 123456",
            // too short, too long, not a number
            "+39 333", "+39 333 1234 5678", "333 12", "abc", "+39 333 123 456a"})
    void everythingElseIsRefused(String written) {
        assertThatThrownBy(() -> new PhoneNumber(written)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankIsRefused(String written) {
        assertThatThrownBy(() -> new PhoneNumber(written)).isInstanceOf(IllegalArgumentException.class);
    }
}
