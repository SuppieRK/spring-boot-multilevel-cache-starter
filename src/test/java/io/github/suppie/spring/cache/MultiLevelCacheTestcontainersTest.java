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
import java.time.Duration;
import java.util.Objects;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(
    classes = {
      DataRedisAutoConfiguration.class,
      CacheAutoConfiguration.class,
      MultiLevelCacheAutoConfiguration.class,
      MultiLevelCacheManager.class
    })
class MultiLevelCacheTestcontainersTest extends AbstractRedisIntegrationTest {

  private static final Duration AWAIT_SHORT = Duration.ofSeconds(3);
  private static final Duration AWAIT_MEDIUM = Duration.ofSeconds(10);
  private static final Duration AWAIT_LONG = Duration.ofSeconds(20);
  private static final Duration AWAIT_POLL = Duration.ofMillis(100);

  @Autowired MultiLevelCacheManager cacheManager;
  @Autowired MultiLevelCacheConfigurationProperties cacheProperties;
  @Autowired ObjectProvider<@NonNull CacheProperties> cachePropertiesProvider;

  @Autowired
  @Qualifier(MultiLevelCacheAutoConfiguration.CIRCUIT_BREAKER_NAME)
  CircuitBreaker circuitBreaker;

  @Autowired
  @Qualifier(MultiLevelCacheAutoConfiguration.CACHE_REDIS_TEMPLATE_NAME)
  RedisTemplate<Object, Object> multiLevelCacheRedisTemplate;

  @Test
  void lookupTest() {
    final String key = "lookupTest";

    MultiLevelCache cache = (MultiLevelCache) cacheManager.getCache(key);
    Assertions.assertNotNull(cache, "Cache should be automatically created upon request");

    Assertions.assertNull(cache.lookup(key), "Entity was not yet created");
    Assertions.assertDoesNotThrow(() -> cache.put(key, key), "Entity must be able to be created");
    Assertions.assertEquals(key, cache.nativeGet(key), "Underlying cache must contain value");
    Assertions.assertEquals(
        key, cache.getLocalCache().getIfPresent(key), "Local cache must contain value");
  }

  @Test
  void lookupTestForExistingRedisKey() {
    final String key = "lookupTestForExistingRedisKey";

    MultiLevelCache cache = (MultiLevelCache) cacheManager.getCache(key);
    Assertions.assertNotNull(cache, "Cache should be automatically created upon request");

    Assertions.assertDoesNotThrow(
        () -> cache.nativePut(key, key),
        "In Redis presence we must be able to load value directly");
    Awaitility.await()
        .pollInterval(AWAIT_POLL)
        .atMost(AWAIT_SHORT)
        .untilAsserted(
            () -> Assertions.assertNotNull(cache.lookup(key), "Entity was present in Redis"));

    Awaitility.await()
        .pollInterval(AWAIT_POLL)
        .atMost(AWAIT_SHORT)
        .untilAsserted(
            () -> {
              Assertions.assertEquals(
                  key, cache.nativeGet(key), "Underlying cache must contain value");
              Assertions.assertEquals(
                  key,
                  cache.getLocalCache().getIfPresent(key),
                  "Local cache must load value during lookup");
            });
  }

  @Test
  void getTest() {
    final String key = "getTest";

    MultiLevelCache cache = (MultiLevelCache) cacheManager.getCache(key);
    Assertions.assertNotNull(cache, "Cache should be automatically created upon request");

    Assertions.assertNull(cache.lookup(key), "Entity was not yet created");
    Assertions.assertDoesNotThrow(
        () -> cache.get(key, () -> key), "Entity must be able to be created");
    Assertions.assertEquals(key, cache.nativeGet(key), "Underlying cache must contain value");
    Assertions.assertEquals(
        key, cache.getLocalCache().getIfPresent(key), "Local cache must contain value");
    Assertions.assertDoesNotThrow(
        () -> cache.get(key, () -> key), "Second call must utilize cache");
  }

  @Test
  void putNullValueTest() {
    final String key = "putNullValueTest";

    MultiLevelCache cache = (MultiLevelCache) cacheManager.getCache(key);
    Assertions.assertNotNull(cache, "Cache should be automatically created upon request");

    Assertions.assertDoesNotThrow(
        () -> cache.put(key, null), "Null value must not raise an exception");
    Assertions.assertNull(cache.nativeGet(key), "Underlying cache must not contain value");
    Assertions.assertNull(
        cache.getLocalCache().getIfPresent(key), "Local cache must not contain value");
  }

  @Test
  void putIfAbsentTest() {
    final String key = "putIfAbsentTest";

    MultiLevelCache cache = (MultiLevelCache) cacheManager.getCache(key);
    Assertions.assertNotNull(cache, "Cache should be automatically created upon request");

    Assertions.assertNull(cache.lookup(key), "Entity was not yet created");

    Cache.ValueWrapper valueWrapper =
        Assertions.assertDoesNotThrow(
            () -> cache.putIfAbsent(key, key), "Entity must be able to be created");
    Assertions.assertNull(valueWrapper, "Value must be set and first return result should be null");
    Assertions.assertEquals(key, cache.nativeGet(key), "Underlying cache must contain value");
    Assertions.assertEquals(
        key, cache.getLocalCache().getIfPresent(key), "Local cache must contain value");

    Cache.ValueWrapper valueWrapper2 =
        Assertions.assertDoesNotThrow(
            () -> cache.putIfAbsent(key, key), "Entity must be read immediately");
    Assertions.assertNotNull(valueWrapper2, "Value wrapper must be set");
    Assertions.assertNotNull(valueWrapper2.get(), "Value must be set");
    Assertions.assertEquals(key, cache.nativeGet(key), "Underlying cache must contain value");
    Assertions.assertEquals(
        key, cache.getLocalCache().getIfPresent(key), "Local cache must contain value");

    Cache.ValueWrapper valueWrapper3 =
        Assertions.assertDoesNotThrow(
            () -> cache.putIfAbsent(key, null), "Null value must not raise an exception");
    Assertions.assertNull(valueWrapper3, "Value wrapper must be null");

    Awaitility.await()
        .pollInterval(AWAIT_POLL)
        .atMost(AWAIT_LONG)
        .untilAsserted(
            () -> {
              Assertions.assertNull(cache.nativeGet(key), "Underlying cache must evict value");
              Assertions.assertNull(
                  cache.getLocalCache().getIfPresent(key), "Local cache must evict value");
            });
  }

  @Test
  void evictIfPresentTest() {
    final String key = "evictIfPresentTest";

    MultiLevelCache cache = (MultiLevelCache) cacheManager.getCache(key);
    Assertions.assertNotNull(cache, "Cache should be automatically created upon request");

    Assertions.assertDoesNotThrow(() -> cache.put(key, key), "Entity must be able to be created");
    Assertions.assertEquals(key, cache.nativeGet(key), "Underlying cache must contain value");
    Assertions.assertEquals(
        key, cache.getLocalCache().getIfPresent(key), "Local cache must contain value");

    Assertions.assertTrue(
        cache.evictIfPresent(key),
        "Entity must be evicted and true must be returned, because value is contained in local cache");

    Assertions.assertNull(cache.nativeGet(key), "Underlying cache must evict value");
    Assertions.assertNull(cache.getLocalCache().getIfPresent(key), "Local cache must evict value");

    Assertions.assertFalse(
        cache.evictIfPresent(key), "Second call must not evict anything and result in false");

    Assertions.assertNull(cache.nativeGet(key), "Underlying cache must evict value");
    Assertions.assertNull(cache.getLocalCache().getIfPresent(key), "Local cache must evict value");
  }

  @Test
  void clearTest() {
    final String key = "clearTest";

    MultiLevelCache cache = (MultiLevelCache) cacheManager.getCache(key);
    Assertions.assertNotNull(cache, "Cache should be automatically created upon request");

    Assertions.assertDoesNotThrow(() -> cache.put(key, key), "Entity must be able to be created");
    Assertions.assertEquals(key, cache.nativeGet(key), "Underlying cache must contain value");
    Assertions.assertEquals(
        key, cache.getLocalCache().getIfPresent(key), "Local cache must contain value");

    Assertions.assertDoesNotThrow(() -> cache.clear(), "Method call should not throw an exception");
    Assertions.assertNull(cache.nativeGet(key), "Underlying cache must evict value");
    Assertions.assertNull(cache.getLocalCache().getIfPresent(key), "Local cache must evict value");
  }

  @Test
  void putBroadcastsInvalidationToOtherInstance() {
    final String key = "putBroadcastsInvalidationToOtherInstance";

    MultiLevelCache localCache = (MultiLevelCache) cacheManager.getCache(key);
    Assertions.assertNotNull(localCache, "Cache should be automatically created upon request");

    MultiLevelCacheManager secondaryManager =
        new MultiLevelCacheManager(
            cachePropertiesProvider, cacheProperties, multiLevelCacheRedisTemplate, circuitBreaker);
    MultiLevelCache remoteCache = (MultiLevelCache) secondaryManager.getCache(key);
    Assertions.assertNotNull(remoteCache, "Secondary cache should be available");

    RedisMessageListenerContainer remoteListener = new RedisMessageListenerContainer();
    remoteListener.setConnectionFactory(
        Objects.requireNonNull(multiLevelCacheRedisTemplate.getConnectionFactory()));
    remoteListener.addMessageListener(
        (message, pattern) -> {
          MultiLevelCacheEvictMessage event =
              (MultiLevelCacheEvictMessage)
                  multiLevelCacheRedisTemplate.getValueSerializer().deserialize(message.getBody());
          if (event == null) {
            return;
          }
          if (secondaryManager.getInstanceId().equals(event.getSenderId())) {
            return;
          }

          MultiLevelCache cache = (MultiLevelCache) secondaryManager.getCache(event.getCacheName());
          if (cache == null) {
            return;
          }

          if (event.getEntryKey() == null) {
            cache.invalidateLocalCache();
          } else {
            cache.invalidateLocalEntry(event.getEntryKey());
          }
        },
        new ChannelTopic(cacheProperties.getTopic()));
    remoteListener.afterPropertiesSet();
    remoteListener.start();

    try {
      Assertions.assertDoesNotThrow(() -> remoteCache.put(key, "stale"));
      Awaitility.await()
          .pollInterval(AWAIT_POLL)
          .atMost(AWAIT_MEDIUM)
          .until(() -> remoteCache.getLocalCache().estimatedSize() > 0);

      String localKey = remoteCache.toLocalKey(key);

      Assertions.assertDoesNotThrow(() -> localCache.put(key, "fresh"));

      Awaitility.await()
          .pollInterval(AWAIT_POLL)
          .atMost(AWAIT_MEDIUM)
          .until(() -> remoteCache.getLocalCache().getIfPresent(localKey) == null);

      Assertions.assertEquals(
          "fresh", remoteCache.nativeGet(key), "Remote cache must see an updated value");
    } finally {
      remoteListener.stop();
      try {
        remoteListener.destroy();
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  void invalidateTest() {
    final String key = "invalidateTest";

    MultiLevelCache cache = (MultiLevelCache) cacheManager.getCache(key);
    Assertions.assertNotNull(cache, "Cache should be automatically created upon request");

    Assertions.assertDoesNotThrow(() -> cache.put(key, key), "Entity must be able to be created");
    Assertions.assertEquals(key, cache.nativeGet(key), "Underlying cache must contain value");
    Assertions.assertEquals(
        key, cache.getLocalCache().getIfPresent(key), "Local cache must contain value");

    Assertions.assertDoesNotThrow(cache::invalidate, "Method call should not throw an exception");
    Assertions.assertNull(cache.nativeGet(key), "Underlying cache must evict value");
    Assertions.assertNull(cache.getLocalCache().getIfPresent(key), "Local cache must evict value");
  }
}
