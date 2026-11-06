package com.posturestudio.booking.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Plain Java: no Spring, no database
public class ServiceOfferTest {

    @Test
    void newServiceIsActiveWithItsDetails() {
        // arrange + act
        ServiceOffer service = new ServiceOffer(1L, new ServiceOfferDetails("Seduta posturale", 60, 3000L, false));

        // assert
        assertThat(service.isActive()).isTrue();
        assertThat(service.getName()).isEqualTo("Seduta posturale");
        assertThat(service.getDurationMinutes()).isEqualTo(60);
        assertThat(service.getPriceCents()).isEqualTo(3000);
    }

    @Test
    void evaluationFlagIsStored() {
        ServiceOffer service = new ServiceOffer(1L, new ServiceOfferDetails("Valutazione", 30, 3000L, true));

        assertThat(service.isEvaluation()).isTrue();
    }

    @Test
    void missingEvaluationFlagIsRejected() {
        var exception = Assertions.assertThrows(NullPointerException.class,
                () -> new ServiceOffer(1L, new ServiceOfferDetails("Seduta", 60, 3000L, null)));
        assertThat(exception.getMessage()).contains("evaluation");
    }

    @Test
    void nameIsStoredWithoutSurroundingSpaces() {
        ServiceOffer service = new ServiceOffer(1L, new ServiceOfferDetails("  Seduta posturale  ", 60, 3000L, false));

        assertThat(service.getName()).isEqualTo("Seduta posturale");
    }

    @Test
    void blankNameIsRejected() {
        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ServiceOffer(1L, new ServiceOfferDetails("   ", 60, 3000L, false)));
        assertThat(exception.getMessage()).contains("name");
    }

    @Test
    void zeroDurationIsRejected() {
        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ServiceOffer(1L, new ServiceOfferDetails("Seduta", 0, 3000L, false)));
        assertThat(exception.getMessage()).contains("durationMinutes");
    }

    @Test
    void zeroPriceIsRejected() {
        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ServiceOffer(1L, new ServiceOfferDetails("Seduta", 60, 0L, false)));
        assertThat(exception.getMessage()).contains("priceCents");
    }

    @Test
    void invalidChangeLeavesServiceUnchanged() {
        // arrange
        ServiceOffer service = new ServiceOffer(1L, new ServiceOfferDetails("Seduta", 60, 3000L, false));

        // act: the name is valid, the price is not
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.changeDetails(new ServiceOfferDetails("Nuovo nome", 60, 0L, false)));

        // assert: nothing changed, not even the valid name
        assertThat(service.getName()).isEqualTo("Seduta");
        assertThat(service.getPriceCents()).isEqualTo(3000);
    }
}
