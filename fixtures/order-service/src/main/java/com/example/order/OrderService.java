package com.example.order;

import java.util.Objects;

public final class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = Objects.requireNonNull(orderRepository);
    }

    public Order findOrder(String orderId) {
        Objects.requireNonNull(orderId);
        return orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    public Order cancel(String orderId) {
        Order cancelledOrder = findOrder(orderId).cancel();
        orderRepository.save(cancelledOrder);
        return cancelledOrder;
    }
}
