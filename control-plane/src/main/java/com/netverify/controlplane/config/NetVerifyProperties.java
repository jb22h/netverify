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

    /** Wall-clock timeout for a single Python runner process. */
    private int runnerTimeoutSeconds = 120;

    /** Interpreter used to launch the automation runner. */
    private String pythonCommand = "python3";

    /**
     * Process role: blank (local/default), {@code api}, or {@code worker}.
     */
    private String role = "";

    /**
     * Stable worker identity for leases and Redis consumer naming.
     * Bound from {@code NETVERIFY_WORKER_ID}; required when {@code role=worker}.
     */
    private String workerId = "";

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

    public String getPythonCommand() {
        return pythonCommand;
    }

    public void setPythonCommand(String pythonCommand) {
        this.pythonCommand = pythonCommand;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }
}
