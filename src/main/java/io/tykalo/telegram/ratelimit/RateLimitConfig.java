package io.tykalo.telegram.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the Jackson 2 {@link ObjectMapper} the outbound queue uses to (de)serialize
 * {@link OutboundMessage} payloads. Spring Boot 4 auto-configures Jackson 3 (the {@code tools.jackson}
 * mapper) for the web layer, so there is no {@code com.fasterxml.jackson.databind.ObjectMapper} bean
 * to inject otherwise. A vanilla mapper is all the simple record payloads need;
 * {@code @ConditionalOnMissingBean} steps aside if a Jackson 2 mapper is ever configured elsewhere.
 */
@Configuration
class RateLimitConfig {

    @Bean
    @ConditionalOnMissingBean
    ObjectMapper outboundQueueObjectMapper() {
        return new ObjectMapper();
    }
}
