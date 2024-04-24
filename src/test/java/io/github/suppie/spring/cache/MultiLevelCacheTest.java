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

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.suppie.spring.cache.MultiLevelCacheConfigurationProperties.CircuitBreakerProperties;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(
    classes = {
      RedisAutoConfiguration.class,
      CacheAutoConfiguration.class,
      MultiLevelCacheAutoConfiguration.class,
      MultiLevelCacheManager.class
    })
class MultiLevelCacheTest {
  private static final AtomicInteger COUNTER = new AtomicInteger(0);

  @Autowired MultiLevelCacheManager cacheManager;

  @MockBean RedisMessageListenerContainer redisMessageListenerContainer;

  @MockBean RedisConnection redisConnection;
  @MockBean RedisConnectionFactory redisConnectionFactory;

  @ParameterizedTest
  @MethodSource("operations")
  void circuitBreakerTest(TrieConsumer<String, MultiLevelCacheManager, MultiLevelCache> consumer)
      throws Throwable {
    final String key = "circuitBreakerTest" + COUNTER.incrementAndGet();
    final CircuitBreakerProperties cbp = cacheManager.getProperties().getCircuitBreaker();

    MultiLevelCache cache = (MultiLevelCache) cacheManager.getCache(key);
    Assertions.assertNotNull(cache, "Cache should be automatically created upon request");

    CircuitBreaker cb = cacheManager.getCircuitBreaker();
    cb.transitionToClosedState();

    Assertions.assertNotNull(cb, "Cache must have circuit breaker defined");
    Assertions.assertEquals(
        State.CLOSED,
        cb.getState(),
        "Circuit breaker initial state must be CLOSED (e.g. permits requests to external service)");

    for (int i = 0; i < cbp.getMinimumNumberOfCalls(); i++) {
      Assertions.assertNull(cache.lookup(key), "There must be no connection to Redis");
    }

    Assertions.assertEquals(
        State.OPEN,
        cb.getState(),
        "Circuit breaker must become OPEN after minimum amount of calls failed / were slow");

    // Execute any operations required between
    consumer.accept(key, cacheManager, cache);

    Awaitility.await()
        .pollInterval(Duration.ofMillis(200))
        .atMost(cbp.getWaitDurationInOpenState().multipliedBy(2))
        .untilAsserted(
            () -> {
              if (SlidingWindowType.COUNT_BASED.equals(cbp.getSlidingWindowType())) {
                Assertions.assertNull(cache.lookup(key), "There must be no connection to Redis");
              }

              Assertions.assertEquals(
                  State.HALF_OPEN,
                  cb.getState(),
                  "Circuit breaker must become HALF_OPEN after certain amount of time / calls");
            });

    for (int i = 0; i < cbp.getPermittedNumberOfCallsInHalfOpenState(); i++) {
      Assertions.assertNull(cache.lookup(key), "There must be no connection to Redis");
    }

    Assertions.assertEquals(
        State.OPEN,
        cb.getState(),
        "Circuit breaker must become OPEN again if permitted calls failed");
  }

  static Stream<Arguments> operations() {
    return Stream.of(
        Arguments.of(
            (TrieConsumer<String, MultiLevelCacheManager, MultiLevelCache>)
                (key, cacheManager, cache) -> {}),
        Arguments.of(
            (TrieConsumer<String, MultiLevelCacheManager, MultiLevelCache>)
                (key, cacheManager, cache) -> {
                  Assertions.assertDoesNotThrow(
                      () -> cache.put(key, key), "Entity must be able to be created");
                  Assertions.assertEquals(
                      key,
                      cache.getLocalCache().getIfPresent(key),
                      "Local cache must contain value despite opened circuit breaker");
                  Awaitility.await()
                      .atMost(cacheManager.getProperties().getTimeToLive())
                      .until(() -> cache.getLocalCache().getIfPresent(key) == null);
                }),
        Arguments.of(
            (TrieConsumer<String, MultiLevelCacheManager, MultiLevelCache>)
                (key, cacheManager, cache) -> {
                  Assertions.assertDoesNotThrow(
                      () -> cache.get(key, (Callable<Object>) () -> key),
                      "Entity must be able to be created");
                  Assertions.assertEquals(
                      key,
                      cache.getLocalCache().getIfPresent(key),
                      "Local cache must contain value despite opened circuit breaker");
                  Awaitility.await()
                      .atMost(cacheManager.getProperties().getTimeToLive())
                      .until(() -> cache.getLocalCache().getIfPresent(key) == null);
                }),
        Arguments.of(
            (TrieConsumer<String, MultiLevelCacheManager, MultiLevelCache>)
                (key, cacheManager, cache) -> {
                  Assertions.assertThrows(
                      Cache.ValueRetrievalException.class,
                      () ->
                          cache.get(
                              key,
                              () -> {
                                throw new IllegalStateException("Test exception");
                              }));
                  Assertions.assertNull(
                      cache.getLocalCache().getIfPresent(key),
                      "Local cache must not contain value when circuit breaker is opened and value loader threw an exception");
                }));
  }

  @FunctionalInterface
  interface TrieConsumer<A, B, C> {
    void accept(A a, B b, C c) throws Throwable;
  }
}
