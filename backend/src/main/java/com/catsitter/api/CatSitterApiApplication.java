package com.catsitter.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.context.annotation.Bean;
import org.flywaydb.core.Flyway;
import javax.sql.DataSource;


@SpringBootApplication
public class CatSitterApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(CatSitterApiApplication.class, args);
	}
}
