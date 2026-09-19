package com.leaseflow.lease.controller;

import com.leaseflow.lease.dto.CreateLeaseRequest;
import com.leaseflow.lease.dto.LeaseDetailResponse;
import com.leaseflow.lease.service.LeaseService;
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
@RequestMapping("/api/leases")
public class LeaseController {

    private final LeaseService leaseService;

    public LeaseController(LeaseService leaseService) {
        this.leaseService = leaseService;
    }

    @PostMapping
    public ResponseEntity<LeaseDetailResponse> create(@Valid @RequestBody CreateLeaseRequest request) {
        LeaseDetailResponse response = leaseService.createLease(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{contractNo}")
    public LeaseDetailResponse getByContractNo(@PathVariable String contractNo) {
        return leaseService.getByContractNo(contractNo);
    }
}
