package com.leaseflow.payment;

import com.leaseflow.payment.dto.PaymentRegistrationResponse;
import com.leaseflow.payment.dto.RegisterPaymentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leases/{contractNo}/schedule")
public class RentPaymentController {

    private final RentPaymentService rentPaymentService;

    public RentPaymentController(RentPaymentService rentPaymentService) {
        this.rentPaymentService = rentPaymentService;
    }

    @PostMapping("/{periodNo}/payments")
    public ResponseEntity<PaymentRegistrationResponse> registerPayment(
            @PathVariable String contractNo, @PathVariable int periodNo,
            @Valid @RequestBody RegisterPaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(rentPaymentService.registerPayment(contractNo, periodNo, request));
    }
}
