package com.mineduck1608.alpha.code.alphacodelogsservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineduck1608.alpha.code.alphacodelogsservice.config.AppProperties;
import com.mineduck1608.alpha.code.alphacodelogsservice.model.RobotLog;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

@Service
public class LogRetry {
    private LogService logService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AppProperties props;

    public LogRetry(LogService logService, AppProperties props) {
        this.logService = logService;
        this.props = props;
    }

    public int retryFailedLogs() {
        File file = new File(props.getFailedLogFile());
        if (!file.exists()) return 0;

        List<String> remaining = new ArrayList<>();
        int successCount = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                try {
                    RobotLog log = mapper.readValue(line, RobotLog.class);
                    if (logService.sendToLokiWithRetry(log)) {
                        successCount++;
                    } else {
                        remaining.add(line); // vẫn fail thì giữ lại
                    }
                } catch (Exception e) {
                    remaining.add(line); // parse lỗi thì giữ lại
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // ghi lại file chỉ còn dòng fail
        try (FileWriter fw = new FileWriter(file, false)) {
            for (String line : remaining) {
                fw.write(line + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return successCount;
    }
}
