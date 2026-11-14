package com.drink.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/drink_order_perf?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456"
})
class DrinkServerApplicationTests {

    @Test
    void contextLoads() {
    }

}
