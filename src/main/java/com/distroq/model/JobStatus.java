package com.distroq.model;

public enum JobStatus {
    QUEUED,
    RUNNING,
    /** Failed with attempts remaining; waiting in the delayed set for its backoff window. */
    RETRYING,
    SUCCEEDED,
    /** Terminal: attempts exhausted. */
    FAILED
}
