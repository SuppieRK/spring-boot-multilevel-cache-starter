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

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

/** Classifies Redis failures that permit seamless operation from the local cache tier. */
final class RedisFailureClassifier {
  private RedisFailureClassifier() {}

  /**
   * Returns whether a failure represents an unavailable or timed-out Redis backend.
   *
   * @param throwable failure to inspect, including its cause chain
   * @return {@code true} when local-cache fallback is safe
   */
  static boolean isAvailabilityFailure(Throwable throwable) {
    Throwable current = unwrap(throwable);
    while (current != null) {
      if (current instanceof CallNotPermittedException
          || current instanceof RedisConnectionFailureException
          || current instanceof DataAccessResourceFailureException
          || current instanceof QueryTimeoutException
          || current instanceof ConnectException
          || current instanceof SocketTimeoutException
          || current instanceof TimeoutException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  /**
   * Removes asynchronous wrapper exceptions while retaining the underlying Redis failure.
   *
   * @param throwable failure to unwrap
   * @return first cause that is not an asynchronous execution wrapper
   */
  static Throwable unwrap(Throwable throwable) {
    Throwable current = throwable;
    while ((current instanceof CompletionException || current instanceof ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}
