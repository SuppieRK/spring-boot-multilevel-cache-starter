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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.vavr.CheckedFunction0;
import io.vavr.control.Try;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Multi-level cache implementation
 *
 * <p>Main goals are:
 *
 * <ul>
 *   <li>Bypass calls to Redis to speed up retrieval entries
 *   <li>Provide fault tolerance means if Redis is unavailable without loss in functionality
 * </ul>
 *
 * <p>WARNING: When dealing with local cache we do partial key conversion using {@link
 * RedisCache#convertKey(Object)} for consistency and retrieval of correct {@link String}
 */
@Slf4j
public class MultiLevelCache extends RedisCache {

  // Error messages
  private static final String NO_REDIS_CONNECTION =
      "Redis connection factory was not found for RedisCacheWriter";
  private static final String LOCK_WAS_NOT_INITIALIZED = "Lock was not initialized";

  // These are local non-overridable properties for ReentrantLocks cache to provide atomicity
  private static final Object CACHE_WIDE_LOCK_OBJECT = new Object();
  private static final long LOCKS_CACHE_MAXIMUM_SIZE = 1000;
  private static final Duration LOCKS_CACHE_EXPIRE_AFTER_ACCESS = Duration.ofSeconds(15);

  protected final MultiLevelCacheConfigurationProperties properties;
  protected final Cache<Object, Object> localCache;
  protected final Cache<Object, ReentrantLock> locks;
  protected final CircuitBreaker cacheCircuitBreaker;

  private final RedisTemplate<Object, Object> redisTemplate;

  public MultiLevelCache(
      String name,
      MultiLevelCacheConfigurationProperties properties,
      RedisTemplate<Object, Object> redisTemplate,
      Cache<Object, Object> localCache,
      CircuitBreaker cacheCircuitBreaker) {
    this(
        name,
        properties,
        RedisCacheWriter.nonLockingRedisCacheWriter(
            Objects.requireNonNull(redisTemplate.getConnectionFactory(), NO_REDIS_CONNECTION)),
        redisTemplate,
        localCache,
        cacheCircuitBreaker);
  }

  public MultiLevelCache(
      String name,
      MultiLevelCacheConfigurationProperties properties,
      RedisCacheWriter redisCacheWriter,
      RedisTemplate<Object, Object> redisTemplate,
      Cache<Object, Object> localCache,
      CircuitBreaker cacheCircuitBreaker) {
    super(name, redisCacheWriter, properties.toRedisCacheConfiguration());

    this.properties = properties;
    this.redisTemplate = redisTemplate;
    this.localCache = localCache;
    this.locks =
        Caffeine.newBuilder()
            .maximumSize(LOCKS_CACHE_MAXIMUM_SIZE)
            .expireAfterAccess(LOCKS_CACHE_EXPIRE_AFTER_ACCESS)
            .build();
    this.cacheCircuitBreaker = cacheCircuitBreaker;
  }

  // Workarounds for tests
  Cache<Object, Object> getLocalCache() {
    return localCache;
  }

  @Nullable
  @SuppressWarnings("unchecked")
  <T> T nativeGet(@NonNull Object key) {
    return (T) callRedis(() -> super.get(key, () -> null)).get();
  }

  void nativePut(@NonNull Object key, @Nullable Object value) {
    callRedis(() -> super.put(key, value));
  }
  // Workarounds for tests

  /**
   * Perform an actual lookup in the underlying store.
   *
   * <p>We do not allow storing {@code null} values, if local cache does not have mapping for
   * specified key we query Redis using circuit breaker and error handling logic. If Redis contains
   * requested mapping, value will be saved in local cache. If Redis is not available, {@code null}
   * will be returned.
   *
   * @param key the key whose associated value is to be returned
   * @return the raw store value for the key, or {@code null} if none
   */
  @Override
  protected Object lookup(@NonNull Object key) {
    final String localKey = convertKey(key);
    Object localValue = localCache.getIfPresent(localKey);

    if (localValue == null) {
      return callRedis(() -> super.lookup(key))
          .andThen(value -> localCache.put(localKey, value))
          .recover(e -> null)
          .get();
    }

    return localValue;
  }

  /**
   * Return the value to which this cache maps the specified key, obtaining that value from {@code
   * valueLoader} if necessary. This method provides a simple substitute for the conventional "if
   * cached, return; otherwise create, cache and return" pattern.
   *
   * <p>If the {@code valueLoader} throws an exception, it is wrapped in a {@link
   * ValueRetrievalException}
   *
   * <p>If Redis cannot be queried, {@code valueLoader} will still be executed and value will be
   * stored in local cache instead.
   *
   * @param key the key whose associated value is to be returned
   * @return the value to which this cache maps the specified key
   * @throws ValueRetrievalException if the {@code valueLoader} throws an exception or retrieved
   *     value was {@code null}
   * @see #get(Object)
   */
  @Override
  @NonNull
  @SuppressWarnings("unchecked")
  public synchronized <T> T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
    Object result = lookup(key);
    if (result != null) return (T) result;

    final String localKey = convertKey(key);
    return callRedis(() -> super.get(key, valueLoader))
        .andThen(value -> localCache.put(localKey, value))
        .recover(
            e -> {
              try {
                T value = valueLoader.call();
                localCache.put(localKey, value);
                return value;
              } catch (Exception recoverException) {
                throw new ValueRetrievalException(key, valueLoader, recoverException);
              }
            })
        .get();
  }

  /**
   * Associate the specified value with the specified key in this cache.
   *
   * <p>If the cache previously contained a mapping for this key, the old value replaced by the
   * specified value.
   *
   * <p>If value is {@code null} specified key will be evicted.
   *
   * <p>Actual registration performed in an asynchronous fashion, with subsequent lookups possibly
   * not seeing the entry yet. Use {@link #putIfAbsent} for guaranteed immediate registration for
   * current cache.
   *
   * @param key the key with which the specified value is to be associated
   * @param value the value to be associated with the specified key
   * @see #putIfAbsent(Object, Object)
   */
  @Override
  public void put(@NonNull Object key, @Nullable Object value) {
    if (value == null) {
      evict(key);
      return;
    }

    localCache.put(convertKey(key), value);
    callRedis(() -> super.put(key, value));
  }

  /**
   * Atomically associate the specified value with the specified key in this cache if it is not set
   * already.
   *
   * <p>This is equivalent to:
   *
   * <pre><code>
   * ValueWrapper existingValue = cache.get(key);
   * if (existingValue == null) {
   *     cache.put(key, value);
   * }
   * return existingValue;
   * </code></pre>
   *
   * except that the action performed atomically for current cache.
   *
   * <p>If value is {@code null} specified key will be evicted.
   *
   * @param key the key with which the specified value is to be associated
   * @param value the value to be associated with the specified key
   * @return the value to which this cache maps the specified key (which may be {@code null}
   *     itself), or also {@code null} if the cache did not contain any mapping for that key prior
   *     to this call. Returning {@code null} is therefore an indicator that the given {@code value}
   *     has been associated with the key, or it was evicted.
   * @see #put(Object, Object)
   */
  @Override
  public ValueWrapper putIfAbsent(@NonNull Object key, @Nullable Object value) {
    if (value == null) {
      evict(key);
      return null;
    }

    final ReentrantLock lock = makeLock(key);

    try {
      lock.lock();

      Object existingValue = lookup(key);
      if (existingValue == null) {
        localCache.put(convertKey(key), value);
        callRedis(() -> super.putIfAbsent(key, value));
        return null;
      } else {
        return new SimpleValueWrapper(existingValue);
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Evict the mapping for this key from this cache if it is present.
   *
   * <p>Actual eviction performed in an asynchronous fashion, with subsequent lookups possibly still
   * seeing the entry. Use {@link #evictIfPresent} for guaranteed immediate removal for current
   * cache.
   *
   * @param key the key whose mapping is to be removed from the cache
   * @see #evictIfPresent(Object)
   */
  @Override
  public void evict(@NonNull Object key) {
    sendViaRedis(localEvict(key));
  }

  /**
   * Local copy of {@link #evict(Object)} method for Redis Pub/Sub listener to avoid infinite
   * message loop
   *
   * @param key the key whose mapping is to be removed from the cache
   * @return computed key for entry to evict
   * @see #evict(Object)
   */
  String localEvict(@NonNull Object key) {
    final String localKey = convertKey(key);
    localCache.invalidate(localKey);
    callRedis(() -> super.evict(key));
    return localKey;
  }

  /**
   * Evict the mapping for this key from this cache if it is present, expecting the key to be
   * immediately invisible for subsequent lookups.
   *
   * @param key the key whose mapping is to be removed from the cache
   * @return {@code true} if local cache was known to have a mapping for this key before, {@code
   *     false} if it did not (or if prior presence could not be determined)
   * @see #evict(Object)
   * @since 5.2
   */
  @Override
  public boolean evictIfPresent(@NonNull Object key) {
    final ReentrantLock lock = makeLock(key);

    try {
      lock.lock();

      final String localKey = convertKey(key);
      boolean haveLocalMapping = localCache.getIfPresent(localKey) != null;

      localCache.invalidate(localKey);
      callRedis(() -> super.evict(key));
      sendViaRedis(localKey);

      return haveLocalMapping;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Clear the cache through removing all mappings.
   *
   * <p>Actual clearing performed in an asynchronous fashion, with subsequent lookups possibly still
   * seeing the entries. Use {@link #invalidate()} for guaranteed immediate removal of entries for
   * current cache.
   *
   * @see #invalidate()
   */
  @Override
  public void clear() {
    localClear();
    sendViaRedis(null);
  }

  /**
   * Local copy of {@link #clear()} method for Redis Pub/Sub listener to avoid infinite message loop
   *
   * @see #clear()
   */
  void localClear() {
    localCache.invalidateAll();
    callRedis(super::clear);
  }

  /**
   * Invalidate the cache through removing all mappings, expecting all entries to be immediately
   * invisible for subsequent lookups.
   *
   * @return {@code true} if local cache was known to have mappings before, {@code false} if it did
   *     not (or if prior presence of entries could not be determined)
   * @see #clear()
   * @since 5.2
   */
  @Override
  public boolean invalidate() {
    final ReentrantLock lock = makeLock(CACHE_WIDE_LOCK_OBJECT);

    try {
      lock.lock();

      boolean hadLocalMappings = localCache.estimatedSize() > 0;

      localCache.invalidateAll();
      callRedis(super::clear);
      sendViaRedis(null);

      return hadLocalMappings;
    } finally {
      lock.unlock();
    }
  }

  /** @param call to Redis */
  private void callRedis(@NonNull Runnable call) {
    Try.runRunnable(cacheCircuitBreaker.decorateRunnable(call));
  }

  /**
   * @param call to Redis
   * @return execution result as {@link Try}
   */
  private <T> Try<T> callRedis(@NonNull CheckedFunction0<T> call) {
    return Try.of(cacheCircuitBreaker.decorateCheckedSupplier(call));
  }

  /** @param key to send notification about eviction. Can be {@code null}. */
  private void sendViaRedis(@Nullable String key) {
    Try.runRunnable(
        cacheCircuitBreaker.decorateRunnable(
            () ->
                redisTemplate.convertAndSend(
                    properties.getTopic(), new MultiLevelCacheEvictMessage(getName(), key))));
  }

  /**
   * @param key to make lock for
   * @return new {@link ReentrantLock} for synchronizing operations
   */
  @NonNull
  private ReentrantLock makeLock(@NonNull Object key) {
    return Objects.requireNonNull(
        locks.get(key, o -> new ReentrantLock()), LOCK_WAS_NOT_INITIALIZED);
  }
}
