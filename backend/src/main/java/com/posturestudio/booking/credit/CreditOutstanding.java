package com.posturestudio.booking.credit;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.customer.CustomerContact;
import com.posturestudio.booking.customer.CustomerService;
import com.posturestudio.booking.practitioner.OutstandingCredit;
import com.posturestudio.booking.practitioner.OutstandingCredits;
import com.posturestudio.booking.shared.StudioTime;

// The answer of credit to practitioner (D-077, guide 45, part 6): the lots still valid of a practitioner's customers,
// the warning of a deactivation. The interface is practitioner's, so credit -> practitioner stays the only way.
// Four queries whatever the number of lots: the ids, the lots, their balances, the customers (D-029)
@Component
class CreditOutstanding implements OutstandingCredits {

    private final CreditLotRepository lots;
    private final CreditMovementRepository movements;
    private final CustomerService customers;
    private final Clock clock;

    CreditOutstanding(CreditLotRepository lots, CreditMovementRepository movements, CustomerService customers,
                      Clock clock) {
        this.lots = lots;
        this.movements = movements;
        this.customers = customers;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OutstandingCredit> validOf(long practitionerId) {
        // valid today in Rome (D-069): the last valid day not gone, and credits left
        LocalDate today = StudioTime.today(clock);
        List<Long> ids = lots.findValidIdsOf(practitionerId, today);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<CreditLot> found = lots.findByIdInOrderByValidUntilAscIdAsc(ids);
        Map<Long, Long> balances = movements.balancesOf(ids).stream()
                .collect(Collectors.toMap(LotBalance::lotId, LotBalance::balance));
        Map<Long, CustomerContact> contacts = customers.contactsOf(
                found.stream().map(CreditLot::getCustomerId).collect(Collectors.toSet()));
        return found.stream()
                .map(lot -> {
                    CustomerContact customer = contacts.get(lot.getCustomerId());
                    return new OutstandingCredit(lot.getId(), lot.getCustomerId(), customer.firstName(),
                            customer.lastName(), customer.email(), lot.getServiceId(), balances.get(lot.getId()),
                            lot.getValidUntil().lastDay());
                })
                .toList();
    }
}
