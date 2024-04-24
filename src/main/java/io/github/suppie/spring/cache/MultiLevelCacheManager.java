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

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;

/** Cache manager to cover basic operations */
@Slf4j
public class MultiLevelCacheManager implements CacheManager {

  private final Set<String> requestedCacheNames;
  private final MultiLevelCacheConfigurationProperties properties;
  private final RedisTemplate<Object, Object> redisTemplate;
  private final CircuitBreaker circuitBreaker;

  private final Map<String, Cache> availableCaches;

  public MultiLevelCacheManager(
      ObjectProvider<CacheProperties> highLevelProperties,
      MultiLevelCacheConfigurationProperties properties,
      RedisTemplate<Object, Object> redisTemplate,
      CircuitBreaker circuitBreaker) {
    CacheProperties hlp = highLevelProperties.getIfAvailable();
    this.requestedCacheNames =
        hlp == null ? Collections.emptySet() : Set.copyOf(hlp.getCacheNames());

    this.properties = properties;
    this.redisTemplate = redisTemplate;
    this.circuitBreaker = circuitBreaker;

    this.availableCaches = new ConcurrentHashMap<>();

    this.requestedCacheNames.forEach(this::getCache);
  }

  // Workarounds for tests

  MultiLevelCacheConfigurationProperties getProperties() {
    return properties;
  }

  CircuitBreaker getCircuitBreaker() {
    return circuitBreaker;
  }

  // Workarounds for tests

  /**
   * Get or create the cache associated with the given name.
   *
   * @param name the cache identifier (must not be {@code null})
   * @return the associated cache, or {@code null} if such a cache does not exist or could be not
   *     created
   */
  @Override
  public Cache getCache(@NonNull String name) {
    if (!requestedCacheNames.isEmpty() && !requestedCacheNames.contains(name)) {
      return null;
    }

    return availableCaches.computeIfAbsent(
        name,
        key ->
            new MultiLevelCache(
                key,
                properties,
                redisTemplate,
                Caffeine.newBuilder()
                    .maximumSize(properties.getLocal().getMaxSize())
                    .expireAfter(new RandomizedLocalExpiryOnWrite(properties))
                    .build(),
                circuitBreaker));
  }

  /**
   * Get a collection of the cache names known by this manager.
   *
   * @return the names of all caches known by the cache manager
   */
  @Override
  public @NonNull Collection<String> getCacheNames() {
    return Collections.unmodifiableSet(availableCaches.keySet());
  }

  /** Expiry policy enabling randomized expiry on writing for local entities */
  static class RandomizedLocalExpiryOnWrite implements Expiry<Object, Object> {

    private final Random random;
    private final Duration timeToLive;
    private final double expiryJitter;

    public RandomizedLocalExpiryOnWrite(
        @NonNull MultiLevelCacheConfigurationProperties properties) {
      this.random = new Random(System.currentTimeMillis());
      this.timeToLive = properties.getTimeToLive();
      this.expiryJitter = properties.getLocal().getExpiryJitter();

      if (timeToLive.isNegative()) {
        throw new IllegalArgumentException("Time to live duration must be positive");
      }

      if (timeToLive.isZero()) {
        throw new IllegalArgumentException("Time to live duration must not be zero");
      }

      if (expiryJitter < 0) {
        throw new IllegalArgumentException("Expiry jitter must be positive");
      }

      if (expiryJitter >= 100) {
        throw new IllegalArgumentException("Expiry jitter must not exceed 100 percents");
      }
    }

    @Override
    public long expireAfterCreate(@NonNull Object key, @NonNull Object value, long currentTime) {
      int jitterSign = random.nextBoolean() ? 1 : -1;
      double randomJitter = 1 + (jitterSign * (expiryJitter / 100) * random.nextDouble());
      Duration expiry = timeToLive.multipliedBy((long) (100 * randomJitter)).dividedBy(200);
      log.trace("Key {} will expire in {}", key, expiry);
      return expiry.toNanos();
    }

    @Override
    public long expireAfterUpdate(
        @NonNull Object key,
        @NonNull Object value,
        long currentTime,
        @NonNegative long currentDuration) {
      return currentDuration;
    }

    @Override
    public long expireAfterRead(
        @NonNull Object key,
        @NonNull Object value,
        long currentTime,
        @NonNegative long currentDuration) {
      return currentDuration;
    }
  }
}
