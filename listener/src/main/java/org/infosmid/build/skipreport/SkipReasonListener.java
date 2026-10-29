package org.infosmid.build.skipreport;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Captures the <em>reason</em> a JUnit Platform test was skipped or aborted and
 * appends a machine-parseable, pipe-delimited line to a shared report file.
 *
 * <p>This is the only layer where skip reasons actually exist. Gradle's own
 * {@code TestListener}/{@code TestResult} collapses everything into
 * {@code SKIPPED} and discards the cause, so condition messages and assumption
 * exceptions must be captured here.</p>
 *
 * <p>Discovered automatically via the ServiceLoader file:
 * {@code META-INF/services/org.junit.platform.launcher.TestExecutionListener}.</p>
 *
 * <p>Output location is controlled by the {@code skipReport.file} system
 * property (set from the consuming build). Falls back to
 * {@code build/reports/skip-reasons.psv}.</p>
 */
public class SkipReasonListener implements TestExecutionListener {

    private static final String DELIM = " | ";

    /**
     * Buffer per JVM (one listener instance per test fork); flushed on plan end.
     */
    private final ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
    private Path reportFile;

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        String configured = System.getProperty("skipReport.file");
        this.reportFile = (configured != null)
                          ? Paths.get(configured)
                          : Paths.get("build", "reports", "skip-reasons.psv");
    }

    /**
     * Fired for {@code @Disabled}, {@code @EnabledIf*}, {@code @DisabledIf*},
     * {@code @EnabledOnOs}, {@code @EnabledForJreRange}, etc. The {@code reason}
     * is the message produced by the {@code ExecutionCondition} that vetoed the
     * test or container &mdash; this is where the "why" lives.
     */
    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        if (id.isContainer()) {
            record("CONTAINER_DISABLED", id, reason);
        } else {
            record("CONDITION_DISABLED", id, reason);
        }
    }

    /**
     * Fired when a node finishes. We only act on {@code ABORTED}, which is how
     * JUnit represents a failed runtime assumption
     * ({@code Assumptions.assumeTrue(...)} / {@code assumeThat(...)}).
     */
    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (!id.isTest()) {
            return;
        }
        if (result.getStatus() == TestExecutionResult.Status.ABORTED) {
            Optional<Throwable> t = result.getThrowable();
            String msg = t.map(this::describeAbort).orElse("assumption failed (no message)");
            record("ASSUMPTION_ABORTED", id, msg);
        }
    }

    private String describeAbort(Throwable t) {
        // TestAbortedException (Jupiter) or AssumptionViolatedException (vintage).
        String simple = t.getClass().getSimpleName();
        String m = t.getMessage();
        return simple + (m != null ? ": " + m : "");
    }

    private String cell(String input) {
        return (input == null || input.contains(DELIM) ? "\"" + input + "\"" : input).replace("\n", " ");
    }

    private void record(String category, TestIdentifier id, String reason) {
        String src = id.getSource().map(Object::toString).orElse("unknown-source");
        String clean = reason == null ? "" : reason.replace("\n", " ");
        lines.add(String.join(
                          DELIM,
                          cell(category),
                          cell(id.getUniqueId()),
                          cell(id.getDisplayName()),
                          cell(src),
                          cell(clean)
                  )
        );
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (lines.isEmpty()) {
            return;
        }
        try {
            Path parent = reportFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // APPEND: multiple test forks each hold their own listener instance.
            try (Writer w = Files.newBufferedWriter(reportFile, StandardCharsets.UTF_8,
                                                    StandardOpenOption.CREATE, StandardOpenOption.APPEND
            )
            ) {
                for (String line : lines) {
                    w.write(line);
                    w.write(System.lineSeparator());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing skip-reason report to " + reportFile, e);
        }
    }
}
