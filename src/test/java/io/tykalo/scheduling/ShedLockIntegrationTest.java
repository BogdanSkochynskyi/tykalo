package io.tykalo.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the AC #5 guarantee — "with two instances, one job runs only once" — directly against the
 * real JDBC {@link LockProvider}. Two acquisitions of the same lock name model two instances racing
 * for the same scheduled tick; only one may hold the lock at a time.
 */
class ShedLockIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LockProvider lockProvider;

    private static LockConfiguration lockNamed(final String name) {
        return new LockConfiguration(Instant.now(), name, Duration.ofMinutes(5), Duration.ZERO);
    }

    @Test
    void secondInstance_cannotAcquireLock_whileFirstHolds() {
        // Arrange — first "instance" grabs the lock
        final Optional<SimpleLock> first = lockProvider.lock(lockNamed("job-a"));
        assertThat(first).isPresent();

        try {
            // Act — second "instance" tries the same lock concurrently
            final Optional<SimpleLock> second = lockProvider.lock(lockNamed("job-a"));

            // Assert — it is refused, so the job body runs on only one node
            assertThat(second).isEmpty();
        } finally {
            first.get().unlock();
        }
    }

    @Test
    void lock_isReacquirable_afterRelease() {
        // Arrange
        lockProvider.lock(lockNamed("job-b")).orElseThrow().unlock();

        // Act — the next tick (after the previous holder released) may run
        final Optional<SimpleLock> next = lockProvider.lock(lockNamed("job-b"));

        // Assert
        assertThat(next).isPresent();
        next.get().unlock();
    }

    @Test
    void differentLockNames_doNotBlockEachOther() {
        // Arrange
        final Optional<SimpleLock> jobC = lockProvider.lock(lockNamed("job-c"));
        assertThat(jobC).isPresent();

        try {
            // Act — an unrelated job holds its own lock independently
            final Optional<SimpleLock> jobD = lockProvider.lock(lockNamed("job-d"));

            // Assert
            assertThat(jobD).isPresent();
            jobD.get().unlock();
        } finally {
            jobC.get().unlock();
        }
    }
}
