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

import java.util.Arrays;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.cache.CacheType;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@ExtendWith(OutputCaptureExtension.class)
class MultiLevelCacheAutoConfigurationTest extends AbstractRedisIntegrationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              UserConfigurations.of(
                  MultiLevelCacheAutoConfiguration.class,
                  RedisAutoConfiguration.class,
                  CacheAutoConfiguration.class));

  @Test
  void instantiationTest() {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .run(
            context -> {
              Assertions.assertThat(context)
                  .hasBean(MultiLevelCacheAutoConfiguration.CACHE_REDIS_TEMPLATE_NAME);
              Assertions.assertThat(context).hasSingleBean(MultiLevelCacheManager.class);
              Assertions.assertThat(context).hasSingleBean(RedisMessageListenerContainer.class);
            });
  }

  @Test
  void instantiationTestWithCacheNames() {
    final String cache1 = "cache1";
    final String cache2 = "cache2";

    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .withPropertyValues("spring.cache.cache-names=" + cache1)
        .run(
            context -> {
              Assertions.assertThat(context)
                  .hasBean(MultiLevelCacheAutoConfiguration.CACHE_REDIS_TEMPLATE_NAME);
              Assertions.assertThat(context).hasSingleBean(MultiLevelCacheManager.class);
              Assertions.assertThat(context).hasSingleBean(RedisMessageListenerContainer.class);

              MultiLevelCacheManager cacheManager = context.getBean(MultiLevelCacheManager.class);
              Assertions.assertThat(cacheManager.getCacheNames()).contains(cache1);
              Assertions.assertThat(cacheManager.getCacheNames()).doesNotContain(cache2);

              Assertions.assertThat(cacheManager.getCache(cache2)).isNull();
            });
  }

  @ParameterizedTest
  @MethodSource("incorrectCacheTypes")
  void instantiationTestWithDifferentCacheTypes(CacheType cacheType) {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + cacheType.name().toLowerCase())
        .run(
            context -> {
              Assertions.assertThat(context)
                  .doesNotHaveBean(MultiLevelCacheAutoConfiguration.CACHE_REDIS_TEMPLATE_NAME);
              Assertions.assertThat(context).doesNotHaveBean(MultiLevelCacheManager.class);
              Assertions.assertThat(context).doesNotHaveBean(RedisMessageListenerContainer.class);
            });
  }

  @ParameterizedTest
  @MethodSource("localExpirationModes")
  void instantiationTestWithDifferentLocalExpirationModes(
      String mode, LocalExpirationMode expected) {
    ApplicationContextRunner applicationContextRunner =
        this.runner
            .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
            .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
            .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase());

    if (mode != null) {
      applicationContextRunner =
          applicationContextRunner.withPropertyValues(
              "spring.cache.multilevel.local.expiration-mode=" + mode);
    }

    applicationContextRunner.run(
        context -> {
          MultiLevelCacheManager cacheManager = context.getBean(MultiLevelCacheManager.class);
          Assertions.assertThat(cacheManager.getProperties().getLocal().getExpirationMode())
              .isEqualTo(expected);
        });
  }

  static Stream<Arguments> incorrectCacheTypes() {
    return Arrays.stream(CacheType.values())
        .filter(cacheType -> !CacheType.REDIS.equals(cacheType))
        .map(Arguments::of);
  }

  static Stream<Arguments> localExpirationModes() {
    return Stream.of(
        Arguments.of(null, LocalExpirationMode.AFTER_CREATE),
        Arguments.of("after-create", LocalExpirationMode.AFTER_CREATE),
        Arguments.of("AFTER_CREATE", LocalExpirationMode.AFTER_CREATE),
        Arguments.of("after-update", LocalExpirationMode.AFTER_UPDATE),
        Arguments.of("AFTER_UPDATE", LocalExpirationMode.AFTER_UPDATE),
        Arguments.of("after-read", LocalExpirationMode.AFTER_READ),
        Arguments.of("AFTER_READ", LocalExpirationMode.AFTER_READ));
  }

  @Test
  void circuitBreakerWarnsWhenWaitDurationTooHigh(CapturedOutput output) {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .withPropertyValues("spring.cache.multilevel.time-to-live=10s")
        .withPropertyValues("spring.cache.multilevel.local.expiry-jitter=80")
        .withPropertyValues(
            "spring.cache.multilevel.circuit-breaker.wait-duration-in-open-state=3s")
        .run(context -> Assertions.assertThat(context).hasSingleBean(MultiLevelCacheManager.class));

    Assertions.assertThat(output)
        .contains(
            "Cache circuit breaker wait duration in open state PT3S is more than recommended value of PT1S");
  }

  @Test
  void useKeyPrefixWithoutPrefixFailsFast() {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .withPropertyValues("spring.cache.cache-names=test")
        .withPropertyValues("spring.cache.multilevel.use-key-prefix=true")
        .withPropertyValues("spring.cache.multilevel.key-prefix=")
        .run(
            context -> {
              Throwable failure = context.getStartupFailure();
              Assertions.assertThat(failure)
                  .isNotNull()
                  .hasMessageContaining("spring.cache.multilevel.key-prefix");
            });
  }

  @Test
  void redisTemplateUsesJsonValueSerializerByDefault() {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .run(
            context -> {
              RedisTemplate<Object, Object> template =
                  context.getBean(
                      MultiLevelCacheAutoConfiguration.CACHE_REDIS_TEMPLATE_NAME,
                      RedisTemplate.class);
              Assertions.assertThat(template.getValueSerializer())
                  .isInstanceOf(GenericJackson2JsonRedisSerializer.class);
            });
  }

  @Test
  void redisTemplateUsesCustomValueSerializerWhenProvided() {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .withUserConfiguration(CustomSerializerConfiguration.class)
        .run(
            context -> {
              RedisTemplate<Object, Object> template =
                  context.getBean(
                      MultiLevelCacheAutoConfiguration.CACHE_REDIS_TEMPLATE_NAME,
                      RedisTemplate.class);
              Assertions.assertThat(template.getValueSerializer())
                  .isInstanceOf(StringRedisSerializer.class);
            });
  }

  static class CustomSerializerConfiguration {
    @Bean
    RedisSerializer<Object> multiLevelCacheValueSerializer() {
      return (RedisSerializer<Object>) (RedisSerializer<?>) new StringRedisSerializer();
    }
  }
}
