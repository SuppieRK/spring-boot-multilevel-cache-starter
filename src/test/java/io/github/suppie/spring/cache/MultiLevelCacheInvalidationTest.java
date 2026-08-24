package io.github.suppie.spring.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.cache.autoconfigure.CacheProperties;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

class MultiLevelCacheInvalidationTest {

  @Test
  void customCacheValueSerializerDoesNotSerializeInvalidationMessage() {
    RedisConnection connection = mock(RedisConnection.class);
    when(connection.publish(any(byte[].class), any(byte[].class))).thenReturn(1L);
    RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
    when(connectionFactory.getConnection()).thenReturn(connection);

    RedisTemplate<Object, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(StringRedisSerializer.UTF_8);
    template.setValueSerializer(stringSerializer());
    template.afterPropertiesSet();

    MultiLevelCache cache =
        new MultiLevelCache(
            "cache",
            new MultiLevelCacheConfigurationProperties(),
            new TestRedisCacheWriter(),
            template,
            Caffeine.newBuilder().build(),
            CircuitBreaker.ofDefaults("invalidation"),
            "instance");

    assertThatCode(() -> cache.put("key", "value")).doesNotThrowAnyException();

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(connection).publish(any(byte[].class), body.capture());
    Object decoded = RedisSerializer.json().deserialize(body.getValue());
    assertThat(decoded).isInstanceOf(MultiLevelCacheEvictMessage.class);
    MultiLevelCacheEvictMessage message = (MultiLevelCacheEvictMessage) decoded;
    assertThat(message.getCacheName()).isEqualTo("cache");
    assertThat(message.getEntryKey()).isEqualTo("key");
    assertThat(message.getSenderId()).isEqualTo("instance");
  }

  @Test
  void inboundInvalidationDoesNotCreateUnknownCache() {
    RedisTemplate<Object, Object> template = listenerTemplate();
    MultiLevelCacheManager manager = manager(template);

    byte[] body =
        CacheInvalidationCodec.serialize(
            new MultiLevelCacheEvictMessage("unknown", "key", "other-instance"));
    MultiLevelCacheAutoConfiguration.createMessageListener(manager)
        .onMessage(new DefaultMessage("topic".getBytes(), body), null);

    assertThat(manager.getCacheNames()).isEmpty();
  }

  @Test
  void malformedAndSelfOriginatedMessagesAreIgnored() {
    RedisTemplate<Object, Object> template = listenerTemplate();
    MultiLevelCacheManager manager = manager(template);
    MultiLevelCache cache = (MultiLevelCache) manager.getCache("known");
    cache.getLocalCache().put(cache.toLocalKey("key"), "value");
    var listener = MultiLevelCacheAutoConfiguration.createMessageListener(manager);

    assertThatCode(
            () ->
                listener.onMessage(
                    new DefaultMessage("topic".getBytes(), "not-json".getBytes()), null))
        .doesNotThrowAnyException();
    byte[] selfMessage =
        CacheInvalidationCodec.serialize(
            new MultiLevelCacheEvictMessage("known", "key", manager.getInstanceId()));
    listener.onMessage(new DefaultMessage("topic".getBytes(), selfMessage), null);

    assertThat(cache.getLocalCache().getIfPresent(cache.toLocalKey("key"))).isEqualTo("value");
  }

  private static RedisTemplate<Object, Object> listenerTemplate() {
    RedisTemplate<Object, Object> template = mock(RedisTemplate.class);
    when(template.getConnectionFactory()).thenReturn(mock(RedisConnectionFactory.class));
    doReturn(RedisSerializer.json()).when(template).getValueSerializer();
    return template;
  }

  private static MultiLevelCacheManager manager(RedisTemplate<Object, Object> template) {
    ObjectProvider<CacheProperties> cacheProperties = mock(ObjectProvider.class);
    return new MultiLevelCacheManager(
        cacheProperties,
        new MultiLevelCacheConfigurationProperties(),
        template,
        CircuitBreaker.ofDefaults("listener"));
  }

  @SuppressWarnings("unchecked")
  private static RedisSerializer<Object> stringSerializer() {
    return (RedisSerializer<Object>) (RedisSerializer<?>) StringRedisSerializer.UTF_8;
  }
}
