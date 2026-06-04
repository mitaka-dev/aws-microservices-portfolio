package com.portfolio.paymentservice.config;

import com.portfolio.paymentservice.strategy.BankTransferPaymentStrategy;
import com.portfolio.paymentservice.strategy.CreditCardPaymentStrategy;
import com.portfolio.paymentservice.strategy.PayPalPaymentStrategy;
import com.portfolio.paymentservice.strategy.PaymentStrategy;
import com.portfolio.proto.payment.PaymentMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class PaymentStrategyConfig {

    @Bean
    Map<PaymentMethod, PaymentStrategy> paymentStrategies(
        CreditCardPaymentStrategy creditCard,
        PayPalPaymentStrategy payPal,
        BankTransferPaymentStrategy bankTransfer
    ) {
        return Map.of(
            PaymentMethod.CREDIT_CARD, creditCard,
            PaymentMethod.PAYPAL, payPal,
            PaymentMethod.BANK_TRANSFER, bankTransfer
        );
    }
}
