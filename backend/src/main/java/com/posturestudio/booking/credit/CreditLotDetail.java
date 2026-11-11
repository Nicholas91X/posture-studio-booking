package com.posturestudio.booking.credit;

import java.util.List;

// One lot with its history, in order of time (guide 41, part 3)
record CreditLotDetail(CreditLotSummary lot, List<CreditEntry> history) {
}
