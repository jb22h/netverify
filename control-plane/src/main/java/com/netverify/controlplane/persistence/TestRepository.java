package com.netverify.controlplane.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestRepository {

    void insert(TestEntity test);

    Optional<TestEntity> findById(UUID id);

    /**
     * Deterministic order across profiles:
     * created_at ASC, then canonical UUID text ASC ({@code id.toString()} /
     * {@code id::text}).
     */
    List<TestEntity> list();
}
