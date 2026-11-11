package com.posturestudio.booking.credit;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import org.hibernate.annotations.Immutable;
import jakarta.persistence.*;

import com.posturestudio.booking.shared.Checks;

// The history of an extension (V15, D-067, guide 36, part 9): the old and the new last valid day, who and why.
// Append-only, as the ledger. Created only by CreditLot.extendTo
@Entity
@Immutable
@Table(name = "credit_lot_extension")
class CreditLotExtension {

    protected CreditLotExtension() {
        // JPA needs a no-arg constructor
    }

    static CreditLotExtension of(long lotId, LocalDate oldValidUntil, LocalDate newValidUntil, long extendedBy,
                                 String note, Instant now) {
        CreditLotExtension extension = new CreditLotExtension();
        extension.creditLotId = lotId;
        extension.oldValidUntil = Objects.requireNonNull(oldValidUntil, "oldValidUntil must not be null");
        extension.newValidUntil = Objects.requireNonNull(newValidUntil, "newValidUntil must not be null");
        extension.extendedBy = extendedBy;
        extension.note = Checks.requireNotBlank(note, "note");
        extension.createdAt = Objects.requireNonNull(now, "now must not be null");
        return extension;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private Long creditLotId;

    @Column
    private LocalDate oldValidUntil;

    @Column
    private LocalDate newValidUntil;

    @Column
    private Long extendedBy;

    @Column
    private String note;

    @Column
    private Instant createdAt;

    Long getId() {
        return id;
    }

    long getCreditLotId() {
        return creditLotId;
    }

    LocalDate getOldValidUntil() {
        return oldValidUntil;
    }

    LocalDate getNewValidUntil() {
        return newValidUntil;
    }

    long getExtendedBy() {
        return extendedBy;
    }

    String getNote() {
        return note;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
