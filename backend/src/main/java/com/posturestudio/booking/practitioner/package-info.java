/**
 * Module "practitioner": the studio's practitioners and their settings
 * (cancellation notice, validity of single-session credits, slot step).
 *
 * Public: PractitionerService and the records it returns. Entity and repository stay
 * package-private, and other modules refer to a practitioner by id only (D-002, D-026).
 * Creating and deactivating practitioners belongs to the admin (D-021, step 8).
 */
package com.posturestudio.booking.practitioner;
