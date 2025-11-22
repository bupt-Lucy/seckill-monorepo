package com.example.relayservice.relay;

import com.example.relayservice.config.RelayProperties;
import com.example.relayservice.config.RelayProperties.StreamBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class RedisStreamRelay implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamRelay.class);

    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final RelayProperties properties;

    public RedisStreamRelay(StringRedisTemplate redisTemplate,
                            RabbitTemplate rabbitTemplate,
                            RelayProperties properties) {
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setUsePublisherConnection(true);

        for (StreamBinding binding : properties.getStreams()) {
            if (!StringUtils.hasText(binding.getStreamKey())) {
                throw new IllegalArgumentException("streamKey must be configured for relay");
            }
            ensureConsumerGroup(binding);
        }
    }

    @Scheduled(fixedDelayString = "#{@relayProperties.pollInterval.toMillis()}")
    public void pollStreams() {
        StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
        for (StreamBinding binding : properties.getStreams()) {
            List<MapRecord<String, String, String>> records = ops.read(
                    Consumer.from(binding.getGroup(), binding.getConsumerName()),
                    StreamReadOptions.empty()
                            .block(properties.getBlockTimeout())
                            .count(properties.getBatchSize()),
                    StreamOffset.create(binding.getStreamKey(), ReadOffset.lastConsumed())
            );

            if (records == null || records.isEmpty()) {
                continue;
            }

            records.forEach(record -> processRecord(binding, record));
        }
    }

    @Scheduled(fixedDelayString = "#{@relayProperties.claimIdle.toMillis()}")
    public void reclaimPending() {
        StreamOperations<String, String, String> ops = redisTemplate.opsForStream();

        for (StreamBinding binding : properties.getStreams()) {
            PendingMessagesSummary sum = ops.pending(binding.getStreamKey(), binding.getGroup());
            if (sum == null || sum.getTotalPendingMessages() == 0) {
                continue;
            }

            Range<String> range = Range.closed(sum.minMessageId(), sum.maxMessageId());
            PendingMessages batch = ops.pending(
                    binding.getStreamKey(),
                    binding.getGroup(),
                    range,
                    properties.getClaimBatchSize()
            );
            if (batch == null) {
                continue;
            }

            List<RecordId> toClaim = new ArrayList<>();
            for (PendingMessage pm : batch) {
                if (pm.getTotalDeliveryCount() > 0
                        && pm.getElapsedTimeSinceLastDelivery().compareTo(properties.getClaimIdle()) > 0) {
                    toClaim.add(pm.getId());
                }
            }
            if (toClaim.isEmpty()) {
                continue;
            }

// 显式构造 Duration（其实直接用 properties.getClaimIdle() 也行）
            java.time.Duration idle = properties.getClaimIdle();

            List<MapRecord<String, String, String>> claimed = ops.claim(
                    binding.getStreamKey(),
                    binding.getGroup(),             // 消费者组（String）
                    binding.getConsumerName(),      // 新 owner/消费者（String）
                    idle,                           // java.time.Duration
                    toClaim.toArray(new RecordId[0])
            );


            if (claimed != null) {
                claimed.forEach(rec -> processRecord(binding, rec));
            }
        }
    }





    private void processRecord(StreamBinding binding, MapRecord<String, String, String> record) {
        String payload = record.getValue().get("payload");
        if (payload == null) {
            log.error("Stream entry {} missing payload field", record.getId());
            ack(binding, record);
            return;
        }

        try {
            boolean acknowledged = publishToRabbit(binding, record, payload);
            if (acknowledged) {
                ack(binding, record);
            } else {
                scheduleRetry(binding, record, "publisher-nack");
            }
        } catch (Exception ex) {
            log.error("Failed to forward stream entry {}", record.getId(), ex);
            scheduleRetry(binding, record, ex.getMessage());
        }
    }

    private boolean publishToRabbit(StreamBinding binding, MapRecord<String, String, String> record, String payload)
            throws Exception {
        CorrelationData correlation = new CorrelationData(record.getId().getValue());
        rabbitTemplate.convertAndSend(binding.getExchange(), binding.getRoutingKey(), payload, correlation);
        // 将 SettableListenableFuture 转换为 CompletableFuture
        SettableListenableFuture<CorrelationData.Confirm> future = correlation.getFuture();
        CompletableFuture<CorrelationData.Confirm> completableFuture = new CompletableFuture<>();
        future.addCallback(
                completableFuture::complete,
                completableFuture::completeExceptionally
        );
        CorrelationData.Confirm confirm = completableFuture.get(5, TimeUnit.SECONDS);

        return confirm != null && confirm.isAck();
    }

    private void ack(StreamBinding binding, MapRecord<String, String, String> record) {
        StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
        ops.acknowledge(binding.getStreamKey(), binding.getGroup(), record.getId());
        ops.delete(binding.getStreamKey(), record.getId());
        clearAttempts(binding, record.getId());
    }

    private void scheduleRetry(StreamBinding binding, MapRecord<String, String, String> record, String reason) {
        long attempts = redisTemplate.opsForHash()
                .increment(resolveAttemptsKey(binding), record.getId().getValue(), 1);

        if (attempts >= binding.getMaxAttempts()) {
            moveToDlq(binding, record, reason, attempts);
        } else {
            log.warn("Relay retry scheduled id={} attempts={}", record.getId(), attempts);
        }
    }

    private void moveToDlq(StreamBinding binding, MapRecord<String, String, String> record,
                           String reason, long attempts) {
        Map<String, String> entry = new LinkedHashMap<>(record.getValue());
        entry.put("error", reason);
        entry.put("attempts", String.valueOf(attempts));
        entry.put("failedAt", String.valueOf(System.currentTimeMillis()));
        String dlqKey = resolveDlqKey(binding);
        redisTemplate.opsForStream().add(dlqKey, entry);
        redisTemplate.opsForStream().acknowledge(binding.getStreamKey(), binding.getGroup(), record.getId());
        redisTemplate.opsForStream().delete(binding.getStreamKey(), record.getId());
        clearAttempts(binding, record.getId());
        log.error("Moved record {} to DLQ {} after {} attempts", record.getId(), dlqKey, attempts);
    }

    private String resolveAttemptsKey(StreamBinding binding) {
        if (StringUtils.hasText(binding.getAttemptsKey())) {
            return binding.getAttemptsKey();
        }
        return binding.getStreamKey() + ":attempts";
    }

    private String resolveDlqKey(StreamBinding binding) {
        if (StringUtils.hasText(binding.getDlqKey())) {
            return binding.getDlqKey();
        }
        return binding.getStreamKey() + ":dlq";
    }

    private void clearAttempts(StreamBinding binding, RecordId id) {
        redisTemplate.opsForHash().delete(resolveAttemptsKey(binding), id.getValue());
    }

    private void ensureConsumerGroup(StreamBinding binding) {
        StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
        try {
            ops.createGroup(binding.getStreamKey(), ReadOffset.from("0-0"), binding.getGroup());
            log.info("Created consumer group {} for stream {}", binding.getGroup(), binding.getStreamKey());
        } catch (Exception ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("BUSYGROUP")) {
                log.info("Consumer group {} already exists for stream {}", binding.getGroup(), binding.getStreamKey());
            } else if (ex.getMessage() != null && ex.getMessage().contains("does not exist")) {
                RecordId id = ops.add(StreamRecords.newRecord()
                        .in(binding.getStreamKey())
                        .ofMap(Collections.singletonMap("bootstrap", "1")));
                ops.createGroup(binding.getStreamKey(), ReadOffset.latest(), binding.getGroup());
                ops.delete(binding.getStreamKey(), id);
                log.info("Bootstrapped stream {} for group {}", binding.getStreamKey(), binding.getGroup());
            } else {
                throw ex;
            }
        }
    }
}
