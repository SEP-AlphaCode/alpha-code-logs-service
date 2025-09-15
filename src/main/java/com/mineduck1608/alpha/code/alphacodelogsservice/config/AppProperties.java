package com.mineduck1608.alpha.code.alphacodelogsservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "logproxy")
public class AppProperties {
    private String lokiUrl;
    private int queueSize;
    private int workerCount;
    private int maxRetries;
    private int baseDelayMs;
    private String failedLogFile;

    public String getLokiUrl() { return lokiUrl; }
    public void setLokiUrl(String lokiUrl) { this.lokiUrl = lokiUrl; }

    public int getQueueSize() { return queueSize; }
    public void setQueueSize(int queueSize) { this.queueSize = queueSize; }

    public int getWorkerCount() { return workerCount; }
    public void setWorkerCount(int workerCount) { this.workerCount = workerCount; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public int getBaseDelayMs() { return baseDelayMs; }
    public void setBaseDelayMs(int baseDelayMs) { this.baseDelayMs = baseDelayMs; }

    public String getFailedLogFile() { return failedLogFile; }
    public void setFailedLogFile(String failedLogFile) { this.failedLogFile = failedLogFile; }
}
