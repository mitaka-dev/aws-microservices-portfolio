package com.portfolio.orderservice.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

@Component
public class SqsMessagePoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SqsMessagePoller.class);

    private final SqsAsyncClient sqsClient;
    private final ObjectMapper objectMapper;
    private final OrderEventListener listener;
    private final String queueUrl;
    private final int pollWaitSeconds;

    private volatile boolean running = false;
    private Thread pollerThread;

    public SqsMessagePoller(SqsAsyncClient sqsClient,
                            ObjectMapper objectMapper,
                            OrderEventListener listener,
                            @Value("${aws.sqs.orders-processing-queue-url}") String queueUrl,
                            @Value("${aws.sqs.poll-wait-seconds:20}") int pollWaitSeconds) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.listener = listener;
        this.queueUrl = queueUrl;
        this.pollWaitSeconds = pollWaitSeconds;
    }

    @Override
    public void start() {
        running = true;
        pollerThread = Thread.ofVirtual().name("sqs-poller").start(this::pollLoop);
        log.info("SQS poller started for queue {}", queueUrl);
    }

    private void pollLoop() {
        while (running) {
            try {
                var resp = sqsClient.receiveMessage(r -> r
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(pollWaitSeconds)
                ).join();

                for (Message msg : resp.messages()) {
                    processMessage(msg);
                }
            } catch (Exception e) {
                if (running) {
                    log.error("SQS polling error, will retry", e);
                    sleepQuietly(5000);
                }
            }
        }
    }

    private void processMessage(Message msg) {
        try {
            var event = objectMapper.readValue(msg.body(), OrderCreatedEvent.class);
            listener.handleOrderCreated(event);
            sqsClient.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(msg.receiptHandle())).join();
        } catch (Exception e) {
            log.error("Failed to process SQS message id={}, leaving for retry/DLQ", msg.messageId(), e);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        running = false;
        log.info("SQS poller stopping");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
