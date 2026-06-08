package io.tykalo.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Verifies the Quartz + ShedLock infrastructure is wired: the Quartz scheduler runs on the clustered
 * JDBC JobStore and the backing tables (qrtz_* and shedlock) were created by Flyway migration V5.
 */
class SchedulingInfrastructureIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void quartzScheduler_isStartedAndClustered() throws SchedulerException {
        // Assert — a JDBC clustered scheduler is running
        assertThat(scheduler.isStarted()).isTrue();
        assertThat(scheduler.getMetaData().isJobStoreClustered()).isTrue();
        assertThat(scheduler.getMetaData().getJobStoreClass().getName()).contains("JobStore");
    }

    @Test
    void quartzAndShedlockTables_exist() {
        // Act
        final List<String> tables = jdbcClient
                .sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
                .query(String.class)
                .list();

        // Assert — a representative Quartz table, the cluster lock table, and the ShedLock table
        assertThat(tables).contains("qrtz_triggers", "qrtz_locks", "qrtz_fired_triggers", "shedlock");
    }
}
