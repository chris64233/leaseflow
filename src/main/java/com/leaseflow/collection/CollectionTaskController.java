package com.leaseflow.collection;

import com.leaseflow.collection.dto.CollectionTaskView;
import com.leaseflow.collection.dto.OverdueScanRequest;
import com.leaseflow.collection.dto.OverdueScanResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/collection-tasks")
public class CollectionTaskController {

    private final CollectionTaskService collectionTaskService;

    public CollectionTaskController(CollectionTaskService collectionTaskService) {
        this.collectionTaskService = collectionTaskService;
    }

    @PostMapping("/scan")
    public OverdueScanResponse scanOverdue(@Valid @RequestBody OverdueScanRequest request) {
        return collectionTaskService.scanOverdue(request.businessDate());
    }

    @GetMapping
    public List<CollectionTaskView> queryTasks(
            @RequestParam(name = "contractNo", required = false) String contractNo,
            @RequestParam(name = "status", required = false) CollectionTaskStatus status) {
        return collectionTaskService.queryTasks(contractNo, status);
    }
}
