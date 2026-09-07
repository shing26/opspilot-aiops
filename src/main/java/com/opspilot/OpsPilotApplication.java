package com.opspilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.opspilot.config.OpsPilotProperties;

@SpringBootApplication
@EnableConfigurationProperties(OpsPilotProperties.class)
public class OpsPilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpsPilotApplication.class, args);
    }
}
