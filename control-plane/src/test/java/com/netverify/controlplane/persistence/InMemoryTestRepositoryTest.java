package com.netverify.controlplane.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryTestRepositoryTest {

    private InMemoryTestRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTestRepository();
    }

    @Test
    void listOrdersByCreatedAtThenCanonicalUuidText() {
        Instant sameInstant = Instant.parse("2026-01-01T00:00:00Z");
        Instant earlier = Instant.parse("2025-12-31T23:59:59Z");
        UUID idLowText = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID idHighText = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID idEarlier = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        // Same createdAt: insert reverse of canonical UUID-text order.
        repository.insert(new TestEntity(
                idHighText, "high", "basic_ping_pong", 1, null, sameInstant
        ));
        repository.insert(new TestEntity(
                idLowText, "low", "basic_ping_pong", 1, null, sameInstant
        ));
        repository.insert(new TestEntity(
                idEarlier, "earlier", "basic_ping_pong", 1, null, earlier
        ));

        List<UUID> listed = repository.list().stream().map(TestEntity::id).toList();
        // earlier by createdAt; then 0000... before ffff... by UUID.toString()
        assertEquals(List.of(idEarlier, idLowText, idHighText), listed);
        assertTrue(idLowText.toString().compareTo(idHighText.toString()) < 0);
    }

    @Test
    void duplicateInsertRejected() {
        UUID id = UUID.randomUUID();
        TestEntity entity = new TestEntity(
                id, "x", "basic_ping_pong", 1, null, Instant.now()
        );
        repository.insert(entity);
        assertThrows(IllegalStateException.class, () -> repository.insert(entity));
        assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
    }
}
