package com.faceless.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FacelessChronicleApp
{
    public static void main(String[] args) {
        SpringApplication.run(FacelessChronicleApp.class, args);
    }
}