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

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.suppie.spring.cache.MultiLevelCacheConfigurationProperties.CircuitBreakerProperties;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.metrics.cache.CacheMeterBinderProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.StringUtils;

/** Autoconfiguration properties for this cache */
@Slf4j
@Configuration
@AutoConfigureAfter(RedisAutoConfiguration.class)
@AutoConfigureBefore(CacheAutoConfiguration.class)
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis")
@EnableConfigurationProperties({
  CacheProperties.class,
  MultiLevelCacheConfigurationProperties.class
})
public class MultiLevelCacheAutoConfiguration {

  public static final String CACHE_REDIS_TEMPLATE_NAME = "multiLevelCacheRedisTemplate";
  public static final String CIRCUIT_BREAKER_NAME = "multiLevelCacheCircuitBreaker";
  public static final String CIRCUIT_BREAKER_CONFIGURATION_NAME =
      "multiLevelCacheCircuitBreakerConfiguration";

  /**
   * Instantiates {@link RedisTemplate} to use for sending {@link MultiLevelCacheEvictMessage}
   *
   * @param connectionFactory to use in template
   * @return template to send messages about evicted entries
   */
  @Bean
  @ConditionalOnMissingBean(name = CACHE_REDIS_TEMPLATE_NAME)
  public RedisTemplate<Object, Object> multiLevelCacheRedisTemplate(
      RedisConnectionFactory connectionFactory) {
    RedisTemplate<Object, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setHashKeySerializer(new StringRedisSerializer());
    return template;
  }

  /**
   * @param cacheProperties for multi-level cache
   * @param multiLevelCacheRedisTemplate to send messages about evicted entries
   * @return cache manager for multi-level caching
   */
  @Bean
  public MultiLevelCacheManager cacheManager(
      ObjectProvider<CacheProperties> highLevelCacheProperties,
      MultiLevelCacheConfigurationProperties cacheProperties,
      RedisTemplate<Object, Object> multiLevelCacheRedisTemplate) {
    CircuitBreaker circuitBreaker = cacheCircuitBreaker(cacheProperties);
    return new MultiLevelCacheManager(
        highLevelCacheProperties, cacheProperties, multiLevelCacheRedisTemplate, circuitBreaker);
  }

  /** @return cache meter binder for local level of multi level cache */
  @Bean
  @ConditionalOnBean(MultiLevelCacheManager.class)
  @ConditionalOnClass({MeterBinder.class, CacheMeterBinderProvider.class})
  public CacheMeterBinderProvider<MultiLevelCache> multiLevelCacheCacheMeterBinderProvider() {
    return (cache, tags) -> new CaffeineCacheMetrics(cache.getLocalCache(), cache.getName(), tags);
  }

  /**
   * @param cacheProperties for multi-level cache
   * @param multiLevelCacheRedisTemplate to receive messages about evicted entries
   * @param cacheManager for multi-level caching
   * @return Redis topic listener to coordinate entries eviction
   */
  @Bean
  public RedisMessageListenerContainer multiLevelCacheRedisMessageListenerContainer(
      MultiLevelCacheConfigurationProperties cacheProperties,
      RedisTemplate<Object, Object> multiLevelCacheRedisTemplate,
      MultiLevelCacheManager cacheManager) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(
        Objects.requireNonNull(multiLevelCacheRedisTemplate.getConnectionFactory()));
    container.addMessageListener(
        createMessageListener(multiLevelCacheRedisTemplate, cacheManager),
        new ChannelTopic(cacheProperties.getTopic()));
    return container;
  }

  /**
   * @param cacheProperties to get circuit breaker properties for fault tolerance
   * @return circuit breaker to handle Redis connection exceptions and fallback to use local cache
   */
  static CircuitBreaker cacheCircuitBreaker(
      MultiLevelCacheConfigurationProperties cacheProperties) {
    CircuitBreakerRegistry cbr = CircuitBreakerRegistry.ofDefaults();

    if (!cbr.getConfiguration(CIRCUIT_BREAKER_CONFIGURATION_NAME).isPresent()) {
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

      Duration recommendedMaxDurationInOpenState =
          cacheProperties
              .getTimeToLive()
              .multipliedBy(cacheProperties.getLocal().getExpiryJitter() - 100L)
              .dividedBy(200);

      if (props.getWaitDurationInOpenState().compareTo(recommendedMaxDurationInOpenState) <= 0) {
        log.warn(
            "Cache circuit breaker wait duration in open state {} is more than recommended value of {}, "
                + "this can result in local cache expiry while circuit breaker is still in OPEN state.",
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
                    "Cache circuit breaker error occurred in " + event.getElapsedDuration(),
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
   * @param multiLevelCacheRedisTemplate to receive messages about evicted entries
   * @param cacheManager for multi-level caching
   * @return Redis topic message listener to coordinate entries eviction
   */
  private static MessageListener createMessageListener(
      RedisTemplate<Object, Object> multiLevelCacheRedisTemplate,
      MultiLevelCacheManager cacheManager) {
    return (message, pattern) -> {
      try {
        MultiLevelCacheEvictMessage request =
            (MultiLevelCacheEvictMessage)
                multiLevelCacheRedisTemplate.getValueSerializer().deserialize(message.getBody());

        if (request == null) return;

        String cacheName = request.getCacheName();
        String entryKey = request.getEntryKey();

        if (!StringUtils.hasText(cacheName)) return;

        MultiLevelCache cache = (MultiLevelCache) cacheManager.getCache(cacheName);

        if (cache == null) return;

        log.trace("Received Redis message to evict key {} from cache {}", entryKey, cacheName);

        if (entryKey == null) cache.localClear();
        else cache.localEvict(entryKey);
      } catch (ClassCastException e) {
        log.error(
            "Cannot cast cache instance returned by cache manager to "
                + MultiLevelCache.class.getName(),
            e);
      } catch (Exception e) {
        log.debug("Unknown Redis message", e);
      }
    };
  }
}
