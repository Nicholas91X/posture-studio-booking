package com.posturestudio.booking.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotFoundException;

@DataJpaTest
@Import({TestcontainersConfiguration.class, CatalogService.class})
public class CatalogServiceTest {

    @Autowired
    CatalogService catalog;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    private static final ServiceOfferDetails SEDUTA = new ServiceOfferDetails("Seduta posturale", 60, 3000);
    private static final PackageOfferDetails CINQUE = new PackageOfferDetails("5 sedute", 5, 14000, 90);

    private long anna;
    private long marco;

    @BeforeEach
    void insertPractitioners() {
        anna = insertPractitioner("anna@example.com");
        marco = insertPractitioner("marco@example.com");
    }

    // ***** SERVICES *****

    @Test
    void createdServiceIsStoredAndActive() {
        // act
        ServiceOfferView created = catalog.createService(anna, SEDUTA);

        // assert
        assertThat(created.id()).isPositive();
        assertThat(created.active()).isTrue();
        Long practitionerId = jdbc.queryForObject(
                "select practitioner_id from service where id = ?", Long.class, created.id());
        assertThat(practitionerId).isEqualTo(anna);
    }

    @Test
    void updatedServiceIsWrittenWithoutSave() {
        // arrange
        long serviceId = catalog.createService(anna, SEDUTA).id();

        // act
        catalog.updateService(anna, serviceId, new ServiceOfferDetails("Seduta posturale", 60, 3500));
        entityManager.flush();

        // assert: the row, not the object in memory
        Long price = jdbc.queryForObject("select price_cents from service where id = ?", Long.class, serviceId);
        assertThat(price).isEqualTo(3500);
    }

    @Test
    void practitionerCannotUpdateServiceOfAnother() {
        // arrange
        long marcosService = catalog.createService(marco, SEDUTA).id();

        // act + assert: someone else's service looks exactly like a missing one
        Assertions.assertThrows(NotFoundException.class,
                () -> catalog.updateService(anna, marcosService, new ServiceOfferDetails("Rubato", 60, 1)));
        entityManager.flush();
        Long price = jdbc.queryForObject("select price_cents from service where id = ?", Long.class, marcosService);
        assertThat(price).isEqualTo(3000);
    }

    @Test
    void deactivatingTwiceIsNotAnError() {
        long serviceId = catalog.createService(anna, SEDUTA).id();

        catalog.deactivateService(anna, serviceId);
        ServiceOfferView again = catalog.deactivateService(anna, serviceId);

        assertThat(again.active()).isFalse();
    }

    @Test
    void panelListsOwnServicesIncludingDeactivatedInCreationOrder() {
        // arrange
        long first = catalog.createService(anna, SEDUTA).id();
        long second = catalog.createService(anna, new ServiceOfferDetails("Valutazione", 90, 5000)).id();
        catalog.deactivateService(anna, first);
        catalog.createService(marco, SEDUTA);

        // act
        List<ServiceOfferView> listed = catalog.listServices(anna);

        // assert
        assertThat(listed).extracting(ServiceOfferView::id).containsExactly(first, second);
        assertThat(listed.get(0).active()).isFalse();
    }

    // ***** PACKAGES *****

    @Test
    void packageOnOwnActiveServiceIsCreated() {
        long serviceId = catalog.createService(anna, SEDUTA).id();

        PackageOfferView created = catalog.createPackage(anna, serviceId, CINQUE);

        assertThat(created.id()).isPositive();
        assertThat(created.serviceId()).isEqualTo(serviceId);
        assertThat(created.serviceName()).isEqualTo("Seduta posturale");
    }

    @Test
    void packageOnServiceOfAnotherIsNotFound() {
        // the Java check of D-025; the composite foreign key is tested in CatalogSchemaTest
        long marcosService = catalog.createService(marco, SEDUTA).id();

        Assertions.assertThrows(NotFoundException.class,
                () -> catalog.createPackage(anna, marcosService, CINQUE));
    }

    @Test
    void packageOnDeactivatedServiceIsRejected() {
        long serviceId = catalog.createService(anna, SEDUTA).id();
        catalog.deactivateService(anna, serviceId);

        var exception = Assertions.assertThrows(BusinessRuleException.class,
                () -> catalog.createPackage(anna, serviceId, CINQUE));
        assertThat(exception.getCode()).isEqualTo(ErrorCode.SERVICE_NOT_ACTIVE);
    }

    @Test
    void reactivatingPackageOfDeactivatedServiceIsRejected() {
        // arrange
        long serviceId = catalog.createService(anna, SEDUTA).id();
        long packageId = catalog.createPackage(anna, serviceId, CINQUE).id();
        catalog.deactivatePackage(anna, packageId);
        catalog.deactivateService(anna, serviceId);

        // act + assert
        var exception = Assertions.assertThrows(BusinessRuleException.class,
                () -> catalog.activatePackage(anna, packageId));
        assertThat(exception.getCode()).isEqualTo(ErrorCode.SERVICE_NOT_ACTIVE);
    }

    @Test
    void reactivatingPackageLoadsItsServiceThroughTheProxy() {
        // arrange: write everything and empty the persistence context,
        // so that the package is read from the database and its service is a lazy proxy
        long serviceId = catalog.createService(anna, SEDUTA).id();
        long packageId = catalog.createPackage(anna, serviceId, CINQUE).id();
        catalog.deactivatePackage(anna, packageId);
        entityManager.flush();
        entityManager.clear();

        // act: if the proxy did not load the service, isActive() would read false and this would throw
        PackageOfferView reactivated = catalog.activatePackage(anna, packageId);

        // assert
        assertThat(reactivated.active()).isTrue();
        assertThat(reactivated.serviceName()).isEqualTo("Seduta posturale");
    }

    @Test
    void practitionerCannotDeactivatePackageOfAnother() {
        long marcosService = catalog.createService(marco, SEDUTA).id();
        long marcosPackage = catalog.createPackage(marco, marcosService, CINQUE).id();

        Assertions.assertThrows(NotFoundException.class,
                () -> catalog.deactivatePackage(anna, marcosPackage));
    }

    @Test
    void panelListsOwnPackagesWithServiceName() {
        // arrange: three packages on three services, then an empty persistence context
        // (otherwise the services already in memory would hide an N+1)
        for (String name : List.of("Seduta posturale", "Valutazione", "Controllo")) {
            long serviceId = catalog.createService(anna, new ServiceOfferDetails(name, 60, 3000)).id();
            catalog.createPackage(anna, serviceId, CINQUE);
        }
        long marcosService = catalog.createService(marco, SEDUTA).id();
        catalog.createPackage(marco, marcosService, CINQUE);
        entityManager.flush();
        entityManager.clear();

        // act
        List<PackageOfferView> listed = catalog.listPackages(anna);

        // assert
        assertThat(listed).extracting(PackageOfferView::serviceName)
                .containsExactly("Seduta posturale", "Valutazione", "Controllo");
    }

    // ***** PUBLIC CATALOG *****

    @Test
    void publicCatalogShowsOnlyWhatIsOnSale() {
        // arrange
        long active = catalog.createService(anna, SEDUTA).id();
        long deactivated = catalog.createService(anna, new ServiceOfferDetails("Vecchio servizio", 60, 2500)).id();
        long onSale = catalog.createPackage(anna, active, CINQUE).id();
        long packageDeactivated = catalog.createPackage(anna, active, new PackageOfferDetails("10 sedute", 10, 26000, 180)).id();
        long packageOfDeactivatedService = catalog.createPackage(anna, deactivated, CINQUE).id();
        catalog.deactivatePackage(anna, packageDeactivated);
        catalog.deactivateService(anna, deactivated);
        long marcosService = catalog.createService(marco, SEDUTA).id();
        catalog.createPackage(marco, marcosService, CINQUE);

        // act
        PublicCatalog result = catalog.publicCatalog(anna);

        // assert
        assertThat(result.services()).extracting(PublicCatalog.ServiceItem::id).containsExactly(active);
        assertThat(result.packages()).extracting(PublicCatalog.PackageItem::id).containsExactly(onSale);
        assertThat(packageOfDeactivatedService).isNotIn(
                result.packages().stream().map(PublicCatalog.PackageItem::id).toList());
    }

    @Test
    void packageComesBackWhenItsServiceIsReactivated() {
        // arrange: the package itself is never deactivated
        long serviceId = catalog.createService(anna, SEDUTA).id();
        long packageId = catalog.createPackage(anna, serviceId, CINQUE).id();
        catalog.deactivateService(anna, serviceId);
        assertThat(catalog.publicCatalog(anna).packages()).isEmpty();

        // act
        catalog.activateService(anna, serviceId);

        // assert
        assertThat(catalog.publicCatalog(anna).packages())
                .extracting(PublicCatalog.PackageItem::id).containsExactly(packageId);
    }

    @Test
    void publicCatalogOfUnknownPractitionerIsEmpty() {
        PublicCatalog result = catalog.publicCatalog(999999);

        assertThat(result.services()).isEmpty();
        assertThat(result.packages()).isEmpty();
    }

    private long insertPractitioner(String email) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', ?) returning id",
                Long.class, email);
        return jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, 'Test', 'Practitioner') returning id",
                Long.class, userId);
    }
}
