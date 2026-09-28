package com.leaseflow.settlement;

import com.leaseflow.asset.AssetStatus;
import com.leaseflow.asset.LeasedAsset;
import com.leaseflow.asset.LeasedAssetRepository;
import com.leaseflow.common.exception.BusinessRuleViolationException;
import com.leaseflow.common.exception.DuplicateResourceException;
import com.leaseflow.common.exception.ResourceNotFoundException;
import com.leaseflow.common.exception.VersionConflictException;
import com.leaseflow.contract.LeaseContract;
import com.leaseflow.contract.LeaseContractRepository;
import com.leaseflow.settlement.dto.CorrectSettlementRequest;
import com.leaseflow.settlement.dto.CreateSettlementRequest;
import com.leaseflow.settlement.dto.FrozenContractBasis;
import com.leaseflow.settlement.dto.FrozenValuationBasis;
import com.leaseflow.settlement.dto.SettlementCorrectionView;
import com.leaseflow.settlement.dto.SettlementItemView;
import com.leaseflow.settlement.dto.SettlementView;
import com.leaseflow.valuation.AssetValuation;
import com.leaseflow.valuation.AssetValuationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 残值结算服务。
 *
 * <p>确认结算时在同一事务内：对租赁物行加悲观写锁（与评估登记共用同一把锁，
 * 因此“新评估”与“结算确认”天然互斥，再叠加 {@code expectedVersion} 版本比对，
 * 只允许基于当前最新评估版本的一方成功）→ 冻结最新评估依据、合同计算规则与实际处置收入
 * → 计算最终结算差额及有序明细 → 资产置为 SETTLED，全部一起提交。
 *
 * <p>结算成功后拒绝普通评估（见 {@code AssetValuationService}）；修正只能通过
 * {@link #correct} 追加只增更正，原始冻结依据永不覆盖。
 */
@Service
public class ResidualSettlementService {

    /** 金额精度：元，2 位小数。 */
    public static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final LeasedAssetRepository assetRepository;
    private final LeaseContractRepository contractRepository;
    private final AssetValuationRepository valuationRepository;
    private final ResidualSettlementRepository settlementRepository;
    private final SettlementItemRepository itemRepository;
    private final SettlementCorrectionRepository correctionRepository;

    public ResidualSettlementService(LeasedAssetRepository assetRepository,
                                     LeaseContractRepository contractRepository,
                                     AssetValuationRepository valuationRepository,
                                     ResidualSettlementRepository settlementRepository,
                                     SettlementItemRepository itemRepository,
                                     SettlementCorrectionRepository correctionRepository) {
        this.assetRepository = assetRepository;
        this.contractRepository = contractRepository;
        this.valuationRepository = valuationRepository;
        this.settlementRepository = settlementRepository;
        this.itemRepository = itemRepository;
        this.correctionRepository = correctionRepository;
    }

    /**
     * 确认残值结算。资产状态、冻结依据、最终金额与明细在同一事务提交，失败整体回滚。
     *
     * <p>返回 {@link SettlementResult}，{@code replayed=true} 表示相同编号、相同内容的
     * 幂等重放（HTTP 200），返回首次结算结果；首次确认 {@code replayed=false}（HTTP 201）。
     */
    @Transactional
    public SettlementResult confirm(String assetCode, CreateSettlementRequest request) {
        LeasedAsset asset = assetRepository.findByAssetCode(assetCode)
                .orElseThrow(() -> new ResourceNotFoundException("租赁物不存在: " + assetCode));
        // 与评估登记共用资产行悲观写锁：新评估与结算确认在此串行，配合版本比对只允许一方成功。
        asset = assetRepository.findByIdForUpdate(asset.getId())
                .orElseThrow(() -> new ResourceNotFoundException("租赁物不存在: " + assetCode));
        LeaseContract contract = contractRepository.findByAssetId(asset.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "租赁物尚未关联租赁合同: " + assetCode));

        String settlementNo = request.settlementNo().trim();
        String ruleCode = request.settlementRuleCode().trim();
        BigDecimal disposalIncome = request.disposalIncome().setScale(MONEY_SCALE, ROUNDING);
        LocalDate settlementDate = request.settlementDate() != null
                ? request.settlementDate() : LocalDate.now();

        // 结算编号全局唯一：相同编号、相同内容的重复提交幂等返回首次结果；复用且不一致 409。
        ResidualSettlement existingByNo =
                settlementRepository.findBySettlementNo(settlementNo).orElse(null);
        if (existingByNo != null) {
            if (sameConfirmContent(existingByNo, asset, request.expectedVersion(),
                    disposalIncome, request.disposalDate(), ruleCode)) {
                return new SettlementResult(toView(existingByNo, true), true);
            }
            throw new DuplicateResourceException("结算编号已被使用且内容不一致: " + settlementNo);
        }

        // 资产维度唯一：已结算资产不允许再次确认（修正只能走更正流程）。
        ResidualSettlement existingForAsset =
                settlementRepository.findByAssetId(asset.getId()).orElse(null);
        if (existingForAsset != null) {
            throw new BusinessRuleViolationException(
                    "资产已完成残值结算，不能重复结算；如需修正请走结算更正流程: " + assetCode);
        }

        AssetValuation latest = valuationRepository
                .findTopByAssetIdOrderByVersionNoDesc(asset.getId())
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "资产尚无评估记录，无法结算: " + assetCode));

        // 版本比对：并发新评估已把版本推进时，基于旧版本的结算请求得到 409。
        if (request.expectedVersion() != latest.getVersionNo()) {
            throw new VersionConflictException(
                    "评估版本已过期，请基于最新版本 %d 重新提交结算".formatted(
                            latest.getVersionNo()));
        }

        if (request.disposalDate().isBefore(latest.getValuationDate())) {
            throw new BusinessRuleViolationException(
                    "处置日期不得早于结算所依据评估的日期: " + latest.getValuationDate());
        }
        if (settlementDate.isBefore(request.disposalDate())) {
            throw new BusinessRuleViolationException("结算日期不得早于处置日期");
        }

        BigDecimal assessedResidual = latest.getResidualValue();
        BigDecimal settlementDiff = assessedResidual.subtract(disposalIncome)
                .setScale(MONEY_SCALE, ROUNDING);
        SettlementDirection direction = SettlementDirection.of(settlementDiff);
        BigDecimal payableAmount = maxZero(settlementDiff);
        BigDecimal refundableAmount = maxZero(settlementDiff.negate());

        ResidualSettlement settlement = new ResidualSettlement(
                settlementNo, asset, request.expectedVersion(),
                latest.getId(), latest.getVersionNo(), latest.getValuationNo(),
                latest.getValuationDate(), assessedResidual, latest.getImpairmentAmount(),
                latest.getResidualRate(), latest.getInstitution(),
                contract.getContractNo(), contract.getStartDate(), asset.getOriginalValue(),
                contract.getFinancingAmount(), contract.getNominalAnnualRate(),
                contract.getRepaymentMethod().name(), ruleCode,
                disposalIncome, request.disposalDate(), settlementDate,
                settlementDiff, direction.name(), payableAmount, refundableAmount);

        // 明细：评估残值（计入）− 实际处置收入（冲减）= 结算差额（结果）。
        settlement.addItem(new SettlementItem(settlement, 1,
                SettlementItem.CODE_RESIDUAL_VALUE, "评估残值（最新评估版本冻结）",
                assessedResidual, SettlementItem.Direction.ADD, assessedResidual,
                "采用评估版本 %d（%s）".formatted(latest.getVersionNo(),
                        latest.getValuationNo())));
        settlement.addItem(new SettlementItem(settlement, 2,
                SettlementItem.CODE_DISPOSAL_INCOME, "实际处置收入",
                disposalIncome, SettlementItem.Direction.DEDUCT, disposalIncome.negate(),
                "处置日期 " + request.disposalDate()));
        settlement.addItem(new SettlementItem(settlement, 3,
                SettlementItem.CODE_SETTLEMENT_DIFF, "结算差额（评估残值 − 实际处置收入）",
                settlementDiff, SettlementItem.Direction.RESULT, settlementDiff,
                directionRemark(direction)));

        // 资产状态与结算金额、明细在同一事务提交。
        asset.markSettled();
        try {
            settlementRepository.saveAndFlush(settlement);
            assetRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // 并发结算（资产唯一）或编号唯一约束兜底，整笔事务回滚。
            throw new DuplicateResourceException("结算编号冲突或资产已被并发结算: " + settlementNo);
        }

        return new SettlementResult(toView(settlement), false);
    }

    /**
     * 追加一笔结算更正。只增不改：原始冻结依据保持不变，仅记录更正后的有效口径、
     * 有效结果及相对上一有效结果的影响额。
     */
    @Transactional
    public SettlementResult correct(String assetCode, CorrectSettlementRequest request) {
        LeasedAsset asset = assetRepository.findByAssetCode(assetCode)
                .orElseThrow(() -> new ResourceNotFoundException("租赁物不存在: " + assetCode));
        asset = assetRepository.findByIdForUpdate(asset.getId())
                .orElseThrow(() -> new ResourceNotFoundException("租赁物不存在: " + assetCode));
        ResidualSettlement settlement = settlementRepository.findByAssetId(asset.getId())
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "资产尚未完成残值结算，不能更正: " + assetCode));

        String correctionNo = request.correctionNo().trim();
        String reason = request.reason().trim();
        BigDecimal adjustedResidual = request.adjustedResidualValue()
                .setScale(MONEY_SCALE, ROUNDING);
        BigDecimal adjustedIncome = request.adjustedDisposalIncome()
                .setScale(MONEY_SCALE, ROUNDING);

        List<SettlementCorrection> priorCorrections =
                correctionRepository.findBySettlementIdOrderBySeqNoAscIdAsc(settlement.getId());

        SettlementCorrection existing = correctionRepository.findByCorrectionNo(correctionNo)
                .orElse(null);
        if (existing != null) {
            if (sameCorrectionContent(existing, settlement, request.correctionDate(), reason,
                    adjustedResidual, adjustedIncome)) {
                return new SettlementResult(toView(settlement, true), true);
            }
            throw new DuplicateResourceException("更正编号已被使用且内容不一致: " + correctionNo);
        }

        if (adjustedResidual.compareTo(asset.getOriginalValue()) > 0) {
            throw new BusinessRuleViolationException(
                    "更正后有效评估残值不得超过资产原值: " + asset.getOriginalValue());
        }
        if (request.correctionDate().isBefore(settlement.getSettlementDate())) {
            throw new BusinessRuleViolationException(
                    "更正日期不得早于结算日期: " + settlement.getSettlementDate());
        }
        if (!priorCorrections.isEmpty()) {
            LocalDate lastCorrectionDate =
                    priorCorrections.get(priorCorrections.size() - 1).getCorrectionDate();
            if (!request.correctionDate().isAfter(lastCorrectionDate)) {
                throw new VersionConflictException(
                        "更正日期必须晚于上一笔更正日期: " + lastCorrectionDate);
            }
        }

        BigDecimal adjustedDiff = adjustedResidual.subtract(adjustedIncome)
                .setScale(MONEY_SCALE, ROUNDING);
        SettlementDirection adjustedDirection = SettlementDirection.of(adjustedDiff);
        BigDecimal adjustedPayable = maxZero(adjustedDiff);
        BigDecimal adjustedRefundable = maxZero(adjustedDiff.negate());

        // 相对上一有效结果（无更正时即原始结算结果）的影响额。
        BigDecimal diffChange = adjustedDiff.subtract(settlement.getEffectiveSettlementDiff())
                .setScale(MONEY_SCALE, ROUNDING);
        BigDecimal payableChange = adjustedPayable
                .subtract(settlement.getEffectivePayableAmount())
                .setScale(MONEY_SCALE, ROUNDING);
        BigDecimal residualChange = adjustedResidual
                .subtract(settlement.getEffectiveResidualValue())
                .setScale(MONEY_SCALE, ROUNDING);
        BigDecimal incomeChange = adjustedIncome
                .subtract(settlement.getEffectiveDisposalIncome())
                .setScale(MONEY_SCALE, ROUNDING);

        SettlementCorrection correction = new SettlementCorrection(
                correctionNo, settlement, priorCorrections.size() + 1,
                request.correctionDate(), reason,
                adjustedResidual, adjustedIncome, adjustedDiff, adjustedDirection.name(),
                adjustedPayable, adjustedRefundable,
                diffChange, payableChange, residualChange, incomeChange);

        try {
            settlement.addCorrection(correction);
            settlementRepository.saveAndFlush(settlement);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateResourceException("更正编号冲突: " + correctionNo);
        }

        return new SettlementResult(toView(settlement), false);
    }

    /**
     * 按资产查询结算完整计算依据（冻结评估、合同规则、明细、有效金额与更正链）。
     */
    @Transactional(readOnly = true)
    public SettlementView getByAsset(String assetCode) {
        LeasedAsset asset = assetRepository.findByAssetCode(assetCode)
                .orElseThrow(() -> new ResourceNotFoundException("租赁物不存在: " + assetCode));
        ResidualSettlement settlement = settlementRepository.findByAssetId(asset.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "资产尚无残值结算记录: " + assetCode));
        return toView(settlement);
    }

    /**
     * 按结算编号查询结算完整计算依据。
     */
    @Transactional(readOnly = true)
    public SettlementView getByNo(String settlementNo) {
        ResidualSettlement settlement = settlementRepository.findBySettlementNo(settlementNo)
                .orElseThrow(() -> new ResourceNotFoundException("结算不存在: " + settlementNo));
        return toView(settlement);
    }

    private static boolean sameConfirmContent(ResidualSettlement existing, LeasedAsset asset,
                                              int expectedVersion, BigDecimal disposalIncome,
                                              LocalDate disposalDate, String ruleCode) {
        // settlementDate 为服务端确认时间戳，不参与内容比对；其余客户端输入须完全一致。
        return existing.getAsset().getId().equals(asset.getId())
                && existing.getExpectedVersion() == expectedVersion
                && existing.getDisposalIncome().compareTo(disposalIncome) == 0
                && existing.getDisposalDate().equals(disposalDate)
                && existing.getSettlementRuleCode().equals(ruleCode);
    }

    private static boolean sameCorrectionContent(SettlementCorrection existing,
                                                 ResidualSettlement settlement,
                                                 LocalDate correctionDate, String reason,
                                                 BigDecimal adjustedResidual,
                                                 BigDecimal adjustedIncome) {
        return existing.getSettlement().getId().equals(settlement.getId())
                && existing.getCorrectionDate().equals(correctionDate)
                && existing.getReason().equals(reason)
                && existing.getAdjustedResidualValue().compareTo(adjustedResidual) == 0
                && existing.getAdjustedDisposalIncome().compareTo(adjustedIncome) == 0;
    }

    private SettlementView toView(ResidualSettlement settlement) {
        return toView(settlement, false);
    }

    private SettlementView toView(ResidualSettlement settlement, boolean replayed) {
        List<SettlementItemView> itemViews =
                itemRepository.findBySettlementIdOrderByLineNoAsc(settlement.getId()).stream()
                        .map(item -> new SettlementItemView(item.getLineNo(), item.getItemCode(),
                                item.getItemName(), item.getAmount(), item.getDirection().name(),
                                item.getSignedAmount(), item.getRemark()))
                        .toList();
        List<SettlementCorrectionView> correctionViews =
                correctionRepository.findBySettlementIdOrderBySeqNoAscIdAsc(settlement.getId())
                        .stream()
                        .map(c -> new SettlementCorrectionView(c.getCorrectionNo(), c.getSeqNo(),
                                c.getCorrectionDate(), c.getReason(), c.getAdjustedResidualValue(),
                                c.getAdjustedDisposalIncome(), c.getAdjustedSettlementDiff(),
                                c.getAdjustedDiffDirection(), c.getAdjustedPayableAmount(),
                                c.getAdjustedRefundableAmount(), c.getDiffChange(),
                                c.getPayableChange(), c.getResidualValueChange(),
                                c.getDisposalIncomeChange()))
                        .toList();

        LeasedAsset asset = settlement.getAsset();
        FrozenValuationBasis valuationBasis = new FrozenValuationBasis(
                settlement.getValuationId(), settlement.getValuationVersionNo(),
                settlement.getValuationNo(), settlement.getValuationDate(),
                settlement.getAssessedResidualValue(), settlement.getAssessedImpairmentAmount(),
                settlement.getAssessedResidualRate(), settlement.getValuationInstitution());
        FrozenContractBasis contractBasis = new FrozenContractBasis(
                settlement.getContractNo(), settlement.getContractStartDate(),
                settlement.getOriginalValue(), settlement.getFinancingAmount(),
                settlement.getNominalAnnualRate(), settlement.getRepaymentMethod(),
                settlement.getSettlementRuleCode());

        return new SettlementView(
                settlement.getSettlementNo(), asset.getAssetCode(),
                asset.getStatus() == AssetStatus.SETTLED
                        ? AssetStatus.SETTLED.name() : AssetStatus.IN_LEASE.name(),
                settlement.getExpectedVersion(), valuationBasis, contractBasis,
                settlement.getDisposalIncome(), settlement.getDisposalDate(),
                settlement.getSettlementDate(), settlement.getSettlementDiff(),
                settlement.getDiffDirection(), settlement.getPayableAmount(),
                settlement.getRefundableAmount(), itemViews,
                settlement.getAppliedCorrectionCount(),
                settlement.getEffectiveResidualValue(),
                settlement.getEffectiveDisposalIncome(),
                settlement.getEffectiveSettlementDiff(),
                settlement.getEffectiveDiffDirection(),
                settlement.getEffectivePayableAmount(),
                settlement.getEffectiveRefundableAmount(),
                correctionViews, replayed);
    }

    private static String directionRemark(SettlementDirection direction) {
        return switch (direction) {
            case PAYABLE -> "评估残值高于处置收入，差额由承租人补足";
            case REFUNDABLE -> "处置收入高于评估残值，差额退还承租人";
            case EVEN -> "评估残值与处置收入相等，结清";
        };
    }

    private static BigDecimal maxZero(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) > 0 ? value : BigDecimal.ZERO.setScale(MONEY_SCALE);
    }

    /** 结算用例结果：{@code replayed} 标记幂等重放。 */
    public record SettlementResult(SettlementView settlement, boolean replayed) {
    }
}
