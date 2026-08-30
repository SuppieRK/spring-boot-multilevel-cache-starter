package io.github.suppie.spring.cache;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/** Benchmarks the stable invalidation wire codec independently of Redis transport. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CacheInvalidationCodecBenchmark {

  /** Measures encoding the stable invalidation message. */
  @Benchmark
  public void serialize(CodecState state, Blackhole blackhole) {
    blackhole.consume(CacheInvalidationCodec.serialize(state.message));
  }

  /** Measures decoding the stable invalidation message. */
  @Benchmark
  public void deserialize(CodecState state, Blackhole blackhole) {
    blackhole.consume(CacheInvalidationCodec.deserialize(state.payload));
  }

  /** Provides immutable message and payload fixtures for codec measurements. */
  @State(Scope.Benchmark)
  public static class CodecState {
    MultiLevelCacheEvictMessage message;
    byte[] payload;

    /** Initializes the codec fixtures once per fork. */
    @Setup(Level.Trial)
    public void setUp() {
      message = new MultiLevelCacheEvictMessage("benchmark", "benchmark-key", "instance");
      payload = CacheInvalidationCodec.serialize(message);
    }
  }
}
