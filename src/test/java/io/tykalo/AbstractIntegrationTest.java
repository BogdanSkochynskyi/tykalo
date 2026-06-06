package io.tykalo;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests that need a real database. Uses the singleton-container
 * pattern: a single Postgres 16 container (matching docker-compose) is started once when this
 * class loads and reused across every test class via Spring's cached context. It is deliberately
 * never stopped between classes — that would invalidate the pooled connection in the cached
 * context — and is torn down by Testcontainers' Ryuk when the JVM exits.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
