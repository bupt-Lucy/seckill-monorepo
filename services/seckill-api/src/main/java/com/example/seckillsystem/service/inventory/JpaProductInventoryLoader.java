package com.example.seckillsystem.service.inventory;

import com.example.seckillsystem.model.Product;
import com.example.seckillsystem.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 基于 MySQL 的 L3 回源实现：在 Redis 与本地缓存失效时查询权威库。
 */
@Component
@Primary
public class JpaProductInventoryLoader implements ProductInventoryLoader {

    private static final Logger log = LoggerFactory.getLogger(JpaProductInventoryLoader.class);

    private final ProductRepository productRepository;

    public JpaProductInventoryLoader(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public Optional<ProductInventorySnapshot> load(Long productId) {
        return productRepository.findById(productId)
                .map(product -> {
                    log.debug("Loaded inventory from MySQL: productId={}, stock={}", productId, product.getStock());
                    return new ProductInventorySnapshot(product.getId(),
                            product.getStock() == null ? 0 : product.getStock(),
                            resolveTitle(product));
                });
    }

    private String resolveTitle(Product product) {
        if (product.getTitle() != null && !product.getTitle().isBlank()) {
            return product.getTitle();
        }
        if (product.getName() != null && !product.getName().isBlank()) {
            return product.getName();
        }
        return "商品" + product.getId();
    }
}
