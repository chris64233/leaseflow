package com.leaseflow.valuation;

import com.leaseflow.valuation.dto.RegisterValuationRequest;
import com.leaseflow.valuation.dto.ValuationHistoryResponse;
import com.leaseflow.valuation.dto.ValuationRegistrationResult;
import com.leaseflow.valuation.dto.ValuationView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assets/{assetCode}/valuations")
public class AssetValuationController {

    private final AssetValuationService valuationService;

    public AssetValuationController(AssetValuationService valuationService) {
        this.valuationService = valuationService;
    }

    /**
     * 登记新评估版本：新版本返回 201；相同编号、相同内容的重复提交幂等返回首次结果（200）；
     * 版本过期、日期不晚于最新版本或编号复用且内容不一致返回 409。
     */
    @PostMapping
    public ResponseEntity<ValuationView> register(
            @PathVariable String assetCode,
            @Valid @RequestBody RegisterValuationRequest request) {
        ValuationRegistrationResult result = valuationService.register(assetCode, request);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.valuation());
    }

    @GetMapping
    public ValuationHistoryResponse getHistory(@PathVariable String assetCode) {
        return valuationService.getHistory(assetCode);
    }

    @GetMapping("/current")
    public ValuationView getCurrent(@PathVariable String assetCode) {
        return valuationService.getCurrent(assetCode);
    }
}
