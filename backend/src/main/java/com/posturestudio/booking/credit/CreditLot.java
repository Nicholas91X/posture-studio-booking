package com.posturestudio.booking.credit;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.*;

import com.posturestudio.booking.shared.Checks;

// A group of credits with the same origin and the same last valid day (V15, D-010, guide 36, part 4).
// Package-private: other modules will use CreditService (D-002, D-026).
// Its credits are not here: they are the sum of its movements (D-009). No initial_credits column either: the
// LOT_CREATED movement says it, and two places for one fact could disagree (guide 36, 4.2).
// Created only through named factories, one per source; after that only the last valid day can change, and
// only through extendTo, which leaves its trace (the database demands it: credit_lot_only_extend)
@Entity
@Table(name = "credit_lot")
class CreditLot {

    protected CreditLot() {
        // JPA needs a no-arg constructor
    }

    // A package bought: what the customer paid and saw, taken from the payment, never from the package on sale,
    // which may have changed or been deactivated since (guide 36, 10.2)
    static CreditLot fromPackage(long customerId, long practitionerId, long serviceId, long paymentId,
                                 long packageOfferId, long priceCents, ValidUntil validUntil, Instant now) {
        CreditLot lot = lot(LotSource.PACKAGE, customerId, practitionerId, serviceId, validUntil, now);
        lot.paymentId = paymentId;
        lot.packageOfferId = packageOfferId;
        lot.priceCents = Checks.requirePositive(priceCents, "priceCents");
        return lot;
    }

    // A gift of the practitioner (D-067): who and why are part of the lot
    static CreditLot grant(long customerId, long practitionerId, long serviceId, long grantedBy, String note,
                           ValidUntil validUntil, Instant now) {
        CreditLot lot = lot(LotSource.GRANT, customerId, practitionerId, serviceId, validUntil, now);
        lot.grantedBy = grantedBy;
        lot.note = Checks.requireNotBlank(note, "note");
        return lot;
    }

    private static CreditLot lot(LotSource source, long customerId, long practitionerId, long serviceId,
                                 ValidUntil validUntil, Instant now) {
        CreditLot lot = new CreditLot();
        lot.source = source;
        lot.customerId = customerId;
        lot.practitionerId = practitionerId;
        lot.serviceId = serviceId;
        lot.validUntil = Objects.requireNonNull(validUntil, "validUntil must not be null").lastDay();
        lot.createdAt = Objects.requireNonNull(now, "now must not be null");
        return lot;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // other modules: only the ids (D-026)
    @Column(updatable = false)
    private Long customerId;

    @Column(updatable = false)
    private Long practitionerId;

    @Column(updatable = false)
    private Long serviceId;

    @Column(updatable = false)
    @Enumerated(EnumType.STRING)
    private LotSource source;

    // PACKAGE only
    @Column(updatable = false)
    private Long paymentId;

    @Column(updatable = false)
    private Long packageOfferId;

    @Column(updatable = false)
    private Long priceCents;

    // GRANT only: the account of the practitioner, and why
    @Column(updatable = false)
    private Long grantedBy;

    @Column(updatable = false)
    private String note;

    // the only column that changes: forward, with an extension
    @Column
    private LocalDate validUntil;

    @Column(updatable = false)
    private Instant createdAt;

    // ***** METHODS *****

    // Moves the last valid day forward (D-067, guide 36, part 9) and returns the row of its history, which the
    // caller saves in the same transaction: the database refuses the new date without that row. The row is
    // written at once (its id comes from the database), the update of the lot at the flush: the right order.
    // Forward only: a date not after the current one is a programming error (the panel checks it first, piece 5)
    CreditLotExtension extendTo(ValidUntil newValidUntil, long extendedBy, String note, Instant now) {
        Objects.requireNonNull(newValidUntil, "newValidUntil must not be null");
        Objects.requireNonNull(id, "the lot must be saved first");
        if (!newValidUntil.lastDay().isAfter(validUntil)) {
            throw new IllegalArgumentException(
                    "an extension moves forward: " + newValidUntil.lastDay() + " is not after " + validUntil);
        }
        CreditLotExtension extension = CreditLotExtension.of(id, validUntil, newValidUntil.lastDay(), extendedBy,
                note, now);
        this.validUntil = newValidUntil.lastDay();
        return extension;
    }

    // ***** GETTERS *****

    Long getId() {
        return id;
    }

    long getCustomerId() {
        return customerId;
    }

    long getPractitionerId() {
        return practitionerId;
    }

    long getServiceId() {
        return serviceId;
    }

    LotSource getSource() {
        return source;
    }

    Long getPaymentId() {
        return paymentId;
    }

    Long getPackageOfferId() {
        return packageOfferId;
    }

    Long getPriceCents() {
        return priceCents;
    }

    Long getGrantedBy() {
        return grantedBy;
    }

    String getNote() {
        return note;
    }

    ValidUntil getValidUntil() {
        return new ValidUntil(validUntil);
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
