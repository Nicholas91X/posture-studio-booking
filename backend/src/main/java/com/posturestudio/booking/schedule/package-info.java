/**
 * Module "schedule": the weekly schedule of each practitioner, in versions valid from a date (D-034),
 * the absences, and from piece 3 of step 6 the free slot calculation (D-035).
 *
 * Working bands are local times of day (Europe/Rome), converted to instants only when slots are
 * computed; absences are instants. Tables: working_schedule, working_hours, unavailability (V3).
 */
package com.posturestudio.booking.schedule;
