package com.netverify.controlplane.persistence.jdbc;

import com.netverify.controlplane.persistence.TestEntity;
import com.netverify.controlplane.persistence.TestRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcTestRepository implements TestRepository {

    private static final RowMapper<TestEntity> ROW_MAPPER = (rs, rowNum) -> new TestEntity(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("scenario"),
            rs.getInt("count"),
            (Integer) rs.getObject("clients"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbcTemplate;

    public JdbcTestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(TestEntity test) {
        jdbcTemplate.update(
                """
                INSERT INTO tests (id, name, scenario, count, clients, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                test.id(),
                test.name(),
                test.scenario(),
                test.count(),
                test.clients(),
                Timestamp.from(test.createdAt())
        );
    }

    @Override
    public Optional<TestEntity> findById(UUID id) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    """
                    SELECT id, name, scenario, count, clients, created_at
                    FROM tests
                    WHERE id = ?
                    """,
                    ROW_MAPPER,
                    id
            ));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<TestEntity> list() {
        return List.copyOf(jdbcTemplate.query(
                """
                SELECT id, name, scenario, count, clients, created_at
                FROM tests
                ORDER BY created_at ASC, id::text ASC
                """,
                ROW_MAPPER
        ));
    }
}
