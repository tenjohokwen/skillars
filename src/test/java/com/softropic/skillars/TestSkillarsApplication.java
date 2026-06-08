package com.softropic.skillars;

import com.softropic.skillars.config.TestConfig;

import org.springframework.boot.SpringApplication;

public class TestSkillarsApplication {

	public static void main(String[] args) {
		SpringApplication.from(SkillarsApplication::main).with(TestConfig.class).run(args);
	}

}
