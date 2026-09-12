package com.distroq.dashboard;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading what the v0.9 pipeline already wrote, and nothing else.
 *
 * <p>Two properties are load-bearing. An empty cell must become null rather than zero — a missing
 * p99 is not a p99 of nothing, and a chart that plots it as zero invents a fast day. And a
 * requested run must be matched against the discovered list rather than joined onto a path, or the
 * run parameter is a directory traversal.
 */
class AnalyticsReportReaderTest {

    private static final DashboardProperties NO_CACHE = properties("does-not-exist");

    @Test
    void aMissingDirectoryIsAnEmptyStateAndNotAFailure() {
        AnalyticsReportReader reader = reader(NO_CACHE);

        assertThat(reader.availableRuns()).isEmpty();
        assertThat(reader.read(null)).isEmpty();
    }

    @Test
    void runsAreDiscoveredNewestFirst(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("20260101T000000Z__20270101T000000Z"));
        Files.createDirectories(root.resolve("20250101T000000Z__20260101T000000Z"));
        Files.createDirectories(root.resolve("not-a-run"));
        Files.createFile(root.resolve(".gitkeep"));

        assertThat(reader(properties(root.toString())).availableRuns())
                .containsExactly("20260101T000000Z__20270101T000000Z",
                        "20250101T000000Z__20260101T000000Z");
    }

    @Test
    void aRequestedRunIsMatchedAgainstTheDiscoveredListNotJoinedOntoAPath(@TempDir Path root)
            throws IOException {
        Files.createDirectories(root.resolve("20260101T000000Z__20270101T000000Z"));

        AnalyticsReportReader reader = reader(properties(root.toString()));

        assertThat(reader.read("../../../etc")).isEmpty();
        assertThat(reader.read("..\\..\\windows")).isEmpty();
        assertThat(reader.read("20990101T000000Z__20990101T000000Z")).isEmpty();
        assertThat(reader.read("20260101T000000Z__20270101T000000Z")).isPresent();
    }

    @Test
    void theWindowIsReadOutOfTheRunIdentifier(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("20260101T000000Z__20270101T000000Z"));

        AnalyticsReportReader.AnalyticsRun run =
                reader(properties(root.toString())).read(null).orElseThrow();

        assertThat(run.windowStart().toString()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(run.windowEnd().toString()).isEqualTo("2027-01-01T00:00:00Z");
        assertThat(run.exact()).isFalse();
    }

    @Test
    void anExportWithoutReportsStillLoadsWithEmptyTables(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("20260101T000000Z__20270101T000000Z"));

        AnalyticsReportReader.AnalyticsRun run =
                reader(properties(root.toString())).read(null).orElseThrow();

        assertThat(run.reports()).containsKey("daily_job_summary");
        assertThat(run.reports().get("daily_job_summary")).isEmpty();
        assertThat(run.headline()).isEmpty();
    }

    @Test
    void aRealReportIsParsedWithTypedValues(@TempDir Path root) throws IOException {
        Path reports = root.resolve("20260101T000000Z__20270101T000000Z").resolve("reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("report_summary.json"), """
                {
                  "generated_at": "2026-09-10T12:51:33.735023Z",
                  "duration_seconds": 32.492,
                  "run_id": "20260101T000000Z__20270101T000000Z",
                  "input": "/workspace/analytics/output/20260101T000000Z__20270101T000000Z",
                  "output": "/workspace/analytics/output/.../reports",
                  "headline": {"submitted_jobs": 95, "success_rate": 0.831579},
                  "fact_row_counts": {"job_facts": 95},
                  "data_quality": {"total_findings": 20, "worst_severity": "WARNING"}
                }
                """);
        Files.writeString(reports.resolve("daily_job_summary.csv"), """
                day,priority,submitted_jobs,average_duration_ms,p99_duration_ms
                2026-09-07,HIGH,11,871.182,4016
                2026-09-08,LOW,3,155.0,
                """);

        AnalyticsReportReader.AnalyticsRun run =
                reader(properties(root.toString())).read(null).orElseThrow();

        assertThat(run.generatedAt().toString()).startsWith("2026-09-10T12:51:33");
        assertThat(run.durationSeconds()).isEqualTo(32.492);
        assertThat(run.headline()).containsEntry("submitted_jobs", 95);

        List<Map<String, Object>> daily = run.reports().get("daily_job_summary");
        assertThat(daily).hasSize(2);
        assertThat(daily.get(0)).containsEntry("day", "2026-09-07")
                .containsEntry("submitted_jobs", 11L)
                .containsEntry("average_duration_ms", 871.182);
        // an absent percentile is null, never zero
        assertThat(daily.get(1).get("p99_duration_ms")).isNull();
    }

    @Test
    void absoluteFilesystemPathsInTheReportMetadataAreNotPassedThrough(@TempDir Path root)
            throws IOException {
        Path reports = root.resolve("20260101T000000Z__20270101T000000Z").resolve("reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("report_summary.json"),
                "{\"input\": \"/workspace/analytics/output/run\", "
                        + "\"output\": \"/var/lib/distroq/reports\", \"headline\": {\"a\": 1}}");

        AnalyticsReportReader.AnalyticsRun run =
                reader(properties(root.toString())).read(null).orElseThrow();

        assertThat(run.toString()).doesNotContain("/workspace").doesNotContain("/var/lib");
    }

    @Test
    void quotedCellsWithCommasAndDoubledQuotesAreParsed() {
        List<Map<String, Object>> rows = AnalyticsReportReader.Csv.parse("""
                check_name,severity,sample_ids,description
                dupes,WARNING,"a,b,c","says ""hello"", loudly"
                """, 100);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("sample_ids", "a,b,c")
                .containsEntry("description", "says \"hello\", loudly");
    }

    @Test
    void aRowShorterThanTheHeaderGetsNullsRatherThanShiftedColumns() {
        List<Map<String, Object>> rows = AnalyticsReportReader.Csv.parse(
                "a,b,c\n1,2\n", 100);

        assertThat(rows.get(0)).containsEntry("a", 1L).containsEntry("b", 2L);
        assertThat(rows.get(0).get("c")).isNull();
    }

    @Test
    void rowCountIsCappedSoAReportCannotBecomeADataDump() {
        StringBuilder csv = new StringBuilder("n\n");
        for (int i = 0; i < 50; i++) {
            csv.append(i).append('\n');
        }

        assertThat(AnalyticsReportReader.Csv.parse(csv.toString(), 10)).hasSize(10);
    }

    private static AnalyticsReportReader reader(DashboardProperties properties) {
        return new AnalyticsReportReader(JsonMapper.builder().findAndAddModules().build(),
                properties);
    }

    private static DashboardProperties properties(String analyticsDirectory) {
        return new DashboardProperties(true, 5000L, 50, 200, 100, 86_400_000L, 30_000L, 0L,
                analyticsDirectory, "dashboard/dist", 5000L);
    }
}
