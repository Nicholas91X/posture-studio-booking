package com.posturestudio.booking.catalog;

/**
 * Whose a package is, and on which service (guide 37, part 7): two facts that never change for a package, so
 * they can be read when it is paid, even if it was deactivated in between.
 */
public record PackageOwner(long practitionerId, long serviceId) {
}
