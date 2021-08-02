/*
 * MIT License
 *
 * Copyright (c) 2021 Roman Khlebnov
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

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.junit4.SpringRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Slf4j
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
    classes = {
      MultiLevelCacheAutoConfiguration.class,
      RedisAutoConfiguration.class,
      CacheAutoConfiguration.class
    })
@RunWith(SpringRunner.class)
@ExtendWith(SpringExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MultiLevelCacheTestcontainersTest {
  @Container
  static final GenericContainer REDIS =
      new GenericContainer(DockerImageName.parse("redis:5.0.3-alpine")).withExposedPorts(6379);

  @Autowired MultiLevelCacheManager cacheManager;

  @BeforeAll
  static void setUpAll() {
    Assumptions.assumeTrue(REDIS.isRunning());

    System.setProperty("HOST", REDIS.getHost());
    System.setProperty("PORT", String.valueOf(REDIS.getFirstMappedPort()));
  }

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

    Assertions.assertNotNull(cache.lookup(key), "Entity was present in Redis");
    Assertions.assertEquals(key, cache.nativeGet(key), "Underlying cache must contain value");
    Assertions.assertEquals(
        key, cache.getLocalCache().getIfPresent(key), "Local cache must load value during lookup");
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
    Assertions.assertNotNull(valueWrapper2.get(), "Value must be set");
    Assertions.assertEquals(key, cache.nativeGet(key), "Underlying cache must contain value");
    Assertions.assertEquals(
        key, cache.getLocalCache().getIfPresent(key), "Local cache must contain value");

    Cache.ValueWrapper valueWrapper3 =
        Assertions.assertDoesNotThrow(
            () -> cache.putIfAbsent(key, null), "Null value must not raise an exception");
    Assertions.assertNull(valueWrapper3, "Value wrapper must be null");
    Assertions.assertNull(cache.nativeGet(key), "Underlying cache must evict value");
    Assertions.assertNull(cache.getLocalCache().getIfPresent(key), "Local cache must evict value");
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

    Assertions.assertDoesNotThrow(cache::clear, "Method call should not throw an exception");
    Assertions.assertNull(cache.nativeGet(key), "Underlying cache must evict value");
    Assertions.assertNull(cache.getLocalCache().getIfPresent(key), "Local cache must evict value");
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
