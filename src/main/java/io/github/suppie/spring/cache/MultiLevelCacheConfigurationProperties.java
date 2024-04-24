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

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import java.time.Duration;
import java.util.Optional;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

/** Simple set of properties to control most aspects of the multi-level cache functionality */
@Data
@ConfigurationProperties(prefix = "spring.cache.multilevel")
public class MultiLevelCacheConfigurationProperties {

  /** Time to live for Redis entries */
  private Duration timeToLive = Duration.ofHours(1L);

  /** Key prefix. */
  private String keyPrefix;

  /** Whether to use the key prefix when writing to Redis. */
  private boolean useKeyPrefix = false;

  /** Topic to use to synchronize eviction of entries */
  private String topic = "cache:multilevel:topic";

  /** Small subset of local cache settings */
  @NestedConfigurationProperty private LocalCacheProperties local = new LocalCacheProperties();

  /** Circuit breaker capability to avoid issues during Redis querying */
  @NestedConfigurationProperty
  private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();

  /**
   * @return configuration for Redis cache
   */
  public RedisCacheConfiguration toRedisCacheConfiguration() {
    RedisCacheConfiguration configuration =
        RedisCacheConfiguration.defaultCacheConfig()
            .disableCachingNullValues()
            .entryTtl(timeToLive);

    if (useKeyPrefix) {
      configuration.prefixCacheNameWith(keyPrefix);
    }

    return configuration;
  }

  @Data
  public static class LocalCacheProperties {

    /** Maximum number of entities too store in local cache */
    private int maxSize = 2000;

    /** Percentage of time deviation for local cache entry expiration */
    private int expiryJitter = 50;

    private Optional<Duration> timeToLive = Optional.empty();

    private LocalExpirationMode expirationMode = LocalExpirationMode.AFTER_CREATE;
  }

  /**
   * Circuit breaker just records calls to Redis - it does not time out them.
   *
   * <p>To simplify defaults, we rely on four core properties:
   *
   * <ul>
   *   <li>{@code failureRateThreshold}
   *   <li>{@code slowCallRateThreshold}
   *   <li>{@code slowCallDurationThreshold}
   *   <li>{@code slidingWindowType}
   * </ul>
   *
   * To compute appropriate values for your properties - use your slow calls as a baseline and
   * consider a sliding window type:
   *
   * <ul>
   *   <li>Assuming that call is considered to be slow after 250ms
   *   <li>Then in 1 second we should be able to process more than 4 calls
   *   <li>If sliding window is count based then {@code permittedNumberOfCallsInHalfOpenState}
   *       should be 4, {@code minimumNumberOfCalls} is 2 and {@code slidingWindowSize} is 8 (calls
   *       == 2 seconds)
   *   <li>If sliding window is time based then {@code permittedNumberOfCallsInHalfOpenState} should
   *       be 4, {@code minimumNumberOfCalls} is 2 and {@code slidingWindowSize} is 2 (seconds == 8
   *       seconds)
   * </ul>
   */
  @Data
  public static class CircuitBreakerProperties {

    /** Percentage of call failures to prohibit further calls to Redis */
    private int failureRateThreshold = 25;

    /** Percentage of slow calls to prohibit further calls to Redis */
    private int slowCallRateThreshold = 25;

    /** Defines the duration after which Redis call considered to be slow */
    private Duration slowCallDurationThreshold = Duration.ofMillis(250);

    /** A sliding window type for connectivity analysis */
    private SlidingWindowType slidingWindowType = SlidingWindowType.COUNT_BASED;

    /** Amount of Redis calls to test if backend is responsive when a circuit breaker closes */
    private int permittedNumberOfCallsInHalfOpenState =
        (int) (Duration.ofSeconds(5).toNanos() / slowCallDurationThreshold.toNanos());

    /** Amount of time to wait before closing circuit breaker, 0 - wait for all permitted calls. */
    private Duration maxWaitDurationInHalfOpenState =
        slowCallDurationThreshold.multipliedBy(permittedNumberOfCallsInHalfOpenState);

    /** Sliding window size for Redis calls analysis (calls / seconds) */
    private int slidingWindowSize = permittedNumberOfCallsInHalfOpenState * 2;

    /** Minimum number of calls which are required before calculating error or slow call rate */
    private int minimumNumberOfCalls = permittedNumberOfCallsInHalfOpenState / 2;

    /** Time to wait before permitting Redis calls to test backend connectivity. */
    private Duration waitDurationInOpenState =
        slowCallDurationThreshold.multipliedBy(minimumNumberOfCalls);
  }
}
