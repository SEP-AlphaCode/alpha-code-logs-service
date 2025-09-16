package com.mineduck1608.alpha.code.alphacodelogsservice.model;

import lombok.Data;

import java.util.UUID;

@Data
public class RobotLog {
    private String robotId;
    private String level;  // INFO, WARN, ERROR
    private String tag;
    private String message;
    private long timestamp;
}