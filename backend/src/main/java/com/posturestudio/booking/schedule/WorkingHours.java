package com.posturestudio.booking.schedule;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Objects;

import org.hibernate.annotations.Generated;
import jakarta.persistence.*;

// A working band, inside the WorkingSchedule aggregate: no repository of its own, created and
// removed only through its version. Never changed: replacing a version replaces its bands
@Entity
@Table(name = "working_hours")
class WorkingHours {

    protected WorkingHours() {
        // JPA needs a no-arg constructor
    }

    WorkingHours(WorkingSchedule schedule, WorkingBand band) {
        this.schedule = Objects.requireNonNull(schedule, "schedule must not be null");
        WorkingBand valid = requireValid(band);
        this.dayOfWeek = valid.dayOfWeek();
        this.startTime = valid.startTime();
        this.endTime = valid.endTime();
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // the owning side of the relation: the foreign key schedule_id is written from here
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", updatable = false)
    private WorkingSchedule schedule;

    @Convert(converter = DayOfWeekConverter.class)
    @Column(updatable = false)
    private DayOfWeek dayOfWeek;

    @Column(updatable = false)
    private LocalTime startTime;

    @Column(updatable = false)
    private LocalTime endTime;

    @Column(updatable = false)
    @Generated
    private Instant createdAt;

    // ***** METHODS *****

    WorkingBand toBand() {
        return new WorkingBand(dayOfWeek, startTime, endTime);
    }

    // The checks of one band, the same as V3. Validation should have stopped a wrong band
    // earlier: here it is a programming error
    static WorkingBand requireValid(WorkingBand band) {
        Objects.requireNonNull(band, "band must not be null");
        Objects.requireNonNull(band.dayOfWeek(), "dayOfWeek must not be null");
        Objects.requireNonNull(band.startTime(), "startTime must not be null");
        Objects.requireNonNull(band.endTime(), "endTime must not be null");
        if (!WorkingBand.isOnTheHalfHour(band.startTime()) || !WorkingBand.isOnTheHalfHour(band.endTime())) {
            throw new IllegalArgumentException("band must start and end on the hour or on the half hour, was " + band);
        }
        if (!band.startTime().isBefore(band.endTime())) {
            throw new IllegalArgumentException("band must start before it ends, was " + band);
        }
        return band;
    }
}
