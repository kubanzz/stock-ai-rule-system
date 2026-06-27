package com.jx.tracker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.druid.initialSize=0",
        "spring.datasource.druid.minIdle=0"
})
class TrackerApplicationTests {

    @Test
    void contextLoads() {
    }

}
