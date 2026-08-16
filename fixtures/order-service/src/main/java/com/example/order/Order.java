package com.example.order;

import java.util.Objects;

public record Order(String id, OrderStatus status) {

    public Order {
        Objects.requireNonNull(id);
        Objects.requireNonNull(status);
    }

    public Order cancel() {
        if (!isCancellable()) {
            throw new OrderCancellationException(id, status);
        }
        return new Order(id, OrderStatus.CANCELLED);
    }

    private boolean isCancellable() {
        return status == OrderStatus.PENDING || status == OrderStatus.CONFIRMED;
    }
}
