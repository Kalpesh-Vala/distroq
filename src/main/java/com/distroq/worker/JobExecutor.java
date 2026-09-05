package com.distroq.worker;

import com.distroq.model.Job;
import org.springframework.stereotype.Component;

@Component
public class JobExecutor {

    private static final long DEFAULT_SLEEP_MILLIS = 1000L;

    public void execute(Job job) throws Exception {
        switch (job.getType()) {
            case "sleep" -> Thread.sleep(parseSleepMillis(job.getPayload()));
            case "always_fail" -> throw new IllegalStateException(
                    "Job type 'always_fail' always fails by design");
            default -> throw new IllegalArgumentException("Unknown job type: " + job.getType());
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
