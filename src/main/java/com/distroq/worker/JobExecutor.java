package com.distroq.worker;

import com.distroq.model.Job;
import org.springframework.stereotype.Component;

@Component
public class JobExecutor {

    private static final long DEFAULT_SLEEP_MILLIS = 1000L;
    private static final int DEFAULT_FAIL_COUNT = 2;

    public void execute(Job job) throws Exception {
        switch (job.getType()) {
            case "sleep" -> Thread.sleep(parseSleepMillis(job.getPayload()));
            case "always_fail" -> throw new IllegalStateException(
                    "Job type 'always_fail' always fails by design");
            case "fail_n_times" -> failFirstN(job);
            default -> throw new IllegalArgumentException("Unknown job type: " + job.getType());
        }
    }

    // stateless: the attempt number comes from the job, not from a counter held here
    private void failFirstN(Job job) {
        int failCount = parseFailCount(job.getPayload());
        if (job.getAttemptCount() <= failCount) {
            throw new IllegalStateException("Job type 'fail_n_times' failing attempt "
                    + job.getAttemptCount() + " of the first " + failCount);
        }
    }

    private int parseFailCount(String payload) {
        if (payload == null || payload.isBlank()) {
            return DEFAULT_FAIL_COUNT;
        }
        try {
            return Integer.parseInt(payload.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_FAIL_COUNT;
        }
    }

    private long parseSleepMillis(String payload) {
        if (payload == null || payload.isBlank()) {
            return DEFAULT_SLEEP_MILLIS;
        }
        try {
            return Long.parseLong(payload.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_SLEEP_MILLIS;
        }
    }
}
