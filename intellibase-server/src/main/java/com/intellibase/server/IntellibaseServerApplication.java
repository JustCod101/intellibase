package com.intellibase.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class IntellibaseServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntellibaseServerApplication.class, args);
    }

}
