package com.aaa.aihealthcare;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.aaa.aihealthcare.dao")
public class AiHealthcareApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiHealthcareApplication.class, args);
    }

}
