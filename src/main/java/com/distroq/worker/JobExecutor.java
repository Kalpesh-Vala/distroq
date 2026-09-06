package com.distroq.worker;

import com.distroq.model.Job;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class JobExecutor {

    private static final long DEFAULT_SLEEP_MILLIS = 1000L;
    private static final int DEFAULT_FAIL_COUNT = 2;
    private static final String FLAG_KEY_PREFIX = "distroq:test:flag:";

    private final StringRedisTemplate redis;

    public JobExecutor(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void execute(Job job) throws Exception {
        switch (job.getType()) {
            case "sleep" -> Thread.sleep(parseSleepMillis(job.getPayload()));
            case "always_fail" -> throw new IllegalStateException(
                    "Job type 'always_fail' always fails by design");
            case "fail_n_times" -> failFirstN(job);
            case "fail_until_flagged" -> failWhileFlagged(job);
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

    /**
     * Stands in for a broken downstream dependency: fails while its Redis flag is set, succeeds
     * once something outside the application clears it. Sets the flag itself on the first attempt,
     * so a freshly submitted job starts out failing.
     */
    private void failWhileFlagged(Job job) {
        String key = FLAG_KEY_PREFIX + job.getId();
        if (job.getAttemptCount() <= 1) {
            redis.opsForValue().set(key, "set");
        }
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            throw new IllegalStateException("Job type 'fail_until_flagged' failing while " + key
                    + " is set; DEL that key to let this job succeed");
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
