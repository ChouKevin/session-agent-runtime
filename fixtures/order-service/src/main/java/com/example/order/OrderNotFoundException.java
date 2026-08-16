package com.example.order;

public final class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderId) {
        super("Order was not found: " + orderId);
    }
}
