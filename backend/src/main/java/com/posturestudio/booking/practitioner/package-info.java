/**
 * Module "practitioner": the studio's practitioners and their settings
 * (cancellation notice, validity of single-session credits, slot step).
 *
 * Public: PractitionerService and the records it returns. Entity and repository stay
 * package-private, and other modules refer to a practitioner by id only (D-002, D-026).
 * Whether a practitioner is active is the state of their account: asked to auth's
 * AccountService, never read from app_user (D-029).
 * Creating and deactivating practitioners belongs to the admin (D-021, step 8).
 */
package com.posturestudio.booking.practitioner;
