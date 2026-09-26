package com.leaseflow.assessment;

import com.leaseflow.assessment.dto.AssessmentRegistration;
import com.leaseflow.assessment.dto.AssessmentView;
import com.leaseflow.assessment.dto.RegisterAssessmentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/assets/{assetCode}/assessments")
public class ResidualAssessmentController {

    private final ResidualAssessmentService assessmentService;

    public ResidualAssessmentController(ResidualAssessmentService assessmentService) {
        this.assessmentService = assessmentService;
    }

    @PostMapping
    public ResponseEntity<AssessmentView> register(
            @PathVariable String assetCode,
            @Valid @RequestBody RegisterAssessmentRequest request) {
        AssessmentRegistration result = assessmentService.register(assetCode, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.assessment());
    }

    @GetMapping
    public List<AssessmentView> history(@PathVariable String assetCode) {
        return assessmentService.history(assetCode);
    }

    @GetMapping("/current")
    public AssessmentView current(@PathVariable String assetCode) {
        return assessmentService.current(assetCode);
    }
}
