package io.github.suppie.spring.cache.benchmark;

import static io.github.suppie.spring.cache.MultiLevelCacheBenchmarkSupport.KEY;

import io.github.suppie.spring.cache.MultiLevelCacheBenchmarkSupport;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/** Benchmarks synchronous and asynchronous cache retrieval surfaces. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MultiLevelCacheBenchmark {

  @Benchmark
  public void cacheHit(L1HitState state, ThreadState threadState, Blackhole blackhole) {
    blackhole.consume(state.cache.get(state.keys[state.nextIndex(threadState)], state.loader));
  }

  @Benchmark
  public void cacheL2Hit(L2HitState state, Blackhole blackhole) {
    blackhole.consume(state.cache.get(KEY, state.loader));
  }

  @Benchmark
  public void cacheMissLoads(MissState state, Blackhole blackhole) {
    blackhole.consume(state.cache.get(KEY, state.loader));
  }

  @Benchmark
  public void valueWrapperL1Hit(SingleL1HitState state, Blackhole blackhole) {
    blackhole.consume(state.cache.get(KEY));
  }

  @Benchmark
  public void valueWrapperL2Hit(L2HitState state, Blackhole blackhole) {
    blackhole.consume(state.cache.get(KEY));
  }

  @Benchmark
  public void valueWrapperMiss(MissState state, Blackhole blackhole) {
    blackhole.consume(state.cache.get(KEY));
  }

  @Benchmark
  public void typedGetL1Hit(SingleL1HitState state, Blackhole blackhole) {
    blackhole.consume(state.cache.get(KEY, String.class));
  }

  @Benchmark
  public void retrieveL2Hit(L2HitState state, Blackhole blackhole) {
    blackhole.consume(state.cache.retrieve(KEY).join());
  }

  @Benchmark
  public void retrieveMiss(MissState state, Blackhole blackhole) {
    blackhole.consume(state.cache.retrieve(KEY).join());
  }

  @Benchmark
  public void retrieveWithLoaderL2Hit(L2HitState state, Blackhole blackhole) {
    blackhole.consume(state.cache.retrieve(KEY, state.asyncLoader).join());
  }

  @Benchmark
  public void retrieveWithLoaderMiss(MissState state, Blackhole blackhole) {
    blackhole.consume(state.cache.retrieve(KEY, state.asyncLoader).join());
  }

  @State(Scope.Benchmark)
  public static class L1HitState extends MultiLevelCacheBenchmarkSupport {
    @Param({"1", "32"})
    int keyCount;

    String[] keys;
    Callable<Object> loader;

    @Setup(Level.Trial)
    public void setUp() {
      initializeCache();
      keys = new String[keyCount];
      for (int index = 0; index < keyCount; index++) {
        keys[index] = "hit-key-" + index;
        seed(keys[index]);
      }
      loader = () -> VALUE;
    }

    int nextIndex(ThreadState state) {
      if (keys.length == 1) {
        return 0;
      }
      int next = state.nextIndex++;
      if (next >= keys.length) {
        next = 0;
        state.nextIndex = 1;
      }
      return next;
    }
  }

  @State(Scope.Benchmark)
  public static class SingleL1HitState extends MultiLevelCacheBenchmarkSupport {
    @Setup(Level.Trial)
    public void setUp() {
      initializeCache();
      seed(KEY);
    }
  }

  @State(Scope.Benchmark)
  public static class L2HitState extends MultiLevelCacheBenchmarkSupport {
    Callable<Object> loader;
    Supplier<CompletableFuture<Object>> asyncLoader;

    @Setup(Level.Trial)
    public void setUpTrial() {
      initializeCache();
      loader = () -> VALUE;
      asyncLoader = () -> CompletableFuture.completedFuture(VALUE);
    }

    @Setup(Level.Invocation)
    public void setUpInvocation() {
      seedRemoteOnly(KEY);
    }
  }

  @State(Scope.Benchmark)
  public static class MissState extends MultiLevelCacheBenchmarkSupport {
    Callable<Object> loader;
    Supplier<CompletableFuture<Object>> asyncLoader;

    @Setup(Level.Trial)
    public void setUpTrial() {
      initializeCache();
      loader = () -> VALUE;
      asyncLoader = () -> CompletableFuture.completedFuture(VALUE);
    }

    @Setup(Level.Invocation)
    public void setUpInvocation() {
      remove(KEY);
    }
  }

  @State(Scope.Thread)
  public static class ThreadState {
    int nextIndex;
  }
}
