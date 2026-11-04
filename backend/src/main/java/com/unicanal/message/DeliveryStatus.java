package com.unicanal.message;

/** Estado de entrega de un mensaje saliente; lo actualizan los `statuses` del webhook de Meta. */
public enum DeliveryStatus {
    PENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}
