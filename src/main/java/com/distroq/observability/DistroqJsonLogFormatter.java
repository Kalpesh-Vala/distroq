package com.distroq.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One JSON object per log line, with a fixed field set.
 *
 * <p>Selected with {@code logging.structured.format.console} in the production profile; the local
 * profile keeps Spring Boot's human-readable pattern, because a developer reading a stack trace in
 * a terminal is not helped by escaped newlines.
 *
 * <p>The field names are the ones the operational contract promises, spelled exactly as documented
 * rather than in a vendor schema's dotted form, so a query written against README.md works without
 * a translation table. {@code service}, {@code version} and {@code instanceId} come from the
 * environment and are therefore on every line without any call site having to remember them.
 *
 * <p>Nothing here decides what is safe to log. That is {@link LogFields}' job: this writes the MDC
 * it is given. It does not read request bodies, job payloads or configuration values.
 */
public class DistroqJsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private final String service;
    private final String version;
    private final String instanceId;

    public DistroqJsonLogFormatter(Environment environment) {
        this.service = environment.getProperty("spring.application.name", "distroq");
        this.version = environment.getProperty("distroq.service.version", "unknown");
        this.instanceId = environment.getProperty("distroq.instance.id", "unknown");
    }

    @Override
    public String format(ILoggingEvent event) {
        StringBuilder json = new StringBuilder(512).append('{');
        field(json, "timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString(), true);
        field(json, "level", event.getLevel().toString(), false);
        field(json, "logger", event.getLoggerName(), false);
        field(json, "thread", event.getThreadName(), false);
        field(json, "service", service, false);
        field(json, "version", version, false);
        field(json, "instanceId", instanceId, false);
        field(json, "message", event.getFormattedMessage(), false);

        Map<String, String> mdc = event.getMDCPropertyMap();
        Set<String> written = new LinkedHashSet<>();
        for (String key : LogFields.ORDERED) {
            String value = mdc.get(key);
            if (value != null) {
                field(json, key, value, false);
                written.add(key);
            }
        }
        for (Map.Entry<String, String> entry : mdc.entrySet()) {
            if (!written.contains(entry.getKey()) && entry.getValue() != null) {
                field(json, entry.getKey(), entry.getValue(), false);
            }
        }

        IThrowableProxy thrown = event.getThrowableProxy();
        if (thrown != null) {
            // errorType may already be in the MDC from an explicit LogContext; the exception's own
            // class name is written under a distinct key rather than overwriting it
            field(json, "exceptionType", thrown.getClassName(), false);
            field(json, "exceptionMessage", thrown.getMessage(), false);
            field(json, "stackTrace", ThrowableProxyUtil.asString(thrown), false);
        }
        return json.append("}\n").toString();
    }

    private static void field(StringBuilder json, String name, String value, boolean first) {
        if (value == null) {
            return;
        }
        if (!first) {
            json.append(',');
        }
        escape(json.append('"'), name).append("\":\"");
        escape(json, value).append('"');
    }

    private static StringBuilder escape(StringBuilder json, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json;
    }
}
