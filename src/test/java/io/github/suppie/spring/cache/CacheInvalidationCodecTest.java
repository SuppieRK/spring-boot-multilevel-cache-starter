package io.github.suppie.spring.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

class CacheInvalidationCodecTest {

  @Test
  void deserializeReturnsNullForAnEmptyPayload() {
    assertThat(CacheInvalidationCodec.deserialize(new byte[0])).isNull();
  }

  @Test
  void deserializeRejectsPayloadsOfTheWrongType() {
    byte[] payload = RedisSerializer.json().serialize("not-an-invalidation-message");

    assertThatThrownBy(() -> CacheInvalidationCodec.deserialize(payload))
        .isInstanceOf(SerializationException.class)
        .hasMessageContaining("Expected cache invalidation message")
        .hasMessageContaining(String.class.getName());
  }
}
