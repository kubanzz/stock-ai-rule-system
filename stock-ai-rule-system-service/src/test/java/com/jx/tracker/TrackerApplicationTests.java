package com.jx.tracker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;

@SpringBootTest(properties = {
        "spring.datasource.druid.initialSize=0",
        "spring.datasource.druid.minIdle=0"
})
@TestExecutionListeners(value = {}, mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TrackerApplicationTests {

    @Test
    void contextLoads() {
    }

}
