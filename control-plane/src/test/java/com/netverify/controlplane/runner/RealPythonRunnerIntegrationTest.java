package com.netverify.controlplane.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netverify.controlplane.config.NetVerifyProperties;
import com.netverify.controlplane.domain.TestStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Launches the real Python runner. Skipped unless NETVERIFY_REPO_ROOT points at
 * a repository whose C binaries have already been built with {@code make}.
 */
class RealPythonRunnerIntegrationTest {

    @Test
    void realBasicPingPongRunPasses() {
        Path repoRoot = configuredRepoRoot();

        NetVerifyProperties properties = new NetVerifyProperties();
        properties.setRepoRoot(repoRoot.toString());
        properties.setRunnerTimeoutSeconds(60);
        PythonRunnerBridge bridge = new PythonRunnerBridge(
                properties, new RunnerOutcomeMapper(new ObjectMapper())
        );

        PythonRunOutcome outcome = bridge.run("basic_ping_pong", 3, null);

        assertEquals(TestStatus.PASSED, outcome.terminalStatus(), outcome.stderr());
        assertEquals(0, outcome.exitCode());
        assertNotNull(outcome.runnerResult());
        assertTrue(outcome.runnerResult().passed());
        assertEquals("basic_ping_pong", outcome.runnerResult().name());
        assertEquals(3, outcome.runnerResult().metrics().get("requested_count"));
    }

    private static Path configuredRepoRoot() {
        String configured = System.getenv("NETVERIFY_REPO_ROOT");
        assumeTrue(
                configured != null && !configured.isBlank(),
                "NETVERIFY_REPO_ROOT is not configured"
        );

        Path root = Path.of(configured).toAbsolutePath().normalize();
        assumeTrue(Files.isDirectory(root), "NETVERIFY_REPO_ROOT is not a directory: " + root);
        assumeTrue(
                Files.isRegularFile(root.resolve("automation/runner.py")),
                "automation/runner.py missing under " + root
        );
        assumeTrue(
                Files.isRegularFile(root.resolve("net_server"))
                        && Files.isRegularFile(root.resolve("net_client")),
                "net_server / net_client not built under " + root
        );
        return root;
    }
}
