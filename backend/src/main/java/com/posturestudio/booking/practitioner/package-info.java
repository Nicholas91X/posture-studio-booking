/**
 * Module "practitioner": the studio's practitioners and their settings
 * (cancellation notice, validity of single-session credits, slot step).
 *
 * Public: PractitionerService and the records it returns. Entity and repository stay
 * package-private, and other modules refer to a practitioner by id only (D-002, D-026).
 * Whether a practitioner is active is the state of their account: asked to auth's
 * AccountService, never read from app_user (D-029).
 * The current practitioner comes from the signed-in user (SessionCurrentPractitioner, guide 25, 7.2).
 * The admin creates, renames, deactivates and reactivates practitioners (D-021, guide 26) through
 * PractitionerAdminController under /api/admin: the account is created and changed by auth's AccountService,
 * in the same transaction as the practitioner row.
 */
package com.posturestudio.booking.practitioner;
