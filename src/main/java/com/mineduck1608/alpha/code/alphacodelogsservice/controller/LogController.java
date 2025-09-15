package com.mineduck1608.alpha.code.alphacodelogsservice.controller;

import com.mineduck1608.alpha.code.alphacodelogsservice.model.RobotLog;
import com.mineduck1608.alpha.code.alphacodelogsservice.service.LogRetry;
import com.mineduck1608.alpha.code.alphacodelogsservice.service.LogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/logs")
@Tag(name = "Log Management", description = "APIs for managing robot logs")
public class LogController {

    private static final Logger logger = LoggerFactory.getLogger(LogController.class);
    private final LogService logService;
    private final LogRetry logRetry;

    public LogController(LogService logService, LogRetry logRetry) {
        this.logService = logService;
        this.logRetry = logRetry;
    }

    @Operation(summary = "Submit a robot log", description = "Accepts and queues a robot log for processing")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Log accepted successfully"),
        @ApiResponse(responseCode = "503", description = "Queue is full, log rejected")
    })
    @PostMapping
    public ResponseEntity<String> receiveLog(@RequestBody RobotLog log) {
        logger.info("Received log request for robot: {}, level: {}, message: {}",
                   log.getRobotId(), log.getLevel(), log.getMessage());

        boolean accepted = logService.enqueue(log);
        if (accepted) {
            logger.info("Log accepted and queued for robot: {}", log.getRobotId());
            return ResponseEntity.ok("Log accepted");
        } else {
            logger.warn("Queue full, rejecting log for robot: {}", log.getRobotId());
            return ResponseEntity.status(503).body("Queue full, log rejected");
        }
    }

    @Operation(summary = "Retry failed logs", description = "Retries processing of previously failed logs")
    @ApiResponse(responseCode = "200", description = "Failed logs retried successfully")
    @PostMapping("/retry-failed")
    public ResponseEntity<String> retryFailed() {
        int retried = logRetry.retryFailedLogs();
        return ResponseEntity.ok("Retried " + retried + " failed logs");
    }
}
