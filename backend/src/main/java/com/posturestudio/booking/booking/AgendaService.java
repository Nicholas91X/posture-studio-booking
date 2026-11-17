package com.posturestudio.booking.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.practitioner.ActivePractitioner;
import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.schedule.ScheduleService;
import com.posturestudio.booking.schedule.WorkingTime;
import com.posturestudio.booking.shared.NotFoundException;
import com.posturestudio.booking.shared.StudioTime;
import com.posturestudio.booking.shared.TimeRange;

/**
 * The agendas compared (D-091): every active practitioner as busy and free, with working hours and absences, and the
 * windows in which some of them are free together. Lives in booking because the busy time is booking's; working
 * hours and absences are asked to schedule, the names to practitioner (D-002, one question per module on a set).
 */
@Service
class AgendaService {

    private final BookingRepository bookings;
    private final ScheduleService schedule;
    private final PractitionerService practitioners;
    private final Clock clock;

    AgendaService(BookingRepository bookings, ScheduleService schedule, PractitionerService practitioners, Clock clock) {
        this.bookings = bookings;
        this.schedule = schedule;
        this.practitioners = practitioners;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AgendaView> agendas(AgendaPeriod period) {
        List<ActivePractitioner> active = practitioners.activeByName();
        Snapshot snapshot = snapshot(active.stream().map(ActivePractitioner::id).toList(), period.from(), period.to());
        return active.stream()
                .map(practitioner -> new AgendaView(practitioner.id(), practitioner.firstName(), practitioner.lastName(),
                        snapshot.busyOf(practitioner.id()).stream().map(TimeSpan::of).toList(),
                        snapshot.time(practitioner.id()).working().stream().map(TimeSpan::of).toList(),
                        snapshot.time(practitioner.id()).absences().stream().map(TimeSpan::of).toList()))
                .toList();
    }

    // Only active practitioners: an id that is not one is 404, as everything that does not exist (D-026)
    @Transactional(readOnly = true)
    public List<TimeSpan> freeTogether(FreeTogetherQuery query) {
        Set<Long> chosen = new LinkedHashSet<>(query.practitionerIds());
        Set<Long> active = practitioners.activeByName().stream().map(ActivePractitioner::id).collect(Collectors.toSet());
        if (!active.containsAll(chosen)) {
            throw new NotFoundException("Not every practitioner of " + chosen + " is active");
        }
        Snapshot snapshot = snapshot(List.copyOf(chosen), query.from(), query.to());
        List<FreeTogether.Agenda> agendas = chosen.stream()
                .map(id -> {
                    List<TimeRange> taken = new ArrayList<>(snapshot.busyOf(id));
                    taken.addAll(snapshot.time(id).absences());
                    return new FreeTogether.Agenda(snapshot.time(id).working(), taken);
                })
                .toList();
        return FreeTogether.compute(agendas, Duration.ofMinutes(query.durationMinutes()), snapshot.now()).stream()
                .map(TimeSpan::of)
                .toList();
    }

    // One reading of the clock; the busy time of all of them in one query, which selects only who and when
    private Snapshot snapshot(List<Long> practitionerIds, LocalDate from, LocalDate to) {
        Instant now = clock.instant();
        Instant start = from.atStartOfDay(StudioTime.ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(StudioTime.ZONE).toInstant();
        Map<Long, List<TimeRange>> busy = practitionerIds.isEmpty() ? Map.of()
                : bookings.findBusy(practitionerIds, start, end, now, BookingStatus.HOLDS_TIME,
                                BookingStatus.PENDING_PAYMENT).stream()
                        .collect(Collectors.groupingBy(BusyRange::practitionerId,
                                Collectors.mapping(range -> new TimeRange(range.start(), range.end()),
                                        Collectors.toList())));
        return new Snapshot(now, busy, schedule.workingTimeOf(practitionerIds, from, to));
    }

    private record Snapshot(Instant now, Map<Long, List<TimeRange>> busy, Map<Long, WorkingTime> times) {

        List<TimeRange> busyOf(long practitionerId) {
            return busy.getOrDefault(practitionerId, List.of()).stream()
                    .sorted(java.util.Comparator.comparing(TimeRange::start))
                    .toList();
        }

        WorkingTime time(long practitionerId) {
            return times.getOrDefault(practitionerId, new WorkingTime(List.of(), List.of()));
        }
    }
}
