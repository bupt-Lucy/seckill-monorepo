package com.example.seckillsystem.service;

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

    public BloomFilterInitializer(SeckillProperties properties,
                                  BloomFilter<Long> productBloomFilter) {
        this.properties = properties;
        this.productBloomFilter = productBloomFilter;
    }

    @Override
    public void run(String... args) {
        if (properties.getProductIds().isEmpty()) {
            log.warn("No productIds configured for Bloom filter preheat. Requests will rely solely on runtime writes.");
            return;
        }

        properties.getProductIds().forEach(id -> {
            productBloomFilter.put(id);
            log.debug("Bloom filter loaded productId={}", id);
        });

        log.info("Bloom filter preheat complete: {} productIds registered", properties.getProductIds().size());
    }
}
