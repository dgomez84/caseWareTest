package com.caseware.fanout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CasewareFanOutWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CasewareFanOutWorkerApplication.class, args);
    }
}
