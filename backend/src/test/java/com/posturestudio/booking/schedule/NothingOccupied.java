package com.posturestudio.booking.schedule;

import java.util.List;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

// For the @DataJpaTest tests of schedule: ScheduleService needs an OccupiedTime, and the real one,
// BookingOccupiedTime, is package-private in booking (D-044). OccupiedTime has a single method,
// so a lambda implements it: nothing is booked. Free slots with real bookings are tested in booking
@TestConfiguration(proxyBeanMethods = false)
public class NothingOccupied {

    @Bean
    OccupiedTime occupiedTime() {
        return (practitionerId, window, now) -> List.of();
    }
}
