package com.portfolio.orderservice.messaging;

import io.awspring.cloud.sns.core.SnsTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderEventPublisher {

    private final SnsTemplate snsTemplate;
    private final String topicArn;

    public OrderEventPublisher(SnsTemplate snsTemplate,
                               @Value("${aws.sns.orders-topic-arn}") String topicArn) {
        this.snsTemplate = snsTemplate;
        this.topicArn = topicArn;
    }

    public void publishOrderConfirmed(OrderConfirmedEvent event) {
        snsTemplate.sendNotification(topicArn, event, "OrderConfirmed");
    }
}
