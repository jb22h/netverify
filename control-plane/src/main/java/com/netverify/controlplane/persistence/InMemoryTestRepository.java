package com.netverify.controlplane.persistence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * Process-local definition catalog for the default (non-{@code postgres}) profile.
 * Source of truth for immutable test configuration when PostgreSQL is not enabled.
 */
@Repository
@Profile("!postgres")
public class InMemoryTestRepository implements TestRepository {

    private final ConcurrentMap<UUID, TestEntity> tests = new ConcurrentHashMap<>();

    @Override
    public void insert(TestEntity test) {
        TestEntity previous = tests.putIfAbsent(test.id(), test);
        if (previous != null) {
            throw new IllegalStateException("duplicate test id: " + test.id());
        }
    }

    @Override
    public Optional<TestEntity> findById(UUID id) {
        return Optional.ofNullable(tests.get(id));
    }

    @Override
    public List<TestEntity> list() {
        List<TestEntity> list = new ArrayList<>(tests.values());
        list.sort(Comparator
                .comparing(TestEntity::createdAt)
                .thenComparing(entity -> entity.id().toString()));
        return List.copyOf(list);
    }
}
