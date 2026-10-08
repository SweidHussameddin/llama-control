package de.sweid.llamactl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Spring Boot entry point for the llama-control backend. */
@SpringBootApplication
@EnableScheduling
public class LlamaControlApplication {

  /**
   * JVM entry point.
   *
   * @param args process arguments forwarded to Spring
   */
  public static void main(String[] args) {
    SpringApplication.run(LlamaControlApplication.class, args);
  }
}
