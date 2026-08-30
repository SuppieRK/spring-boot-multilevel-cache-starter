package io.github.suppie.spring.cache.example;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** Example service whose simulated database lookup is cached in the {@code products} cache. */
@Service
public class ProductService {

  private static final Logger log = LoggerFactory.getLogger(ProductService.class);

  private final Map<String, Product> database = new ConcurrentHashMap<>();
  private final AtomicInteger loads = new AtomicInteger();

  /**
   * Loads a product, with repeated calls served through Spring's cache abstraction.
   *
   * @param id product identifier
   * @return existing or newly created product
   */
  @Cacheable(cacheNames = "products")
  public Product getProduct(String id) {
    int count = loads.incrementAndGet();
    log.info("Cache miss for product, loading entity (load #{})", count);

    try {
      TimeUnit.MILLISECONDS.sleep(250);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while loading product", ex);
    }

    return database.computeIfAbsent(id, key -> new Product(key, "Gadget " + key, Instant.now()));
  }

  /**
   * Product value stored in Redis and the local cache.
   *
   * @param id product identifier
   * @param name display name
   * @param lastUpdated time the simulated database entry was created
   */
  public record Product(String id, String name, Instant lastUpdated) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    /**
     * @return product identifier
     */
    @Override
    public String id() {
      return id;
    }

    /**
     * @return display name
     */
    @Override
    public String name() {
      return name;
    }

    /**
     * @return time the simulated database entry was created
     */
    @Override
    public Instant lastUpdated() {
      return lastUpdated;
    }
  }
}
