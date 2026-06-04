package com.portfolio.paymentservice.config;

import com.portfolio.paymentservice.grpc.PaymentGrpcService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private final PaymentGrpcService paymentGrpcService;
    private Server server;
    private volatile boolean running = false;

    public GrpcServerLifecycle(PaymentGrpcService paymentGrpcService) {
        this.paymentGrpcService = paymentGrpcService;
    }

    @Override
    public void start() {
        try {
            server = ServerBuilder.forPort(9090)
                .addService(paymentGrpcService)
                .build()
                .start();
            running = true;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start gRPC server on port 9090", e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            }
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
