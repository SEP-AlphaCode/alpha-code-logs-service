package com.mineduck1608.alpha.code.alphacodelogsservice.model;

import lombok.Data;

import java.util.UUID;

@Data
public class RobotLog {
    private String robotId;
    private UUID organizationId; // UUID format
    private String level;     // INFO, WARN, ERROR
    private String message;
    private long timestamp;
}