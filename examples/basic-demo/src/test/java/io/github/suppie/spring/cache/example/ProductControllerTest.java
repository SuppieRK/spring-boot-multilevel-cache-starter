package io.github.suppie.spring.cache.example;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@Import(ProductControllerTest.CacheConfig.class)
class ProductControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ProductService productService;

  @TestConfiguration
  static class CacheConfig {
    @Bean
    CacheManager cacheManager() {
      return new ConcurrentMapCacheManager("products");
    }
  }

  @Test
  void returnsProduct() throws Exception {
    ProductService.Product product =
        new ProductService.Product("42", "Gadget 42", Instant.parse("2024-01-01T00:00:00Z"));
    when(productService.getProduct("42")).thenReturn(product);

    mockMvc
        .perform(get("/products/42"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("42"))
        .andExpect(jsonPath("$.name").value("Gadget 42"));
  }
}
