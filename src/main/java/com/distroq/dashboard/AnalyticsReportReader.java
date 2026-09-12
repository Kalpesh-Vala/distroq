package com.distroq.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the CSV and JSON that the v0.9 analytics pipeline already wrote.
 *
 * <p>The pipeline is not re-run, not triggered, and not talked to. Spark does not start because a
 * browser rendered a chart, and PostgreSQL is not queried for a historical aggregate — the whole
 * point of v0.9 was to compute these once, offline, against a read-only connection. This class
 * opens files and stops.
 *
 * <p>An absent directory is not an error. A deployment that has never run an export gets an empty
 * state on the analytics page and a working dashboard everywhere else, which is why every failure
 * below turns into {@link Optional#empty()} or an exception the caller renders as an unavailable
 * section rather than a 500.
 *
 * <p>Two things are deliberately not passed through from the pipeline's own metadata. The
 * {@code input} and {@code output} keys of {@code report_summary.json} are absolute filesystem
 * paths, and {@code effect_counters_snapshot} in the export metadata holds effect keys, which are
 * chosen by the submitter and are therefore user data. Neither reaches the response.
 */
@Component
public class AnalyticsReportReader {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsReportReader.class);

    /** {@code <start>__<end>}, as {@code distroq_analytics} names its run directories. */
    private static final Pattern RUN_ID = Pattern.compile("^[0-9]{8}T[0-9]{6}Z__[0-9]{8}T[0-9]{6}Z$");

    private static final DateTimeFormatter WINDOW =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    /** A report is a summary, so a row count beyond this means the file is not what we think. */
    static final int MAX_ROWS_PER_REPORT = 5000;

    /** Refuse to read a "report" that is really a data dump. */
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private static final List<String> REPORTS = List.of(
            "daily_job_summary", "priority_summary", "job_type_summary", "outbox_summary",
            "effect_summary", "reliability_summary", "data_quality_summary");

    private final ObjectMapper objectMapper;
    private final Path root;
    private final Duration ttl;
    private final AtomicReference<Cached> cached = new AtomicReference<>();

    public AnalyticsReportReader(ObjectMapper objectMapper, DashboardProperties properties) {
        this.objectMapper = objectMapper;
        this.root = Path.of(properties.analyticsDirectory());
        this.ttl = Duration.ofMillis(Math.max(0, properties.analyticsCacheMs()));
    }

    /**
     * One analytics run as the dashboard shows it.
     *
     * @param exact false for every value here: these are batch aggregates over a closed window,
     *              not live state, and the UI says so next to them
     */
    public record AnalyticsRun(String runId,
                               Instant windowStart,
                               Instant windowEnd,
                               Instant generatedAt,
                               Instant exportedAt,
                               String analyticsVersion,
                               String applicationVersion,
                               double durationSeconds,
                               boolean exact,
                               Map<String, Object> headline,
                               Map<String, Object> factRowCounts,
                               Map<String, Object> dataQuality,
                               Map<String, List<Map<String, Object>>> reports) {
    }

    private record Cached(Instant readAt, String runId, AnalyticsRun run) {
    }

    /** Run directory names, newest first. Empty when analytics has never been exported here. */
    public List<String> availableRuns() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> RUN_ID.matcher(name).matches())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @param requestedRun a run id from the caller, or null for the newest. It is matched against
     *                     {@link #availableRuns()} rather than resolved as a path, so a caller
     *                     cannot walk out of the analytics directory with one.
     */
    public Optional<AnalyticsRun> read(String requestedRun) {
        List<String> runs = availableRuns();
        if (runs.isEmpty()) {
            return Optional.empty();
        }
        String runId = requestedRun == null || requestedRun.isBlank() ? runs.get(0)
                : runs.stream().filter(requestedRun::equals).findFirst().orElse(null);
        if (runId == null) {
            return Optional.empty();
        }

        Cached existing = cached.get();
        if (existing != null && existing.runId().equals(runId)
                && Duration.between(existing.readAt(), Instant.now()).compareTo(ttl) < 0) {
            return Optional.of(existing.run());
        }

        AnalyticsRun run = load(runId);
        cached.set(new Cached(Instant.now(), runId, run));
        return Optional.of(run);
    }

    private AnalyticsRun load(String runId) {
        Path runDirectory = root.resolve(runId);
        JsonNode summary = readJson(runDirectory.resolve("reports").resolve("report_summary.json"));
        JsonNode metadata = readJson(runDirectory.resolve("metadata").resolve("export_metadata.json"));

        Map<String, List<Map<String, Object>>> reports = new LinkedHashMap<>();
        for (String report : REPORTS) {
            reports.put(report, readCsv(runDirectory.resolve("reports").resolve(report + ".csv")));
        }

        return new AnalyticsRun(
                runId,
                windowBound(runId, 0),
                windowBound(runId, 1),
                instant(summary, "generated_at"),
                instant(metadata, "created_at"),
                text(metadata, "analytics_version"),
                text(metadata, "application_version"),
                summary == null ? 0d : summary.path("duration_seconds").asDouble(0d),
                false,
                object(summary, "headline"),
                object(summary, "fact_row_counts"),
                object(summary, "data_quality"),
                reports);
    }

    private JsonNode readJson(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                log.warn("Analytics file {} is larger than the dashboard will read; ignoring",
                        file.getFileName());
                return null;
            }
            return objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Analytics file {} could not be read: {}", file.getFileName(),
                    e.getClass().getSimpleName());
            return null;
        }
    }

    List<Map<String, Object>> readCsv(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return List.of();
            }
            return Csv.parse(Files.readString(file, StandardCharsets.UTF_8), MAX_ROWS_PER_REPORT);
        } catch (IOException e) {
            log.warn("Analytics report {} could not be read: {}", file.getFileName(),
                    e.getClass().getSimpleName());
            return List.of();
        }
    }

    private static Instant windowBound(String runId, int index) {
        String[] halves = runId.split("__", 2);
        if (halves.length != 2) {
            return null;
        }
        try {
            return LocalDateTime.parse(halves[index], WINDOW).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant instant(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field) || !node.get(field).isTextual()) {
            return null;
        }
        return node.get(field).asText();
    }

    private Map<String, Object> object(JsonNode node, String field) {
        if (node == null || !node.has(field) || !node.get(field).isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node.get(field), Map.class);
    }

    /**
     * Enough of RFC 4180 to read what Spark writes: quoted fields, embedded commas and newlines,
     * and doubled quotes. Adding a CSV dependency to parse seven files this application generated
     * itself would be a larger change than the parser.
     */
    static final class Csv {

        private Csv() {
        }

        static List<Map<String, Object>> parse(String content, int maxRows) {
            List<List<String>> rows = split(content, maxRows + 1);
            if (rows.isEmpty()) {
                return List.of();
            }
            List<String> header = rows.get(0);
            List<Map<String, Object>> parsed = new ArrayList<>(rows.size() - 1);
            for (int i = 1; i < rows.size(); i++) {
                List<String> cells = rows.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int column = 0; column < header.size(); column++) {
                    row.put(header.get(column),
                            typed(column < cells.size() ? cells.get(column) : null));
                }
                parsed.add(row);
            }
            return List.copyOf(parsed);
        }

        /** Empty becomes null, not zero: a missing percentile is not a percentile of nothing. */
        private static Object typed(String raw) {
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            try {
                return Long.valueOf(raw);
            } catch (NumberFormatException notALong) {
                try {
                    double value = Double.parseDouble(raw);
                    return Double.isFinite(value) ? Double.valueOf(value) : raw;
                } catch (NumberFormatException notADouble) {
                    return raw;
                }
            }
        }

        private static List<List<String>> split(String content, int maxRows) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;

            for (int i = 0; i < content.length() && rows.size() < maxRows; i++) {
                char c = content.charAt(i);
                if (quoted) {
                    if (c != '"') {
                        cell.append(c);
                    } else if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                    continue;
                }
                switch (c) {
                    case '"' -> quoted = true;
                    case ',' -> {
                        row.add(cell.toString());
                        cell.setLength(0);
                    }
                    case '\r' -> { /* a bare CR belongs to the CRLF ahead of it */ }
                    case '\n' -> {
                        row.add(cell.toString());
                        cell.setLength(0);
                        rows.add(List.copyOf(row));
                        row.clear();
                    }
                    default -> cell.append(c);
                }
            }
            if (!cell.isEmpty() || !row.isEmpty()) {
                row.add(cell.toString());
                rows.add(List.copyOf(row));
            }
            return rows;
        }
    }
}
