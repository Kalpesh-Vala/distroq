package com.distroq.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Runs one panel's query and turns a failure into an unavailable {@link Section} rather than a
 * failed request.
 *
 * <p>This is what makes partial failure a first-class outcome. A dashboard that returns 503 the
 * moment Redis goes away hides the PostgreSQL-backed half of the picture at exactly the point an
 * operator needs it, and a dashboard that catches the failure and returns zeros is worse still.
 *
 * <p>The log line carries the panel name and the exception type only. {@code log.warn(msg, e)}
 * would print the message and the stack trace, and a Redis or Hikari failure message contains the
 * connection string.
 */
final class Sections {

    private static final Logger log = LoggerFactory.getLogger(Sections.class);

    private Sections() {
    }

    static <T> Section<T> read(String panel, Supplier<T> supplier) {
        try {
            return Section.available(supplier.get());
        } catch (RuntimeException e) {
            log.warn("Dashboard panel {} is unavailable: {}", panel, e.getClass().getSimpleName());
            return Section.unavailable(e);
        }
    }
}
