package com.leaseflow.valuation;

import com.leaseflow.asset.AssetStatus;
import com.leaseflow.asset.LeasedAsset;
import com.leaseflow.asset.LeasedAssetRepository;
import com.leaseflow.common.exception.BusinessRuleViolationException;
import com.leaseflow.common.exception.DuplicateResourceException;
import com.leaseflow.common.exception.ResourceNotFoundException;
import com.leaseflow.common.exception.VersionConflictException;
import com.leaseflow.contract.LeaseContract;
import com.leaseflow.contract.LeaseContractRepository;
import com.leaseflow.valuation.dto.RegisterValuationRequest;
import com.leaseflow.valuation.dto.ValuationHistoryResponse;
import com.leaseflow.valuation.dto.ValuationRegistrationResult;
import com.leaseflow.valuation.dto.ValuationView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class AssetValuationService {

    /** 金额精度：元，2 位小数。 */
    public static final int MONEY_SCALE = 2;
    /** 残值率精度：6 位小数。 */
    public static final int RATE_SCALE = 6;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final LeasedAssetRepository assetRepository;
    private final LeaseContractRepository contractRepository;
    private final AssetValuationRepository valuationRepository;

    public AssetValuationService(LeasedAssetRepository assetRepository,
                                 LeaseContractRepository contractRepository,
                                 AssetValuationRepository valuationRepository) {
        this.assetRepository = assetRepository;
        this.contractRepository = contractRepository;
        this.valuationRepository = valuationRepository;
    }

    /**
     * 登记新的残值评估版本。
     *
     * <p>事务内先对租赁物行加悲观写锁，串行化同一资产的并发登记，随后执行：
     * 编号幂等/冲突判定 → 业务校验（日期不早于起租日、价值在 0 与原值之间）
     * → 客户端版本匹配与评估日期严格晚于最新版本校验 → 计算减值金额与残值率 → 落库。
     * 任一步骤失败整体回滚，不留下跳号版本或不完整记录。
     */
    @Transactional
    public ValuationRegistrationResult register(String assetCode, RegisterValuationRequest request) {
        LeasedAsset asset = assetRepository.findByAssetCode(assetCode)
                .orElseThrow(() -> new ResourceNotFoundException("租赁物不存在: " + assetCode));
        // 锁定资产行：同一资产的并发评估在此串行，后到者将基于新版本重新判定。
        asset = assetRepository.findByIdForUpdate(asset.getId())
                .orElseThrow(() -> new ResourceNotFoundException("租赁物不存在: " + assetCode));
        LeaseContract contract = contractRepository.findByAssetId(asset.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "租赁物尚未关联租赁合同: " + assetCode));

        String valuationNo = request.valuationNo().trim();
        String institution = request.institution().trim();
        BigDecimal residualValue = request.residualValue().setScale(MONEY_SCALE, ROUNDING);
        AssetValuation latest = valuationRepository
                .findTopByAssetIdOrderByVersionNoDesc(asset.getId())
                .orElse(null);

        // 编号全局唯一：相同编号、相同内容的重复提交返回首次结果；编号复用且内容不一致返回 409。
        AssetValuation existing = valuationRepository.findByValuationNo(valuationNo).orElse(null);
        if (existing != null) {
            if (sameContent(existing, asset, request.valuationDate(), residualValue, institution)) {
                return new ValuationRegistrationResult(toView(existing, latest), true);
            }
            throw new DuplicateResourceException("评估编号已被使用且内容不一致: " + valuationNo);
        }

        // 结算成功后资产为 SETTLED：不再接受普通评估，修正只能走结算更正流程。
        // 已结算资产的历史评估编号仍可幂等重放（上方分支），这里只拦截“新增”。
        if (asset.getStatus() == AssetStatus.SETTLED) {
            throw new BusinessRuleViolationException(
                    "资产已完成残值结算，不再接受普通评估；如需修正请走结算更正流程: "
                            + assetCode);
        }

        if (request.valuationDate().isBefore(contract.getStartDate())) {
            throw new BusinessRuleViolationException(
                    "评估日期不得早于合同起租日: " + contract.getStartDate());
        }
        if (residualValue.compareTo(asset.getOriginalValue()) > 0) {
            throw new BusinessRuleViolationException(
                    "评估价值不得超过资产原值: " + asset.getOriginalValue());
        }

        int currentVersion = latest == null ? 0 : latest.getVersionNo();
        if (request.expectedVersion() != currentVersion) {
            throw new VersionConflictException(
                    "评估版本已过期，请基于最新版本 %d 重新提交".formatted(currentVersion));
        }
        if (latest != null && !request.valuationDate().isAfter(latest.getValuationDate())) {
            throw new VersionConflictException(
                    "评估日期必须晚于当前最新评估日期: " + latest.getValuationDate());
        }

        BigDecimal impairmentAmount = asset.getOriginalValue().subtract(residualValue)
                .setScale(MONEY_SCALE, ROUNDING);
        BigDecimal residualRate = residualValue.divide(asset.getOriginalValue(),
                RATE_SCALE, ROUNDING);

        AssetValuation valuation = new AssetValuation(valuationNo, asset, currentVersion + 1,
                request.valuationDate(), residualValue, impairmentAmount, residualRate,
                institution);
        try {
            valuationRepository.saveAndFlush(valuation);
        } catch (DataIntegrityViolationException ex) {
            // 并发下唯一约束兜底（评估编号复用或同资产版本号竞争），整笔事务回滚。
            throw new DuplicateResourceException("评估编号已存在或评估版本冲突: " + valuationNo);
        }

        return new ValuationRegistrationResult(toView(valuation, valuation), false);
    }

    /**
     * 查询资产评估历史，版本号升序稳定排列，并标明最新版本；资产不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ValuationHistoryResponse getHistory(String assetCode) {
        LeasedAsset asset = assetRepository.findByAssetCode(assetCode)
                .orElseThrow(() -> new ResourceNotFoundException("租赁物不存在: " + assetCode));
        return toHistory(asset);
    }

    /**
     * 查询当前最新评估版本；尚无评估返回 404。
     */
    @Transactional(readOnly = true)
    public ValuationView getCurrent(String assetCode) {
        LeasedAsset asset = assetRepository.findByAssetCode(assetCode)
                .orElseThrow(() -> new ResourceNotFoundException("租赁物不存在: " + assetCode));
        AssetValuation latest = valuationRepository
                .findTopByAssetIdOrderByVersionNoDesc(asset.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "资产尚无评估记录: " + assetCode));
        return toView(latest, latest);
    }

    private ValuationHistoryResponse toHistory(LeasedAsset asset) {
        List<AssetValuation> valuations =
                valuationRepository.findByAssetIdOrderByVersionNoAscIdAsc(asset.getId());
        int latestVersion = valuations.isEmpty() ? 0
                : valuations.get(valuations.size() - 1).getVersionNo();
        List<ValuationView> views = valuations.stream()
                .map(valuation -> toView(valuation, latestVersion))
                .toList();
        return new ValuationHistoryResponse(asset.getAssetCode(), latestVersion, views);
    }

    private static boolean sameContent(AssetValuation existing, LeasedAsset asset,
                                       java.time.LocalDate valuationDate,
                                       BigDecimal residualValue, String institution) {
        return existing.getAsset().getId().equals(asset.getId())
                && existing.getValuationDate().equals(valuationDate)
                && existing.getResidualValue().compareTo(residualValue) == 0
                && existing.getInstitution().equals(institution);
    }

    private static ValuationView toView(AssetValuation valuation, AssetValuation latest) {
        return toView(valuation, latest == null ? 0 : latest.getVersionNo());
    }

    private static ValuationView toView(AssetValuation valuation, int latestVersionNo) {
        return new ValuationView(valuation.getValuationNo(),
                valuation.getAsset().getAssetCode(), valuation.getVersionNo(),
                valuation.getValuationDate(), valuation.getResidualValue(),
                valuation.getImpairmentAmount(), valuation.getResidualRate(),
                valuation.getInstitution(),
                valuation.getVersionNo() == latestVersionNo);
    }
}
