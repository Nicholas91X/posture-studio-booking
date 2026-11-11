package com.posturestudio.booking.credit;

// the balance of one lot, from the grouped query of CreditMovementRepository.balancesOf (guide 41, part 1)
public record LotBalance(long lotId, long balance) {
}
