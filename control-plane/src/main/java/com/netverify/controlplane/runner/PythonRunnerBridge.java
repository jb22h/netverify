package com.netverify.controlplane.runner;

import com.netverify.controlplane.config.NetVerifyProperties;
import com.netverify.controlplane.domain.AllowedScenarios;
import com.netverify.controlplane.domain.FailureCategory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.netverify.controlplane.execution.ConditionalOnPythonExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Owns ProcessBuilder execution of {@code automation/runner.py}.
 *
 * <p>argv is always a fixed-shape list built from validated, whitelisted values:
 * no shell, no command-string concatenation, no caller-supplied paths.
 *
 * <p>stdout/stderr are redirected to temporary files rather than left as
 * unconsumed pipes, so a chatty runner cannot deadlock on pipe backpressure.
 */
@Component
@ConditionalOnPythonExecution
public class PythonRunnerBridge {

    static final int MAX_CAPTURED_BYTES = 1024 * 1024;
    private static final long KILL_WAIT_SECONDS = 5;
    /** Overall budget to discover and gracefully stop descendants after parent.destroy(). */
    private static final long DESCENDANT_DRAIN_SECONDS = 2;
    /** Park between rescans when at least one known descendant is still alive. */
    private static final long RESCAN_SLICE_MILLIS = 50;
    /**
     * Park between rescans when no descendants are known yet. Must stay short: a
     * long wait is a window where the parent can fork a child and then exit,
     * leaving an orphan that {@code process.descendants()} can never show again.
     */
    private static final long EMPTY_RESCAN_SLICE_MILLIS = 1;
    /**
     * When the first snapshot is empty (interrupt/timeout during runner startup),
     * observe briefly for the first child fork before SIGTERM on the parent.
     * Caps how long a still-active parent may spawn before shutdown is requested.
     */
    private static final long STARTUP_OBSERVE_MILLIS = 200;

    private static final Logger log = LoggerFactory.getLogger(PythonRunnerBridge.class);

    private final NetVerifyProperties properties;
    private final RunnerOutcomeMapper outcomeMapper;

    public PythonRunnerBridge(NetVerifyProperties properties, RunnerOutcomeMapper outcomeMapper) {
        this.properties = properties;
        this.outcomeMapper = outcomeMapper;
    }

    public PythonRunOutcome run(String scenario, int count, Integer clients) {
        if (!AllowedScenarios.isAllowed(scenario)) {
            return PythonRunOutcome.preLaunchFailure(
                    FailureCategory.SETUP,
                    "scenario is not permitted through the control plane: " + scenario
            );
        }

        int timeoutSeconds = properties.getRunnerTimeoutSeconds();
        if (timeoutSeconds <= 0) {
            return PythonRunOutcome.preLaunchFailure(
                    FailureCategory.SETUP,
                    "netverify.runner-timeout-seconds must be > 0, got " + timeoutSeconds
            );
        }

        Path repoRoot;
        try {
            repoRoot = resolveRepoRoot();
        } catch (RepoRootException ex) {
            return PythonRunOutcome.preLaunchFailure(FailureCategory.SETUP, ex.getMessage());
        }

        return execute(buildCommand(scenario, count, clients), repoRoot, timeoutSeconds);
    }

    private Path resolveRepoRoot() throws RepoRootException {
        String configured = properties.getRepoRoot();
        if (configured == null || configured.isBlank()) {
            throw new RepoRootException(
                    "netverify.repo-root is not configured (set NETVERIFY_REPO_ROOT)"
            );
        }

        Path root;
        try {
            root = Path.of(configured).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            throw new RepoRootException("netverify.repo-root is not a valid path: " + configured);
        }
        if (!Files.isDirectory(root)) {
            throw new RepoRootException("netverify.repo-root is not a directory: " + root);
        }
        for (String required : List.of("automation/runner.py", "net_server", "net_client")) {
            if (!Files.isRegularFile(root.resolve(required))) {
                throw new RepoRootException(
                        "missing " + required + " under netverify.repo-root " + root
                                + " (run `make` first)"
                );
            }
        }
        return root;
    }

    private List<String> buildCommand(String scenario, int count, Integer clients) {
        List<String> command = new ArrayList<>();
        command.add(properties.getPythonCommand());
        command.add("automation/runner.py");
        command.add("--test");
        command.add(scenario);
        command.add("--count");
        command.add(Integer.toString(count));
        if (AllowedScenarios.isConcurrentLoad(scenario) && clients != null) {
            command.add("--clients");
            command.add(Integer.toString(clients));
        }
        command.add("--json");
        return List.copyOf(command);
    }

    private PythonRunOutcome execute(List<String> command, Path repoRoot, int timeoutSeconds) {
        Path stdoutFile = null;
        Path stderrFile = null;
        try {
            stdoutFile = Files.createTempFile("netverify-runner-stdout-", ".log");
            stderrFile = Files.createTempFile("netverify-runner-stderr-", ".log");

            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(repoRoot.toFile())
                    .redirectOutput(stdoutFile.toFile())
                    .redirectError(stderrFile.toFile());

            Process process;
            try {
                process = builder.start();
            } catch (IOException ex) {
                return PythonRunOutcome.preLaunchFailure(
                        FailureCategory.LAUNCH,
                        "failed to launch runner " + command + ": " + ex.getMessage()
                );
            }

            boolean exited;
            try {
                exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                // Tear the tree down and read the captures before re-arming the
                // interrupt: a set flag makes bounded waits and file reads abort
                // immediately, which would leak processes and lose the logs.
                terminate(process);
                PythonRunOutcome outcome = PythonRunOutcome.failed(
                        FailureCategory.LAUNCH, null, null,
                        readCapped(stdoutFile), readCapped(stderrFile),
                        "runner wait was interrupted"
                );
                Thread.currentThread().interrupt();
                return outcome;
            }

            if (!exited) {
                terminate(process);
                return PythonRunOutcome.failed(
                        FailureCategory.TIMEOUT, null, null,
                        readCapped(stdoutFile), readCapped(stderrFile),
                        "runner exceeded " + timeoutSeconds + "s timeout and was terminated"
                );
            }

            return outcomeMapper.map(
                    process.exitValue(), readCapped(stdoutFile), readCapped(stderrFile)
            );
        } catch (IOException ex) {
            return PythonRunOutcome.preLaunchFailure(
                    FailureCategory.LAUNCH,
                    "failed to prepare runner output capture: " + ex.getMessage()
            );
        } finally {
            deleteQuietly(stdoutFile);
            deleteQuietly(stderrFile);
        }
    }

    /**
     * Terminates the runner and everything it spawned.
     *
     * <p>Strategy: snapshot descendants, request parent shutdown early (so the
     * runner stops launching new work), then drain/re-snapshot descendants for a
     * bounded window while the parent is still observable. Late forks that miss
     * the first snapshot are caught on a subsequent scan. Forcible kill follows,
     * then bounded waits. Never blocks indefinitely.
     *
     * <p>Cleanup parks the caller's interrupt flag on entry and restores it in
     * {@code finally}, because a set flag would abort every bounded wait and
     * leave the tree half-killed.
     */
    private void terminate(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            // Snapshot before signalling anything. If the tree is still empty
            // (interrupt during startup), keep observing briefly — without
            // signalling — so the first child is in {@code known} before the
            // parent gets SIGTERM and can disappear.
            Set<ProcessHandle> known = new LinkedHashSet<>(process.descendants().toList());
            if (known.isEmpty()) {
                interrupted |= observeStartupDescendants(process, known);
            }

            // Request shutdown so the runner stops launching further work. The
            // process tree remains discoverable until the parent is reaped.
            process.destroy();
            destroyQuietly(known);

            interrupted |= drainDescendants(process, known);

            for (ProcessHandle handle : known) {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }

            interrupted |= awaitExit(process, KILL_WAIT_SECONDS);
            interrupted |= awaitAllExit(known, KILL_WAIT_SECONDS);

            for (ProcessHandle handle : known) {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            interrupted |= awaitExit(process, KILL_WAIT_SECONDS);
            interrupted |= awaitAllExit(known, KILL_WAIT_SECONDS);

            if (process.isAlive()) {
                log.warn("runner process {} still alive after forcible termination", process.pid());
            }
            for (ProcessHandle handle : known) {
                if (handle.isAlive()) {
                    log.warn("runner descendant {} still alive after forcible termination",
                            handle.pid());
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Bounded pre-shutdown watch for the first descendant when none were visible
     * yet. Discovery only — no signals — so the later destroy() still means
     * "request shutdown early" relative to forcible kill and final waits.
     */
    private static boolean observeStartupDescendants(Process process, Set<ProcessHandle> known) {
        boolean interrupted = false;
        long deadlineNanos =
                System.nanoTime() + Duration.ofMillis(STARTUP_OBSERVE_MILLIS).toNanos();
        long sliceNanos = Duration.ofMillis(EMPTY_RESCAN_SLICE_MILLIS).toNanos();
        while (known.isEmpty() && process.isAlive() && System.nanoTime() < deadlineNanos) {
            known.addAll(process.descendants().toList());
            if (!known.isEmpty()) {
                break;
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            interrupted |= awaitAnyExitOrTimeout(
                    known, process, Math.min(sliceNanos, remaining)
            );
        }
        return interrupted;
    }

    /**
     * After parent.destroy(), re-scan for late-forked descendants while the
     * parent is still observable. Short empty-set slices avoid a window where
     * the parent forks and exits unseen; one await must never consume the whole
     * drain budget.
     */
    private static boolean drainDescendants(Process process, Set<ProcessHandle> known) {
        boolean interrupted = false;
        long deadlineNanos =
                System.nanoTime() + Duration.ofSeconds(DESCENDANT_DRAIN_SECONDS).toNanos();
        long liveSliceNanos = Duration.ofMillis(RESCAN_SLICE_MILLIS).toNanos();
        long emptySliceNanos = Duration.ofMillis(EMPTY_RESCAN_SLICE_MILLIS).toNanos();

        // Immediate post-destroy scan before any park (catches children already forked).
        mergeAndDestroyDescendants(process, known);

        while (System.nanoTime() < deadlineNanos) {
            mergeAndDestroyDescendants(process, known);

            if (!process.isAlive()) {
                // Parent reaped: tree discovery is over. One last merge above may
                // still have caught children observed just before exit.
                break;
            }

            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                break;
            }

            boolean anyLive = false;
            for (ProcessHandle handle : known) {
                if (handle.isAlive()) {
                    anyLive = true;
                    break;
                }
            }
            long slice = Math.min(anyLive ? liveSliceNanos : emptySliceNanos, remaining);
            interrupted |= awaitAnyExitOrTimeout(known, process, slice);
        }
        return interrupted;
    }

    private static void mergeAndDestroyDescendants(Process process, Set<ProcessHandle> known) {
        // Safe if the parent has already exited: descendants() is then empty.
        for (ProcessHandle handle : process.descendants().toList()) {
            if (known.add(handle)) {
                handle.destroy();
            } else if (handle.isAlive()) {
                handle.destroy();
            }
        }
    }

    /**
     * Parks for up to {@code nanos} waiting for any known descendant (or the
     * parent) to exit. Returns when one exits, the parent dies, or the slice
     * elapses — so the caller can re-scan for late forks.
     */
    private static boolean awaitAnyExitOrTimeout(
            Set<ProcessHandle> known,
            Process process,
            long nanos
    ) {
        boolean interrupted = false;
        long deadlineNanos = System.nanoTime() + nanos;
        while (process.isAlive() && System.nanoTime() < deadlineNanos) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            ProcessHandle live = null;
            for (ProcessHandle handle : known) {
                if (handle.isAlive()) {
                    live = handle;
                    break;
                }
            }
            try {
                if (live != null) {
                    live.onExit().get(remaining, TimeUnit.NANOSECONDS);
                    return interrupted;
                }
                // No known live descendants yet: brief parent wait, not a busy-spin.
                process.waitFor(remaining, TimeUnit.NANOSECONDS);
                return interrupted;
            } catch (InterruptedException ex) {
                interrupted = true;
            } catch (ExecutionException | TimeoutException ex) {
                return interrupted;
            }
        }
        return interrupted;
    }

    private static void destroyQuietly(Set<ProcessHandle> handles) {
        for (ProcessHandle handle : handles) {
            try {
                handle.destroy();
            } catch (RuntimeException ignored) {
                // Process may have already exited between snapshot and signal.
            }
        }
    }

    /**
     * Waits up to {@code seconds} for the parent to exit, retrying within the
     * deadline if interrupted.
     *
     * @return true if an interrupt was absorbed and must be restored later
     */
    private static boolean awaitExit(Process process, long seconds) {
        boolean interrupted = false;
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (process.isAlive()) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                process.waitFor(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                // The throw cleared the flag; keep waiting out the deadline and
                // hand the interrupt back to the caller.
                interrupted = true;
            }
        }
        return interrupted;
    }

    /**
     * Waits for all handles within one shared deadline, never per-handle.
     *
     * @return true if an interrupt was absorbed and must be restored later
     */
    private static boolean awaitAllExit(Set<ProcessHandle> handles, long seconds) {
        boolean interrupted = false;
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        for (ProcessHandle handle : handles) {
            while (handle.isAlive()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    return interrupted;
                }
                try {
                    handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ex) {
                    interrupted = true;
                } catch (ExecutionException | TimeoutException ex) {
                    // Deadline elapsed or the handle is no longer observable; the
                    // forcible pass and warning above handle whatever remains.
                    break;
                }
            }
        }
        return interrupted;
    }

    private static String readCapped(Path path) {
        if (path == null) {
            return "";
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(MAX_CAPTURED_BYTES);
            boolean truncated = in.read() != -1;
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (truncated) {
                return text + System.lineSeparator()
                        + "[truncated: output exceeded " + MAX_CAPTURED_BYTES + " bytes]";
            }
            return text;
        } catch (IOException ex) {
            return "";
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("could not delete temporary runner log {}", path, ex);
        }
    }

    private static final class RepoRootException extends Exception {

        private RepoRootException(String message) {
            super(message);
        }
    }
}
