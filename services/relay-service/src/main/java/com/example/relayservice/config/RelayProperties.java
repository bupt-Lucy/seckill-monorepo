package com.example.relayservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component("relayProperties")
@ConfigurationProperties(prefix = "relay")
@Validated
public class RelayProperties {

    @NotEmpty(message = "At least one stream binding must be configured")
    @Valid
    private List<StreamBinding> streams = new ArrayList<>();

    private Duration pollInterval = Duration.ofMillis(200);
    private Duration blockTimeout = Duration.ofSeconds(1);
    private int batchSize = 50;
    private Duration confirmTimeout = Duration.ofSeconds(5);
    private Duration claimIdle = Duration.ofSeconds(60);
    private Duration claimInterval = Duration.ofSeconds(30);
    private int claimBatchSize = 100;

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

    public Duration getConfirmTimeout() {
        return confirmTimeout;
    }

    public void setConfirmTimeout(Duration confirmTimeout) {
        this.confirmTimeout = confirmTimeout;
    }

    public Duration getClaimIdle() {
        return claimIdle;
    }

    public void setClaimIdle(Duration claimIdle) {
        this.claimIdle = claimIdle;
    }

    public Duration getClaimInterval() {
        return claimInterval;
    }

    public void setClaimInterval(Duration claimInterval) {
        this.claimInterval = claimInterval;
    }

    public int getClaimBatchSize() {
        return claimBatchSize;
    }

    public void setClaimBatchSize(int claimBatchSize) {
        this.claimBatchSize = claimBatchSize;
    }

    public static class StreamBinding {
        @NotBlank
        private String streamKey;
        @NotBlank
        private String group;
        @NotBlank
        private String consumerName;
        @NotBlank
        private String exchange;
        @NotBlank
        private String routingKey;
        private String dlqKey;
        private String attemptsKey;
        @Min(1)
        private int maxAttempts = 5;
        private boolean deleteAfterAck = true;

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

        public boolean isDeleteAfterAck() {
            return deleteAfterAck;
        }

        public void setDeleteAfterAck(boolean deleteAfterAck) {
            this.deleteAfterAck = deleteAfterAck;
        }

        public String identity() {
            return streamKey + "|" + group + "|" + consumerName;
        }
    }
}
