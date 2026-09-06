package com.netverify.controlplane.domain;

import java.util.Set;

/**
 * HTTP-allowed NetVerify scenarios (non-privileged only).
 * latency_injection is intentionally excluded.
 */
public final class AllowedScenarios {

    public static final String BASIC_PING_PONG = "basic_ping_pong";
    public static final String CONNECT_FAIL_NO_SERVER = "connect_fail_no_server";
    public static final String SERVER_CRASH_MID_SESSION = "server_crash_mid_session";
    public static final String CONCURRENT_LOAD = "concurrent_load";

    public static final Set<String> ALL = Set.of(
            BASIC_PING_PONG,
            CONNECT_FAIL_NO_SERVER,
            SERVER_CRASH_MID_SESSION,
            CONCURRENT_LOAD
    );

    public static final int COUNT_MIN = 1;
    public static final int COUNT_MAX = 1_000_000;
    public static final int CLIENTS_MIN = 1;
    public static final int CLIENTS_MAX = 1000;
    public static final int CLIENTS_DEFAULT = 10;
    public static final int NAME_MAX_LENGTH = 200;

    private AllowedScenarios() {
    }

    public static boolean isAllowed(String scenario) {
        return scenario != null && ALL.contains(scenario);
    }

    public static boolean isConcurrentLoad(String scenario) {
        return CONCURRENT_LOAD.equals(scenario);
    }
}
