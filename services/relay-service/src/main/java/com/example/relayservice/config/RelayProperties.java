package com.example.relayservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "relay")
public class RelayProperties {

    private List<StreamBinding> streams = new ArrayList<>();
    private Duration pollInterval = Duration.ofMillis(200);
    private Duration blockTimeout = Duration.ofMillis(1000);
    private int batchSize = 20;
    private Duration claimIdle = Duration.ofSeconds(60);
    private int claimBatchSize = 50;

    public List<StreamBinding> getStreams() {
        return streams;
    }

    public void setStreams(List<StreamBinding> streams) {
        this.streams = streams;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getBlockTimeout() {
        return blockTimeout;
    }

    public void setBlockTimeout(Duration blockTimeout) {
        this.blockTimeout = blockTimeout;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getClaimIdle() {
        return claimIdle;
    }

    public void setClaimIdle(Duration claimIdle) {
        this.claimIdle = claimIdle;
    }

    public int getClaimBatchSize() {
        return claimBatchSize;
    }

    public void setClaimBatchSize(int claimBatchSize) {
        this.claimBatchSize = claimBatchSize;
    }

    public static class StreamBinding {
        private String streamKey;
        private String group;
        private String consumerName;
        private String exchange;
        private String routingKey;
        private String dlqKey;
        private String attemptsKey;
        private int maxAttempts = 5;

        public String getStreamKey() {
            return streamKey;
        }

        public void setStreamKey(String streamKey) {
            this.streamKey = streamKey;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getConsumerName() {
            return consumerName;
        }

        public void setConsumerName(String consumerName) {
            this.consumerName = consumerName;
        }

        public String getExchange() {
            return exchange;
        }

        public void setExchange(String exchange) {
            this.exchange = exchange;
        }

        public String getRoutingKey() {
            return routingKey;
        }

        public void setRoutingKey(String routingKey) {
            this.routingKey = routingKey;
        }

        public String getDlqKey() {
            return dlqKey;
        }

        public void setDlqKey(String dlqKey) {
            this.dlqKey = dlqKey;
        }

        public String getAttemptsKey() {
            return attemptsKey;
        }

        public void setAttemptsKey(String attemptsKey) {
            this.attemptsKey = attemptsKey;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }
}
