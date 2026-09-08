package com.netverify.controlplane.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netverify.controlplane.config.NetVerifyProperties;
import com.netverify.controlplane.domain.FailureCategory;
import com.netverify.controlplane.domain.TestStatus;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the bridge without needing real Python: a small shell script stands
 * in for the interpreter so argv, working directory, output capture, exit-code
 * classification and timeout cleanup can all be asserted deterministically.
 */
class PythonRunnerBridgeTest {

    private static final String PASS_JSON_ECHO =
            "echo '{\"name\":\"basic_ping_pong\",\"passed\":true,\"failures\":[],"
                    + "\"client_exit_code\":0,\"duration_s\":0.01,"
                    + "\"metrics\":{\"requested_count\":3}}'";

    @TempDir
    Path tempDir;

    private NetVerifyProperties properties;
    private PythonRunnerBridge bridge;

    @BeforeEach
    void setUp() {
        properties = new NetVerifyProperties();
        properties.setRunnerTimeoutSeconds(30);
        bridge = new PythonRunnerBridge(properties, new RunnerOutcomeMapper(new ObjectMapper()));
    }

    @Test
    void blankRepoRootIsSetupFailure() {
        properties.setRepoRoot("");

        PythonRunOutcome outcome = bridge.run("basic_ping_pong", 3, null);

        assertEquals(TestStatus.FAILED, outcome.terminalStatus());
        assertEquals(FailureCategory.SETUP, outcome.failureCategory());
        assertTrue(outcome.message().contains("repo-root"), outcome.message());
    }

    @Test
    void missingBinaryIsSetupFailure() throws IOException {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("automation"));
        Files.writeString(repo.resolve("automation/runner.py"), "");
        Files.writeString(repo.resolve("net_server"), "");
        properties.setRepoRoot(repo.toString());

        PythonRunOutcome outcome = bridge.run("basic_ping_pong", 3, null);

        assertEquals(FailureCategory.SETUP, outcome.failureCategory());
        assertTrue(outcome.message().contains("net_client"), outcome.message());
    }

    @Test
    void nonPositiveTimeoutIsSetupFailure() throws IOException {
        properties.setRepoRoot(fakeRepo().toString());
        properties.setRunnerTimeoutSeconds(0);

        PythonRunOutcome outcome = bridge.run("basic_ping_pong", 3, null);

        assertEquals(FailureCategory.SETUP, outcome.failureCategory());
        assertTrue(outcome.message().contains("runner-timeout-seconds"), outcome.message());
    }

    @Test
    void disallowedScenarioNeverLaunches() throws IOException {
        properties.setRepoRoot(fakeRepo().toString());

        PythonRunOutcome outcome = bridge.run("latency_injection", 3, null);

        assertEquals(FailureCategory.SETUP, outcome.failureCategory());
        assertTrue(outcome.message().contains("not permitted"), outcome.message());
    }

    @Test
    void missingInterpreterIsLaunchFailure() throws IOException {
        properties.setRepoRoot(fakeRepo().toString());
        properties.setPythonCommand(tempDir.resolve("no-such-python").toString());

        PythonRunOutcome outcome = bridge.run("basic_ping_pong", 3, null);

        assertEquals(TestStatus.FAILED, outcome.terminalStatus());
        assertEquals(FailureCategory.LAUNCH, outcome.failureCategory());
        assertNull(outcome.exitCode());
    }

    @Test
    void passingRunnerProducesParsedPassedOutcome() throws IOException {
        assumePosix();
        Path repo = fakeRepo();
        writeFakeInterpreter(repo, """
                echo "args: $*" >&2
                """ + PASS_JSON_ECHO + """

                exit 0
                """);

        PythonRunOutcome outcome = bridge.run("basic_ping_pong", 3, null);

        assertEquals(TestStatus.PASSED, outcome.terminalStatus());
        assertEquals(0, outcome.exitCode());
        assertNotNull(outcome.runnerResult());
        assertTrue(outcome.runnerResult().passed());
        assertTrue(
                outcome.stderr().contains(
                        "automation/runner.py --test basic_ping_pong --count 3 --json"
                ),
                outcome.stderr()
        );
        assertFalse(outcome.stderr().contains("--clients"), outcome.stderr());
    }

    @Test
    void concurrentLoadAddsClientsFlag() throws IOException {
        assumePosix();
        Path repo = fakeRepo();
        writeFakeInterpreter(repo, """
                echo "args: $*" >&2
                echo '{"name":"concurrent_load","passed":true,"metrics":{}}'
                exit 0
                """);

        PythonRunOutcome outcome = bridge.run("concurrent_load", 100, 4);

        assertEquals(TestStatus.PASSED, outcome.terminalStatus());
        assertTrue(
                outcome.stderr().contains("--test concurrent_load --count 100 --clients 4 --json"),
                outcome.stderr()
        );
    }

    @Test
    void exitOneFromRunnerIsVerificationFailure() throws IOException {
        assumePosix();
        Path repo = fakeRepo();
        writeFakeInterpreter(repo, """
                echo '{"name":"basic_ping_pong","passed":false,"failures":["boom"],"metrics":{}}'
                exit 1
                """);

        PythonRunOutcome outcome = bridge.run("basic_ping_pong", 3, null);

        assertEquals(TestStatus.FAILED, outcome.terminalStatus());
        assertEquals(FailureCategory.VERIFICATION, outcome.failureCategory());
        assertEquals(1, outcome.exitCode());
        assertEquals(java.util.List.of("boom"), outcome.runnerResult().failures());
    }

    @Test
    void timeoutTerminatesRunnerAndItsDescendants() throws Exception {
        assumePosix();
        Path repo = fakeRepo();
        writeFakeInterpreter(repo, """
                sleep 30 &
                echo $! > child.pid
                wait
                """);
        properties.setRunnerTimeoutSeconds(1);

        PythonRunOutcome outcome = bridge.run("basic_ping_pong", 3, null);

        assertEquals(TestStatus.FAILED, outcome.terminalStatus());
        assertEquals(FailureCategory.TIMEOUT, outcome.failureCategory());
        assertNull(outcome.exitCode());

        Path pidFile = repo.resolve("child.pid");
        assumeTrue(Files.isRegularFile(pidFile), "fake interpreter did not record a child pid");
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        assertFalse(waitUntilDead(childPid), "spawned descendant survived timeout cleanup");
    }

    @Test
    void interruptedWaitCleansTreeThenRestoresInterruptFlag() throws Exception {
        assumePosix();
        Path repo = fakeRepo();
        writeFakeInterpreter(repo, """
                sleep 30 &
                echo $! > child.pid
                wait
                """);
        // Bounded so a swallowed interrupt fails fast instead of hanging.
        properties.setRunnerTimeoutSeconds(5);

        PythonRunOutcome outcome;
        boolean flagRestored;
        Thread.currentThread().interrupt();
        try {
            outcome = bridge.run("basic_ping_pong", 3, null);
            flagRestored = Thread.currentThread().isInterrupted();
        } finally {
            // Never leak an interrupt into the next test.
            Thread.interrupted();
        }

        assertTrue(flagRestored, "interrupt status must be restored after cleanup");
        assertEquals(TestStatus.FAILED, outcome.terminalStatus());
        assertEquals(FailureCategory.LAUNCH, outcome.failureCategory());
        assertNull(outcome.exitCode());
        assertTrue(outcome.message().contains("interrupted"), outcome.message());
        assertNoSurvivingDescendant(repo);
    }

    @Test
    void terminateCleansTreeEvenWhenThreadIsAlreadyInterrupted() throws Exception {
        assumePosix();
        Path repo = fakeRepo();
        writeFakeInterpreter(repo, """
                sleep 30 &
                echo $! > child.pid
                wait
                """);

        Process process = new ProcessBuilder(properties.getPythonCommand())
                .directory(repo.toFile())
                .redirectOutput(tempDir.resolve("out.log").toFile())
                .redirectError(tempDir.resolve("err.log").toFile())
                .start();
        awaitPidFile(repo.resolve("child.pid"));

        Method terminate = PythonRunnerBridge.class.getDeclaredMethod("terminate", Process.class);
        terminate.setAccessible(true);

        boolean flagRestored;
        Thread.currentThread().interrupt();
        try {
            terminate.invoke(bridge, process);
            flagRestored = Thread.currentThread().isInterrupted();
        } finally {
            Thread.interrupted();
        }

        assertTrue(flagRestored, "interrupt status must be restored after cleanup");
        assertFalse(process.isAlive(), "runner parent survived cleanup");
        assertNoSurvivingDescendant(repo);
    }

    private static void assertNoSurvivingDescendant(Path repo) throws Exception {
        Path pidFile = repo.resolve("child.pid");
        assumeTrue(Files.isRegularFile(pidFile), "fake interpreter did not record a child pid");
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        assertFalse(waitUntilDead(childPid), "spawned descendant survived cleanup");
    }

    private static void awaitPidFile(Path pidFile) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && !Files.isRegularFile(pidFile)) {
            Thread.sleep(25);
        }
    }

    private static boolean waitUntilDead(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                return false;
            }
            Thread.sleep(50);
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private Path fakeRepo() throws IOException {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("automation"));
        Files.writeString(repo.resolve("automation/runner.py"), "");
        Files.writeString(repo.resolve("net_server"), "");
        Files.writeString(repo.resolve("net_client"), "");
        properties.setRepoRoot(repo.toString());
        return repo;
    }

    private void writeFakeInterpreter(Path repo, String body) throws IOException {
        Path script = repo.resolve("fake-python.sh");
        Files.writeString(script, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        properties.setPythonCommand(script.toAbsolutePath().toString());
    }

    private static void assumePosix() {
        assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX file permissions required for the fake interpreter"
        );
    }
}
