package com.distroq.api;

import com.distroq.api.dto.JobDetailResponse;
import com.distroq.api.dto.JobResponse;
import com.distroq.api.dto.SubmitJobRequest;
import com.distroq.config.DistroqProperties;
import com.distroq.model.Job;
import com.distroq.model.JobStatus;
import com.distroq.queue.JobQueue;
import com.distroq.repository.JobAttemptRepository;
import com.distroq.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobRepository jobRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final JobQueue jobQueue;
    private final int defaultMaxAttempts;

    public JobController(JobRepository jobRepository,
                         JobAttemptRepository jobAttemptRepository,
                         JobQueue jobQueue,
                         DistroqProperties properties) {
        this.jobRepository = jobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.jobQueue = jobQueue;
        this.defaultMaxAttempts = properties.retry().defaultMaxAttempts();
    }

    @PostMapping("/jobs")
    public ResponseEntity<JobResponse> submit(@RequestBody SubmitJobRequest request) {
        int maxAttempts = resolveMaxAttempts(request.maxAttempts());
        Job job = jobRepository.save(Job.create(request.type(), request.payload(), maxAttempts));
        jobQueue.enqueue(job.getId());
        log.info("Job {} ({}) QUEUED with maxAttempts {}", job.getId(), job.getType(), maxAttempts);
        return ResponseEntity.accepted().body(JobResponse.from(job));
    }

    private int resolveMaxAttempts(Integer requested) {
        if (requested == null) {
            return defaultMaxAttempts;
        }
        if (requested < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "maxAttempts must be at least 1, got " + requested);
        }
        return requested;
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<JobDetailResponse> get(@PathVariable UUID id) {
        return jobRepository.findById(id)
                .map(job -> JobDetailResponse.from(
                        job, jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(job.getId())))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/jobs")
    public List<JobResponse> list(@RequestParam(required = false) JobStatus status) {
        List<Job> jobs = (status == null)
                ? jobRepository.findTop50ByOrderByCreatedAtDesc()
                : jobRepository.findTop50ByStatusOrderByCreatedAtDesc(status);
        return jobs.stream().map(JobResponse::from).toList();
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return Map.of(
                "queueDepth", jobQueue.depth(),
                "delayedDepth", jobQueue.delayedDepth(),
                "totalJobs", jobRepository.count());
    }
}
