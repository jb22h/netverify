package com.netverify.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "netverify")
public class NetVerifyProperties {

    /**
     * Absolute or relative path to the NetVerify repository root
     * (directory containing automation/ and net_server).
     * Overridable via NETVERIFY_REPO_ROOT.
     */
    private String repoRoot = "";

    /** Wall-clock timeout for a single Python runner process (Milestone 2). */
    private int runnerTimeoutSeconds = 120;

    public String getRepoRoot() {
        return repoRoot;
    }

    public void setRepoRoot(String repoRoot) {
        this.repoRoot = repoRoot;
    }

    public int getRunnerTimeoutSeconds() {
        return runnerTimeoutSeconds;
    }

    public void setRunnerTimeoutSeconds(int runnerTimeoutSeconds) {
        this.runnerTimeoutSeconds = runnerTimeoutSeconds;
    }
}
