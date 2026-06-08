package io.tykalo.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables fixed-interval {@code @Scheduled} sweep jobs and guards them with ShedLock so that, on a
 * multi-instance deploy, each scheduled tick runs on exactly one node.
 *
 * <p>The {@link LockProvider} is backed by the application datasource (the {@code shedlock} table,
 * created in migration V5). {@code usingDbTime()} makes ShedLock read lock timestamps from the
 * database clock rather than each JVM's clock, so the lock stays correct even when instance clocks
 * drift apart. {@code defaultLockAtMostFor} is the safety net that releases a lock if the holder
 * dies mid-job; individual jobs override it via {@code @SchedulerLock(lockAtMostFor = ...)}.
 *
 * <p>This is distinct from Quartz's own clustered JDBC JobStore (configured via {@code spring.quartz}
 * in {@code application.yml}): Quartz coordinates dynamically scheduled, persistent jobs (future
 * per-task reminders and escalations), while ShedLock coordinates these periodic {@code @Scheduled}
 * methods.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(final DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
