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
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
public class PythonRunnerBridge {

    static final int MAX_CAPTURED_BYTES = 1024 * 1024;
    private static final long KILL_WAIT_SECONDS = 5;

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
     * <p>Descendants are snapshotted before the parent is signalled, because the
     * handle tree is unavailable once the parent is reaped. SIGKILL of the Python
     * parent does not run its cleanup handlers, so net_server / net_client are
     * terminated explicitly here too.
     *
     * <p>Graceful destroy first, then a bounded wait on parent and descendants,
     * then forcible destroy of whatever is still alive, then a final bounded
     * wait. Every wait has a deadline: this method never blocks indefinitely.
     *
     * <p>Cleanup runs to completion even when the calling thread is interrupted.
     * The interrupt flag is parked on entry and any interrupt absorbed during
     * cleanup is remembered, because a set flag makes every bounded wait below
     * throw immediately and would leave the process tree half-killed. The flag
     * is restored once cleanup has finished.
     */
    private void terminate(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            List<ProcessHandle> descendants = process.descendants().toList();

            descendants.forEach(ProcessHandle::destroy);
            process.destroy();

            interrupted |= awaitExit(process, KILL_WAIT_SECONDS);
            interrupted |= awaitAllExit(descendants, KILL_WAIT_SECONDS);

            boolean forced = false;
            for (ProcessHandle handle : descendants) {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                    forced = true;
                }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
                forced = true;
            }

            if (forced) {
                interrupted |= awaitExit(process, KILL_WAIT_SECONDS);
                interrupted |= awaitAllExit(descendants, KILL_WAIT_SECONDS);
            }

            if (process.isAlive()) {
                log.warn("runner process {} still alive after forcible termination", process.pid());
            }
            for (ProcessHandle handle : descendants) {
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
    private static boolean awaitAllExit(List<ProcessHandle> handles, long seconds) {
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
