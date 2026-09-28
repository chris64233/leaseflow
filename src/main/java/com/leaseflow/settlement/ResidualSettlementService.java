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
import com.leaseflow.settlement.dto.DisposalView;
import com.leaseflow.settlement.dto.FrozenContractRuleView;
import com.leaseflow.settlement.dto.FrozenValuationView;
import com.leaseflow.settlement.dto.SettlementAmountsView;
import com.leaseflow.settlement.dto.SettlementCorrectionView;
import com.leaseflow.settlement.dto.SettlementItemView;
import com.leaseflow.settlement.dto.SettlementRegistrationResult;
import com.leaseflow.settlement.dto.SettlementView;
import com.leaseflow.valuation.AssetValuation;
import com.leaseflow.valuation.AssetValuationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 租赁结束后的残值结算。
 *
 * <p>结算确认在同一事务内完成：锁定资产行 → 校验客户端评估版本仍为最新版本
 * （与并发新评估互斥）→ 冻结最新评估版本、实际处置情况与合同计算规则 →
 * 计算差额、方向、应收/应付及逐项明细 → 写入结算单、明细并把资产置为 SETTLED。
 * 任一步骤失败整体回滚，不留下结算单、明细或部分资产状态。
 */
@Service
public class ResidualSettlementService {

    /** 金额精度：元，2 位小数，与残值评估保持一致。 */
    public static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, ROUNDING);

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
     * 确认残值结算。
     *
     * <p>并发控制与幂等：
     * <ul>
     *   <li>先对租赁物行加悲观写锁（与评估登记同一把锁），锁内读取最新评估版本；
     *       仅当 {@code expectedVersion} 仍为最新版本时才允许结算，否则返回 409——
     *       因此新评估与结算并发时只有一方成功；</li>
     *   <li>每个资产至多一份结算（唯一约束 {@code uk_residual_settlement_asset}）；</li>
     *   <li>相同结算编号连同相同内容重复提交视为重试，幂等返回首次结果（200），
     *       编号复用且内容不一致、或编号已被其他资产使用返回 409。</li>
     * </ul>
     */
    @Transactional
    public SettlementRegistrationResult confirm(String assetCode, CreateSettlementRequest request) {
        LeasedAsset asset = assetRepository.findByAssetCodeForUpdate(assetCode)
                .orElseThrow(() -> new ResourceNotFoundException("租赁物不存在: " + assetCode));
        LeaseContract contract = contractRepository.findByAssetId(asset.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "租赁物尚未关联租赁合同: " + assetCode));

        String settlementNo = request.settlementNo().trim();
        BigDecimal disposalIncome = request.disposalIncome().setScale(MONEY_SCALE, ROUNDING);
        BigDecimal disposalCost = request.disposalCost() == null
                ? ZERO : request.disposalCost().setScale(MONEY_SCALE, ROUNDING);

        ResidualSettlement existingByAsset =
                settlementRepository.findByAssetId(asset.getId()).orElse(null);
        ResidualSettlement existingByNo =
                settlementRepository.findBySettlementNo(settlementNo).orElse(null);

        if (existingByAsset != null) {
            // 资产已有结算单：仅允许相同编号 + 相同内容的幂等重试，其余一律拒绝，原始依据不可覆盖。
            if (existingByNo != null && existingByNo.getId().equals(existingByAsset.getId())
                    && sameSettlementContent(existingByAsset, request, disposalIncome,
                    disposalCost)) {
                return replay(existingByAsset);
            }
            throw new DuplicateResourceException(
                    "资产残值已完成结算（结算编号 %s），不允许重复结算或覆盖原始依据"
                            .formatted(existingByAsset.getSettlementNo()));
        }
        if (existingByNo != null) {
            throw new DuplicateResourceException("结算编号已被使用: " + settlementNo);
        }

        AssetValuation latest = valuationRepository
                .findTopByAssetIdOrderByVersionNoDesc(asset.getId())
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "资产尚无残值评估记录，无法结算: " + assetCode));

        // 版本匹配优先于业务校验：并发新评估抢先时，结算方稳定得到 409 版本冲突信号。
        if (request.expectedVersion() != latest.getVersionNo()) {
            throw new VersionConflictException(
                    "评估版本已过期，请基于最新版本 %d 重新提交结算"
                            .formatted(latest.getVersionNo()));
        }
        if (request.disposalDate().isBefore(latest.getValuationDate())) {
            throw new BusinessRuleViolationException(
                    "处置日期不得早于结算采用的评估日期: " + latest.getValuationDate());
        }
        if (request.settlementDate().isBefore(request.disposalDate())) {
            throw new BusinessRuleViolationException("结算日期不得早于处置日期");
        }

        BigDecimal netProceeds = disposalIncome.subtract(disposalCost)
                .setScale(MONEY_SCALE, ROUNDING);
        BigDecimal difference = netProceeds.subtract(latest.getResidualValue())
                .setScale(MONEY_SCALE, ROUNDING);
        SettlementAmounts amounts = amountsOf(difference);

        ResidualSettlement settlement = new ResidualSettlement(
                settlementNo, asset, request.settlementDate(),
                latest.getId(), latest.getVersionNo(), latest.getValuationNo(),
                latest.getValuationDate(), latest.getResidualValue(),
                latest.getImpairmentAmount(), latest.getResidualRate(),
                latest.getInstitution(),
                request.disposalDate(), disposalIncome, disposalCost, netProceeds,
                asset.getOriginalValue(), contract.getFinancingAmount(),
                contract.getNominalAnnualRate(), contract.getTermMonths(),
                contract.getRepaymentMethod(),
                difference, amounts.direction(), amounts.receivable(), amounts.payable());

        List<SettlementItem> items = buildItems(settlement);

        asset.markSettled();
        try {
            settlementRepository.saveAndFlush(settlement);
            itemRepository.saveAll(items);
            itemRepository.flush();
            // 资产状态由脏检查在事务提交时更新；显式 flush 使其与结算写入同生共死。
            assetRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // 并发下唯一约束兜底（结算编号复用或同一资产重复结算），整笔事务回滚。
            throw new DuplicateResourceException(
                    "结算编号已存在或资产已完成结算: " + settlementNo);
        }

        return new SettlementRegistrationResult(toView(asset, settlement, items, List.of()),
                false);
    }

    /**
     * 查询结算单及其完整计算依据（冻结评估快照、处置情况、合同规则快照）、
     * 逐项明细与历次更正；资产不存在返回 404，尚未结算返回 404。
     */
    @Transactional(readOnly = true)
    public SettlementView get(String assetCode) {
        LeasedAsset asset = assetRepository.findByAssetCode(assetCode)
                .orElseThrow(() -> new ResourceNotFoundException("租赁物不存在: " + assetCode));
        ResidualSettlement settlement = settlementRepository.findByAssetId(asset.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "资产尚未完成残值结算: " + assetCode));
        return toView(asset, settlement,
                itemRepository.findBySettlementIdOrderByLineNoAsc(settlement.getId()),
                correctionRepository.findBySettlementIdOrderBySeqNoAscIdAsc(settlement.getId()));
    }

    /**
     * 追加结算更正。结算后只能通过此入口修正：更正仅记录原结算差额之上的调整额，
     * 结算单冻结的原始依据（评估版本、处置收入、合同规则）永不覆盖。
     *
     * <p>相同更正编号连同相同内容重复提交幂等返回首次结果（200）；编号复用且内容不一致
     * 返回 409。更正记录在资产行锁内分配连续序号，并发更正在此串行。
     */
    @Transactional
    public SettlementRegistrationResult correct(String assetCode,
                                                CorrectSettlementRequest request) {
        LeasedAsset asset = assetRepository.findByAssetCodeForUpdate(assetCode)
                .orElseThrow(() -> new ResourceNotFoundException("租赁物不存在: " + assetCode));
        ResidualSettlement settlement = settlementRepository.findByAssetId(asset.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "资产尚未完成残值结算，无法更正: " + assetCode));

        String correctionNo = request.correctionNo().trim();
        String reason = request.reason().trim();
        BigDecimal adjustment = request.adjustmentAmount().setScale(MONEY_SCALE, ROUNDING);
        if (adjustment.compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessRuleViolationException("更正调整金额不得为 0");
        }

        List<SettlementCorrection> corrections =
                correctionRepository.findBySettlementIdOrderBySeqNoAscIdAsc(settlement.getId());
        SettlementCorrection latestCorrection =
                corrections.isEmpty() ? null : corrections.get(corrections.size() - 1);

        SettlementCorrection existing = correctionRepository.findByCorrectionNo(correctionNo)
                .orElse(null);
        if (existing != null) {
            if (existing.getSettlement().getId().equals(settlement.getId())
                    && sameCorrectionContent(existing, request, adjustment, reason)) {
                return replay(settlement);
            }
            throw new DuplicateResourceException("更正编号已被使用且内容不一致: " + correctionNo);
        }

        if (request.correctionDate().isBefore(settlement.getSettlementDate())) {
            throw new BusinessRuleViolationException(
                    "更正日期不得早于结算日期: " + settlement.getSettlementDate());
        }
        if (latestCorrection != null
                && !request.correctionDate().isAfter(latestCorrection.getCorrectionDate())) {
            throw new BusinessRuleViolationException(
                    "更正日期必须晚于最近一次更正日期: "
                            + latestCorrection.getCorrectionDate());
        }

        BigDecimal currentDifference = latestCorrection == null
                ? settlement.getDifferenceAmount()
                : latestCorrection.getEffectiveDifference();
        BigDecimal effectiveDifference = currentDifference.add(adjustment)
                .setScale(MONEY_SCALE, ROUNDING);
        SettlementAmounts amounts = amountsOf(effectiveDifference);

        SettlementCorrection correction = new SettlementCorrection(
                correctionNo, settlement, corrections.size() + 1, request.correctionDate(),
                adjustment, reason, effectiveDifference, amounts.direction(),
                amounts.receivable(), amounts.payable());
        try {
            correctionRepository.saveAndFlush(correction);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateResourceException("更正编号已存在: " + correctionNo);
        }

        List<SettlementCorrection> all = new ArrayList<>(corrections);
        all.add(correction);
        return new SettlementRegistrationResult(toView(asset, settlement,
                itemRepository.findBySettlementIdOrderByLineNoAsc(settlement.getId()), all),
                false);
    }

    private SettlementRegistrationResult replay(ResidualSettlement settlement) {
        LeasedAsset asset = assetRepository.findById(settlement.getAsset().getId()).orElseThrow();
        SettlementView view = toView(asset, settlement,
                itemRepository.findBySettlementIdOrderByLineNoAsc(settlement.getId()),
                correctionRepository.findBySettlementIdOrderBySeqNoAscIdAsc(settlement.getId()));
        return new SettlementRegistrationResult(view, true);
    }

    private static boolean sameSettlementContent(ResidualSettlement existing,
                                                 CreateSettlementRequest request,
                                                 BigDecimal disposalIncome,
                                                 BigDecimal disposalCost) {
        // expectedVersion 是乐观并发令牌而非请求身份：结算后版本链冻结，重试内容一致即幂等返回。
        return existing.getSettlementDate().equals(request.settlementDate())
                && existing.getDisposalDate().equals(request.disposalDate())
                && existing.getDisposalIncome().compareTo(disposalIncome) == 0
                && existing.getDisposalCost().compareTo(disposalCost) == 0;
    }

    private static boolean sameCorrectionContent(SettlementCorrection existing,
                                                 CorrectSettlementRequest request,
                                                 BigDecimal adjustment, String reason) {
        return existing.getCorrectionDate().equals(request.correctionDate())
                && existing.getAdjustmentAmount().compareTo(adjustment) == 0
                && existing.getReason().equals(reason);
    }

    private static List<SettlementItem> buildItems(ResidualSettlement settlement) {
        List<SettlementItem> items = new ArrayList<>();
        items.add(new SettlementItem(settlement, SettlementItemType.ORIGINAL_VALUE,
                signed(settlement.getFrozenOriginalValue(), SettlementItemType.ORIGINAL_VALUE)));
        items.add(new SettlementItem(settlement, SettlementItemType.IMPAIRMENT,
                signed(settlement.getFrozenImpairmentAmount(), SettlementItemType.IMPAIRMENT)));
        items.add(new SettlementItem(settlement, SettlementItemType.RESIDUAL_VALUE,
                signed(settlement.getFrozenResidualValue(),
                        SettlementItemType.RESIDUAL_VALUE)));
        items.add(new SettlementItem(settlement, SettlementItemType.DISPOSAL_INCOME,
                signed(settlement.getDisposalIncome(), SettlementItemType.DISPOSAL_INCOME)));
        items.add(new SettlementItem(settlement, SettlementItemType.DISPOSAL_COST,
                signed(settlement.getDisposalCost(), SettlementItemType.DISPOSAL_COST)));
        items.add(new SettlementItem(settlement, SettlementItemType.NET_PROCEEDS,
                signed(settlement.getNetDisposalProceeds(), SettlementItemType.NET_PROCEEDS)));
        items.add(new SettlementItem(settlement, SettlementItemType.DIFFERENCE,
                signed(settlement.getDifferenceAmount(), SettlementItemType.DIFFERENCE)));
        return items;
    }

    private static BigDecimal signed(BigDecimal amount, SettlementItemType type) {
        BigDecimal scaled = amount.setScale(MONEY_SCALE, ROUNDING);
        return type.isSigned() ? scaled : scaled.negate();
    }

    /**
     * 由带符号差额推导方向与应收/应付：差额 ≥ 0 为盈余（应收），< 0 为缺口（应付）。
     */
    private static SettlementAmounts amountsOf(BigDecimal difference) {
        if (difference.compareTo(BigDecimal.ZERO) >= 0) {
            return new SettlementAmounts(difference, SettlementDirection.SURPLUS,
                    difference.setScale(MONEY_SCALE, ROUNDING), ZERO);
        }
        return new SettlementAmounts(difference, SettlementDirection.DEFICIT, ZERO,
                difference.abs().setScale(MONEY_SCALE, ROUNDING));
    }

    private record SettlementAmounts(BigDecimal difference, SettlementDirection direction,
                                     BigDecimal receivable, BigDecimal payable) {
    }

    private static SettlementView toView(LeasedAsset asset, ResidualSettlement settlement,
                                         List<SettlementItem> items,
                                         List<SettlementCorrection> corrections) {
        SettlementAmountsView original = new SettlementAmountsView(
                settlement.getDifferenceAmount(), settlement.getDirection(),
                settlement.getReceivableAmount(), settlement.getPayableAmount());

        SettlementAmountsView effective;
        List<SettlementCorrectionView> correctionViews;
        if (corrections.isEmpty()) {
            effective = original;
            correctionViews = List.of();
        } else {
            SettlementCorrection latest = corrections.get(corrections.size() - 1);
            effective = new SettlementAmountsView(latest.getEffectiveDifference(),
                    latest.getEffectiveDirection(), latest.getEffectiveReceivable(),
                    latest.getEffectivePayable());
            correctionViews = corrections.stream()
                    .map(c -> new SettlementCorrectionView(c.getCorrectionNo(), c.getSeqNo(),
                            c.getCorrectionDate(), c.getAdjustmentAmount(), c.getReason(),
                            c.getEffectiveDifference(), c.getEffectiveDirection(),
                            c.getEffectiveReceivable(), c.getEffectivePayable()))
                    .toList();
        }

        List<SettlementItemView> itemViews = items.stream()
                .map(item -> new SettlementItemView(item.getLineNo(),
                        item.getItemType().name(), item.getItemName(), item.getAmount()))
                .toList();

        SettlementView.Basis basis = new SettlementView.Basis(
                new FrozenValuationView(settlement.getValuationNo(),
                        settlement.getValuationVersionNo(), settlement.getValuationDate(),
                        settlement.getFrozenResidualValue(),
                        settlement.getFrozenImpairmentAmount(),
                        settlement.getFrozenResidualRate(), settlement.getFrozenInstitution()),
                new DisposalView(settlement.getDisposalDate(), settlement.getDisposalIncome(),
                        settlement.getDisposalCost(), settlement.getNetDisposalProceeds()),
                new FrozenContractRuleView(settlement.getFrozenOriginalValue(),
                        settlement.getFrozenFinancingAmount(),
                        settlement.getFrozenNominalAnnualRate(),
                        settlement.getFrozenTermMonths(),
                        settlement.getFrozenRepaymentMethod().name()));

        return new SettlementView(settlement.getSettlementNo(), asset.getAssetCode(),
                asset.getStatus() == null ? AssetStatus.IN_SERVICE : asset.getStatus(),
                settlement.getSettlementDate(), basis, original, effective, itemViews,
                correctionViews);
    }
}
