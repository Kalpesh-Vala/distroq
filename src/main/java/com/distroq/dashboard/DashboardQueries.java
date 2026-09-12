package com.distroq.dashboard;

import com.distroq.model.DeadLetter;
import com.distroq.model.Job;
import com.distroq.model.JobStatus;
import com.distroq.model.OutboxEvent;
import com.distroq.model.OutboxStatus;
import com.distroq.model.Priority;
import com.distroq.model.ReliabilityAction;
import com.distroq.outbox.OutboxEventType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every database query the dashboard makes, in one file.
 *
 * <p>One place, so that "the dashboard does not write to PostgreSQL" is a claim a reviewer can
 * check by reading a single class rather than by auditing a service. There is no {@code
 * @Modifying}, no {@code createNativeQuery(...).executeUpdate()}, no {@code persist} and no
 * {@code merge} anywhere below, and {@code @Transactional(readOnly = true)} makes the intent
 * explicit to Hibernate as well as to the reader.
 *
 * <p>Every query is bounded. The list queries take a page or a {@code maxResults} ceiling; the
 * aggregates are indexed counts. Nothing here joins every table together to answer one request —
 * a dashboard that does that is a dashboard that takes the database down during the incident it
 * was opened to diagnose.
 *
 * <p>Sort columns come from {@link #JOB_SORT_FIELDS} rather than from the request. A sort field
 * is the one part of a query that cannot be a bound parameter, so it is the one part that has to
 * be an allowlist.
 */
@Component
public class DashboardQueries {

    /** The only column names that may reach a JPQL {@code order by} clause. */
    static final Set<String> JOB_SORT_FIELDS = Set.of(
            "createdAt", "scheduledAt", "startedAt", "finishedAt", "updatedAt",
            "priority", "status", "type", "attemptCount");

    static final Set<String> OUTBOX_SORT_FIELDS = Set.of(
            "createdAt", "availableAt", "publishedAt", "terminalFailedAt", "status", "eventType");

    /** How many publications the latency percentiles are computed from. */
    static final int LATENCY_SAMPLE_SIZE = 500;

    /** How many dead letters the daily histogram is built from. */
    static final int DEAD_LETTER_SAMPLE_SIZE = 2000;

    @PersistenceContext
    private EntityManager entityManager;

    // ------------------------------------------------------------------------------- jobs

    /**
     * The jobs table as the dashboard filters it.
     *
     * <p>{@code payload} is selected because it is a column of the entity and Hibernate has no
     * cheap way to leave it behind, but it never leaves the service: the DTO does not have a field
     * for it. See {@code JobRow}.
     */
    public record JobFilter(JobStatus status,
                            Priority priority,
                            String jobType,
                            Instant createdAfter,
                            Instant createdBefore,
                            Boolean scheduled,
                            Boolean hasAttempts,
                            UUID jobId) {
    }

    public record Slice<T>(List<T> content, long totalElements, int page, int size) {

        public int totalPages() {
            return size <= 0 ? 0 : (int) Math.ceilDiv(totalElements, (long) size);
        }
    }

    @Transactional(readOnly = true)
    public Slice<Job> jobs(JobFilter filter, int page, int size, String sortField,
                           boolean ascending) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (filter.jobId() != null) {
            where.append(" and job.id = :jobId");
            parameters.put("jobId", filter.jobId());
        }
        if (filter.status() != null) {
            where.append(" and job.status = :status");
            parameters.put("status", filter.status());
        }
        if (filter.priority() != null) {
            where.append(" and job.priority = :priority");
            parameters.put("priority", filter.priority());
        }
        if (filter.jobType() != null && !filter.jobType().isBlank()) {
            where.append(" and job.type = :jobType");
            parameters.put("jobType", filter.jobType().trim());
        }
        if (filter.createdAfter() != null) {
            where.append(" and job.createdAt >= :createdAfter");
            parameters.put("createdAfter", filter.createdAfter());
        }
        if (filter.createdBefore() != null) {
            where.append(" and job.createdAt <= :createdBefore");
            parameters.put("createdBefore", filter.createdBefore());
        }
        if (Boolean.TRUE.equals(filter.scheduled())) {
            where.append(" and job.scheduledAt is not null");
        } else if (Boolean.FALSE.equals(filter.scheduled())) {
            where.append(" and job.scheduledAt is null");
        }
        if (Boolean.TRUE.equals(filter.hasAttempts())) {
            where.append(" and job.attemptCount > 0");
        } else if (Boolean.FALSE.equals(filter.hasAttempts())) {
            where.append(" and job.attemptCount = 0");
        }

        String sort = JOB_SORT_FIELDS.contains(sortField) ? sortField : "createdAt";
        String direction = ascending ? "asc" : "desc";

        TypedQuery<Job> query = entityManager.createQuery(
                "select job from Job job" + where + " order by job." + sort + " " + direction,
                Job.class);
        TypedQuery<Long> count = entityManager.createQuery(
                "select count(job) from Job job" + where, Long.class);
        parameters.forEach((name, value) -> {
            query.setParameter(name, value);
            count.setParameter(name, value);
        });

        query.setFirstResult(page * size);
        query.setMaxResults(size);
        return new Slice<>(query.getResultList(), count.getSingleResult(), page, size);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> jobCountsByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (JobStatus status : JobStatus.values()) {
            counts.put(status.name(), 0L);
        }
        entityManager.createQuery(
                        "select job.status, count(job) from Job job group by job.status",
                        Object[].class)
                .getResultList()
                .forEach(row -> counts.put(((JobStatus) row[0]).name(), (Long) row[1]));
        return counts;
    }

    /** RUNNING jobs whose lease has not yet expired, oldest lease first. */
    @Transactional(readOnly = true)
    public List<Job> activeLeases(Instant now, int limit) {
        return entityManager.createQuery("""
                        select job from Job job
                        where job.executionOwner is not null and job.executionLeaseUntil > :now
                        order by job.executionLeaseUntil asc
                        """, Job.class)
                .setParameter("now", now)
                .setMaxResults(limit)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public long countActiveLeases(Instant now) {
        return entityManager.createQuery("""
                        select count(job) from Job job
                        where job.executionOwner is not null and job.executionLeaseUntil > :now
                        """, Long.class)
                .setParameter("now", now)
                .getSingleResult();
    }

    /**
     * Submitted, started, succeeded and dead-lettered per tier since {@code since}.
     *
     * <p>Approximate by construction, and labelled as such in the UI. {@code startedAt} holds the
     * most recent start rather than every start, so a job that began before the window and was
     * retried inside it counts once; and a job that succeeded inside the window but was submitted
     * before it appears in the success column and not in the submitted one. That is the right
     * trade for a panel that answers "is work flowing", and the exact history is what the v0.9
     * analytics pipeline is for.
     */
    @Transactional(readOnly = true)
    public Map<String, ThroughputRow> throughputByPriority(Instant since) {
        Map<String, ThroughputRow> rows = new LinkedHashMap<>();
        for (Priority priority : Priority.STRICT_ORDER) {
            rows.put(priority.name(), new ThroughputRow(priority.name(), 0, 0, 0, 0));
        }
        countInto(rows, "select job.priority, count(job) from Job job "
                + "where job.createdAt >= :since group by job.priority", since,
                (row, value) -> row.withSubmitted(value));
        countInto(rows, "select job.priority, count(job) from Job job "
                + "where job.startedAt >= :since group by job.priority", since,
                (row, value) -> row.withStarted(value));
        countInto(rows, "select job.priority, count(job) from Job job "
                + "where job.finishedAt >= :since and job.status = com.distroq.model.JobStatus.SUCCEEDED "
                + "group by job.priority", since, (row, value) -> row.withSucceeded(value));
        countInto(rows, "select job.priority, count(job) from Job job "
                + "where job.finishedAt >= :since "
                + "and job.status = com.distroq.model.JobStatus.DEAD_LETTERED "
                + "group by job.priority", since, (row, value) -> row.withDeadLettered(value));
        return rows;
    }

    public record ThroughputRow(String priority, long submitted, long started, long succeeded,
                                long deadLettered) {

        ThroughputRow withSubmitted(long value) {
            return new ThroughputRow(priority, value, started, succeeded, deadLettered);
        }

        ThroughputRow withStarted(long value) {
            return new ThroughputRow(priority, submitted, value, succeeded, deadLettered);
        }

        ThroughputRow withSucceeded(long value) {
            return new ThroughputRow(priority, submitted, started, value, deadLettered);
        }

        ThroughputRow withDeadLettered(long value) {
            return new ThroughputRow(priority, submitted, started, succeeded, value);
        }

        /** Null rather than 1.0 when nothing finished: an unknown rate is not a perfect one. */
        public Double successRate() {
            long finished = succeeded + deadLettered;
            return finished == 0 ? null : (double) succeeded / finished;
        }
    }

    private void countInto(Map<String, ThroughputRow> rows, String jpql, Instant since,
                           java.util.function.BiFunction<ThroughputRow, Long, ThroughputRow> merge) {
        entityManager.createQuery(jpql, Object[].class)
                .setParameter("since", since)
                .getResultList()
                .forEach(row -> {
                    String priority = ((Priority) row[0]).name();
                    rows.computeIfPresent(priority,
                            (key, existing) -> merge.apply(existing, (Long) row[1]));
                });
    }

    // ------------------------------------------------------------------------------ outbox

    @Transactional(readOnly = true)
    public Slice<OutboxEvent> outboxEvents(OutboxStatus status, OutboxEventType eventType,
                                           UUID aggregateId, int page, int size, String sortField,
                                           boolean ascending) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (status != null) {
            where.append(" and event.status = :status");
            parameters.put("status", status);
        }
        if (eventType != null) {
            where.append(" and event.eventType = :eventType");
            parameters.put("eventType", eventType);
        }
        if (aggregateId != null) {
            where.append(" and event.aggregateId = :aggregateId");
            parameters.put("aggregateId", aggregateId);
        }

        String sort = OUTBOX_SORT_FIELDS.contains(sortField) ? sortField : "createdAt";
        String direction = ascending ? "asc" : "desc";

        TypedQuery<OutboxEvent> query = entityManager.createQuery(
                "select event from OutboxEvent event" + where + " order by event." + sort + " "
                        + direction, OutboxEvent.class);
        TypedQuery<Long> count = entityManager.createQuery(
                "select count(event) from OutboxEvent event" + where, Long.class);
        parameters.forEach((name, value) -> {
            query.setParameter(name, value);
            count.setParameter(name, value);
        });

        query.setFirstResult(page * size);
        query.setMaxResults(size);
        return new Slice<>(query.getResultList(), count.getSingleResult(), page, size);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> outboxCountsByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (OutboxStatus status : OutboxStatus.values()) {
            counts.put(status.name(), 0L);
        }
        entityManager.createQuery(
                        "select event.status, count(event) from OutboxEvent event "
                                + "group by event.status", Object[].class)
                .getResultList()
                .forEach(row -> counts.put(((OutboxStatus) row[0]).name(), (Long) row[1]));
        return counts;
    }

    @Transactional(readOnly = true)
    public Map<String, Long> outboxCountsByEventType() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (OutboxEventType type : OutboxEventType.values()) {
            counts.put(type.name(), 0L);
        }
        entityManager.createQuery(
                        "select event.eventType, count(event) from OutboxEvent event "
                                + "group by event.eventType", Object[].class)
                .getResultList()
                .forEach(row -> counts.put(((OutboxEventType) row[0]).name(), (Long) row[1]));
        return counts;
    }

    /** Unpublished events bucketed by how long they have been waiting. */
    @Transactional(readOnly = true)
    public Map<String, Long> outboxUnpublishedByAge(Instant now) {
        Map<String, Long> buckets = new LinkedHashMap<>();
        buckets.put("under1m", unpublishedBetween(now.minusSeconds(60), null));
        buckets.put("1mTo5m", unpublishedBetween(now.minusSeconds(300), now.minusSeconds(60)));
        buckets.put("5mTo1h", unpublishedBetween(now.minusSeconds(3600), now.minusSeconds(300)));
        buckets.put("over1h", unpublishedBetween(null, now.minusSeconds(3600)));
        return buckets;
    }

    private long unpublishedBetween(Instant from, Instant to) {
        StringBuilder jpql = new StringBuilder(
                "select count(event) from OutboxEvent event where event.publishedAt is null");
        if (from != null) {
            jpql.append(" and event.createdAt >= :from");
        }
        if (to != null) {
            jpql.append(" and event.createdAt < :to");
        }
        TypedQuery<Long> query = entityManager.createQuery(jpql.toString(), Long.class);
        if (from != null) {
            query.setParameter("from", from);
        }
        if (to != null) {
            query.setParameter("to", to);
        }
        return query.getSingleResult();
    }

    @Transactional(readOnly = true)
    public long outboxOperatorRetries() {
        Long total = entityManager.createQuery(
                        "select coalesce(sum(event.operatorRetryCount), 0) from OutboxEvent event",
                        Long.class)
                .getSingleResult();
        return total == null ? 0L : total;
    }

    /**
     * Publication latency for the most recent {@value #LATENCY_SAMPLE_SIZE} publications.
     *
     * <p>A sample rather than the whole table, and labelled approximate in the UI. Percentiles
     * over every event ever published would be a full scan on every poll, and the number an
     * operator wants during an incident is "how is the relay doing now" anyway.
     */
    @Transactional(readOnly = true)
    public List<Long> recentPublicationLatenciesMs() {
        return entityManager.createQuery("""
                        select event.createdAt, event.publishedAt from OutboxEvent event
                        where event.publishedAt is not null
                        order by event.publishedAt desc
                        """, Object[].class)
                .setMaxResults(LATENCY_SAMPLE_SIZE)
                .getResultList()
                .stream()
                .map(row -> java.time.Duration.between((Instant) row[0], (Instant) row[1]).toMillis())
                .map(millis -> Math.max(0L, millis))
                .toList();
    }

    @Transactional(readOnly = true)
    public Instant oldestUnpublishedCreatedAt() {
        return entityManager.createQuery(
                        "select min(event.createdAt) from OutboxEvent event "
                                + "where event.publishedAt is null", Instant.class)
                .getSingleResult();
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> outboxEventsForJob(UUID jobId, int limit) {
        return entityManager.createQuery(
                        "select event from OutboxEvent event where event.aggregateId = :jobId "
                                + "order by event.createdAt asc", OutboxEvent.class)
                .setParameter("jobId", jobId)
                .setMaxResults(limit)
                .getResultList();
    }

    // --------------------------------------------------------------------------------- dlq

    @Transactional(readOnly = true)
    public Slice<DeadLetter> deadLetters(Boolean replayed, int page, int size) {
        String where = replayed == null ? "" : " where letter.replayed = :replayed";
        TypedQuery<DeadLetter> query = entityManager.createQuery(
                "select letter from DeadLetter letter" + where + " order by letter.movedAt desc",
                DeadLetter.class);
        TypedQuery<Long> count = entityManager.createQuery(
                "select count(letter) from DeadLetter letter" + where, Long.class);
        if (replayed != null) {
            query.setParameter("replayed", replayed);
            count.setParameter("replayed", replayed);
        }
        query.setFirstResult(page * size);
        query.setMaxResults(size);
        return new Slice<>(query.getResultList(), count.getSingleResult(), page, size);
    }

    @Transactional(readOnly = true)
    public long countDeadLetters(boolean replayed) {
        return entityManager.createQuery(
                        "select count(letter) from DeadLetter letter where letter.replayed = :replayed",
                        Long.class)
                .setParameter("replayed", replayed)
                .getSingleResult();
    }

    @Transactional(readOnly = true)
    public long totalReplays() {
        Long total = entityManager.createQuery(
                        "select coalesce(sum(letter.replayCount), 0) from DeadLetter letter",
                        Long.class)
                .getSingleResult();
        return total == null ? 0L : total;
    }

    @Transactional(readOnly = true)
    public Instant oldestDeadLetteredAt() {
        return entityManager.createQuery(
                        "select min(letter.movedAt) from DeadLetter letter "
                                + "where letter.replayed = false", Instant.class)
                .getSingleResult();
    }

    /** Dead letters joined to their job's tier and type, bounded to the most recent sample. */
    @Transactional(readOnly = true)
    public List<DeadLetterFact> deadLetterFacts(int limit) {
        return entityManager.createQuery("""
                        select letter.jobId, letter.movedAt, letter.replayed, letter.replayCount,
                               job.priority, job.type, job.status, job.attemptCount, job.scheduledAt
                        from DeadLetter letter, Job job
                        where job.id = letter.jobId
                        order by letter.movedAt desc
                        """, Object[].class)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(row -> new DeadLetterFact((UUID) row[0], (Instant) row[1], (Boolean) row[2],
                        (Integer) row[3], ((Priority) row[4]).name(), (String) row[5],
                        ((JobStatus) row[6]).name(), (Integer) row[7], (Instant) row[8]))
                .toList();
    }

    public record DeadLetterFact(UUID jobId, Instant movedAt, boolean replayed, int replayCount,
                                 String priority, String jobType, String status, int attemptCount,
                                 Instant scheduledAt) {
    }

    // ---------------------------------------------------------------------------- activity

    @Transactional(readOnly = true)
    public List<Job> recentlyChangedJobs(int limit) {
        return entityManager.createQuery(
                        "select job from Job job order by job.updatedAt desc", Job.class)
                .setMaxResults(limit)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> recentOutboxEvents(int limit) {
        return entityManager.createQuery(
                        "select event from OutboxEvent event order by event.createdAt desc",
                        OutboxEvent.class)
                .setMaxResults(limit)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<ReliabilityAction> recentReliabilityActions(int limit) {
        return entityManager.createQuery(
                        "select action from ReliabilityAction action order by action.createdAt desc",
                        ReliabilityAction.class)
                .setMaxResults(limit)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<AbandonedAttempt> recentAbandonedAttempts(int limit) {
        return entityManager.createQuery("""
                        select attempt.jobId, attempt.finishedAt, attempt.workerId,
                               attempt.attemptNumber
                        from JobAttempt attempt
                        where attempt.outcome = com.distroq.model.AttemptOutcome.ABANDONED
                        order by attempt.finishedAt desc
                        """, Object[].class)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(row -> new AbandonedAttempt((UUID) row[0], (Instant) row[1], (String) row[2],
                        (Integer) row[3]))
                .toList();
    }

    public record AbandonedAttempt(UUID jobId, Instant finishedAt, String workerId,
                                   int attemptNumber) {
    }

    @Transactional(readOnly = true)
    public List<DeadLetter> recentDeadLetters(int limit) {
        return entityManager.createQuery(
                        "select letter from DeadLetter letter order by letter.movedAt desc",
                        DeadLetter.class)
                .setMaxResults(limit)
                .getResultList();
    }

    // ------------------------------------------------------------------------------ counts

    @Transactional(readOnly = true)
    public long countAbandonedAttempts() {
        return entityManager.createQuery("""
                        select count(attempt) from JobAttempt attempt
                        where attempt.outcome = com.distroq.model.AttemptOutcome.ABANDONED
                        """, Long.class)
                .getSingleResult();
    }

    @Transactional(readOnly = true)
    public List<String> jobTypes(int limit) {
        return new ArrayList<>(entityManager.createQuery(
                        "select distinct job.type from Job job order by job.type", String.class)
                .setMaxResults(limit)
                .getResultList());
    }
}
