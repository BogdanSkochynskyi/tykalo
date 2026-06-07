package io.tykalo;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests that need real backing services. Uses the singleton-container
 * pattern: a single Postgres 16 and a single Redis 7 container (matching docker-compose) are
 * started once when this class loads and reused across every test class via Spring's cached
 * context. They are deliberately never stopped between classes — that would invalidate pooled
 * connections in the cached context — and are torn down by Testcontainers' Ryuk when the JVM exits.
 *
 * <p>Postgres is wired via {@link ServiceConnection}; Redis is wired via
 * {@link DynamicPropertySource} (a plain {@link GenericContainer} has no service-connection support
 * of its own).
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
