package com.example.order;

public final class OrderCancellationException extends RuntimeException {

    public OrderCancellationException(String orderId, OrderStatus status) {
        super("Order " + orderId + " cannot be cancelled from " + status);
    }
}
