package com.mineduck1608.alpha.code.alphacodelogsservice.grpc.client;

import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;
import submission.SubmissionServiceGrpc;
import submission.Submission.RobotSubmissionRequest;
import submission.Submission.RobotSubmissionResponse;

@Service
@Slf4j
public class SubmissionGrpcClient {
    @GrpcClient("alpha-course-service")
    private SubmissionServiceGrpc.SubmissionServiceBlockingStub submissionServiceBlockingStub;

    public boolean submitLogs(String robotId, String accountLessonId, String logDataJson) {
        try {
            RobotSubmissionRequest request = RobotSubmissionRequest.newBuilder()
                    .setRobotId(robotId)
                    .setAccountLessonId(accountLessonId)
                    .setLogDataJson(logDataJson)
                    .build();

            RobotSubmissionResponse response = submissionServiceBlockingStub.submitRobotLogs(request);

            if (response.getSuccess()) {
                log.info("Sent logs for robot {} (accountLessonId={}) successfully: {}",
                        robotId, accountLessonId, response.getMessage());
                return true;
            } else {
                log.warn("Failed to submit logs for robot {} (accountLessonId={}): {}",
                        robotId, accountLessonId, response.getMessage());
                return false;
            }

        } catch (Exception e) {
            log.error("gRPC call to CourseService failed for robot {} (accountLessonId={}): {}",
                    robotId, accountLessonId, e.getMessage(), e);
            return false;
        }
    }
}
