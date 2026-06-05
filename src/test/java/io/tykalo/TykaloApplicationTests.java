package io.tykalo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "telegram.bot.token=test-token")
class TykaloApplicationTests {

    @Test
    void contextLoads() {
    }
}
