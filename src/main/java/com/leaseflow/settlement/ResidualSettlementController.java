package com.leaseflow.settlement;

import com.leaseflow.settlement.dto.CorrectSettlementRequest;
import com.leaseflow.settlement.dto.CreateSettlementRequest;
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
 * 残值结算接口。
 *
 * <ul>
 *   <li>{@code POST /api/assets/{assetCode}/residual-settlements}：确认结算（幂等）；</li>
 *   <li>{@code POST /api/assets/{assetCode}/residual-settlements/corrections}：
 *       结算后只追加的更正（幂等）；</li>
 *   <li>{@code GET /api/assets/{assetCode}/residual-settlements/current}：
 *       按资产查询完整计算依据；</li>
 *   <li>{@code GET /api/residual-settlements/{settlementNo}}：按结算编号查询完整计算依据。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ResidualSettlementController {

    private final ResidualSettlementService settlementService;

    public ResidualSettlementController(ResidualSettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /**
     * 确认残值结算：首次 201；相同编号、相同内容重复提交幂等返回首次结果（200）；
     * 评估版本过期或并发新评估抢先返回 409，资产已结算返回 400。
     */
    @PostMapping("/assets/{assetCode}/residual-settlements")
    public ResponseEntity<SettlementView> confirm(
            @PathVariable String assetCode,
            @Valid @RequestBody CreateSettlementRequest request) {
        ResidualSettlementService.SettlementResult result =
                settlementService.confirm(assetCode, request);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.settlement());
    }

    /**
     * 追加结算更正：首次 201；相同更正编号、相同内容重放幂等返回（200）；
     * 更正编号复用且内容不一致返回 409，资产未结算返回 400。
     */
    @PostMapping("/assets/{assetCode}/residual-settlements/corrections")
    public ResponseEntity<SettlementView> correct(
            @PathVariable String assetCode,
            @Valid @RequestBody CorrectSettlementRequest request) {
        ResidualSettlementService.SettlementResult result =
                settlementService.correct(assetCode, request);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.settlement());
    }

    /** 按资产查询结算完整计算依据；资产不存在 404，资产未结算 404。 */
    @GetMapping("/assets/{assetCode}/residual-settlements/current")
    public SettlementView getByAsset(@PathVariable String assetCode) {
        return settlementService.getByAsset(assetCode);
    }

    /** 按结算编号查询结算完整计算依据；不存在 404。 */
    @GetMapping("/residual-settlements/{settlementNo}")
    public SettlementView getByNo(@PathVariable String settlementNo) {
        return settlementService.getByNo(settlementNo);
    }
}
