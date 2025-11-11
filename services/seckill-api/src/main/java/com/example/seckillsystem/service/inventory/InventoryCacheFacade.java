package com.example.seckillsystem.service.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * 封装本地 Caffeine 缓存访问，避免 {@code SeckillService} 产生 self-invocation。
 */
@Component
public class InventoryCacheFacade {

    private static final Logger log = LoggerFactory.getLogger(InventoryCacheFacade.class);

    private final ProductInventoryLoader loader;

    public InventoryCacheFacade(ProductInventoryLoader loader) {
        this.loader = loader;
    }

    @Cacheable(cacheNames = "inventory", key = "#productId", unless = "#result == null")
    public ProductInventorySnapshot load(Long productId) {
        return loader.load(productId)
                .map(snapshot -> {
                    log.debug("Loaded product {} via L3 data source", productId);
                    return snapshot;
                })
                .orElse(null);
    }
}
