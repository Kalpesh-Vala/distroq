package com.distroq.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production log line.
 *
 * <p>Two things are being protected. The field set, because a query written against README.md must
 * keep working; and the absence of everything else, because the MDC is the one place a payload or
 * a credential could plausibly end up by accident and a log file is the least private artefact a
 * deployment produces.
 */
class StructuredLoggingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(StructuredLoggingTest.class);
        appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    void everyRequiredEventNameExists() throws Exception {
        List<String> declared = new ArrayList<>();
        for (Field field : Events.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                declared.add((String) field.get(null));
            }
        }

        assertThat(declared).contains(
                "job.submitted", "job.scheduled", "job.started", "job.succeeded",
                "job.retry_scheduled", "job.dead_lettered", "job.replayed", "job.reclaimed",
                "job.execution_claimed", "job.execution_lease_lost",
                "outbox.published", "outbox.failed", "outbox.operator_retry",
                "reconciliation.finding", "reconciliation.repair",
                "application.readiness_changed", "application.shutdown_started",
                "application.shutdown_completed");
    }

    @Test
    void aScopedContextAddsItsFieldsAndThenRestoresWhatWasThereBefore() {
        MDC.put(LogFields.WORKER_ID, "worker-a");

        try (LogContext ignored = LogContext.event(Events.JOB_STARTED)
                .job("11111111-1111-1111-1111-111111111111")
                .worker("worker-b")
                .priority("HIGH")) {
            logger.info("started");
            assertThat(MDC.get(LogFields.WORKER_ID)).isEqualTo("worker-b");
        }

        assertThat(MDC.get(LogFields.WORKER_ID)).isEqualTo("worker-a");
        assertThat(MDC.get(LogFields.EVENT)).isNull();
    }

    @Test
    void aNullFieldIsOmittedRatherThanWrittenAsTheStringNull() {
        try (LogContext ignored = LogContext.event(Events.JOB_STARTED).job(null).attempt(null)) {
            assertThat(MDC.get(LogFields.JOB_ID)).isNull();
            assertThat(MDC.get(LogFields.ATTEMPT_ID)).isNull();
        }
    }

    @Test
    void anExceptionContributesItsTypeAndNotItsMessage() {
        try (LogContext ignored = LogContext.empty()
                .errorType(new IllegalStateException("password=hunter2"))) {
            assertThat(MDC.get(LogFields.ERROR_TYPE))
                    .isEqualTo("java.lang.IllegalStateException")
                    .doesNotContain("hunter2");
        }
    }

    @Test
    void aFormattedLineIsOneParseableJsonObjectWithTheDocumentedFields() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "distroq")
                .withProperty("distroq.service.version", "1.0.0")
                .withProperty("distroq.instance.id", "host-abc12345");
        DistroqJsonLogFormatter formatter = new DistroqJsonLogFormatter(environment);

        String line;
        try (LogContext ignored = LogContext.event(Events.JOB_SUCCEEDED)
                .job("11111111-1111-1111-1111-111111111111")
                .priority("HIGH").status("SUCCEEDED").durationMs(42)) {
            logger.info("Job succeeded");
            // logback fills an event's MDC map lazily, so it has to be read inside the scope
            line = formatter.format(appender.list.get(0));
        }

        assertThat(line).endsWith("\n");
        JsonNode json = JSON.readTree(line);

        assertThat(json.get("timestamp").asText()).isNotBlank();
        assertThat(json.get("level").asText()).isEqualTo("INFO");
        assertThat(json.get("logger").asText()).contains("StructuredLoggingTest");
        assertThat(json.get("service").asText()).isEqualTo("distroq");
        assertThat(json.get("version").asText()).isEqualTo("1.0.0");
        assertThat(json.get("instanceId").asText()).isEqualTo("host-abc12345");
        assertThat(json.get("event").asText()).isEqualTo("job.succeeded");
        assertThat(json.get("jobId").asText()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(json.get("priority").asText()).isEqualTo("HIGH");
        assertThat(json.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(json.get("durationMs").asText()).isEqualTo("42");
        assertThat(json.get("message").asText()).isEqualTo("Job succeeded");
    }

    @Test
    void aMessageContainingQuotesAndNewlinesStaysOneValidJsonObject() throws Exception {
        DistroqJsonLogFormatter formatter = new DistroqJsonLogFormatter(new MockEnvironment());

        logger.info("a \"quoted\" value\nwith a newline\tand a tab");

        String line = formatter.format(appender.list.get(0));
        JsonNode json = JSON.readTree(line);

        assertThat(json.get("message").asText())
                .isEqualTo("a \"quoted\" value\nwith a newline\tand a tab");
        assertThat(line.chars().filter(c -> c == '\n').count()).isEqualTo(1);
    }

    @Test
    void anExceptionIsRenderedWithoutOverwritingAnExplicitErrorType() throws Exception {
        DistroqJsonLogFormatter formatter = new DistroqJsonLogFormatter(new MockEnvironment());

        String line;
        try (LogContext ignored = LogContext.empty().errorType("RedisTimeout")) {
            logger.error("failed", new IllegalStateException("boom"));
            line = formatter.format(appender.list.get(0));
        }

        JsonNode json = JSON.readTree(line);

        assertThat(json.get("errorType").asText()).isEqualTo("RedisTimeout");
        assertThat(json.get("exceptionType").asText()).isEqualTo("java.lang.IllegalStateException");
        assertThat(json.get("stackTrace").asText()).contains("IllegalStateException");
    }

    @Test
    void theDocumentedFieldOrderIsTheOneTheFormatterUses() {
        assertThat(LogFields.ORDERED).containsExactly(
                "event", "correlationId", "workerId", "consumerName", "jobId", "attemptId",
                "stream", "streamEntryId", "outboxEventId", "eventType", "priority", "status",
                "durationMs", "errorType");
    }
}
