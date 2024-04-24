package io.github.suppie.spring.cache;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** A base class for integration tests that require a Redis container. */
abstract class AbstractRedisIntegrationTest {
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.2.4-alpine")).withExposedPorts(6379);

  static {
    REDIS.start();

    System.setProperty("HOST", REDIS.getHost());
    System.setProperty("PORT", String.valueOf(REDIS.getFirstMappedPort()));
  }
}
