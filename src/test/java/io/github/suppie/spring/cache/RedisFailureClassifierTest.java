package io.github.suppie.spring.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.serializer.SerializationException;

class RedisFailureClassifierTest {

  @Test
  void classifiesOnlyAvailabilityFailures() {
    assertThat(
            RedisFailureClassifier.isAvailabilityFailure(
                new RedisConnectionFailureException("down")))
        .isTrue();
    assertThat(
            RedisFailureClassifier.isAvailabilityFailure(
                new CompletionException(new QueryTimeoutException("slow"))))
        .isTrue();
    assertThat(RedisFailureClassifier.isAvailabilityFailure(new ConnectException("refused")))
        .isTrue();
    assertThat(RedisFailureClassifier.isAvailabilityFailure(new SocketTimeoutException("slow")))
        .isTrue();
    assertThat(RedisFailureClassifier.isAvailabilityFailure(new TimeoutException("slow"))).isTrue();
    assertThat(
            RedisFailureClassifier.isAvailabilityFailure(
                new ExecutionException(new RedisConnectionFailureException("down"))))
        .isTrue();
    assertThat(RedisFailureClassifier.unwrap(new CompletionException("no cause", null)))
        .isInstanceOf(CompletionException.class);

    CircuitBreaker breaker = CircuitBreaker.ofDefaults("open");
    breaker.transitionToOpenState();
    Throwable openFailure =
        org.assertj.core.api.Assertions.catchThrowable(() -> breaker.executeRunnable(() -> {}));
    assertThat(openFailure).isInstanceOf(CallNotPermittedException.class);
    assertThat(RedisFailureClassifier.isAvailabilityFailure(openFailure)).isTrue();

    assertThat(RedisFailureClassifier.isAvailabilityFailure(new SerializationException("bad data")))
        .isFalse();
    assertThat(RedisFailureClassifier.isAvailabilityFailure(new IllegalArgumentException("bug")))
        .isFalse();
  }
}
