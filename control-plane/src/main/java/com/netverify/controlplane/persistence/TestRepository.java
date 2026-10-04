package com.netverify.controlplane.persistence;

import java.util.Optional;
import java.util.UUID;

public interface TestRepository {

    void insert(TestEntity test);

    Optional<TestEntity> findById(UUID id);
}
