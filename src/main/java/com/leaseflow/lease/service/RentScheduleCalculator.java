package com.leaseflow.lease.service;

import com.leaseflow.lease.domain.LeaseContract;
import com.leaseflow.lease.domain.RentScheduleItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 等额本金租金计划计算器。所有金额计算均使用 BigDecimal，保留 2 位小数并使用 HALF_UP。
 */
@Component
public class RentScheduleCalculator {

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    public List<RentScheduleItem> calculate(LeaseContract contract) {
        BigDecimal financingAmount = contract.getFinancingAmount();
        int periods = contract.getPeriods();
        BigDecimal annualRate = contract.getAnnualRate();

        BigDecimal regularPrincipal = financingAmount.divide(BigDecimal.valueOf(periods), MONEY_SCALE, RoundingMode.HALF_UP);

        List<RentScheduleItem> items = new ArrayList<>(periods);
        BigDecimal opening = financingAmount;
        for (int periodNo = 1; periodNo <= periods; periodNo++) {
            boolean lastPeriod = periodNo == periods;
            BigDecimal principal = lastPeriod ? opening : regularPrincipal;
            BigDecimal interest = opening.multiply(annualRate)
                    .divide(MONTHS_PER_YEAR, MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal total = principal.add(interest);
            BigDecimal closing = opening.subtract(principal);
            LocalDate dueDate = contract.getFirstDueDate().plusMonths(periodNo - 1L);

            items.add(new RentScheduleItem(periodNo, dueDate, opening, principal, interest, total, closing));
            opening = closing;
        }
        return items;
    }
}
