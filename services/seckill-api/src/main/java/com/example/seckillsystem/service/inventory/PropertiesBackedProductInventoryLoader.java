package com.example.seckillsystem.service.inventory;

import com.example.seckillsystem.service.props.SeckillProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 默认的 L3 回源实现：从 {@link SeckillProperties} 提供的 catalog 中读取商品信息。
 * 若实际项目仍需要从数据库加载，只需自定义一个 {@link ProductInventoryLoader} Bean 覆盖此实现即可。
 */
@Component
public class PropertiesBackedProductInventoryLoader implements ProductInventoryLoader {

    private static final Logger log = LoggerFactory.getLogger(PropertiesBackedProductInventoryLoader.class);

    private final SeckillProperties properties;

    public PropertiesBackedProductInventoryLoader(SeckillProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<ProductInventorySnapshot> load(Long productId) {
        return properties.lookupProductSpec(productId)
                .map(spec -> {
                    log.debug("Resolved inventory from properties: productId={}, stock={}", productId, spec.getStock());
                    return new ProductInventorySnapshot(spec.getId(), spec.getStock(), spec.getTitle());
                });
    }
}
