package com.unicanal.conversation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Marca (o desmarca) la conversación como venta. {@code saleAmount} es obligatorio cuando
 * {@code isSale = true} (lo comprueba el servicio) y se descarta cuando es false.
 */
public record SaleRequest(
        @JsonProperty("isSale") @NotNull(message = "Indica si hubo venta") Boolean isSale,
        @DecimalMin(value = "0", message = "El monto no puede ser negativo")
        @Digits(integer = 12, fraction = 2, message = "Máximo 12 enteros y 2 decimales") BigDecimal saleAmount) {
}
