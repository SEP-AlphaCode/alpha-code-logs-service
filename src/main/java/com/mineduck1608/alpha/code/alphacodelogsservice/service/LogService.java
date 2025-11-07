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
public class LogService {
    private static final Logger logger = LoggerFactory.getLogger(LogService.class);

    private final BlockingQueue<RobotLog> queue;
    private final AppProperties props;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private ExecutorService workers;
    private final SubmissionGrpcClient submissionGrpcClient;

    // Bộ nhớ lưu tạm log cho các submission
    private final ConcurrentMap<String, CopyOnWriteArrayList<RobotLog>> inMemoryLogs = new ConcurrentHashMap<>();

    public LogService(AppProperties props) {
        this.props = props;
        this.queue = new LinkedBlockingQueue<>(props.getQueueSize());
        this.submissionGrpcClient = new SubmissionGrpcClient();
    }

    /** Nhận log từ robot */
    public boolean enqueue(RobotLog log) {
        // Chỉ lưu log liên quan đến submission
        if (shouldStoreInMemory(log)) {
            inMemoryLogs.computeIfAbsent(log.getRobotId(), k -> new CopyOnWriteArrayList<>()).add(log);
            logger.debug("Stored in memory log for robot {} tag {}", log.getRobotId(), log.getTag());
        }

        // Nếu là log kết thúc submission → gửi toàn bộ qua gRPC
        if ("submission_end".equalsIgnoreCase(log.getTag())) {
            handleSubmissionEnd(log);
        }

        // Dù sao vẫn đẩy log sang Loki
        return queue.offer(log);
    }

    private boolean shouldStoreInMemory(RobotLog log) {
        return "submission".equalsIgnoreCase(log.getTag())
                || "submission_start".equalsIgnoreCase(log.getTag())
                || "submission_end".equalsIgnoreCase(log.getTag());
    }

    private void handleSubmissionEnd(RobotLog log) {
        List<RobotLog> logs = inMemoryLogs.getOrDefault(log.getRobotId(), new CopyOnWriteArrayList<>());
        if (logs.isEmpty()) {
            logger.warn("No logs found for robot {} when submission_end received", log.getRobotId());
            return;
        }

        try {
            String logJson = mapper.writeValueAsString(logs);
            String accountLessonId = log.getAccountLessonId().toString(); // bạn có thể tùy chỉnh parse message
            boolean ok = submissionGrpcClient.submitLogs(log.getRobotId(), accountLessonId, logJson);

            if (ok) {
                logger.info("Submission logs for robot {} sent successfully", log.getRobotId());
            } else {
                logger.error("Submission logs for robot {} failed to send", log.getRobotId());
            }
        } catch (Exception e) {
            logger.error("Error processing submission_end for {}: {}", log.getRobotId(), e.getMessage(), e);
        } finally {
            // Dọn bộ nhớ
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
            String logLine = mapper.writeValueAsString(log);
            fw.write(logLine + "\n");
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
