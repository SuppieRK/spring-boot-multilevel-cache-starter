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
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.suppie.spring.cache.MultiLevelCacheConfigurationProperties.CircuitBreakerProperties;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheProperties;
import org.springframework.boot.cache.metrics.CacheMeterBinderProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.RedisMessageListenerContainerConfigurer;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.StringUtils;

/** Autoconfiguration properties for this cache */
@Slf4j
@AutoConfiguration(after = DataRedisAutoConfiguration.class, before = CacheAutoConfiguration.class)
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis")
@ConditionalOnClass({RedisCache.class, Caffeine.class, CircuitBreaker.class})
@ConditionalOnSingleCandidate(RedisConnectionFactory.class)
@ConditionalOnMissingBean(CacheManager.class)
@EnableConfigurationProperties({
  CacheProperties.class,
  MultiLevelCacheConfigurationProperties.class
})
public class MultiLevelCacheAutoConfiguration {

  /** Bean name for RedisTemplate used by multi-level cache messaging */
  public static final String CACHE_REDIS_TEMPLATE_NAME = "multiLevelCacheRedisTemplate";

  /** Bean name for the circuit breaker guarding Redis cache access */
  public static final String CIRCUIT_BREAKER_NAME = "multiLevelCacheCircuitBreaker";

  /** Named configuration profile for the cache circuit breaker */
  public static final String CIRCUIT_BREAKER_CONFIGURATION_NAME =
      "multiLevelCacheCircuitBreakerConfiguration";

  /** Bean name for the shared Redis message listener container */
  public static final String REDIS_MESSAGE_LISTENER_CONTAINER_NAME =
      "redisMessageListenerContainer";

  /** Bean name for the listener that handles multi-level cache invalidation messages */
  public static final String CACHE_INVALIDATION_MESSAGE_LISTENER_NAME =
      "multiLevelCacheInvalidationMessageListener";

  /** Bean name for the registrar that attaches the invalidation listener to Redis */
  public static final String CACHE_INVALIDATION_MESSAGE_LISTENER_REGISTRAR_NAME =
      "multiLevelCacheInvalidationMessageListenerRegistrar";

  /**
   * Instantiates {@link RedisTemplate} to use for sending {@link MultiLevelCacheEvictMessage}
   *
   * @param connectionFactory to use in template
   * @param valueSerializerProvider to use in template
   * @return template to send messages about evicted entries
   */
  @Bean
  @ConditionalOnMissingBean(name = CACHE_REDIS_TEMPLATE_NAME)
  public RedisTemplate<Object, Object> multiLevelCacheRedisTemplate(
      RedisConnectionFactory connectionFactory,
      ObjectProvider<@NonNull RedisSerializer<@NonNull Object>> valueSerializerProvider) {
    RedisTemplate<Object, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setHashKeySerializer(new StringRedisSerializer());
    RedisSerializer<@NonNull Object> valueSerializer =
        valueSerializerProvider.getIfAvailable(RedisSerializer::json);
    template.setValueSerializer(valueSerializer);
    template.setHashValueSerializer(valueSerializer);
    template.afterPropertiesSet();
    return template;
  }

  /**
   * @param highLevelCacheProperties as a baseline
   * @param cacheProperties for multi-level cache
   * @param circuitBreaker if application defined its own circuit breaker
   * @param multiLevelCacheRedisTemplate to send messages about evicted entries
   * @return cache manager for multi-level caching
   */
  @Bean
  public MultiLevelCacheManager cacheManager(
      ObjectProvider<@NonNull CacheProperties> highLevelCacheProperties,
      MultiLevelCacheConfigurationProperties cacheProperties,
      @Qualifier(CIRCUIT_BREAKER_NAME) CircuitBreaker circuitBreaker,
      @Qualifier(CACHE_REDIS_TEMPLATE_NAME)
          RedisTemplate<Object, Object> multiLevelCacheRedisTemplate) {
    return new MultiLevelCacheManager(
        highLevelCacheProperties, cacheProperties, multiLevelCacheRedisTemplate, circuitBreaker);
  }

  /**
   * @return cache meter binder for local level of multi level cache
   */
  @Bean
  @ConditionalOnBean(MultiLevelCacheManager.class)
  @ConditionalOnClass({MeterBinder.class, CacheMeterBinderProvider.class})
  public CacheMeterBinderProvider<@NonNull MultiLevelCache>
      multiLevelCacheCacheMeterBinderProvider() {
    return (cache, tags) ->
        new CaffeineCacheMetrics<>(cache.getLocalCache(), cache.getName(), tags);
  }

  /**
   * @param redisConnectionFactory to use when a shared listener container is not provided
   * @param configurerProvider to align the fallback listener container with Spring Boot settings
   * @return Redis topic listener container to coordinate entry eviction
   */
  @Bean(name = REDIS_MESSAGE_LISTENER_CONTAINER_NAME)
  @ConditionalOnMissingBean(name = REDIS_MESSAGE_LISTENER_CONTAINER_NAME)
  public RedisMessageListenerContainer redisMessageListenerContainer(
      RedisConnectionFactory redisConnectionFactory,
      ObjectProvider<@NonNull RedisMessageListenerContainerConfigurer> configurerProvider) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    RedisMessageListenerContainerConfigurer configurer = configurerProvider.getIfAvailable();

    if (configurer != null) {
      configurer.configure(container, redisConnectionFactory);
    } else {
      container.setConnectionFactory(redisConnectionFactory);
    }

    return container;
  }

  /**
   * @param multiLevelCacheRedisTemplate to receive legacy invalidation messages during rolling
   *     upgrades
   * @param cacheManager for multi-level caching
   * @return Redis topic listener that handles entry eviction messages
   */
  @Bean(name = CACHE_INVALIDATION_MESSAGE_LISTENER_NAME)
  @ConditionalOnMissingBean(name = CACHE_INVALIDATION_MESSAGE_LISTENER_NAME)
  public MessageListener multiLevelCacheInvalidationMessageListener(
      @Qualifier(CACHE_REDIS_TEMPLATE_NAME)
          RedisTemplate<Object, Object> multiLevelCacheRedisTemplate,
      MultiLevelCacheManager cacheManager) {
    return createMessageListener(multiLevelCacheRedisTemplate, cacheManager);
  }

  /**
   * @param cacheProperties for multi-level cache
   * @param listenerContainer shared Redis topic listener container
   * @param messageListener listener that handles entry eviction messages
   * @return registrar that subscribes the invalidation listener to the configured topic
   */
  @Bean(name = CACHE_INVALIDATION_MESSAGE_LISTENER_REGISTRAR_NAME)
  @ConditionalOnMissingBean(name = CACHE_INVALIDATION_MESSAGE_LISTENER_REGISTRAR_NAME)
  public SmartInitializingSingleton multiLevelCacheInvalidationMessageListenerRegistrar(
      MultiLevelCacheConfigurationProperties cacheProperties,
      @Qualifier(REDIS_MESSAGE_LISTENER_CONTAINER_NAME)
          RedisMessageListenerContainer listenerContainer,
      @Qualifier(CACHE_INVALIDATION_MESSAGE_LISTENER_NAME) MessageListener messageListener) {
    return () ->
        listenerContainer.addMessageListener(
            messageListener, new ChannelTopic(cacheProperties.getTopic()));
  }

  /**
   * @param cacheProperties to get circuit breaker properties for fault tolerance
   * @return circuit breaker to handle Redis connection exceptions and fallback to use local cache
   */
  @Bean(name = CIRCUIT_BREAKER_NAME)
  @ConditionalOnMissingBean(name = CIRCUIT_BREAKER_NAME)
  public CircuitBreaker cacheCircuitBreaker(
      MultiLevelCacheConfigurationProperties cacheProperties) {
    CircuitBreakerRegistry cbr = CircuitBreakerRegistry.ofDefaults();

    if (cbr.getConfiguration(CIRCUIT_BREAKER_CONFIGURATION_NAME).isEmpty()) {
      CircuitBreakerProperties props = cacheProperties.getCircuitBreaker();

      CircuitBreakerConfig.Builder cbc = CircuitBreakerConfig.custom();
      cbc.failureRateThreshold(props.getFailureRateThreshold());
      cbc.slowCallRateThreshold(props.getSlowCallRateThreshold());
      cbc.slowCallDurationThreshold(props.getSlowCallDurationThreshold());
      cbc.permittedNumberOfCallsInHalfOpenState(props.getPermittedNumberOfCallsInHalfOpenState());
      cbc.maxWaitDurationInHalfOpenState(props.getMaxWaitDurationInHalfOpenState());
      cbc.slidingWindowType(props.getSlidingWindowType());
      cbc.slidingWindowSize(props.getSlidingWindowSize());
      cbc.minimumNumberOfCalls(props.getMinimumNumberOfCalls());
      cbc.waitDurationInOpenState(props.getWaitDurationInOpenState());
      cbc.recordException(RedisFailureClassifier::isAvailabilityFailure);
      cbc.ignoreException(throwable -> !RedisFailureClassifier.isAvailabilityFailure(throwable));

      Duration recommendedMaxDurationInOpenState =
          cacheProperties
              .getTimeToLive()
              .multipliedBy(100L - cacheProperties.getLocal().getExpiryJitter())
              .dividedBy(200);

      if (props.getWaitDurationInOpenState().compareTo(recommendedMaxDurationInOpenState) > 0) {
        log.warn(
            "Cache circuit breaker wait duration in open state {} is more than recommended value of"
                + " {}, this can result in local cache expiry while circuit breaker is still in"
                + " OPEN state.",
            props.getWaitDurationInOpenState(),
            recommendedMaxDurationInOpenState);
      }

      cbr.addConfiguration(CIRCUIT_BREAKER_CONFIGURATION_NAME, cbc.build());
    }

    CircuitBreaker cb =
        cbr.circuitBreaker(CIRCUIT_BREAKER_NAME, CIRCUIT_BREAKER_CONFIGURATION_NAME);
    cb.getEventPublisher()
        .onError(
            event ->
                log.trace(
                    "Cache circuit breaker error occurred in {}",
                    event.getElapsedDuration(),
                    event.getThrowable()))
        .onSlowCallRateExceeded(
            event ->
                log.trace(
                    "Cache circuit breaker {} calls were slow, rate exceeded",
                    event.getSlowCallRate()))
        .onFailureRateExceeded(
            event ->
                log.trace(
                    "Cache circuit breaker {} calls failed, rate exceeded", event.getFailureRate()))
        .onStateTransition(
            event ->
                log.trace(
                    "Cache circuit breaker {} state transitioned from {} to {}",
                    event.getCircuitBreakerName(),
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState()));
    return cb;
  }

  /**
   * @param multiLevelCacheRedisTemplate to decode legacy invalidation messages during rolling
   *     upgrades
   * @param cacheManager for multi-level caching
   * @return Redis topic message listener to coordinate entry eviction
   */
  static MessageListener createMessageListener(
      RedisTemplate<Object, Object> multiLevelCacheRedisTemplate,
      MultiLevelCacheManager cacheManager) {
    return (message, pattern) ->
        handleInvalidationMessage(message.getBody(), multiLevelCacheRedisTemplate, cacheManager);
  }

  private static void handleInvalidationMessage(
      byte[] body,
      RedisTemplate<Object, Object> multiLevelCacheRedisTemplate,
      MultiLevelCacheManager cacheManager) {
    try {
      MultiLevelCacheEvictMessage request =
          deserializeInvalidationMessage(body, multiLevelCacheRedisTemplate);

      if (request == null) return;

      if (cacheManager.getInstanceId().equals(request.getSenderId())) return;

      String cacheName = request.getCacheName();
      String entryKey = request.getEntryKey();

      if (!StringUtils.hasText(cacheName)) return;

      MultiLevelCache cache = cacheManager.getExistingCache(cacheName);

      if (cache == null) return;

      log.trace("Received Redis message to evict key {} from cache {}", entryKey, cacheName);

      if (entryKey == null) cache.invalidateLocalCache();
      else cache.invalidateLocalEntry(entryKey);
    } catch (RuntimeException exception) {
      log.debug("Unknown Redis cache invalidation message", exception);
    }
  }

  private static @Nullable MultiLevelCacheEvictMessage deserializeInvalidationMessage(
      byte[] body, RedisTemplate<Object, Object> multiLevelCacheRedisTemplate) {
    try {
      return CacheInvalidationCodec.deserialize(body);
    } catch (RuntimeException stableCodecFailure) {
      Object legacyValue = multiLevelCacheRedisTemplate.getValueSerializer().deserialize(body);
      if (legacyValue instanceof MultiLevelCacheEvictMessage legacyMessage) {
        return legacyMessage;
      }
      throw stableCodecFailure;
    }
  }
}
