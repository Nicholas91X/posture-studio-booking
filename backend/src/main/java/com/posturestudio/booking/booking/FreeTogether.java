package com.posturestudio.booking.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.posturestudio.booking.shared.TimeRange;

/**
 * "Free together" (D-091): the windows in which every chosen practitioner works, is not away and has nothing booked,
 * at least as long as the duration, and not before a given instant (now). A pure function on half-open intervals
 * [start, end): a session ending at 10:00 leaves 10:00 free, and a window exactly as long as the duration counts.
 */
final class FreeTogether {

    /** One practitioner's time: when they work, and what takes it (bookings and absences, in any order). */
    record Agenda(List<TimeRange> working, List<TimeRange> taken) {
    }

    private FreeTogether() {
    }

    static List<TimeRange> compute(List<Agenda> agendas, Duration duration, Instant notBefore) {
        if (agendas.isEmpty()) {
            return List.of();
        }
        List<TimeRange> together = null;
        for (Agenda agenda : agendas) {
            List<TimeRange> free = subtract(merge(agenda.working()), merge(agenda.taken()));
            together = together == null ? free : intersect(together, free);
        }
        List<TimeRange> windows = new ArrayList<>();
        for (TimeRange window : together) {
            Instant start = window.start().isBefore(notBefore) ? notBefore : window.start();
            if (!start.plus(duration).isAfter(window.end())) {
                windows.add(new TimeRange(start, window.end()));
            }
        }
        return List.copyOf(windows);
    }

    // sorted, and touching or overlapping ranges joined: [9,10) and [10,11) are [9,11)
    static List<TimeRange> merge(List<TimeRange> ranges) {
        List<TimeRange> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparing(TimeRange::start));
        List<TimeRange> merged = new ArrayList<>();
        for (TimeRange range : sorted) {
            if (!merged.isEmpty() && !range.start().isAfter(merged.getLast().end())) {
                TimeRange last = merged.removeLast();
                merged.add(new TimeRange(last.start(), later(last.end(), range.end())));
            } else {
                merged.add(range);
            }
        }
        return merged;
    }

    // what is left of the first list once the second is taken away; both merged
    static List<TimeRange> subtract(List<TimeRange> from, List<TimeRange> taken) {
        List<TimeRange> left = new ArrayList<>();
        for (TimeRange range : from) {
            Instant cursor = range.start();
            for (TimeRange cut : taken) {
                if (!cut.end().isAfter(cursor) || !cut.start().isBefore(range.end())) {
                    continue;
                }
                if (cut.start().isAfter(cursor)) {
                    left.add(new TimeRange(cursor, cut.start()));
                }
                cursor = later(cursor, cut.end());
            }
            if (cursor.isBefore(range.end())) {
                left.add(new TimeRange(cursor, range.end()));
            }
        }
        return left;
    }

    // the parts both lists have; both merged and sorted
    static List<TimeRange> intersect(List<TimeRange> a, List<TimeRange> b) {
        List<TimeRange> both = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            Instant start = later(a.get(i).start(), b.get(j).start());
            Instant end = earlier(a.get(i).end(), b.get(j).end());
            if (start.isBefore(end)) {
                both.add(new TimeRange(start, end));
            }
            if (a.get(i).end().isBefore(b.get(j).end())) {
                i++;
            } else {
                j++;
            }
        }
        return both;
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
