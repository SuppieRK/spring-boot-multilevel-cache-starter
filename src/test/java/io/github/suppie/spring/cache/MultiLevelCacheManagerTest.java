/*
 * MIT License
 *
 * Copyright (c) 2024 Roman Khlebnov
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.suppie.spring.cache;

import io.github.suppie.spring.cache.MultiLevelCacheManager.RandomizedLocalExpiry;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(
    classes = {
      DataRedisAutoConfiguration.class,
      CacheAutoConfiguration.class,
      MultiLevelCacheAutoConfiguration.class,
      MultiLevelCacheManager.class
    })
class MultiLevelCacheManagerTest extends AbstractRedisIntegrationTest {
  @Autowired MultiLevelCacheManager cacheManager;

  @Test
  void cacheNamesTest() {
    final String key = "cacheNamesTest";

    Assertions.assertDoesNotThrow(
        () -> cacheManager.getCache(key), "Cache should be automatically created upon request");
    Assertions.assertTrue(
        cacheManager.getCacheNames().contains(key), "Cache name must be accessible");
  }

  @Nested
  class RandomizedLocalExpiryTest {
    @Test
    void negativeTimeToLive() {
      MultiLevelCacheConfigurationProperties properties =
          new MultiLevelCacheConfigurationProperties();
      properties.setTimeToLive(Duration.ofSeconds(1).negated());

      Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> new RandomizedLocalExpiry(properties),
          "Negative TTL must throw an exception");
    }

    @Test
    void zeroTimeToLive() {
      MultiLevelCacheConfigurationProperties properties =
          new MultiLevelCacheConfigurationProperties();
      properties.setTimeToLive(Duration.ZERO);

      Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> new RandomizedLocalExpiry(properties),
          "Zero TTL must throw an exception");
    }

    @Test
    void negativeExpiryJitter() {
      MultiLevelCacheConfigurationProperties properties =
          new MultiLevelCacheConfigurationProperties();
      properties.getLocal().setExpiryJitter(-1);

      Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> new RandomizedLocalExpiry(properties),
          "Negative expiry jitter must throw an exception");
    }

    @Test
    void tooBigExpiryJitter() {
      MultiLevelCacheConfigurationProperties properties =
          new MultiLevelCacheConfigurationProperties();
      properties.getLocal().setExpiryJitter(200);

      Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> new RandomizedLocalExpiry(properties),
          "Too big expiry jitter must throw an exception");
    }

    @Test
    void negativeLocalTimeToLive() {
      MultiLevelCacheConfigurationProperties properties =
          new MultiLevelCacheConfigurationProperties();
      properties.getLocal().setTimeToLive(Optional.of(Duration.ofSeconds(1).negated()));

      Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> new RandomizedLocalExpiry(properties),
          "Negative TTL must throw an exception");
    }

    @Test
    void zeroLocalTimeToLive() {
      MultiLevelCacheConfigurationProperties properties =
          new MultiLevelCacheConfigurationProperties();
      properties.getLocal().setTimeToLive(Optional.of(Duration.ZERO));

      Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> new RandomizedLocalExpiry(properties),
          "Zero TTL must throw an exception");
    }

    @Test
    void expirationWithinConfiguredJitterRange() {
      MultiLevelCacheConfigurationProperties properties =
          new MultiLevelCacheConfigurationProperties();
      properties.setTimeToLive(Duration.ofSeconds(10));
      properties.getLocal().setExpiryJitter(20);

      RandomizedLocalExpiry expiry = new RandomizedLocalExpiry(properties);
      Duration ttl = Duration.ofSeconds(10);
      double baseMultiplier = 0.5d;
      double jitterFraction = properties.getLocal().getExpiryJitter() / 100d;
      long minNanos = (long) (ttl.toNanos() * baseMultiplier * (1 - jitterFraction));
      long maxNanos = (long) (ttl.toNanos() * baseMultiplier * (1 + jitterFraction));

      for (int i = 0; i < 100; i++) {
        long computedNanos = expiry.expireAfterCreate("key-" + i, "value", 0);
        Assertions.assertTrue(
            computedNanos >= minNanos && computedNanos <= maxNanos,
            "Computed expiration must respect jitter bounds");
      }
    }
  }
}
