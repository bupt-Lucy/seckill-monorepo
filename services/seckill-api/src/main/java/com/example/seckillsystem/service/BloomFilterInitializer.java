package com.example.seckillsystem.service;

import com.example.seckillsystem.model.Product;
import com.example.seckillsystem.repository.ProductRepository;
import com.example.seckillsystem.service.props.SeckillProperties;
import com.google.common.hash.BloomFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class BloomFilterInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BloomFilterInitializer.class);

    private final SeckillProperties properties;
    private final BloomFilter<Long> productBloomFilter;
    private final ProductRepository productRepository;

    public BloomFilterInitializer(SeckillProperties properties,
                                  BloomFilter<Long> productBloomFilter,
                                  ProductRepository productRepository) {
        this.properties = properties;
        this.productBloomFilter = productBloomFilter;
        this.productRepository = productRepository;
    }

    @Override
    public void run(String... args) {
        var productIds = new java.util.ArrayList<>(properties.resolvedProductIds());

        if (productIds.isEmpty()) {
            log.info("Bloom filter catalog empty; falling back to MySQL to resolve product ids");
            productIds.addAll(productRepository.findAll().stream()
                    .map(Product::getId)
                    .filter(java.util.Objects::nonNull)
                    .toList());
        }

        if (productIds.isEmpty()) {
            log.warn("No productIds configured for Bloom filter preheat. Requests will rely solely on runtime writes.");
            return;
        }

        productIds.forEach(id -> {
            productBloomFilter.put(id);
            log.debug("Bloom filter loaded productId={}", id);
        });

        log.info("Bloom filter preheat complete: {} productIds registered", productIds.size());
    }
}
