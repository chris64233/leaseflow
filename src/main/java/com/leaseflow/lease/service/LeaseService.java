package com.leaseflow.lease.service;

import com.leaseflow.common.error.BusinessException;
import com.leaseflow.lease.domain.LeaseContract;
import com.leaseflow.lease.domain.LeasedAsset;
import com.leaseflow.lease.domain.RentScheduleItem;
import com.leaseflow.lease.dto.CreateLeaseRequest;
import com.leaseflow.lease.dto.LeaseDetailResponse;
import com.leaseflow.lease.repository.LeaseContractRepository;
import com.leaseflow.lease.repository.LeasedAssetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class LeaseService {

    private final LeasedAssetRepository assetRepository;
    private final LeaseContractRepository contractRepository;
    private final RentScheduleCalculator calculator;

    public LeaseService(LeasedAssetRepository assetRepository,
                        LeaseContractRepository contractRepository,
                        RentScheduleCalculator calculator) {
        this.assetRepository = assetRepository;
        this.contractRepository = contractRepository;
        this.calculator = calculator;
    }

    @Transactional
    public LeaseDetailResponse createLease(CreateLeaseRequest request) {
        CreateLeaseRequest.AssetPart assetPart = request.asset();
        CreateLeaseRequest.ContractPart contractPart = request.contract();

        if (contractPart.financingAmount().compareTo(assetPart.originalValue()) > 0) {
            throw BusinessException.badRequest("FINANCING_EXCEEDS_ORIGINAL_VALUE", "融资金额不得超过租赁物原值");
        }
        if (contractPart.firstDueDate().isBefore(contractPart.startDate())) {
            throw BusinessException.badRequest("FIRST_DUE_DATE_BEFORE_START", "首期应还日不得早于起租日");
        }
        if (assetRepository.existsByAssetCode(assetPart.assetCode())) {
            throw BusinessException.conflict("DUPLICATE_ASSET_CODE", "租赁物编码已存在: " + assetPart.assetCode());
        }
        if (contractRepository.existsByContractNo(contractPart.contractNo())) {
            throw BusinessException.conflict("DUPLICATE_CONTRACT_NO", "合同编号已存在: " + contractPart.contractNo());
        }

        LeasedAsset asset = new LeasedAsset(assetPart.assetCode(), assetPart.assetName(),
                assetPart.category(), assetPart.originalValue());
        LeaseContract contract = new LeaseContract(contractPart.contractNo(), asset,
                contractPart.startDate(), contractPart.firstDueDate(),
                contractPart.financingAmount(), contractPart.annualRate(), contractPart.periods());

        List<RentScheduleItem> items = calculator.calculate(contract);
        items.forEach(contract::addScheduleItem);

        try {
            assetRepository.save(asset);
            contractRepository.saveAndFlush(contract);
        } catch (DataIntegrityViolationException ex) {
            throw BusinessException.conflict("DUPLICATE_KEY", "租赁物编码或合同编号已存在");
        }
        return toResponse(contract);
    }

    @Transactional(readOnly = true)
    public LeaseDetailResponse getByContractNo(String contractNo) {
        LeaseContract contract = contractRepository.findByContractNo(contractNo)
                .orElseThrow(() -> BusinessException.notFound("LEASE_NOT_FOUND", "合同不存在: " + contractNo));
        return toResponse(contract);
    }

    private LeaseDetailResponse toResponse(LeaseContract contract) {
        LeasedAsset asset = contract.getAsset();
        LeaseDetailResponse.AssetView assetView = new LeaseDetailResponse.AssetView(
                asset.getAssetCode(), asset.getAssetName(), asset.getCategory(), asset.getOriginalValue());
        LeaseDetailResponse.ContractView contractView = new LeaseDetailResponse.ContractView(
                contract.getContractNo(), contract.getStartDate(), contract.getFirstDueDate(),
                contract.getFinancingAmount(), contract.getAnnualRate(), contract.getPeriods());

        List<LeaseDetailResponse.ScheduleItemView> schedule = contract.getScheduleItems().stream()
                .map(item -> new LeaseDetailResponse.ScheduleItemView(
                        item.getPeriodNo(), item.getDueDate(), item.getOpeningPrincipal(),
                        item.getPrincipal(), item.getInterest(), item.getTotal(), item.getClosingPrincipal()))
                .toList();

        BigDecimal totalPrincipal = contract.getScheduleItems().stream()
                .map(RentScheduleItem::getPrincipal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalInterest = contract.getScheduleItems().stream()
                .map(RentScheduleItem::getInterest).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAmount = contract.getScheduleItems().stream()
                .map(RentScheduleItem::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        LeaseDetailResponse.SummaryView summary = new LeaseDetailResponse.SummaryView(
                totalPrincipal, totalInterest, totalAmount);

        return new LeaseDetailResponse(assetView, contractView, schedule, summary);
    }
}
