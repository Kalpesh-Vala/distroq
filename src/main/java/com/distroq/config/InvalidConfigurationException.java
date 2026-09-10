package com.distroq.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup failed because the configuration is not usable.
 *
 * <p>Named properties and nothing else. The message ends up in a container's logs, which are the
 * least private place in a deployment, so it says which property is wrong and what the constraint
 * is — never the value, because the invalid property might be a URL with a password in it.
 */
public class InvalidConfigurationException extends IllegalStateException {

    private final transient List<String> problems;

    public InvalidConfigurationException(List<String> problems) {
        super(render(problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }

    private static String render(List<String> problems) {
        List<String> lines = new ArrayList<>();
        lines.add("DistroQ refused to start: " + problems.size()
                + " configuration problem(s) must be fixed first.");
        for (int i = 0; i < problems.size(); i++) {
            lines.add("  " + (i + 1) + ". " + problems.get(i));
        }
        return String.join(System.lineSeparator(), lines);
    }
}
