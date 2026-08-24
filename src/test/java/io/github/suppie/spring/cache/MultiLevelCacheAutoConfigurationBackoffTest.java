package io.github.suppie.spring.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

class MultiLevelCacheAutoConfigurationBackoffTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(MultiLevelCacheAutoConfiguration.class))
          .withPropertyValues("spring.cache.type=redis");

  @Test
  void userCacheManagerSuppressesStarterConfiguration() {
    runner
        .withUserConfiguration(UserCacheManagerConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(CacheManager.class);
              assertThat(context).doesNotHaveBean(MultiLevelCacheManager.class);
              assertThat(context.getBean(CacheManager.class))
                  .isInstanceOf(ConcurrentMapCacheManager.class);
            });
  }

  @Test
  void namedCircuitBreakerIsRetained() {
    runner
        .withUserConfiguration(UserCircuitBreakerConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasBean(MultiLevelCacheAutoConfiguration.CIRCUIT_BREAKER_NAME);
              assertThat(
                      context.getBean(
                          MultiLevelCacheAutoConfiguration.CIRCUIT_BREAKER_NAME,
                          CircuitBreaker.class))
                  .isSameAs(UserCircuitBreakerConfiguration.CIRCUIT_BREAKER);
            });
  }

  @Test
  void missingRedisConnectionFactoryBacksOffCleanly() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(MultiLevelCacheManager.class);
        });
  }

  @Test
  void ambiguousRedisConnectionFactoriesBackOffCleanly() {
    runner
        .withUserConfiguration(AmbiguousRedisConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(MultiLevelCacheManager.class);
            });
  }

  @Test
  void legacyUniquelyTypedSerializerIsRetainedWithoutReservedBeanName() {
    runner
        .withUserConfiguration(LegacySerializerConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              RedisTemplate<?, ?> template =
                  context.getBean(
                      MultiLevelCacheAutoConfiguration.CACHE_REDIS_TEMPLATE_NAME,
                      RedisTemplate.class);
              assertThat(template.getValueSerializer())
                  .isSameAs(LegacySerializerConfiguration.SERIALIZER);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class UserCacheManagerConfiguration {
    @Bean
    CacheManager userCacheManager() {
      return new ConcurrentMapCacheManager();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class UserCircuitBreakerConfiguration {
    static final CircuitBreaker CIRCUIT_BREAKER = CircuitBreaker.ofDefaults("user");

    @Bean
    RedisConnectionFactory redisConnectionFactory() {
      return mock(RedisConnectionFactory.class);
    }

    @Bean(name = MultiLevelCacheAutoConfiguration.REDIS_MESSAGE_LISTENER_CONTAINER_NAME)
    RedisMessageListenerContainer redisMessageListenerContainer() {
      return mock(RedisMessageListenerContainer.class);
    }

    @Bean(name = MultiLevelCacheAutoConfiguration.CIRCUIT_BREAKER_NAME)
    CircuitBreaker circuitBreaker() {
      return CIRCUIT_BREAKER;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AmbiguousRedisConfiguration {
    @Bean
    RedisConnectionFactory firstRedisConnectionFactory() {
      return mock(RedisConnectionFactory.class);
    }

    @Bean
    RedisConnectionFactory secondRedisConnectionFactory() {
      return mock(RedisConnectionFactory.class);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class LegacySerializerConfiguration {
    @SuppressWarnings("unchecked")
    static final RedisSerializer<Object> SERIALIZER =
        (RedisSerializer<Object>) (RedisSerializer<?>) StringRedisSerializer.UTF_8;

    @Bean
    RedisConnectionFactory redisConnectionFactory() {
      return mock(RedisConnectionFactory.class);
    }

    @Bean(name = MultiLevelCacheAutoConfiguration.REDIS_MESSAGE_LISTENER_CONTAINER_NAME)
    RedisMessageListenerContainer redisMessageListenerContainer() {
      return mock(RedisMessageListenerContainer.class);
    }

    @Bean
    RedisSerializer<Object> legacySerializerWithArbitraryBeanName() {
      return SERIALIZER;
    }
  }
}
