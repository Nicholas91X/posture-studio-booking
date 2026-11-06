package com.posturestudio.booking.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.hibernate.annotations.Generated;
import jakarta.persistence.*;

// A version of the weekly schedule, valid from a local date (D-034). The root of an aggregate:
// its bands are saved, replaced and deleted with it, and it is the only one that sees all of them,
// so it keeps the rule "no overlapping bands" (D-036). Package-private (D-002, D-026).
// Unlike service -> packages (guide 10, question 6), here a @OneToMany is mapped: bands have no life of their own
@Entity
@Table(name = "working_schedule")
class WorkingSchedule {

    private static final Comparator<WorkingBand> BY_DAY_AND_START =
            Comparator.comparing(WorkingBand::dayOfWeek).thenComparing(WorkingBand::startTime);

    protected WorkingSchedule() {
        // JPA needs a no-arg constructor
    }

    WorkingSchedule(Long practitionerId, LocalDate validFrom, List<WorkingBand> bands) {
        this.practitionerId = Objects.requireNonNull(practitionerId, "practitionerId must not be null");
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom must not be null");
        addBands(bands);
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // practitioner belongs to another module: only the id (D-026)
    @Column(updatable = false)
    private Long practitionerId;

    @Column
    private LocalDate validFrom;

    // mappedBy: the foreign key is in working_hours, written by WorkingHours.schedule.
    // cascade ALL: saved and deleted with the version; orphanRemoval: a band taken out of the list is deleted
    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dayOfWeek, startTime")
    private List<WorkingHours> bands = new ArrayList<>();

    @Column(updatable = false)
    @Generated
    private Instant createdAt;

    // ***** METHODS *****

    // A version that started before today is history: it shows which hours applied on past days.
    // One starting today is still editable: it never applied to a past day (D-034, refined in guide 15)
    boolean isEditableOn(LocalDate today) {
        return !validFrom.isBefore(today);
    }

    void moveTo(LocalDate newValidFrom) {
        this.validFrom = Objects.requireNonNull(newValidFrom, "validFrom must not be null");
    }

    // Replacing the bands is done in two steps by the service, with a flush in between:
    // Hibernate could otherwise insert the new bands before deleting the old ones, and the
    // exclusion constraint would see them overlap (guide 15, part 4)
    void removeAllBands() {
        bands.clear();
    }

    // all new bands are checked, against each other and against those already there,
    // before any is added: an invalid list leaves the version as it was
    void addBands(List<WorkingBand> newBands) {
        Objects.requireNonNull(newBands, "bands must not be null");
        List<WorkingBand> accepted = new ArrayList<>(bands());
        for (WorkingBand band : newBands) {
            WorkingHours.requireValid(band);
            for (WorkingBand other : accepted) {
                if (band.overlaps(other)) {
                    throw new IllegalArgumentException("band " + band + " overlaps " + other);
                }
            }
            accepted.add(band);
        }
        for (WorkingBand band : newBands) {
            bands.add(new WorkingHours(this, band));
        }
    }

    // sorted here too: bands added in this session are not ordered by @OrderBy until reloaded
    List<WorkingBand> bands() {
        return bands.stream().map(WorkingHours::toBand).sorted(BY_DAY_AND_START).toList();
    }

    Long getId() {
        return id;
    }

    Long getPractitionerId() {
        return practitionerId;
    }

    LocalDate getValidFrom() {
        return validFrom;
    }
}
