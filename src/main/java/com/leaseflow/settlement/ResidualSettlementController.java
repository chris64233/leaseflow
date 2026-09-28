package com.leaseflow.settlement;

import com.leaseflow.settlement.dto.CorrectSettlementRequest;
import com.leaseflow.settlement.dto.CreateSettlementRequest;
import com.leaseflow.settlement.dto.SettlementRegistrationResult;
import com.leaseflow.settlement.dto.SettlementView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 残值结算接口：
 *
 * <ul>
 *   <li>POST   /api/assets/{assetCode}/residual-settlement 确认结算；</li>
 *   <li>GET    /api/assets/{assetCode}/residual-settlement 查询结算单与完整计算依据；</li>
 *   <li>POST   /api/assets/{assetCode}/residual-settlement/corrections 追加结算更正。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/assets/{assetCode}/residual-settlement")
public class ResidualSettlementController {

    private final ResidualSettlementService settlementService;

    public ResidualSettlementController(ResidualSettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /**
     * 确认残值结算：首次成功返回 201；相同编号、相同内容的重复提交幂等返回首次结果（200）；
     * 客户端评估版本过期（并发新评估抢先）返回 409；资产已结算且请求不构成幂等重试返回 409。
     */
    @PostMapping
    public ResponseEntity<SettlementView> confirm(
            @PathVariable String assetCode,
            @Valid @RequestBody CreateSettlementRequest request) {
        SettlementRegistrationResult result = settlementService.confirm(assetCode, request);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.settlement());
    }

    @GetMapping
    public SettlementView get(@PathVariable String assetCode) {
        return settlementService.get(assetCode);
    }

    /**
     * 追加结算更正：首次返回 201，相同编号、相同内容的重复提交幂等返回首次结果（200），
     * 编号复用且内容不一致返回 409。更正不覆盖结算冻结的原始依据。
     */
    @PostMapping("/corrections")
    public ResponseEntity<SettlementView> correct(
            @PathVariable String assetCode,
            @Valid @RequestBody CorrectSettlementRequest request) {
        SettlementRegistrationResult result = settlementService.correct(assetCode, request);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.settlement());
    }
}
