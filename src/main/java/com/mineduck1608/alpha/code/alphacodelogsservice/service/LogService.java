package com.mineduck1608.alpha.code.alphacodelogsservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineduck1608.alpha.code.alphacodelogsservice.config.AppProperties;
import com.mineduck1608.alpha.code.alphacodelogsservice.model.RobotLog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.*;

@Service
public class LogService {
    private static final Logger logger = LoggerFactory.getLogger(LogService.class);
    private final BlockingQueue<RobotLog> queue;
    private final AppProperties props;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private ExecutorService workers;

    public LogService(AppProperties props) {
        this.props = props;
        this.queue = new LinkedBlockingQueue<>(props.getQueueSize());
    }

    public boolean enqueue(RobotLog log) {
        return queue.offer(log);
    }

    @PostConstruct
    public void startWorkers() {
        workers = Executors.newFixedThreadPool(props.getWorkerCount());
        for (int i = 0; i < props.getWorkerCount(); i++) {
            workers.submit(this::workerLoop);
        }
    }

    private void workerLoop() {
        while (true) {
            try {
                RobotLog log = queue.take();
                boolean success = sendToLokiWithRetry(log);
                if (!success) {
                    saveToFailedFile(log);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public boolean sendToLokiWithRetry(RobotLog log) {
        logger.info("Attempting to send log to Loki for robot: {}, level: {}", log.getRobotId(), log.getLevel());

        for (int i = 0; i < props.getMaxRetries(); i++) {
            try {
                String lokiPayload = buildLokiPayload(log);
                logger.debug("Loki payload: {}", lokiPayload);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                HttpEntity<String> request = new HttpEntity<>(lokiPayload, headers);

                var response = restTemplate.postForEntity(props.getLokiUrl(), request, String.class);
                logger.info("Successfully sent log to Loki. Response status: {}", response.getStatusCode());
                return true;
            } catch (Exception e) {
                logger.error("Failed to send log to Loki (attempt {}/{}): {}", i + 1, props.getMaxRetries(), e.getMessage(), e);
                try {
                    Thread.sleep(props.getBaseDelayMs() * (i + 1));
                } catch (InterruptedException ignored) {}
            }
        }
        logger.error("Failed to send log to Loki after {} retries. Saving to failed file.", props.getMaxRetries());
        return false;
    }


    private String buildLokiPayload(RobotLog log) throws IOException {
        // Loki expects nanoseconds timestamp
        long tsNanos = System.currentTimeMillis() * 1_000_000L;
        String msg = log.getMessage().replace("\"", "\\\"").replace("\n", "\\n");

        String payload = """
        {
          "streams": [
            {
              "stream": { "robot": "%s", "level": "%s" },
              "values": [ [ "%d", "%s" ] ]
            }
          ]
        }
        """.formatted(log.getRobotId(), log.getLevel(), tsNanos, msg);

        logger.debug("Built Loki payload for robot {}: {}", log.getRobotId(), payload);
        return payload;
    }

    private void saveToFailedFile(RobotLog log) {
        try (FileWriter fw = new FileWriter(props.getFailedLogFile(), true)) {
            String logLine = mapper.writeValueAsString(log);
            fw.write(logLine + "\n");
            logger.warn("Saved failed log to file: {}", logLine);
        } catch (IOException e) {
            logger.error("Failed to save log to failed file: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (workers != null) {
            workers.shutdownNow();
        }
    }
}
