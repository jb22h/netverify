package com.netverify.controlplane.persistence;

import java.time.Instant;
import java.util.UUID;

/** Row model for the {@code tests} table (not a JPA entity). */
public record TestEntity(
        UUID id,
        String name,
        String scenario,
        int count,
        Integer clients,
        Instant createdAt
) {
}
