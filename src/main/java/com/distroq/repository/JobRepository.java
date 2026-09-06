package com.distroq.repository;

import com.distroq.model.Job;
import com.distroq.model.JobStatus;
import com.distroq.model.Priority;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findTop50ByOrderByCreatedAtDesc();

    List<Job> findTop50ByStatusOrderByCreatedAtDesc(JobStatus status);

    List<Job> findTop50ByPriorityOrderByCreatedAtDesc(Priority priority);

    /** Combined filter pushed into SQL; filtering a 50-row page in memory would silently truncate. */
    List<Job> findTop50ByStatusAndPriorityOrderByCreatedAtDesc(JobStatus status, Priority priority);
}
