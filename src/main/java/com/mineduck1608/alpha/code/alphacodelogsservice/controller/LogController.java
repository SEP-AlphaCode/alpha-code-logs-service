package com.mineduck1608.alpha.code.alphacodelogsservice.controller;

import com.mineduck1608.alpha.code.alphacodelogsservice.model.RobotLog;
import com.mineduck1608.alpha.code.alphacodelogsservice.service.LogRetry;
import com.mineduck1608.alpha.code.alphacodelogsservice.service.LogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/logs")
public class LogController {

    private final LogService logService;
    private final LogRetry logRetry;

    public LogController(LogService logService, LogRetry logRetry) {
        this.logService = logService;
        this.logRetry = logRetry;
    }

    @PostMapping
    public ResponseEntity<String> receiveLog(@RequestBody RobotLog log) {
        boolean accepted = logService.enqueue(log);
        if (accepted) {
            return ResponseEntity.ok("Log accepted");
        } else {
            return ResponseEntity.status(503).body("Queue full, log rejected");
        }
    }

    @PostMapping("/retry-failed")
    public ResponseEntity<String> retryFailed() {
        int retried = logRetry.retryFailedLogs();
        return ResponseEntity.ok("Retried " + retried + " failed logs");
    }
}
