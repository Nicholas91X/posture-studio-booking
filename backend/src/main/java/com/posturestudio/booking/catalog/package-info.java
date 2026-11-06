/**
 * Module "catalog": the services and the packages each practitioner sells, and their prices.
 *
 * Public: CatalogService and the records it receives and returns. Entities and repositories stay
 * package-private. The practitioner is referenced by id (D-026); a package references its service
 * as a JPA relation, because both belong to this module.
 * Nothing is ever deleted: services and packages are deactivated and reactivated.
 */
package com.posturestudio.booking.catalog;
