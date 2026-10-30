package com.posturestudio.booking.catalog;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotFoundException;

/**
 * The only door of the catalog module (D-002): returns records, never entities.
 * Every panel method receives the id of the practitioner who acts, and finds only what is theirs:
 * someone else's service or package is reported as not found (404, not 403).
 */
@Service
public class CatalogService {

    private final ServiceOfferRepository services;
    private final PackageOfferRepository packages;
    private final PractitionerService practitioners;

    CatalogService(ServiceOfferRepository services, PackageOfferRepository packages, PractitionerService practitioners) {
        this.services = services;
        this.packages = packages;
        this.practitioners = practitioners;
    }

    // ***** SERVICES (panel) *****

    @Transactional(readOnly = true)
    public List<ServiceOfferView> listServices(long practitionerId) {
        return services.findByPractitionerIdOrderById(practitionerId).stream()
                .map(ServiceOfferView::from)
                .toList();
    }

    @Transactional
    public ServiceOfferView createService(long practitionerId, ServiceOfferDetails details) {
        // new entity: save() is needed (persist); with IDENTITY the id is there right after
        ServiceOffer service = services.save(new ServiceOffer(practitionerId, details));
        return ServiceOfferView.from(service);
    }

    @Transactional
    public ServiceOfferView updateService(long practitionerId, long serviceId, ServiceOfferDetails details) {
        ServiceOffer service = findService(practitionerId, serviceId);
        service.changeDetails(details);
        // no save(): managed entity, written at commit (dirty checking)
        return ServiceOfferView.from(service);
    }

    // Deactivating and reactivating are idempotent. Packages of a deactivated service are not
    // touched: the public catalog hides them, and they come back if the service is reactivated
    @Transactional
    public ServiceOfferView deactivateService(long practitionerId, long serviceId) {
        ServiceOffer service = findService(practitionerId, serviceId);
        service.deactivate();
        return ServiceOfferView.from(service);
    }

    @Transactional
    public ServiceOfferView activateService(long practitionerId, long serviceId) {
        ServiceOffer service = findService(practitionerId, serviceId);
        service.activate();
        return ServiceOfferView.from(service);
    }

    // ***** PACKAGES (panel) *****

    @Transactional(readOnly = true)
    public List<PackageOfferView> listPackages(long practitionerId) {
        return packages.findForPanel(practitionerId).stream()
                .map(PackageOfferView::from)
                .toList();
    }

    @Transactional
    public PackageOfferView createPackage(long practitionerId, long serviceId, PackageOfferDetails details) {
        // the Java side of D-025: only an own service is found (the composite foreign key stays below)
        ServiceOffer service = findService(practitionerId, serviceId);
        requireActive(service);
        PackageOffer offer = packages.save(new PackageOffer(practitionerId, service, details));
        return PackageOfferView.from(offer);
    }

    @Transactional
    public PackageOfferView updatePackage(long practitionerId, long packageId, PackageOfferDetails details) {
        PackageOffer offer = findPackage(practitionerId, packageId);
        offer.changeDetails(details);
        return PackageOfferView.from(offer);
    }

    @Transactional
    public PackageOfferView deactivatePackage(long practitionerId, long packageId) {
        PackageOffer offer = findPackage(practitionerId, packageId);
        offer.deactivate();
        return PackageOfferView.from(offer);
    }

    // Reactivating means selling again: same rule as creating, the service must be active
    @Transactional
    public PackageOfferView activatePackage(long practitionerId, long packageId) {
        PackageOffer offer = findPackage(practitionerId, packageId);
        requireActive(offer.getService());
        offer.activate();
        return PackageOfferView.from(offer);
    }

    // ***** PUBLIC CATALOG *****

    // Only for an active practitioner: missing or deactivated → NotFoundException (404), whatever
    // services they have. No cascade: if the practitioner is reactivated, the catalog comes back as it was
    @Transactional(readOnly = true)
    public PublicCatalog publicCatalog(long practitionerId) {
        practitioners.requireActive(practitionerId);
        List<PublicCatalog.ServiceItem> onSaleServices = services.findByPractitionerIdAndActiveTrueOrderById(practitionerId)
                .stream()
                .map(PublicCatalog.ServiceItem::from)
                .toList();
        List<PublicCatalog.PackageItem> onSalePackages = packages.findOnSale(practitionerId).stream()
                .map(PublicCatalog.PackageItem::from)
                .toList();
        return new PublicCatalog(onSaleServices, onSalePackages);
    }

    // ***** HELPERS *****

    private ServiceOffer findService(long practitionerId, long serviceId) {
        return services.findByIdAndPractitionerId(serviceId, practitionerId)
                .orElseThrow(() -> new NotFoundException("Service " + serviceId + " not found"));
    }

    private PackageOffer findPackage(long practitionerId, long packageId) {
        return packages.findByIdAndPractitionerId(packageId, practitionerId)
                .orElseThrow(() -> new NotFoundException("Package " + packageId + " not found"));
    }

    private static void requireActive(ServiceOffer service) {
        if (!service.isActive()) {
            throw new BusinessRuleException(ErrorCode.SERVICE_NOT_ACTIVE,
                    "Service " + service.getId() + " is not active");
        }
    }
}
