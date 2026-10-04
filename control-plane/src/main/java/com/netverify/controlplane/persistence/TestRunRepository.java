package com.netverify.controlplane.persistence;

import java.util.Optional;
import java.util.UUID;

public interface TestRunRepository {

    void insert(TestRunEntity run);

    Optional<TestRunEntity> findById(UUID id);
}
