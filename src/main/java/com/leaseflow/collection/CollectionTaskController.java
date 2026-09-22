package com.leaseflow.collection;

import com.leaseflow.collection.dto.CollectionTaskView;
import com.leaseflow.collection.dto.OverdueScanResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/collection-tasks")
public class CollectionTaskController {

    private final CollectionTaskService collectionTaskService;

    public CollectionTaskController(CollectionTaskService collectionTaskService) {
        this.collectionTaskService = collectionTaskService;
    }

    @PostMapping("/scan")
    public OverdueScanResponse scan(
            @RequestParam("businessDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate businessDate) {
        return collectionTaskService.scanOverdue(businessDate);
    }

    @GetMapping
    public List<CollectionTaskView> query(
            @RequestParam(name = "contractNo", required = false) String contractNo,
            @RequestParam(name = "status", required = false) String status) {
        return collectionTaskService.queryTasks(contractNo, status);
    }
}
