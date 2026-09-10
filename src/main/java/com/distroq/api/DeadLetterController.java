package com.distroq.api;

import com.distroq.api.dto.DeadLetterDetailResponse;
import com.distroq.api.dto.DeadLetterResponse;
import com.distroq.api.error.ApiException;
import com.distroq.api.error.ErrorCode;
import com.distroq.model.DeadLetter;
import com.distroq.model.Job;
import com.distroq.repository.DeadLetterRepository;
import com.distroq.repository.JobAttemptRepository;
import com.distroq.repository.JobRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dlq")
public class DeadLetterController {

    private final DeadLetterRepository deadLetterRepository;
    private final JobRepository jobRepository;
    private final JobAttemptRepository jobAttemptRepository;

    public DeadLetterController(DeadLetterRepository deadLetterRepository,
                                JobRepository jobRepository,
                                JobAttemptRepository jobAttemptRepository) {
        this.deadLetterRepository = deadLetterRepository;
        this.jobRepository = jobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
    }

    @GetMapping
    public List<DeadLetterResponse> list(@RequestParam(required = false) Boolean replayed) {
        List<DeadLetter> deadLetters = (replayed == null)
                ? deadLetterRepository.findTop50ByOrderByMovedAtDesc()
                : deadLetterRepository.findTop50ByReplayedOrderByMovedAtDesc(replayed);

        // one IN query for every job in the page, not one lookup per dead-letter
        List<UUID> jobIds = deadLetters.stream().map(DeadLetter::getJobId).toList();
        Map<UUID, Job> jobsById = jobRepository.findAllById(jobIds).stream()
                .collect(Collectors.toMap(Job::getId, Function.identity()));

        return deadLetters.stream()
                .map(deadLetter -> DeadLetterResponse.from(deadLetter, jobsById.get(deadLetter.getJobId())))
                .toList();
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<DeadLetterDetailResponse> get(@PathVariable UUID jobId) {
        return deadLetterRepository.findById(jobId)
                .flatMap(deadLetter -> jobRepository.findById(jobId)
                        .map(job -> DeadLetterDetailResponse.from(
                                deadLetter, job,
                                jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId))))
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ApiException(ErrorCode.DEAD_LETTER_NOT_FOUND,
                        "No dead-lettered job with id " + jobId));
    }
}
