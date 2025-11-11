package com.example.seckillsystem.service.inventory;

import java.util.Optional;

/**
 * L3 数据源抽象：负责在本地与 Redis 缓存都失效时回源商品库存信息。
 */
public interface ProductInventoryLoader {

    /**
     * 加载指定商品的库存快照。
     *
     * @param productId 商品 ID
     * @return 若商品存在则返回快照，否则返回空 Optional
     */
    Optional<ProductInventorySnapshot> load(Long productId);
}
