package com.posturestudio.booking.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EmailTest {

    @Test 
    void emailIsNormalizedToLowerCase() {
        // arrange + act
        Email email = new Email("JOHN.DOE@EXAMPLE.COM");

        // assert
        assertThat(email.value()).isEqualTo("john.doe@example.com");
    }
    
    @Test 
    void emailCannotBeNull() {
        // arrange + act + assert
        assertThatThrownBy(() -> new Email(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Email cannot be null or blank");
    }

    @Test 
    void emailCannotBeBlank() {
        // arrange + act + assert
        assertThatThrownBy(() -> new Email("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Email cannot be null or blank");
    }

    @Test
    void emailMustBeValid() {
        // arrange + act + assert
        assertThatThrownBy(() -> new Email("invalid-email"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid email address");
    }

    @Test 
    void emailWithLeadingAndTrailingSpacesIsTrimmed() {
        // arrange + act
        Email email = new Email("  JOHN.DOE@EXAMPLE.COM  ");

        // assert
        assertThat(email.value()).isEqualTo("john.doe@example.com");
    }

    @Test 
    void emailWithVoidStringIsInvalid() {
        // arrange + act + assert
        assertThatThrownBy(() -> new Email(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Email cannot be null or blank");
    }

    @Test 
    void sameEmailWithDifferentCaseIsEqual() {
        // arrange + act
        Email email1 = new Email("JOHN.DOE@EXAMPLE.COM");
        Email email2 = new Email("john.doe@example.com");

        // assert
        assertThat(email1).isEqualTo(email2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "john@",
        "john@@example.com",
        "john .doe@example..com"
    })
    @NullAndEmptySource
    void invalidEmailValuesAreRejected(String emailValue) {
        // NullAndEmptySource aggiunge i casi null e stringa vuota.
        assertThatThrownBy(() -> new Email(emailValue))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
