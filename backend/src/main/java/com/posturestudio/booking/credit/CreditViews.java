package com.posturestudio.booking.credit;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.shared.NotFoundException;
import com.posturestudio.booking.shared.StudioTime;

/**
 * What can be read of the credits (step 10, piece 6, guide 41): the lots of a customer, the lots of a customer of a
 * practitioner (only theirs), and one lot with its history. Read only.
 *
 * Two queries for a list whatever its length: the lots, then the balances of all of them grouped (D-029); never
 * one sum per lot. The state of a lot (usable, used up, expired) is decided here, with the day of Rome (D-069):
 * the frontend shows it, it does not compute it (D-005).
 */
@Service
class CreditViews {

    // the history of a lot read by the practitioner carries the notes; read by the customer, never (guide 41, part 3)
    enum Notes { INCLUDED, LEFT_OUT }

    private final CreditLotRepository lots;
    private final CreditMovementRepository movements;
    private final CreditLotExtensionRepository extensions;
    // which practitioners are still active: their lots are usable, the others' are not (D-077, guide 45, part 7)
    private final PractitionerService practitioners;
    private final Clock clock;

    CreditViews(CreditLotRepository lots, CreditMovementRepository movements, CreditLotExtensionRepository extensions,
                PractitionerService practitioners, Clock clock) {
        this.lots = lots;
        this.movements = movements;
        this.extensions = extensions;
        this.practitioners = practitioners;
        this.clock = clock;
    }

    // ***** the customer *****

    @Transactional(readOnly = true)
    public List<CreditLotSummary> forCustomer(long customerId) {
        return summaries(lots.findByCustomerIdOrderByValidUntilAscIdAsc(customerId));
    }

    // the lot of another customer is not found (404), as everything of another
    @Transactional(readOnly = true)
    public CreditLotDetail lotOfCustomer(long customerId, long lotId) {
        CreditLot lot = lots.findByIdAndCustomerId(lotId, customerId).orElseThrow(() -> notFound(lotId));
        return detail(lot, Notes.LEFT_OUT);
    }

    // ***** the practitioner *****

    // the lots of a customer with THIS practitioner only: of a customer in common, never the other's (guide 41, 4)
    @Transactional(readOnly = true)
    public List<CreditLotSummary> forCustomerOfPractitioner(long practitionerId, long customerId) {
        return summaries(lots.findByCustomerIdAndPractitionerIdOrderByValidUntilAscIdAsc(customerId, practitionerId));
    }

    @Transactional(readOnly = true)
    public CreditLotDetail lotOfPractitioner(long practitionerId, long lotId) {
        CreditLot lot = lots.findByIdAndPractitionerId(lotId, practitionerId).orElseThrow(() -> notFound(lotId));
        return detail(lot, Notes.INCLUDED);
    }

    // ***** helpers *****

    private List<CreditLotSummary> summaries(List<CreditLot> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> balances = movements.balancesOf(found.stream().map(CreditLot::getId).toList()).stream()
                .collect(Collectors.toMap(LotBalance::lotId, LotBalance::balance));
        LocalDate today = StudioTime.today(clock);
        // one question for the whole list (D-029)
        Set<Long> active = practitioners.activeAmong(found.stream().map(CreditLot::getPractitionerId).collect(Collectors.toSet()));
        return found.stream()
                .map(lot -> CreditLotSummary.of(lot, balances.getOrDefault(lot.getId(), 0L), today,
                        active.contains(lot.getPractitionerId())))
                .toList();
    }

    // Movements and extensions in one history, in order of time; the same instant: movements first, then by id
    private CreditLotDetail detail(CreditLot lot, Notes notes) {
        boolean withNotes = notes == Notes.INCLUDED;
        List<CreditMovement> lines = movements.findByCreditLotIdOrderById(lot.getId());
        long balance = lines.stream().mapToLong(CreditMovement::getAmount).sum();
        List<Timed> history = new ArrayList<>();
        for (CreditMovement movement : lines) {
            String note = switch (movement.getReason()) {
                // a gift has its why in the lot (guide 36, 4.4), a correction in its movement
                case LOT_CREATED -> lot.getNote();
                case ADJUSTMENT -> movement.getNote();
                case BOOKING, BOOKING_RELEASE, CANCELLATION_RESTORE -> null;
            };
            history.add(new Timed(movement.getCreatedAt(), 0, movement.getId(), new CreditEntry(
                    CreditEntry.Kind.valueOf(movement.getReason().name()), inRome(movement.getCreatedAt()),
                    movement.getAmount(), movement.getBookingId(),
                    movement.getReason() == MovementReason.LOT_CREATED ? lot.getSource() : null,
                    null, null, withNotes ? note : null)));
        }
        for (CreditLotExtension extension : extensions.findByCreditLotIdOrderById(lot.getId())) {
            history.add(new Timed(extension.getCreatedAt(), 1, extension.getId(), new CreditEntry(
                    CreditEntry.Kind.EXTENSION, inRome(extension.getCreatedAt()), null, null, null,
                    extension.getOldValidUntil(), extension.getNewValidUntil(),
                    withNotes ? extension.getNote() : null)));
        }
        history.sort(Comparator.comparing(Timed::at).thenComparingInt(Timed::table).thenComparingLong(Timed::id));
        boolean practitionerActive = !practitioners.activeAmong(List.of(lot.getPractitionerId())).isEmpty();
        return new CreditLotDetail(CreditLotSummary.of(lot, balance, StudioTime.today(clock), practitionerActive),
                history.stream().map(Timed::entry).toList());
    }

    // an entry with what it is sorted by
    private record Timed(Instant at, int table, long id, CreditEntry entry) {
    }

    private static OffsetDateTime inRome(Instant instant) {
        return StudioTime.inStudioZone(instant);
    }

    private static NotFoundException notFound(long lotId) {
        return new NotFoundException("Credit lot " + lotId + " not found");
    }
}
