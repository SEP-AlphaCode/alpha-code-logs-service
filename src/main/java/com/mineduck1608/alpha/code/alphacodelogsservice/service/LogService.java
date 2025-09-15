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

import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.*;

@Service
public class LogService {
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
        for (int i = 0; i < props.getMaxRetries(); i++) {
            try {
                String lokiPayload = buildLokiPayload(log);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                HttpEntity<String> request = new HttpEntity<>(lokiPayload, headers);

                restTemplate.postForEntity(props.getLokiUrl(), request, String.class);
                return true;
            } catch (Exception e) {
                e.printStackTrace(); // để log ra lỗi
                try {
                    Thread.sleep(props.getBaseDelayMs() * (i + 1));
                } catch (InterruptedException ignored) {}
            }
        }
        return false;
    }


    private String buildLokiPayload(RobotLog log) throws IOException {
        long tsMillis = log.getTimestamp() > 0 ? log.getTimestamp() : System.currentTimeMillis();
        String ts = String.valueOf(tsMillis * 1_000_000); // ns
        String msg = log.getMessage().replace("\"", "'");
        return """
        {
          "streams": [
            {
              "stream": { "robot": "%s", "level": "%s" },
              "values": [ [ "%s", "%s" ] ]
            }
          ]
        }
        """.formatted(log.getRobotId(), log.getLevel(), ts, msg);
    }

    private void saveToFailedFile(RobotLog log) {
        try (FileWriter fw = new FileWriter(props.getFailedLogFile(), true)) {
            fw.write(mapper.writeValueAsString(log) + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (workers != null) {
            workers.shutdownNow();
        }
    }
}
