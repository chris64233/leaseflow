package com.leaseflow.schedule;

import com.leaseflow.contract.RepaymentMethod;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 租金计划计算器，支持等额本金与等额本息两种还款方式。
 *
 * <p>等额本金：每月应还本金 = 融资金额 / 期数（保留 2 位小数，HALF_UP），最后一期承担累计舍入差额。
 *
 * <p>等额本息：月供按标准年金公式 A = P·r·(1+r)^n / ((1+r)^n − 1) 计算（保留 2 位小数，HALF_UP），
 * 零利率时按融资金额平均分摊；每期利息 = 期初剩余本金 × 月利率，应还本金 = 月供 − 当期利息；
 * 最后一期以剩余本金为应还本金、本金加当期利息为应还总额，吸收累计舍入差额。
 *
 * <p>月利率 = 名义年利率 / 12；每期应还日以首期应还日为锚点逐月增加，避免月末日期漂移。
 */
@Component
public class RentScheduleCalculator {

    private static final int MONEY_SCALE = 2;
    private static final int RATE_SCALE = 10;
    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    public RentSchedule calculate(BigDecimal financingAmount, BigDecimal nominalAnnualRate,
                                  int termMonths, LocalDate firstPaymentDate,
                                  RepaymentMethod repaymentMethod) {
        BigDecimal monthlyRate = nominalAnnualRate
                .divide(MONTHS_PER_YEAR, RATE_SCALE, RoundingMode.HALF_UP);
        return switch (repaymentMethod) {
            case EQUAL_PRINCIPAL -> calculateEqualPrincipal(financingAmount, monthlyRate,
                    termMonths, firstPaymentDate);
            case EQUAL_PAYMENT -> calculateEqualPayment(financingAmount, monthlyRate,
                    termMonths, firstPaymentDate);
        };
    }

    private RentSchedule calculateEqualPrincipal(BigDecimal financingAmount, BigDecimal monthlyRate,
                                                 int termMonths, LocalDate firstPaymentDate) {
        BigDecimal monthlyPrincipal = financingAmount
                .divide(BigDecimal.valueOf(termMonths), MONEY_SCALE, RoundingMode.HALF_UP);

        List<Row> rows = new ArrayList<>(termMonths);
        BigDecimal remaining = financingAmount;
        for (int period = 1; period <= termMonths; period++) {
            BigDecimal openingPrincipal = remaining;
            BigDecimal principalDue = period < termMonths
                    ? monthlyPrincipal.min(remaining)
                    : remaining;
            BigDecimal interestDue = openingPrincipal.multiply(monthlyRate)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal totalDue = principalDue.add(interestDue);
            BigDecimal closingPrincipal = openingPrincipal.subtract(principalDue);
            LocalDate dueDate = firstPaymentDate.plusMonths(period - 1L);

            rows.add(new Row(period, dueDate, openingPrincipal, principalDue,
                    interestDue, totalDue, closingPrincipal));
            remaining = closingPrincipal;
        }

        return toSchedule(rows);
    }

    private RentSchedule calculateEqualPayment(BigDecimal financingAmount, BigDecimal monthlyRate,
                                               int termMonths, LocalDate firstPaymentDate) {
        BigDecimal annuityPayment = annuityPayment(financingAmount, monthlyRate, termMonths);

        List<Row> rows = new ArrayList<>(termMonths);
        BigDecimal remaining = financingAmount;
        for (int period = 1; period <= termMonths; period++) {
            BigDecimal openingPrincipal = remaining;
            BigDecimal interestDue = openingPrincipal.multiply(monthlyRate)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal principalDue = period < termMonths
                    ? annuityPayment.subtract(interestDue).min(remaining)
                    : remaining;
            BigDecimal totalDue = principalDue.add(interestDue);
            BigDecimal closingPrincipal = openingPrincipal.subtract(principalDue);
            LocalDate dueDate = firstPaymentDate.plusMonths(period - 1L);

            rows.add(new Row(period, dueDate, openingPrincipal, principalDue,
                    interestDue, totalDue, closingPrincipal));
            remaining = closingPrincipal;
        }

        return toSchedule(rows);
    }

    private BigDecimal annuityPayment(BigDecimal principal, BigDecimal monthlyRate, int termMonths) {
        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(BigDecimal.valueOf(termMonths), MONEY_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal factor = BigDecimal.ONE.add(monthlyRate).pow(termMonths);
        return principal.multiply(monthlyRate).multiply(factor)
                .divide(factor.subtract(BigDecimal.ONE), MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private RentSchedule toSchedule(List<Row> rows) {
        BigDecimal totalPrincipal = rows.stream().map(Row::principalDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalInterest = rows.stream().map(Row::interestDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAmount = rows.stream().map(Row::totalDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new RentSchedule(rows, new Summary(totalPrincipal, totalInterest, totalAmount));
    }

    public record Row(int periodNo, LocalDate dueDate, BigDecimal openingPrincipal,
                      BigDecimal principalDue, BigDecimal interestDue, BigDecimal totalDue,
                      BigDecimal closingPrincipal) {
    }

    public record Summary(BigDecimal totalPrincipal, BigDecimal totalInterest,
                          BigDecimal totalAmount) {
    }

    public record RentSchedule(List<Row> rows, Summary summary) {
    }
}
