package io.github.suppie.spring.cache.example;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProductController {

  private final ProductService productService;

  ProductController(ProductService productService) {
    this.productService = productService;
  }

  @GetMapping("/products/{id}")
  ResponseEntity<ProductService.Product> getProduct(@PathVariable String id) {
    return ResponseEntity.ok(productService.getProduct(id));
  }
}
