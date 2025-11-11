package com.mineduck1608.alpha.code.alphacodelogsservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineduck1608.alpha.code.alphacodelogsservice.config.AppProperties;
import com.mineduck1608.alpha.code.alphacodelogsservice.grpc.client.SubmissionGrpcClient;
import com.mineduck1608.alpha.code.alphacodelogsservice.model.RobotLog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor // ✅ Spring sẽ tự tạo constructor đúng
public class LogService {

    private static final Logger logger = LoggerFactory.getLogger(LogService.class);

    private final AppProperties props;
    private final SubmissionGrpcClient submissionGrpcClient;

    private BlockingQueue<RobotLog> queue;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private ExecutorService workers;

    private final ConcurrentMap<String, CopyOnWriteArrayList<RobotLog>> inMemoryLogs = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        this.queue = new LinkedBlockingQueue<>(props.getQueueSize());
    }

    /** Nhận log từ robot */
    public boolean enqueue(RobotLog log) {
        if (shouldStoreInMemory(log)) {
            inMemoryLogs
                    .computeIfAbsent(log.getRobotId(), k -> new CopyOnWriteArrayList<>())
                    .add(log);

            logger.debug("Stored in memory log for robot {} tag {}", log.getRobotId(), log.getTag());
        }

        if ("submission_end".equalsIgnoreCase(log.getTag())) {
            handleSubmissionEnd(log);
        }

        return queue.offer(log);
    }

    private boolean shouldStoreInMemory(RobotLog log) {
        return "submission".equalsIgnoreCase(log.getTag());
    }

    private void handleSubmissionEnd(RobotLog log) {
        List<RobotLog> logs = inMemoryLogs.getOrDefault(log.getRobotId(), new CopyOnWriteArrayList<>());
        if (logs.isEmpty()) {
            logger.warn("No logs found for robot {} when submission_end received", log.getRobotId());
            return;
        }

        try {
            String logJson = mapper.writeValueAsString(logs);
            String accountLessonId = log.getAccountLessonId().toString();

            boolean ok = submissionGrpcClient.submitLogs(log.getRobotId(), accountLessonId, logJson);

            if (ok) {
                logger.info("Submission logs for robot {} sent successfully", log.getRobotId());
            } else {
                logger.error("Submission logs for robot {} failed to send", log.getRobotId());
            }
        } catch (Exception e) {
            logger.error("Error processing submission_end for {}: {}", log.getRobotId(), e.getMessage(), e);
        } finally {
            inMemoryLogs.remove(log.getRobotId());
        }
    }

    @PostConstruct
    public void startWorkers() {
        workers = Executors.newFixedThreadPool(props.getWorkerCount());
        for (int i = 0; i < props.getWorkerCount(); i++) {
            workers.submit(this::workerLoop);
        }
    }

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                RobotLog log = queue.take();
                if (!sendToLokiWithRetry(log)) {
                    saveToFailedFile(log);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean sendToLokiWithRetry(RobotLog log) {
        logger.debug("Attempting to send log to Loki for robot {} level {}", log.getRobotId(), log.getLevel());

        for (int i = 0; i < props.getMaxRetries(); i++) {
            try {
                String lokiPayload = buildLokiPayload(log);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<String> request = new HttpEntity<>(lokiPayload, headers);

                var response = restTemplate.postForEntity(props.getLokiUrl(), request, String.class);
                logger.debug("Sent log to Loki OK: {}", response.getStatusCode());
                return true;

            } catch (Exception e) {
                logger.error("Failed to send log to Loki (attempt {}/{}): {}", i + 1, props.getMaxRetries(), e.getMessage());
                try {
                    Thread.sleep(props.getBaseDelayMs() * (i + 1));
                } catch (InterruptedException ignored) {}
            }
        }
        return false;
    }

    private String buildLokiPayload(RobotLog log) {
        long tsNanos = System.currentTimeMillis() * 1_000_000L;
        String msg = log.getMessage().replace("\"", "\\\"").replace("\n", "\\n");

        return """
        {
          "streams": [
            {
              "stream": { "robot": "%s", "level": "%s", "tag": "%s" },
              "values": [ [ "%d", "%s" ] ]
            }
          ]
        }
        """.formatted(log.getRobotId(), log.getLevel(), log.getTag(), tsNanos, msg);
    }

    private void saveToFailedFile(RobotLog log) {
        try (FileWriter fw = new FileWriter(props.getFailedLogFile(), true)) {
            fw.write(mapper.writeValueAsString(log) + "\n");
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
