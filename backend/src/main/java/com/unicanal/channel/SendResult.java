package com.unicanal.channel;

import com.unicanal.message.DeliveryStatus;

public record SendResult(String externalId, DeliveryStatus deliveryStatus) {
}
