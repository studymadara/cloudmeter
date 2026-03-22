package io.cloudmeter.smoketestsb2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class SmokeTestSb2App {
    public static void main(String[] args) {
        SpringApplication.run(SmokeTestSb2App.class, args);
    }
}
