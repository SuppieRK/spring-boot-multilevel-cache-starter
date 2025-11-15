package io.github.suppie.spring.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.suppie.spring.cache.MultiLevelCacheConfigurationProperties.CircuitBreakerProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MultiLevelCacheConfigurationPropertiesTest {

  @Test
  void derivedCircuitBreakerDefaultsFollowSlowCallDuration() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();
    CircuitBreakerProperties cbp = properties.getCircuitBreaker();

    cbp.setSlowCallDurationThreshold(Duration.ofSeconds(1));

    assertThat(cbp.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(5);
    assertThat(cbp.getMaxWaitDurationInHalfOpenState()).isEqualTo(Duration.ofSeconds(5));
    assertThat(cbp.getSlidingWindowSize()).isEqualTo(10);
    assertThat(cbp.getMinimumNumberOfCalls()).isEqualTo(2);
    assertThat(cbp.getWaitDurationInOpenState()).isEqualTo(Duration.ofSeconds(2));
  }

  @Test
  void circuitBreakerManualOverridesPreserved() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();
    CircuitBreakerProperties cbp = properties.getCircuitBreaker();

    cbp.setSlowCallDurationThreshold(Duration.ofSeconds(2));
    cbp.setPermittedNumberOfCallsInHalfOpenState(3);
    cbp.setMaxWaitDurationInHalfOpenState(Duration.ofSeconds(7));
    cbp.setSlidingWindowSize(30);
    cbp.setMinimumNumberOfCalls(4);
    cbp.setWaitDurationInOpenState(Duration.ofSeconds(9));

    assertThat(cbp.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
    assertThat(cbp.getMaxWaitDurationInHalfOpenState()).isEqualTo(Duration.ofSeconds(7));
    assertThat(cbp.getSlidingWindowSize()).isEqualTo(30);
    assertThat(cbp.getMinimumNumberOfCalls()).isEqualTo(4);
    assertThat(cbp.getWaitDurationInOpenState()).isEqualTo(Duration.ofSeconds(9));
  }
}
