package com.leaseflow.lease;

import com.leaseflow.asset.LeasedAsset;
import com.leaseflow.asset.LeasedAssetRepository;
import com.leaseflow.common.exception.BusinessRuleViolationException;
import com.leaseflow.common.exception.DuplicateResourceException;
import com.leaseflow.common.exception.ResourceNotFoundException;
import com.leaseflow.contract.LeaseContract;
import com.leaseflow.contract.LeaseContractRepository;
import com.leaseflow.lease.dto.CreateLeaseRequest;
import com.leaseflow.lease.dto.LeaseResponse;
import com.leaseflow.schedule.PaymentScheduleItem;
import com.leaseflow.schedule.PaymentScheduleItemRepository;
import com.leaseflow.schedule.RentScheduleCalculator;
import com.leaseflow.schedule.RentScheduleCalculator.RentSchedule;
import com.leaseflow.schedule.RentScheduleCalculator.Summary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class LeaseService {

    private final LeasedAssetRepository assetRepository;
    private final LeaseContractRepository contractRepository;
    private final PaymentScheduleItemRepository scheduleItemRepository;
    private final RentScheduleCalculator calculator;

    public LeaseService(LeasedAssetRepository assetRepository,
                        LeaseContractRepository contractRepository,
                        PaymentScheduleItemRepository scheduleItemRepository,
                        RentScheduleCalculator calculator) {
        this.assetRepository = assetRepository;
        this.contractRepository = contractRepository;
        this.scheduleItemRepository = scheduleItemRepository;
        this.calculator = calculator;
    }

    @Transactional
    public LeaseResponse createLease(CreateLeaseRequest request) {
        if (request.financingAmount().compareTo(request.originalValue()) > 0) {
            throw new BusinessRuleViolationException("融资金额不得超过租赁物原值");
        }
        if (request.firstPaymentDate().isBefore(request.startDate())) {
            throw new BusinessRuleViolationException("首期应还日不得早于起租日");
        }
        if (assetRepository.existsByAssetCode(request.assetCode())) {
            throw new DuplicateResourceException("租赁物编码已存在: " + request.assetCode());
        }
        if (contractRepository.existsByContractNo(request.contractNo())) {
            throw new DuplicateResourceException("合同编号已存在: " + request.contractNo());
        }

        LeasedAsset asset = new LeasedAsset(request.assetCode(), request.assetName(),
                request.category(), request.originalValue());
        LeaseContract contract = new LeaseContract(request.contractNo(), asset,
                request.startDate(), request.firstPaymentDate(), request.financingAmount(),
                request.nominalAnnualRate(), request.termMonths());

        RentSchedule schedule = calculator.calculate(request.financingAmount(),
                request.nominalAnnualRate(), request.termMonths(), request.firstPaymentDate());
        List<PaymentScheduleItem> items = schedule.rows().stream()
                .map(row -> new PaymentScheduleItem(contract, row.periodNo(), row.dueDate(),
                        row.openingPrincipal(), row.principalDue(), row.interestDue(),
                        row.totalDue(), row.closingPrincipal()))
                .toList();

        try {
            contractRepository.save(contract);
            scheduleItemRepository.saveAll(items);
            contractRepository.flush();
            scheduleItemRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateResourceException("租赁物编码或合同编号已存在");
        }

        return toResponse(contract, items, schedule.summary());
    }

    @Transactional(readOnly = true)
    public LeaseResponse getByContractNo(String contractNo) {
        LeaseContract contract = contractRepository.findByContractNo(contractNo)
                .orElseThrow(() -> new ResourceNotFoundException("合同不存在: " + contractNo));
        List<PaymentScheduleItem> items = scheduleItemRepository
                .findByContractIdOrderByPeriodNoAsc(contract.getId());

        Summary summary = new Summary(
                items.stream().map(PaymentScheduleItem::getPrincipalDue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                items.stream().map(PaymentScheduleItem::getInterestDue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                items.stream().map(PaymentScheduleItem::getTotalDue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));

        return toResponse(contract, items, summary);
    }

    private LeaseResponse toResponse(LeaseContract contract, List<PaymentScheduleItem> items,
                                     Summary summary) {
        LeasedAsset asset = contract.getAsset();
        return new LeaseResponse(
                new LeaseResponse.AssetView(asset.getAssetCode(), asset.getAssetName(),
                        asset.getCategory(), asset.getOriginalValue()),
                new LeaseResponse.ContractView(contract.getContractNo(), contract.getStartDate(),
                        contract.getFirstPaymentDate(), contract.getFinancingAmount(),
                        contract.getNominalAnnualRate(), contract.getTermMonths()),
                items.stream()
                        .map(item -> new LeaseResponse.ScheduleItemView(item.getPeriodNo(),
                                item.getDueDate(), item.getOpeningPrincipal(),
                                item.getPrincipalDue(), item.getInterestDue(),
                                item.getTotalDue(), item.getClosingPrincipal()))
                        .toList(),
                new LeaseResponse.SummaryView(summary.totalPrincipal(),
                        summary.totalInterest(), summary.totalAmount()));
    }
}
