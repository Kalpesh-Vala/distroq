package com.distroq.outbox;

public enum OutboxEventType {
    ENQUEUE_SUBMIT,
    SCHEDULE_RETRY,
    SCHEDULE_USER_JOB,
    ENQUEUE_REPLAY
}