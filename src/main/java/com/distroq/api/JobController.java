package com.distroq.api;

import com.distroq.api.dto.JobDetailResponse;
import com.distroq.api.dto.JobResponse;
import com.distroq.api.dto.SubmitJobRequest;
import com.distroq.config.DistroqProperties;
import com.distroq.model.Job;
import com.distroq.model.JobStatus;
import com.distroq.model.Priority;
import com.distroq.queue.EnqueueSource;
import com.distroq.queue.JobQueue;
import com.distroq.queue.ScheduledJobQueue;
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

import java.time.Instant;
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
    private final ScheduledJobQueue scheduledJobQueue;
    private final int defaultMaxAttempts;
    private final int replayAttempts;

    public JobController(JobRepository jobRepository,
                         JobAttemptRepository jobAttemptRepository,
                         DeadLetterRepository deadLetterRepository,
                         JobQueue jobQueue,
                         ScheduledJobQueue scheduledJobQueue,
                         DistroqProperties properties) {
        this.jobRepository = jobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.jobQueue = jobQueue;
        this.scheduledJobQueue = scheduledJobQueue;
        this.defaultMaxAttempts = properties.retry().defaultMaxAttempts();
        this.replayAttempts = properties.dlq().replayAttempts();
    }

    /**
     * Two outcomes, decided entirely by {@code scheduledAt}.
     *
     * <p>Every validation runs before the first write, so a rejected request leaves nothing behind
     * in either system. A future time saves the job SCHEDULED and puts it on the scheduled sorted
     * set; anything else saves it QUEUED and puts it straight on its tier's stream.
     */
    @PostMapping("/jobs")
    public ResponseEntity<JobResponse> submit(@RequestBody SubmitJobRequest request) {
        int maxAttempts = resolveMaxAttempts(request.maxAttempts());
        Priority priority = resolvePriority(request.priority());
        Instant scheduledAt = ScheduledAtParser.parse(request.scheduledAt());

        Job job = jobRepository.save(
                Job.create(request.type(), request.payload(), maxAttempts, priority, scheduledAt));

        // first of the three dual writes: the row is committed, the Redis write is a separate
        // system and can still fail on its own. Streams did not change that and neither does
        // scheduling - a crash here leaves a SCHEDULED row with no sorted-set member. See NOTES.md
        if (job.isScheduled()) {
            scheduledJobQueue.schedule(job.getId(), job.getPriority(), scheduledAt);
            log.info("Job {} ({}) SCHEDULED at {} for {} with maxAttempts {}",
                    job.getId(), job.getType(), job.getPriority(), scheduledAt, maxAttempts);
        } else {
            String entryId = jobQueue.enqueue(job.getId(), job.getPriority(), EnqueueSource.SUBMIT);
            log.info("Job {} ({}) QUEUED at {} with maxAttempts {} as stream entry {}{}",
                    job.getId(), job.getType(), job.getPriority(), maxAttempts, entryId,
                    scheduledAt == null ? "" : " (requested time " + scheduledAt + " already past)");
        }
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

        // straight onto the tier's stream: replay is an explicit human action, so making the
        // operator wait out a backoff window they did not ask for would be surprising.
        // The job's own tier, not the default - a LOW job replayed as NORMAL would jump ahead of
        // work it was deliberately ranked behind, and a HIGH one would silently lose its rank.
        // Third of the three dual writes; the window is unchanged from v0.4.
        //
        // Immediate even when the job carried a scheduledAt. That timestamp described the original
        // request; replaying is a new operator action taken now, and re-honouring a time that has
        // usually already passed would either run immediately anyway or strand the job. The
        // original value stays on the row as history - see NOTES.md.
        String entryId = jobQueue.enqueue(job.getId(), job.getPriority(), EnqueueSource.REPLAY);
        log.info("Job {} ({}) replayed from the DLQ as stream entry {}, attemptCount {} preserved, "
                        + "maxAttempts {} -> {}",
                job.getId(), job.getPriority(), entryId, job.getAttemptCount(), previousMaxAttempts,
                job.getMaxAttempts());
        return ResponseEntity.accepted().body(JobResponse.from(job));
    }

    /**
     * Three different counts of "entries in a stream", because they answer three different
     * questions and conflating them is the easiest mistake to make with Streams:
     *
     * <ul>
     *   <li>{@code queueDepth} / {@code queueDepthByPriority} — waiting to be delivered. The
     *       successor to v0.4's list depth, read from the consumer group's {@code lag}. Exact
     *       while nothing is trimmed or deleted, and nothing in v0.5 trims or deletes.</li>
     *   <li>{@code streamDepthByPriority} — {@code XLEN}. Everything the stream has ever held,
     *       acknowledged or not. It only ever grows. It is not a backlog.</li>
     *   <li>{@code pendingEntriesByPriority} — delivered and not yet acknowledged. In-flight work
     *       plus anything abandoned and not yet reclaimed. Exact.</li>
     * </ul>
     *
     * <p>{@code activeConsumers} counts consumers <em>holding</em> pending entries, not consumers
     * registered in the group: an idle worker holds nothing and does not appear here.
     *
     * <p>v0.6 adds a fourth kind of waiting, kept out of all three of the above.
     * {@code scheduledDepth} counts jobs waiting on a time the submitter asked for. They are not
     * backlog — no worker could run them yet even if every worker were idle — and folding them
     * into {@code queueDepth} would make an autoscaler start workers for work that is not due, or
     * into {@code delayedDepth} would make a retry-rate alarm fire because someone scheduled a
     * report for midnight.
     */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        // LinkedHashMap rather than Map.of: the key order is stable in the response, and there are
        // now well over Map.of's ten-pair overload set
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("queueDepth", jobQueue.readyDepth());
        metrics.put("queueDepthByPriority", jobQueue.readyDepthByPriority());
        metrics.put("delayedDepth", jobQueue.delayedDepth());
        metrics.put("scheduledDepth", scheduledJobQueue.scheduledDepth());
        metrics.put("scheduledDepthByPriority", scheduledJobQueue.scheduledDepthByPriority());
        metrics.put("totalJobs", jobRepository.count());
        metrics.put("deadLetterCount", deadLetterRepository.countByReplayed(false));
        metrics.put("replayedCount", deadLetterRepository.countByReplayed(true));
        metrics.put("streamDepthByPriority", jobQueue.streamDepthByPriority());
        metrics.put("pendingEntriesByPriority", jobQueue.pendingEntriesByPriority());
        metrics.put("activeConsumers", jobQueue.consumersHoldingEntries().size());
        return metrics;
    }
}
