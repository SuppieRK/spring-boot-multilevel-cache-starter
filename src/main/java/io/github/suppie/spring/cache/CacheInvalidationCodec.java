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

import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

/** Stable v0 codec for cache invalidation messages. */
final class CacheInvalidationCodec {
  private static final RedisSerializer<Object> SERIALIZER = RedisSerializer.json();

  private CacheInvalidationCodec() {}

  static byte[] serialize(MultiLevelCacheEvictMessage message) {
    return SERIALIZER.serialize(message);
  }

  static @Nullable MultiLevelCacheEvictMessage deserialize(byte[] body) {
    Object value = SERIALIZER.deserialize(body);
    if (value == null) {
      return null;
    }
    if (value instanceof MultiLevelCacheEvictMessage message) {
      return message;
    }
    throw new SerializationException(
        "Expected cache invalidation message but received " + value.getClass().getName());
  }
}
