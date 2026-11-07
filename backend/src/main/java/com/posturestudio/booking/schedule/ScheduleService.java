package com.posturestudio.booking.schedule;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.catalog.ServiceOnSale;
import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.practitioner.PractitionerSettings;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotFoundException;
import com.posturestudio.booking.shared.StudioTime;
import com.posturestudio.booking.shared.TimeRange;

/**
 * The only door of the schedule module (D-002): returns records, never entities.
 * Every panel method receives the id of the practitioner who acts and finds only what is theirs:
 * someone else's version or absence is reported as not found (404, not 403).
 * "Today" is the date in Rome, from the injected Clock (D-035).
 * Dependencies go one way only (D-029): schedule -> catalog -> practitioner -> auth, schedule -> practitioner.
 * Booked time comes through OccupiedTime, implemented by booking (D-044): booking -> schedule, never back.
 */
@Service
public class ScheduleService {

    private final WorkingScheduleRepository schedules;
    private final UnavailabilityRepository unavailabilities;
    private final CatalogService catalog;
    private final PractitionerService practitioners;
    private final OccupiedTime occupiedTime;
    private final Clock clock;

    ScheduleService(WorkingScheduleRepository schedules, UnavailabilityRepository unavailabilities,
                    CatalogService catalog, PractitionerService practitioners, OccupiedTime occupiedTime,
                    Clock clock) {
        this.schedules = schedules;
        this.unavailabilities = unavailabilities;
        this.catalog = catalog;
        this.practitioners = practitioners;
        this.occupiedTime = occupiedTime;
        this.clock = clock;
    }

    // ***** FREE SLOTS (public) *****

    // The glue around the pure calculation (FreeSlots): gather the data, read the clock once, convert.
    // Not on sale, someone else's, deactivated service or practitioner: NotFoundException (404)
    @Transactional(readOnly = true)
    public List<PublicSlot> freeSlots(long practitionerId, long serviceId, LocalDate from, LocalDate to) {
        ServiceOnSale service = catalog.serviceOnSale(practitionerId, serviceId);
        return compute(practitionerId, service.durationMinutes(), from, to, clock.instant()).stream()
                .map(PublicSlot::from)
                .toList();
    }

    // For booking (guide 19, part 3): is this start one of the free slots? The same calculation the customer
    // saw, so the two can never disagree. Duration and now come from the caller, who already asked the
    // catalog and read the clock once for the whole request. Only the day of the start is computed
    @Transactional(readOnly = true)
    public TimeRange requireFreeSlot(long practitionerId, int durationMinutes, Instant start, Instant now) {
        LocalDate day = LocalDate.ofInstant(start, StudioTime.ZONE);
        return compute(practitionerId, durationMinutes, day, day, now).stream()
                .filter(slot -> slot.start().equals(start))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.SLOT_NOT_AVAILABLE,
                        "No free slot of practitioner " + practitionerId + " starts at " + start));
    }

    // For the panel (guide 22, part 2): the range lies inside a working band of the version valid that day
    // and touches no absence. No grid, no notice, no horizon: those are rules for customers booking alone
    @Transactional(readOnly = true)
    public void requireWorkingTime(long practitionerId, TimeRange range) {
        boolean absent = !unavailabilities
                .findByPractitionerIdAndStartsAtBeforeAndEndsAtAfter(practitionerId, range.end(), range.start())
                .isEmpty();
        if (absent || !withinWorkingHours(versionsOf(practitionerId), range)) {
            throw new BusinessRuleException(ErrorCode.SLOT_NOT_AVAILABLE,
                    "Practitioner " + practitionerId + " does not work at " + range.start());
        }
    }

    // For the panel lists (D-034): which of these ranges are outside the working hours valid on their day,
    // e.g. after a new schedule version. One query for all the ranges
    @Transactional(readOnly = true)
    public Set<TimeRange> outsideWorkingHours(long practitionerId, Collection<TimeRange> ranges) {
        if (ranges.isEmpty()) {
            return Set.of();
        }
        TreeMap<LocalDate, List<WorkingBand>> versions = versionsOf(practitionerId);
        return ranges.stream()
                .filter(range -> !withinWorkingHours(versions, range))
                .collect(Collectors.toSet());
    }

    // The glue shared by the two methods above
    private List<TimeRange> compute(long practitionerId, int durationMinutes, LocalDate from, LocalDate to, Instant now) {
        PractitionerSettings settings = practitioners.getSettings(practitionerId);

        // absences and booked time in a window decided by the server, not by the client (guide 17, part 4):
        // from now to the end of the last day of the horizon, whatever from and to say
        LocalDate today = LocalDate.ofInstant(now, StudioTime.ZONE);
        Instant windowEnd = today.plusDays(settings.bookingHorizonDays() + 1L).atStartOfDay(StudioTime.ZONE).toInstant();
        List<TimeRange> busy = new ArrayList<>();
        unavailabilities.findByPractitionerIdAndStartsAtBeforeAndEndsAtAfter(practitionerId, windowEnd, now)
                .forEach(absence -> busy.add(new TimeRange(absence.getStartsAt(), absence.getEndsAt())));
        // FreeSlots does not care why a range is taken: absences and bookings in one list
        busy.addAll(occupiedTime.between(practitionerId, new TimeRange(now, windowEnd), now));

        SlotQuery query = new SlotQuery(versionsOf(practitionerId), busy, Duration.ofMinutes(durationMinutes),
                settings.slotStepMinutes(), settings.minBookingNoticeHours(), settings.bookingHorizonDays(),
                from, to, now);
        return FreeSlots.compute(query);
    }

    // ***** SCHEDULE VERSIONS (panel) *****

    // in order of validFrom; the current one is the last among those already started
    @Transactional(readOnly = true)
    public List<ScheduleVersionView> listVersions(long practitionerId) {
        LocalDate today = StudioTime.today(clock);
        List<WorkingSchedule> versions = schedules.findWithBands(practitionerId);
        List<WorkingSchedule> started = versions.stream()
                .filter(version -> !version.getValidFrom().isAfter(today))
                .toList();
        // getLast(): sequenced collections, Java 21
        Long currentId = started.isEmpty() ? null : started.getLast().getId();
        return versions.stream()
                .map(version -> ScheduleVersionView.from(version, version.getId().equals(currentId), today))
                .toList();
    }

    @Transactional
    public ScheduleVersionView createVersion(long practitionerId, ScheduleVersionRequest request) {
        LocalDate today = StudioTime.today(clock);
        requireNotInThePast(request.validFrom(), today);
        if (schedules.existsByPractitionerIdAndValidFrom(practitionerId, request.validFrom())) {
            throw dateTaken(request.validFrom());
        }
        // new entity: save() is needed (persist), and the cascade saves the bands too
        WorkingSchedule version = schedules.save(new WorkingSchedule(practitionerId, request.validFrom(), request.bands()));
        return viewOfEditable(version, today);
    }

    // Replaces date and bands of a version that is still editable
    @Transactional
    public ScheduleVersionView replaceVersion(long practitionerId, long versionId, ScheduleVersionRequest request) {
        LocalDate today = StudioTime.today(clock);
        WorkingSchedule version = findVersion(practitionerId, versionId);
        requireEditable(version, today);
        requireNotInThePast(request.validFrom(), today);
        if (schedules.existsByPractitionerIdAndValidFromAndIdNot(practitionerId, request.validFrom(), versionId)) {
            throw dateTaken(request.validFrom());
        }
        version.moveTo(request.validFrom());
        version.removeAllBands();
        // At flush Hibernate may run the inserts before the deletes. The new bands are often the same
        // as the old ones (Monday 9-13 before and after), and the exclusion constraint of V3 would see
        // them overlap. Flushing here sends the deletes first, whatever Hibernate does inside (guide 15, part 4)
        schedules.flush();
        version.addBands(request.bands());
        return viewOfEditable(version, today);
    }

    @Transactional
    public void deleteVersion(long practitionerId, long versionId) {
        WorkingSchedule version = findVersion(practitionerId, versionId);
        requireEditable(version, StudioTime.today(clock));
        // the cascade removes the bands (and V3 has on delete cascade as the last defense)
        schedules.delete(version);
    }

    // ***** UNAVAILABILITIES (panel) *****

    @Transactional(readOnly = true)
    public List<UnavailabilityView> listUpcomingUnavailabilities(long practitionerId) {
        return unavailabilities.findByPractitionerIdAndEndsAtAfterOrderByStartsAt(practitionerId, clock.instant())
                .stream()
                .map(UnavailabilityView::from)
                .toList();
    }

    // Absences in the past are allowed: they do no harm, and "I record it at the end of the day" is normal
    @Transactional
    public UnavailabilityView createUnavailability(long practitionerId, NewUnavailability request) {
        Unavailability unavailability = unavailabilities.save(new Unavailability(practitionerId,
                request.startsAt().toInstant(), request.endsAt().toInstant(), request.reason()));
        return UnavailabilityView.from(unavailability);
    }

    @Transactional
    public void deleteUnavailability(long practitionerId, long unavailabilityId) {
        Unavailability unavailability = unavailabilities.findByIdAndPractitionerId(unavailabilityId, practitionerId)
                .orElseThrow(() -> new NotFoundException("Unavailability " + unavailabilityId + " not found"));
        unavailabilities.delete(unavailability);
    }

    // ***** HELPERS *****

    // every version with its bands, by valid_from: one query (findWithBands)
    private TreeMap<LocalDate, List<WorkingBand>> versionsOf(long practitionerId) {
        TreeMap<LocalDate, List<WorkingBand>> versions = new TreeMap<>();
        for (WorkingSchedule version : schedules.findWithBands(practitionerId)) {
            versions.put(version.getValidFrom(), version.bands());
        }
        return versions;
    }

    // Inside one band of the version valid on the (Rome) day the range starts: the version with the latest
    // valid_from not after that day (D-034, floorEntry as in FreeSlots). Local times become instants on that
    // very day with ZonedDateTime.of, as in the calculation, so daylight saving changes are handled the same
    private static boolean withinWorkingHours(TreeMap<LocalDate, List<WorkingBand>> versions, TimeRange range) {
        LocalDate day = LocalDate.ofInstant(range.start(), StudioTime.ZONE);
        Map.Entry<LocalDate, List<WorkingBand>> version = versions.floorEntry(day);
        if (version == null) {
            return false;
        }
        for (WorkingBand band : version.getValue()) {
            if (band.dayOfWeek() != day.getDayOfWeek()) {
                continue;
            }
            Instant bandStart = ZonedDateTime.of(day, band.startTime(), StudioTime.ZONE).toInstant();
            Instant bandEnd = ZonedDateTime.of(day, band.endTime(), StudioTime.ZONE).toInstant();
            if (!range.start().isBefore(bandStart) && !range.end().isAfter(bandEnd)) {
                return true;
            }
        }
        return false;
    }

    private WorkingSchedule findVersion(long practitionerId, long versionId) {
        return schedules.findByIdAndPractitionerId(versionId, practitionerId)
                .orElseThrow(() -> new NotFoundException("Schedule version " + versionId + " not found"));
    }

    // An editable version starts today or later, so it is the current one only if it starts today:
    // any other started version starts before it
    private static ScheduleVersionView viewOfEditable(WorkingSchedule version, LocalDate today) {
        return ScheduleVersionView.from(version, version.getValidFrom().equals(today), today);
    }

    private static void requireEditable(WorkingSchedule version, LocalDate today) {
        if (!version.isEditableOn(today)) {
            throw new BusinessRuleException(ErrorCode.SCHEDULE_ALREADY_STARTED,
                    "Schedule version " + version.getId() + " started on " + version.getValidFrom());
        }
    }

    private static void requireNotInThePast(LocalDate validFrom, LocalDate today) {
        if (validFrom.isBefore(today)) {
            throw new BusinessRuleException(ErrorCode.SCHEDULE_STARTS_IN_THE_PAST,
                    "A schedule version cannot start before " + today);
        }
    }

    private static BusinessRuleException dateTaken(LocalDate validFrom) {
        return new BusinessRuleException(ErrorCode.SCHEDULE_DATE_TAKEN,
                "Another schedule version already starts on " + validFrom);
    }
}
