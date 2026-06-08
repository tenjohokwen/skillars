package com.softropic.skillars;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class SkillarsApplication {

	public static void main(String[] args) {
		SpringApplication.run(SkillarsApplication.class, args);
	}

}
