package com.distroq.repository;

import com.distroq.model.Job;
import com.distroq.model.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findTop50ByOrderByCreatedAtDesc();

    List<Job> findTop50ByStatusOrderByCreatedAtDesc(JobStatus status);
}
