package com.leaseflow.assessment;

import com.leaseflow.assessment.dto.AssessmentRegistration;
import com.leaseflow.assessment.dto.AssessmentView;
import com.leaseflow.assessment.dto.RegisterAssessmentRequest;
import com.leaseflow.asset.LeasedAsset;
import com.leaseflow.asset.LeasedAssetRepository;
import com.leaseflow.common.exception.BusinessRuleViolationException;
import com.leaseflow.common.exception.DuplicateResourceException;
import com.leaseflow.common.exception.ResourceNotFoundException;
import com.leaseflow.common.exception.VersionConflictException;
import com.leaseflow.contract.LeaseContract;
import com.leaseflow.contract.LeaseContractRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

@Service
public class ResidualAssessmentService {

    /** 残值率保留 4 位小数（HALF_UP），减值金额保留 2 位小数（HALF_UP）。 */
    static final int RATE_SCALE = 4;
    static final int MONEY_SCALE = 2;

    private final LeasedAssetRepository assetRepository;
    private final LeaseContractRepository contractRepository;
    private final ResidualAssessmentRepository assessmentRepository;

    public ResidualAssessmentService(LeasedAssetRepository assetRepository,
                                     LeaseContractRepository contractRepository,
                                     ResidualAssessmentRepository assessmentRepository) {
        this.assetRepository = assetRepository;
        this.contractRepository = contractRepository;
        this.assessmentRepository = assessmentRepository;
    }

    /**
     * 登记一次残值评估，在租赁物的不可变版本链末尾追加新版本。
     *
     * <p>同一租赁物的并发登记通过对租赁物行加悲观写锁串行化，(asset_id, version)
     * 唯一约束兜底；评估编号全局唯一，内容完全一致的重复提交返回首次结果（幂等），
     * 编号复用但内容不一致返回 409。整个登记在单一事务内完成，失败时整体回滚，
     * 不留下跳号版本或不完整记录。
     */
    @Transactional
    public AssessmentRegistration register(String assetCode, RegisterAssessmentRequest request) {
        LeasedAsset asset = assetRepository.findByAssetCodeForUpdate(assetCode)
                .orElseThrow(() -> new ResourceNotFoundException("租赁物不存在: " + assetCode));

        ResidualAssessment latest = assessmentRepository
                .findTopByAssetIdOrderByVersionDesc(asset.getId()).orElse(null);
        int currentVersion = latest == null ? 0 : latest.getVersion();

        Optional<ResidualAssessment> existing =
                assessmentRepository.findByAssessmentNo(request.assessmentNo());
        if (existing.isPresent()) {
            ResidualAssessment assessment = existing.get();
            if (assessment.getAsset().getId().equals(asset.getId())
                    && assessment.getAssessmentDate().equals(request.assessmentDate())
                    && assessment.getAssessedValue().compareTo(request.assessedValue()) == 0
                    && assessment.getAppraiser().equals(request.appraiser())) {
                return new AssessmentRegistration(
                        toView(assessment, assessment.getVersion() == currentVersion), false);
            }
            throw new DuplicateResourceException(
                    "评估编号已存在且内容不一致: " + request.assessmentNo());
        }

        if (request.baseVersion() != currentVersion) {
            throw new VersionConflictException(
                    "评估基准版本已过期: 客户端版本 %d，当前最新版本 %d"
                            .formatted(request.baseVersion(), currentVersion));
        }
        if (latest != null && !request.assessmentDate().isAfter(latest.getAssessmentDate())) {
            throw new BusinessRuleViolationException(
                    "评估日期必须晚于当前最新评估日期: " + latest.getAssessmentDate());
        }
        LeaseContract contract = contractRepository.findByAssetId(asset.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "租赁物未关联租赁合同: " + assetCode));
        if (request.assessmentDate().isBefore(contract.getStartDate())) {
            throw new BusinessRuleViolationException(
                    "评估日期不得早于合同起租日: " + contract.getStartDate());
        }
        BigDecimal originalValue = asset.getOriginalValue();
        BigDecimal assessedValue = request.assessedValue();
        if (assessedValue.signum() < 0 || assessedValue.compareTo(originalValue) > 0) {
            throw new BusinessRuleViolationException(
                    "评估价值必须处于 0 到资产原值之间，资产原值: " + originalValue);
        }

        BigDecimal impairmentAmount = originalValue.subtract(assessedValue)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal residualRate = assessedValue
                .divide(originalValue, RATE_SCALE, RoundingMode.HALF_UP);

        ResidualAssessment assessment = new ResidualAssessment(request.assessmentNo(), asset,
                currentVersion + 1, request.assessmentDate(),
                assessedValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP), request.appraiser(),
                impairmentAmount, residualRate);
        try {
            assessmentRepository.saveAndFlush(assessment);
        } catch (DataIntegrityViolationException ex) {
            throw new VersionConflictException(
                    "评估编号或版本并发冲突，请基于最新版本重新提交: " + request.assessmentNo());
        }
        return new AssessmentRegistration(toView(assessment, true), true);
    }

    /**
     * 查询租赁物的评估历史，按版本号升序稳定排序，并标明最新记录。
     */
    @Transactional(readOnly = true)
    public List<AssessmentView> history(String assetCode) {
        LeasedAsset asset = assetRepository.findByAssetCode(assetCode)
                .orElseThrow(() -> new ResourceNotFoundException("租赁物不存在: " + assetCode));
        List<ResidualAssessment> assessments =
                assessmentRepository.findByAssetIdOrderByVersionAsc(asset.getId());
        int latestVersion = assessments.isEmpty()
                ? 0 : assessments.get(assessments.size() - 1).getVersion();
        return assessments.stream()
                .map(assessment -> toView(assessment, assessment.getVersion() == latestVersion))
                .toList();
    }

    /**
     * 查询租赁物当前最新评估版本。
     */
    @Transactional(readOnly = true)
    public AssessmentView current(String assetCode) {
        LeasedAsset asset = assetRepository.findByAssetCode(assetCode)
                .orElseThrow(() -> new ResourceNotFoundException("租赁物不存在: " + assetCode));
        ResidualAssessment latest = assessmentRepository
                .findTopByAssetIdOrderByVersionDesc(asset.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "租赁物暂无评估记录: " + assetCode));
        return toView(latest, true);
    }

    private AssessmentView toView(ResidualAssessment assessment, boolean latest) {
        return new AssessmentView(assessment.getAssessmentNo(),
                assessment.getAsset().getAssetCode(), assessment.getVersion(),
                assessment.getAssessmentDate(), assessment.getAssessedValue(),
                assessment.getAppraiser(), assessment.getImpairmentAmount(),
                assessment.getResidualRate(), latest);
    }
}
