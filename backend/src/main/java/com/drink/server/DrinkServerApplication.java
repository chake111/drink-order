package com.drink.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class DrinkServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DrinkServerApplication.class, args);
    }

}
