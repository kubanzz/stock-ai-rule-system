package com.jx.tracker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.druid.master.url=jdbc:h2:mem:stock_ai_rule_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.druid.master.username=sa",
        "spring.datasource.druid.master.password=",
        "spring.datasource.druid.master.driver-class-name=org.h2.Driver",
        "spring.datasource.druid.initialSize=0",
        "spring.datasource.druid.minIdle=0",
        "spring.datasource.druid.maxActive=2",
        "spring.datasource.druid.validationQuery=SELECT 1"
})
class TrackerApplicationTests {

    @Test
    void contextLoads() {
    }

}
