package com.distroq.api;

import com.distroq.api.dto.JobResponse;
import com.distroq.api.dto.SubmitJobRequest;
import com.distroq.model.Job;
import com.distroq.model.JobStatus;
import com.distroq.queue.JobQueue;
import com.distroq.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobRepository jobRepository;
    private final JobQueue jobQueue;

    public JobController(JobRepository jobRepository, JobQueue jobQueue) {
        this.jobRepository = jobRepository;
        this.jobQueue = jobQueue;
    }

    @PostMapping("/jobs")
    public ResponseEntity<JobResponse> submit(@RequestBody SubmitJobRequest request) {
        Job job = jobRepository.save(Job.create(request.type(), request.payload()));
        jobQueue.enqueue(job.getId());
        log.info("Job {} ({}) QUEUED", job.getId(), job.getType());
        return ResponseEntity.accepted().body(JobResponse.from(job));
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<JobResponse> get(@PathVariable UUID id) {
        return jobRepository.findById(id)
                .map(JobResponse::from)
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
                "totalJobs", jobRepository.count());
    }
}
