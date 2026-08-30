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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.functions.CheckedSupplier;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.support.NullValue;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * L1-first Spring cache backed by a shared Redis L2 cache.
 *
 * <p>Reads use the local Caffeine cache first and populate it from Redis after a miss. Mutations
 * update Redis when it is available, always leave the local tier in a usable state, and publish
 * invalidation messages so other application instances discard stale local entries. Warm L1 reads
 * remain lock-free; cold reads and mutations are serialized per key, while cache-wide mutations
 * exclude those key operations.
 */
@Slf4j
public class MultiLevelCache extends RedisCache {

  private static final String NO_REDIS_CONNECTION =
      "Redis connection factory was not found for RedisCacheWriter";
  private static final String LOCK_WAS_NOT_INITIALIZED = "Lock was not initialized";

  /** Configuration settings governing TTL, jitter, and other cache behavior. */
  protected final MultiLevelCacheConfigurationProperties properties;

  /** Local in-memory cache tier. */
  protected final Cache<@NonNull Object, Object> localCache;

  /** Weak lock registry; holders and waiters retain strong references while using a lock. */
  protected final Cache<@NonNull Object, ReentrantLock> locks;

  /** Circuit breaker protecting Redis I/O only. */
  protected final CircuitBreaker cacheCircuitBreaker;

  private final ReentrantReadWriteLock cacheWideLock = new ReentrantReadWriteLock();
  private final RedisTemplate<Object, Object> redisTemplate;
  private final String instanceId;

  /**
   * Creates a multi-level cache using a non-locking Redis writer.
   *
   * @param name cache name
   * @param properties cache properties
   * @param redisTemplate template used for values and invalidation publication
   * @param localCache local L1 cache
   * @param cacheCircuitBreaker Redis circuit breaker
   * @param instanceId current instance identifier
   */
  public MultiLevelCache(
      String name,
      MultiLevelCacheConfigurationProperties properties,
      RedisTemplate<Object, Object> redisTemplate,
      Cache<@NonNull Object, Object> localCache,
      CircuitBreaker cacheCircuitBreaker,
      String instanceId) {
    this(
        name,
        properties,
        RedisCacheWriter.nonLockingRedisCacheWriter(
            Objects.requireNonNull(redisTemplate.getConnectionFactory(), NO_REDIS_CONNECTION)),
        redisTemplate,
        localCache,
        cacheCircuitBreaker,
        instanceId);
  }

  /**
   * Creates a multi-level cache with an explicit Redis writer.
   *
   * @param name cache name
   * @param properties cache properties
   * @param redisCacheWriter Redis writer
   * @param redisTemplate template used for values and invalidation publication
   * @param localCache local L1 cache
   * @param cacheCircuitBreaker Redis circuit breaker
   * @param instanceId current instance identifier
   */
  public MultiLevelCache(
      String name,
      MultiLevelCacheConfigurationProperties properties,
      RedisCacheWriter redisCacheWriter,
      RedisTemplate<Object, Object> redisTemplate,
      Cache<@NonNull Object, Object> localCache,
      CircuitBreaker cacheCircuitBreaker,
      String instanceId) {
    super(name, redisCacheWriter, adjustRedisCacheConfiguration(properties, redisTemplate));
    this.properties = Objects.requireNonNull(properties);
    this.redisTemplate = Objects.requireNonNull(redisTemplate);
    this.localCache = Objects.requireNonNull(localCache);
    this.cacheCircuitBreaker = Objects.requireNonNull(cacheCircuitBreaker);
    this.instanceId = Objects.requireNonNull(instanceId);
    this.locks = Caffeine.newBuilder().weakValues().build();
  }

  /** Returns the local tier for package-level diagnostics and tests. */
  Cache<@NonNull Object, Object> getLocalCache() {
    return localCache;
  }

  /** Reads directly from Redis, bypassing the local tier and circuit-breaker fallback. */
  @SuppressWarnings("unchecked")
  <T> @Nullable T nativeGet(@NonNull Object key) {
    return (T) fromStoreValue(super.lookup(key));
  }

  /** Writes directly to Redis, bypassing local mutation and invalidation publication. */
  void nativePut(@NonNull Object key, @Nullable Object value) {
    super.put(key, value);
  }

  /** Returns the canonical key representation used by the local tier. */
  String toLocalKey(@NonNull Object key) {
    return convertKey(key);
  }

  /**
   * Looks up a value in L1 first and consults Redis only after a local miss.
   *
   * <p>Cold reads are serialized with same-key and cache-wide mutations so an older Redis read
   * cannot repopulate L1 after invalidation. Redis availability failures are treated as misses;
   * serialization and programming failures are propagated.
   *
   * @param key cache key
   * @return cached store value, or {@code null} after a miss or Redis availability failure
   */
  @Override
  protected @Nullable Object lookup(@NonNull Object key) {
    String localKey = convertKey(key);
    Object localValue = localCache.getIfPresent(localKey);
    if (localValue != null) {
      log.trace("Local cache hit for cache '{}' and key '{}'", getName(), localKey);
      return localValue;
    }

    ReentrantLock lock = makeLock(localKey);
    lock.lock();
    try {
      return lookupWhileKeyLocked(key, localKey);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Consults both tiers while the caller holds the lock for {@code localKey}.
   *
   * <p>The cache-wide read lock prevents a concurrent clear from completing between the Redis read
   * and the L1 population. The local tier is rechecked after acquiring it because a preceding
   * same-key operation may already have populated the value.
   */
  private @Nullable Object lookupWhileKeyLocked(@NonNull Object key, String localKey) {
    cacheWideLock.readLock().lock();
    try {
      Object localValue = localCache.getIfPresent(localKey);
      if (localValue != null) {
        return localValue;
      }

      byte[] redisKey = serializeRedisKey(key);
      RemoteCall<byte[]> remote =
          callRedis(() -> getCacheWriter().get(getName(), redisKey), "read");
      if (!remote.available()) {
        log.trace("Redis unavailable for cache '{}' and key '{}'", getName(), localKey);
        return null;
      }
      byte[] remoteBytes = remote.value();
      if (remoteBytes == null) {
        log.trace("Redis cache miss for cache '{}' and key '{}'", getName(), localKey);
        return null;
      }

      Object value = deserializeNonNullCacheValue(remoteBytes);
      if (value == NullValue.INSTANCE) {
        log.debug(
            "Ignoring legacy Redis null value for cache '{}' and key '{}'", getName(), localKey);
        return null;
      }

      localCache.put(localKey, value);
      log.trace("Redis cache hit for cache '{}' and key '{}'", getName(), localKey);
      return value;
    } finally {
      cacheWideLock.readLock().unlock();
    }
  }

  /**
   * Returns a cached value or invokes the loader once per local key.
   *
   * <p>The loader executes outside the Redis circuit breaker. A loaded value is written through to
   * Redis when possible and remains available in L1 during Redis availability failures.
   *
   * @param key cache key
   * @param valueLoader loader invoked after both tiers miss
   * @param <T> cached value type
   * @return the cached or loaded non-null value
   * @throws ValueRetrievalException when loading or storing the loaded value fails
   */
  @Override
  @SuppressWarnings("unchecked")
  public <T> @NonNull T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
    String localKey = convertKey(key);
    Object localValue = localCache.getIfPresent(localKey);
    if (localValue != null) {
      return (T) localValue;
    }

    ReentrantLock lock = makeLock(localKey);
    lock.lock();
    try {
      localValue = localCache.getIfPresent(localKey);
      if (localValue != null) {
        return (T) localValue;
      }

      Object remoteValue = lookupWhileKeyLocked(key, localKey);
      if (remoteValue != null) {
        return (T) remoteValue;
      }

      T loaded;
      try {
        loaded = valueLoader.call();
      } catch (Exception exception) {
        throw new ValueRetrievalException(key, valueLoader, exception);
      }
      if (loaded == null) {
        throw new ValueRetrievalException(key, valueLoader, null);
      }

      try {
        put(key, loaded);
      } catch (ValueRetrievalException exception) {
        throw exception;
      } catch (RuntimeException exception) {
        throw new ValueRetrievalException(key, valueLoader, exception);
      }
      return loaded;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Writes a value to Redis when available and always updates L1.
   *
   * <p>A {@code null} value is treated as eviction because this cache does not retain null entries.
   * Same-key and cache-wide mutations cannot cross the Redis-write-to-L1-commit window. Successful
   * Redis writes publish an invalidation to other nodes.
   *
   * @param key cache key
   * @param value value to cache, or {@code null} to evict
   */
  @Override
  public void put(@NonNull Object key, @Nullable Object value) {
    if (value == null) {
      evict(key);
      return;
    }

    String localKey = convertKey(key);
    byte[] redisKey = serializeRedisKey(key);
    byte[] redisValue = serializeCacheValue(value);
    Duration ttl = timeToLive(key, value);
    ReentrantLock lock = makeLock(localKey);
    RemoteCall<Void> remote;
    lock.lock();
    cacheWideLock.readLock().lock();
    try {
      remote =
          callRedis(
              () -> {
                getCacheWriter().put(getName(), redisKey, redisValue, ttl);
                return null;
              },
              "write");
      localCache.put(localKey, value);
    } finally {
      cacheWideLock.readLock().unlock();
      lock.unlock();
    }
    if (remote.available()) {
      sendViaRedis(localKey);
    }
  }

  /**
   * Returns an existing L1 value without Redis I/O. Redis coordinates only cold L1 misses while it
   * is available.
   *
   * <p>Warm L1 hits return before locking. Cold calls on this node are serialized per local key and
   * recheck L1 after acquiring the lock. When Redis is unavailable, the candidate value remains
   * available on this node so local caching continues seamlessly.
   *
   * @param key cache key
   * @param value candidate value, or {@code null} to evict
   * @return the existing value, or {@code null} when the candidate was stored
   */
  @Override
  public @Nullable ValueWrapper putIfAbsent(@NonNull Object key, @Nullable Object value) {
    if (value == null) {
      evict(key);
      return null;
    }

    String localKey = convertKey(key);
    Object localValue = localCache.getIfPresent(localKey);
    if (localValue != null) {
      return new SimpleValueWrapper(localValue);
    }

    ReentrantLock lock = makeLock(localKey);
    lock.lock();
    cacheWideLock.readLock().lock();
    try {
      localValue = localCache.getIfPresent(localKey);
      if (localValue != null) {
        return new SimpleValueWrapper(localValue);
      }

      byte[] redisKey = serializeRedisKey(key);
      byte[] redisValue = serializeCacheValue(value);
      Duration ttl = timeToLive(key, value);
      RemoteCall<byte[]> remote = remotePutIfAbsent(redisKey, redisValue, ttl);

      if (!remote.available()) {
        localCache.put(localKey, value);
        return null;
      }
      if (remote.value() == null) {
        localCache.put(localKey, value);
        sendViaRedis(localKey);
        return null;
      }

      byte[] existingBytes =
          Objects.requireNonNull(remote.value(), "Available Redis value must not be null");
      Object existingValue = deserializeNonNullCacheValue(existingBytes);
      if (existingValue == NullValue.INSTANCE) {
        RemoteCall<Void> removed =
            callRedis(
                () -> {
                  getCacheWriter().evict(getName(), redisKey);
                  return null;
                },
                "remove legacy null");
        if (!removed.available()) {
          localCache.put(localKey, value);
          return null;
        }
        RemoteCall<byte[]> retry = remotePutIfAbsent(redisKey, redisValue, ttl);
        if (!retry.available()) {
          localCache.put(localKey, value);
          return null;
        }
        byte[] retryBytes = retry.value();
        if (retryBytes == null) {
          localCache.put(localKey, value);
          sendViaRedis(localKey);
          return null;
        }
        existingValue = deserializeNonNullCacheValue(retryBytes);
        if (existingValue == NullValue.INSTANCE) {
          return null;
        }
      }
      localCache.put(localKey, existingValue);
      return new SimpleValueWrapper(existingValue);
    } finally {
      cacheWideLock.readLock().unlock();
      lock.unlock();
    }
  }

  /**
   * Evicts a key from Redis when available and always evicts it from L1.
   *
   * @param key cache key
   */
  @Override
  public void evict(@NonNull Object key) {
    String localKey = convertKey(key);
    byte[] redisKey = serializeRedisKey(key);
    ReentrantLock lock = makeLock(localKey);
    RemoteCall<Void> remote;
    lock.lock();
    cacheWideLock.readLock().lock();
    try {
      remote =
          callRedis(
              () -> {
                getCacheWriter().evict(getName(), redisKey);
                return null;
              },
              "evict");
      localCache.invalidate(localKey);
    } finally {
      cacheWideLock.readLock().unlock();
      lock.unlock();
    }
    if (remote.available()) {
      sendViaRedis(localKey);
    }
  }

  /** Evicts a key and returns its canonical local representation for test coordination. */
  String localEvict(@NonNull Object key) {
    String localKey = convertKey(key);
    evict(key);
    return localKey;
  }

  /** Applies a remote invalidation to one local entry without writing to Redis. */
  void invalidateLocalEntry(@NonNull String localKey) {
    ReentrantLock lock = makeLock(localKey);
    lock.lock();
    cacheWideLock.readLock().lock();
    try {
      localCache.invalidate(localKey);
    } finally {
      cacheWideLock.readLock().unlock();
      lock.unlock();
    }
  }

  /**
   * Evicts a key and reports whether it was present in this node's local tier.
   *
   * @param key cache key
   * @return {@code true} when L1 contained the key before eviction
   */
  @Override
  public boolean evictIfPresent(@NonNull Object key) {
    String localKey = convertKey(key);
    ReentrantLock lock = makeLock(localKey);
    lock.lock();
    try {
      boolean present = localCache.getIfPresent(localKey) != null;
      evict(key);
      return present;
    } finally {
      lock.unlock();
    }
  }

  /** Clears both tiers and notifies other nodes to clear their local tiers. */
  @Override
  public void clear() {
    clearInternal(null);
  }

  /**
   * Clears Redis entries matching the pattern and clears this node's complete local tier.
   *
   * @param keyPattern Redis cache-key pattern
   */
  @Override
  public void clear(@NonNull String keyPattern) {
    clearInternal(keyPattern);
  }

  /** Applies a remote cache-wide invalidation without writing to Redis. */
  void invalidateLocalCache() {
    cacheWideLock.writeLock().lock();
    try {
      localCache.invalidateAll();
    } finally {
      cacheWideLock.writeLock().unlock();
    }
  }

  /**
   * Invalidates both tiers and reports whether either tier had mappings.
   *
   * @return {@code true} when a local or Redis mapping was removed
   */
  @Override
  public boolean invalidate() {
    boolean hadLocalMappings;
    RemoteCall<Boolean> remote;
    cacheWideLock.writeLock().lock();
    try {
      hadLocalMappings = localCache.estimatedSize() > 0;
      remote = callRedis(MultiLevelCache.super::invalidate, "invalidate");
      localCache.invalidateAll();
    } finally {
      cacheWideLock.writeLock().unlock();
    }
    if (remote.available()) {
      sendViaRedis(null);
    }
    return hadLocalMappings || Boolean.TRUE.equals(remote.value());
  }

  private void clearInternal(@Nullable String keyPattern) {
    RemoteCall<Void> remote;
    cacheWideLock.writeLock().lock();
    try {
      remote =
          callRedis(
              () -> {
                if (keyPattern == null) {
                  MultiLevelCache.super.clear("*");
                } else {
                  MultiLevelCache.super.clear(keyPattern);
                }
                return null;
              },
              "clear");
      localCache.invalidateAll();
    } finally {
      cacheWideLock.writeLock().unlock();
    }
    if (remote.available()) {
      sendViaRedis(null);
    }
  }

  /** Publishes stable and, when different, configured legacy invalidation representations. */
  private void sendViaRedis(@Nullable String key) {
    byte[] channel =
        Objects.requireNonNull(
            StringRedisSerializer.UTF_8.serialize(properties.getTopic()),
            "Invalidation channel was not serialized");
    MultiLevelCacheEvictMessage message =
        new MultiLevelCacheEvictMessage(getName(), key, instanceId);
    byte[] body = CacheInvalidationCodec.serialize(message);
    byte @Nullable [] legacyBody = serializeLegacyInvalidation(message, body);
    callRedis(
        () -> {
          redisTemplate.execute(
              (RedisCallback<Long>)
                  connection -> {
                    Long recipients = connection.publish(channel, body);
                    if (legacyBody != null) {
                      connection.publish(channel, legacyBody);
                    }
                    return recipients;
                  });
          return null;
        },
        "publish invalidation");
  }

  /** Encodes the rolling-upgrade payload unless it duplicates the stable representation. */
  private byte @Nullable [] serializeLegacyInvalidation(
      MultiLevelCacheEvictMessage message, byte[] stableBody) {
    try {
      byte[] legacyBody = legacyValueSerializer().serialize(message);
      return legacyBody != null && !Arrays.equals(legacyBody, stableBody) ? legacyBody : null;
    } catch (RuntimeException exception) {
      log.debug(
          "Legacy cache invalidation serializer cannot encode messages for cache '{}'",
          getName(),
          exception);
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private RedisSerializer<Object> legacyValueSerializer() {
    return (RedisSerializer<Object>) redisTemplate.getValueSerializer();
  }

  /** Runs Redis I/O through the breaker and distinguishes availability from data failures. */
  private <T> RemoteCall<T> callRedis(CheckedSupplier<T> call, String operation) {
    try {
      return new RemoteCall<>(true, cacheCircuitBreaker.executeCheckedSupplier(call));
    } catch (Throwable throwable) {
      Throwable failure = RedisFailureClassifier.unwrap(throwable);
      if (RedisFailureClassifier.isAvailabilityFailure(failure)) {
        log.debug("Redis {} unavailable for cache '{}'", operation, getName(), failure);
        return new RemoteCall<>(false, null);
      }
      throw propagate(failure);
    }
  }

  private static RuntimeException propagate(Throwable throwable) {
    if (throwable instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    if (throwable instanceof Error error) {
      throw error;
    }
    return new IllegalStateException("Unexpected checked Redis failure", throwable);
  }

  private byte[] serializeRedisKey(Object key) {
    return serializeCacheKey(createCacheKey(key));
  }

  private Object deserializeNonNullCacheValue(byte[] value) {
    return Objects.requireNonNull(
        deserializeCacheValue(value), "Redis value must not deserialize to null");
  }

  private RemoteCall<byte[]> remotePutIfAbsent(byte[] key, byte[] value, Duration ttl) {
    return callRedis(
        () -> getCacheWriter().putIfAbsent(getName(), key, value, ttl), "put-if-absent");
  }

  private Duration timeToLive(Object key, Object value) {
    return getCacheConfiguration().getTtlFunction().getTimeToLive(key, value);
  }

  private ReentrantLock makeLock(String localKey) {
    return Objects.requireNonNull(
        locks.get(localKey, ignored -> new ReentrantLock()), LOCK_WAS_NOT_INITIALIZED);
  }

  private static RedisCacheConfiguration adjustRedisCacheConfiguration(
      MultiLevelCacheConfigurationProperties properties,
      RedisTemplate<Object, Object> redisTemplate) {
    RedisSerializer<?> valueSerializer =
        Objects.requireNonNull(redisTemplate.getValueSerializer(), "Value serializer is required");
    return properties
        .toRedisCacheConfiguration()
        .disableCachingNullValues()
        .serializeValuesWith(
            RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer));
  }

  /** Result of Redis I/O, separating an unavailable backend from a legitimate null value. */
  private record RemoteCall<T>(boolean available, @Nullable T value) {}
}
