package com.distroq.repository;

import com.distroq.model.JobAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobAttemptRepository extends JpaRepository<JobAttempt, UUID> {

    List<JobAttempt> findByJobIdOrderByAttemptNumberAsc(UUID jobId);
}
