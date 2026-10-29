package com.posturestudio.booking.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Plain Java: no Spring, no database
public class PackageOfferTest {

    private final ServiceOffer service = new ServiceOffer(1L, new ServiceOfferDetails("Seduta posturale", 60, 3000));

    @Test
    void newPackageIsActiveWithItsService() {
        // arrange + act
        PackageOffer offer = new PackageOffer(1L, service, new PackageOfferDetails("5 sedute", 5, 14000, 90));

        // assert
        assertThat(offer.isActive()).isTrue();
        assertThat(offer.getService()).isSameAs(service);
        assertThat(offer.getSessionsCount()).isEqualTo(5);
    }

    @Test
    void packageOfTwoSessionsIsAccepted() {
        // boundary value: catches a "> 2" written instead of ">= 2"
        PackageOffer offer = new PackageOffer(1L, service, new PackageOfferDetails("2 sedute", 2, 5800, 30));

        assertThat(offer.getSessionsCount()).isEqualTo(2);
    }

    @Test
    void packageOfOneSessionIsRejected() {
        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PackageOffer(1L, service, new PackageOfferDetails("1 seduta", 1, 3000, 30)));
        assertThat(exception.getMessage()).contains("sessionsCount");
    }

    @Test
    void zeroValidityIsRejected() {
        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PackageOffer(1L, service, new PackageOfferDetails("5 sedute", 5, 14000, 0)));
        assertThat(exception.getMessage()).contains("validityDays");
    }

    @Test
    void zeroPriceIsRejected() {
        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PackageOffer(1L, service, new PackageOfferDetails("5 sedute", 5, 0, 90)));
        assertThat(exception.getMessage()).contains("priceCents");
    }

    @Test
    void missingServiceIsRejected() {
        Assertions.assertThrows(NullPointerException.class,
                () -> new PackageOffer(1L, null, new PackageOfferDetails("5 sedute", 5, 14000, 90)));
    }

    @Test
    void invalidChangeLeavesPackageUnchanged() {
        // arrange
        PackageOffer offer = new PackageOffer(1L, service, new PackageOfferDetails("5 sedute", 5, 14000, 90));

        // act: everything valid except the validity
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> offer.changeDetails(new PackageOfferDetails("10 sedute", 10, 26000, 0)));

        // assert
        assertThat(offer.getName()).isEqualTo("5 sedute");
        assertThat(offer.getSessionsCount()).isEqualTo(5);
        assertThat(offer.getPriceCents()).isEqualTo(14000);
    }
}
