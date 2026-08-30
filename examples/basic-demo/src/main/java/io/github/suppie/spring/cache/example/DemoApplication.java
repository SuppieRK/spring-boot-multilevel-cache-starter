package io.github.suppie.spring.cache.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/** Runs the example application with Spring's annotation-driven cache support enabled. */
@SpringBootApplication
@EnableCaching
public class DemoApplication {

  /**
   * Starts the example application.
   *
   * @param args command-line arguments passed to Spring Boot
   */
  public static void main(String[] args) {
    SpringApplication.run(DemoApplication.class, args);
  }
}
