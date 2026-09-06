package com.distroq.api;

import com.distroq.api.dto.JobDetailResponse;
import com.distroq.api.dto.JobResponse;
import com.distroq.api.dto.SubmitJobRequest;
import com.distroq.config.DistroqProperties;
import com.distroq.model.Job;
import com.distroq.model.JobStatus;
import com.distroq.model.Priority;
import com.distroq.queue.JobQueue;
import com.distroq.repository.DeadLetterRepository;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobRepository jobRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final JobQueue jobQueue;
    private final int defaultMaxAttempts;
    private final int replayAttempts;

    public JobController(JobRepository jobRepository,
                         JobAttemptRepository jobAttemptRepository,
                         DeadLetterRepository deadLetterRepository,
                         JobQueue jobQueue,
                         DistroqProperties properties) {
        this.jobRepository = jobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.jobQueue = jobQueue;
        this.defaultMaxAttempts = properties.retry().defaultMaxAttempts();
        this.replayAttempts = properties.dlq().replayAttempts();
    }

    @PostMapping("/jobs")
    public ResponseEntity<JobResponse> submit(@RequestBody SubmitJobRequest request) {
        int maxAttempts = resolveMaxAttempts(request.maxAttempts());
        Priority priority = resolvePriority(request.priority());
        Job job = jobRepository.save(
                Job.create(request.type(), request.payload(), maxAttempts, priority));
        jobQueue.enqueue(job.getId(), job.getPriority());
        log.info("Job {} ({}) QUEUED at {} with maxAttempts {}",
                job.getId(), job.getType(), job.getPriority(), maxAttempts);
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

    /** Absent or blank is not an error and resolves to the default; an unknown value is a 400. */
    private Priority resolvePriority(String requested) {
        return Priority.parse(requested)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "priority must be one of " + Priority.validValues()
                                + " (case-insensitive), got '" + requested + "'"));
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
    public List<JobResponse> list(@RequestParam(required = false) JobStatus status,
                                  @RequestParam(required = false) Priority priority) {
        // every combination is a distinct query rather than a filtered-in-memory page: trimming a
        // 50-row page after the fact would silently return fewer than the 50 matches that exist
        List<Job> jobs;
        if (status == null && priority == null) {
            jobs = jobRepository.findTop50ByOrderByCreatedAtDesc();
        } else if (priority == null) {
            jobs = jobRepository.findTop50ByStatusOrderByCreatedAtDesc(status);
        } else if (status == null) {
            jobs = jobRepository.findTop50ByPriorityOrderByCreatedAtDesc(priority);
        } else {
            jobs = jobRepository.findTop50ByStatusAndPriorityOrderByCreatedAtDesc(status, priority);
        }
        return jobs.stream().map(JobResponse::from).toList();
    }

    /**
     * Replay a dead-lettered job. History is continued rather than reset: {@code attemptCount}
     * carries over and the budget is extended, so the job detail still reads as one story.
     */
    @PostMapping("/jobs/{id}/retry")
    public ResponseEntity<JobResponse> replay(@PathVariable UUID id) {
        Job job = jobRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No job with id " + id));

        if (job.getStatus() != JobStatus.DEAD_LETTERED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only DEAD_LETTERED jobs can be replayed; job " + id + " is " + job.getStatus());
        }

        int previousMaxAttempts = job.getMaxAttempts();
        job.prepareForReplay(replayAttempts);
        jobRepository.save(job);

        // retained as history rather than deleted, so replayCount keeps naming repeat offenders
        deadLetterRepository.findById(id).ifPresent(deadLetter -> {
            deadLetter.markReplayed();
            deadLetterRepository.save(deadLetter);
        });

        // straight onto the pending list: replay is an explicit human action, so making the
        // operator wait out a backoff window they did not ask for would be surprising.
        // The job's own tier, not the default - a LOW job replayed as NORMAL would jump ahead of
        // work it was deliberately ranked behind, and a HIGH one would silently lose its rank.
        jobQueue.enqueue(job.getId(), job.getPriority());
        log.info("Job {} ({}) replayed from the DLQ, attemptCount {} preserved, maxAttempts {} -> {}",
                job.getId(), job.getPriority(), job.getAttemptCount(), previousMaxAttempts,
                job.getMaxAttempts());
        return ResponseEntity.accepted().body(JobResponse.from(job));
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        // LinkedHashMap rather than Map.of: the key order is stable in the response, and there is
        // now one entry past Map.of's ten-pair overload set
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("queueDepth", jobQueue.depth());
        metrics.put("queueDepthByPriority", jobQueue.depthByPriority());
        metrics.put("delayedDepth", jobQueue.delayedDepth());
        metrics.put("totalJobs", jobRepository.count());
        metrics.put("deadLetterCount", deadLetterRepository.countByReplayed(false));
        metrics.put("replayedCount", deadLetterRepository.countByReplayed(true));
        return metrics;
    }
}
