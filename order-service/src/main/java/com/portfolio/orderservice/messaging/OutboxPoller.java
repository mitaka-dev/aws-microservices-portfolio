package com.portfolio.orderservice.messaging;

import com.portfolio.orderservice.repository.OutboxEventRepository;
import io.awspring.cloud.sns.core.SnsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventRepository outboxEventRepository;
    private final SnsTemplate snsTemplate;
    private final String topicArn;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> new Thread(r, "outbox-poller"));
    private volatile boolean running = false;

    public OutboxPoller(OutboxEventRepository outboxEventRepository,
                        SnsTemplate snsTemplate,
                        @Value("${aws.sns.orders-topic-arn}") String topicArn) {
        this.outboxEventRepository = outboxEventRepository;
        this.snsTemplate = snsTemplate;
        this.topicArn = topicArn;
    }

    @Override
    public void start() {
        running = true;
        scheduler.scheduleWithFixedDelay(this::poll, 5, 5, TimeUnit.SECONDS);
        log.info("OutboxPoller started");
    }

    @Override
    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("OutboxPoller stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public void poll() {
        var unpublished = outboxEventRepository.findTop10ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (var event : unpublished) {
            try {
                snsTemplate.sendNotification(topicArn, event.getPayload(), event.getEventType());
                outboxEventRepository.markPublished(event.getId(), Instant.now());
                log.debug("Published outbox event id={} type={}", event.getId(), event.getEventType());
            } catch (Exception e) {
                log.warn("Failed to publish outbox event id={}, will retry next poll", event.getId(), e);
            }
        }
    }
}
