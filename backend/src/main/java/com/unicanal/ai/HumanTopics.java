package com.unicanal.ai;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Red de seguridad determinista: temas que SIEMPRE atiende una persona (el estado de un pedido, un pago ya hecho,
 * los datos para transferir, un reclamo o una devolución). Un modelo pequeño no siempre respeta esa regla del
 * prompt, así que si el último mensaje del cliente toca uno de estos temas ni siquiera se le consulta.
 *
 * <p>Se busca en el texto en minúsculas y sin tildes. Es a propósito amplio: equivocarse aquí solo significa que
 * responde un humano.
 */
public final class HumanTopics {

    private static final List<String> PHRASES = List.of(
            // Estado de un pedido ya hecho
            "no me ha llegado", "no me llega", "no ha llegado", "no llega", "no me llego", "todavia no llega",
            "mi pedido", "el pedido que hice", "mi compra", "numero de guia", "rastrear", "seguimiento",
            // Pagos y transferencias
            "comprobante", "ya pague", "ya te pague", "ya les pague", "ya transferi", "ya consigne", "ya envie el pago",
            "a que numero", "numero de cuenta", "datos para transferir", "datos para consignar", "la transferencia",
            // Reclamos, devoluciones, cambios de un pedido
            "reclamo", "queja", "devolucion", "devolver", "reembolso", "me llego mal", "llego danado", "llego roto",
            "talla equivocada", "estafa",
            // Cotizaciones y pedidos especiales (precio a convenir)
            "cotiz", "dotacion", "con logo", "bordad",
            // Pide a una persona
            "hablar con una persona", "hablar con alguien", "con un asesor", "con una asesora", "un humano");

    private HumanTopics() {
    }

    public static boolean needsPerson(String customerText) {
        if (customerText == null || customerText.isBlank()) {
            return false;
        }
        String normalized = Normalizer.normalize(customerText, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return PHRASES.stream().anyMatch(normalized::contains);
    }
}
