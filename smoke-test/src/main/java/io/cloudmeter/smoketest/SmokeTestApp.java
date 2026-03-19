package io.cloudmeter.smoketest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class SmokeTestApp {

    public static void main(String[] args) {
        SpringApplication.run(SmokeTestApp.class, args);
    }
}
