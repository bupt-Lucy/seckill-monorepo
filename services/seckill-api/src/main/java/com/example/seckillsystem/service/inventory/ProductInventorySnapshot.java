package com.example.seckillsystem.service.inventory;

/**
 * 商品库存快照，用于描述三级缓存回源结果。
 */
public record ProductInventorySnapshot(Long productId, long totalStock, String title) {
}
