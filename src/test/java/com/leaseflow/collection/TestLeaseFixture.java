package com.leaseflow.collection;

import com.leaseflow.asset.LeasedAsset;
import com.leaseflow.contract.LeaseContract;
import com.leaseflow.contract.LeaseContractRepository;
import com.leaseflow.contract.RepaymentMethod;
import com.leaseflow.schedule.PaymentScheduleItem;
import com.leaseflow.schedule.PaymentScheduleItemRepository;
import com.leaseflow.schedule.RentScheduleCalculator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Component
public class TestLeaseFixture {

    private final LeaseContractRepository contractRepository;
    private final PaymentScheduleItemRepository scheduleItemRepository;
    private final RentScheduleCalculator calculator;

    public TestLeaseFixture(LeaseContractRepository contractRepository,
                            PaymentScheduleItemRepository scheduleItemRepository,
                            RentScheduleCalculator calculator) {
        this.contractRepository = contractRepository;
        this.scheduleItemRepository = scheduleItemRepository;
        this.calculator = calculator;
    }

    @Transactional
    public String createLease(String assetCode, String contractNo) {
        BigDecimal financingAmount = new BigDecimal("6000.00");
        LocalDate startDate = LocalDate.of(2025, 1, 1);
        LocalDate firstPaymentDate = LocalDate.of(2025, 2, 1);

        LeasedAsset asset = new LeasedAsset(assetCode, "数控机床", "生产设备",
                new BigDecimal("6000.00"));
        LeaseContract contract = new LeaseContract(contractNo, asset, startDate,
                firstPaymentDate, financingAmount, BigDecimal.ZERO, 3,
                RepaymentMethod.EQUAL_PRINCIPAL);

        RentScheduleCalculator.RentSchedule schedule = calculator.calculate(
                financingAmount, BigDecimal.ZERO, 3, firstPaymentDate,
                RepaymentMethod.EQUAL_PRINCIPAL);
        List<PaymentScheduleItem> items = schedule.rows().stream()
                .map(row -> new PaymentScheduleItem(contract, row.periodNo(), row.dueDate(),
                        row.openingPrincipal(), row.principalDue(), row.interestDue(),
                        row.totalDue(), row.closingPrincipal()))
                .toList();

        contractRepository.save(contract);
        scheduleItemRepository.saveAll(items);
        contractRepository.flush();
        scheduleItemRepository.flush();
        return contractNo;
    }
}
