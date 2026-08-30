package io.github.suppie.spring.cache.benchmark;

import static io.github.suppie.spring.cache.MultiLevelCacheBenchmarkSupport.KEY;
import static io.github.suppie.spring.cache.MultiLevelCacheBenchmarkSupport.VALUE;

import io.github.suppie.spring.cache.MultiLevelCache;
import io.github.suppie.spring.cache.MultiLevelCacheBenchmarkSupport;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.springframework.cache.Cache.ValueWrapper;

/** Benchmarks synchronized contention waves over cache operations with concurrency contracts. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class MultiLevelCacheConcurrentBenchmark {

  @Benchmark
  public void concurrentColdGet(ColdGetState state, ContractCounters counters) {
    state.runWave(counters);
  }

  @Benchmark
  public void concurrentCrossInstancePutIfAbsent(
      PutIfAbsentState state, ContractCounters counters) {
    state.runWave(counters);
  }

  abstract static class ConcurrentState extends MultiLevelCacheBenchmarkSupport {
    private final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
    private ExecutorService executor;
    private CyclicBarrier start;
    private CyclicBarrier complete;
    private ContractCounters counters;
    private volatile boolean stopping;

    @Setup(Level.Trial)
    public final void setUpTrial() {
      initializeCache();
      initializeScenario();
      int concurrency = concurrency();
      start = new CyclicBarrier(concurrency + 1);
      complete = new CyclicBarrier(concurrency + 1);
      executor = Executors.newFixedThreadPool(concurrency);
      for (int worker = 0; worker < concurrency; worker++) {
        int workerIndex = worker;
        executor.submit(() -> runWorker(workerIndex));
      }
    }

    @Setup(Level.Invocation)
    public final void setUpInvocation() {
      workerFailure.set(null);
      prepareInvocation();
    }

    @TearDown(Level.Invocation)
    public final void tearDownInvocation() {
      Throwable failure = workerFailure.get();
      if (failure != null) {
        throw new IllegalStateException("Concurrent benchmark worker failed", failure);
      }
      counters.waves++;
      if (!contractSatisfied()) {
        counters.contractFailures++;
      }
    }

    @TearDown(Level.Trial)
    public final void tearDownTrial() throws InterruptedException {
      stopping = true;
      await(start);
      executor.shutdown();
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }

    final void runWave(ContractCounters contractCounters) {
      counters = contractCounters;
      await(start);
      await(complete);
    }

    protected void initializeScenario() {}

    protected abstract int concurrency();

    protected abstract void prepareInvocation();

    protected abstract void invoke(int workerIndex) throws Exception;

    protected abstract boolean contractSatisfied();

    private void runWorker(int workerIndex) {
      while (true) {
        await(start);
        if (stopping) {
          return;
        }
        try {
          invoke(workerIndex);
        } catch (Throwable throwable) {
          workerFailure.compareAndSet(null, throwable);
        } finally {
          await(complete);
        }
      }
    }

    private static void await(CyclicBarrier barrier) {
      try {
        barrier.await();
      } catch (Exception exception) {
        throw new IllegalStateException("Concurrent benchmark barrier failed", exception);
      }
    }
  }

  @State(Scope.Thread)
  public static class ColdGetState extends ConcurrentState {
    @Param({"2", "8", "32"})
    public String concurrency;

    @Param KeyMode keyMode;

    private final AtomicInteger loaderCalls = new AtomicInteger();
    private int workerCount;
    private Callable<Object> loader;
    private Object[] results;

    @Override
    protected void initializeScenario() {
      workerCount = Integer.parseInt(concurrency);
      loader =
          () -> {
            loaderCalls.incrementAndGet();
            return VALUE;
          };
      results = new Object[workerCount];
    }

    @Override
    protected int concurrency() {
      return workerCount;
    }

    @Override
    protected void prepareInvocation() {
      loaderCalls.set(0);
      Arrays.fill(results, null);
      if (keyMode == KeyMode.SAME_KEY) {
        remove(KEY);
      } else {
        for (int worker = 0; worker < workerCount; worker++) {
          remove(key(worker));
        }
      }
    }

    @Override
    protected void invoke(int workerIndex) {
      String key = keyMode == KeyMode.SAME_KEY ? KEY : key(workerIndex);
      results[workerIndex] = cache.get(key, loader);
    }

    @Override
    protected boolean contractSatisfied() {
      int expectedLoaderCalls = keyMode == KeyMode.SAME_KEY ? 1 : workerCount;
      return loaderCalls.get() == expectedLoaderCalls
          && Arrays.stream(results).allMatch(result -> Objects.equals(VALUE, result));
    }

    private static String key(int workerIndex) {
      return KEY + '-' + workerIndex;
    }
  }

  @State(Scope.Thread)
  public static class PutIfAbsentState extends ConcurrentState {
    @Param({"2", "8", "32"})
    public String concurrency;

    private int workerCount;
    private MultiLevelCache[] instances;
    private Object[] observedValues;
    private boolean[] inserted;

    @Override
    protected void initializeScenario() {
      workerCount = Integer.parseInt(concurrency);
      instances = new MultiLevelCache[workerCount];
      instances[0] = cache;
      for (int worker = 1; worker < workerCount; worker++) {
        instances[worker] = createCache("benchmark-instance-" + worker);
      }
      observedValues = new Object[workerCount];
      inserted = new boolean[workerCount];
    }

    @Override
    protected int concurrency() {
      return workerCount;
    }

    @Override
    protected void prepareInvocation() {
      Arrays.fill(observedValues, null);
      Arrays.fill(inserted, false);
      for (MultiLevelCache instance : instances) {
        instance.evict(KEY);
      }
    }

    @Override
    protected void invoke(int workerIndex) {
      String candidate = "candidate-" + workerIndex;
      ValueWrapper existing = instances[workerIndex].putIfAbsent(KEY, candidate);
      inserted[workerIndex] = existing == null;
      observedValues[workerIndex] = existing == null ? candidate : existing.get();
    }

    @Override
    protected boolean contractSatisfied() {
      long insertions = 0;
      for (boolean workerInserted : inserted) {
        if (workerInserted) {
          insertions++;
        }
      }
      Object winner = observedValues[0];
      return insertions == 1
          && winner != null
          && Arrays.stream(observedValues).allMatch(winner::equals);
    }
  }

  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class ContractCounters {
    public long contractFailures;
    public long waves;
  }

  public enum KeyMode {
    SAME_KEY,
    DISTINCT_KEYS
  }
}
