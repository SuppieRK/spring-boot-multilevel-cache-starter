package io.github.suppie.spring.cache.benchmark;

import static io.github.suppie.spring.cache.MultiLevelCacheBenchmarkSupport.KEY;
import static io.github.suppie.spring.cache.MultiLevelCacheBenchmarkSupport.VALUE;

import io.github.suppie.spring.cache.MultiLevelCacheBenchmarkSupport;
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

/** Benchmarks cache write surfaces and the meaningful put-if-absent branches. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MultiLevelCacheWriteBenchmark {

  @Benchmark
  public void putNew(AbsentState state) {
    state.cache.put(KEY, VALUE);
  }

  @Benchmark
  public void putNullEvicts(PresentState state) {
    state.cache.put(KEY, null);
  }

  @Benchmark
  public void putIfAbsentL1Hit(PresentState state, Blackhole blackhole) {
    blackhole.consume(state.cache.putIfAbsent(KEY, "candidate"));
  }

  @Benchmark
  public void putIfAbsentL2Hit(L2PresentState state, Blackhole blackhole) {
    blackhole.consume(state.cache.putIfAbsent(KEY, "candidate"));
  }

  @Benchmark
  public void putIfAbsentColdInsert(AbsentState state, Blackhole blackhole) {
    blackhole.consume(state.cache.putIfAbsent(KEY, VALUE));
  }

  @State(Scope.Benchmark)
  public static class AbsentState extends MultiLevelCacheBenchmarkSupport {
    @Setup(Level.Trial)
    public void setUpTrial() {
      initializeCache();
    }

    @Setup(Level.Invocation)
    public void setUpInvocation() {
      remove(KEY);
    }
  }

  @State(Scope.Benchmark)
  public static class PresentState extends MultiLevelCacheBenchmarkSupport {
    @Setup(Level.Trial)
    public void setUpTrial() {
      initializeCache();
    }

    @Setup(Level.Invocation)
    public void setUpInvocation() {
      seed(KEY);
    }
  }

  @State(Scope.Benchmark)
  public static class L2PresentState extends MultiLevelCacheBenchmarkSupport {
    @Setup(Level.Trial)
    public void setUpTrial() {
      initializeCache();
    }

    @Setup(Level.Invocation)
    public void setUpInvocation() {
      seedRemoteOnly(KEY);
    }
  }
}
