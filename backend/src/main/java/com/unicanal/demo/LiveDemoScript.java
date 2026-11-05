package com.unicanal.demo;

import java.util.List;

/**
 * Guion de la demo en vivo: solo los MENSAJES (las preguntas), sin personas. Quién escribe lo decide
 * {@link DemoCustomers} en cada pasada, así que cada vez que se inicia la demo llegan clientes distintos aunque
 * las preguntas se repitan.
 *
 * <p>Se recorre en orden y, al terminar, vuelve a empezar. De cada 4 mensajes, 3 los escribe un cliente nuevo
 * ({@link Kind#NEW}) y 1 es la continuación de una conversación que la demo ya abrió ({@link Kind#FOLLOW_UP}):
 * el mismo cliente vuelve a preguntar (p. ej. por el envío).
 */
public final class LiveDemoScript {

    public enum Kind {
        /** Lo escribe un cliente nuevo (nombre aleatorio que no existe en la bandeja). */
        NEW,
        /** Lo escribe un cliente al que la demo ya le abrió una conversación que sigue abierta. */
        FOLLOW_UP
    }

    public record Line(Kind kind, String text) {
    }

    public static final List<Line> LINES = List.of(
            new Line(Kind.NEW, "Hola, ¿la chaqueta de jean la tienen en talla S?"),
            new Line(Kind.NEW, "Buenas, ¿cuánto vale la camiseta oversize negra?"),
            new Line(Kind.NEW, "¿Hacen envíos a Barranquilla? ¿Cuánto se demora en llegar?"),
            new Line(Kind.FOLLOW_UP, "¿Y cuánto me cobran por el envío?"),
            new Line(Kind.NEW, "Necesito cotizar 30 polos bordadas para la dotación de mi empresa"),
            new Line(Kind.NEW, "¿A qué hora abren los sábados?"),
            new Line(Kind.NEW, "¿Tienen el jogger gris en talla L?"),
            new Line(Kind.FOLLOW_UP, "¿Puedo pagar por Nequi?"),
            new Line(Kind.NEW, "Me encantó el vestido de la última publicación 😍 ¿qué tallas tienen?"),
            new Line(Kind.NEW, "¿Puedo pagar con Daviplata o solo reciben Nequi?"),
            new Line(Kind.NEW, "¿Tienen tienda física en Medellín o solo venden por internet?"),
            new Line(Kind.FOLLOW_UP, "Listo, me lo llevo. ¿A qué número te hago la transferencia?"),
            new Line(Kind.NEW, "¿Hacen envíos a Cali contra entrega?"),
            new Line(Kind.NEW, "Si llevo 12 camisetas, ¿me hacen precio por cantidad?"),
            new Line(Kind.NEW, "Hola, el pedido que hice el lunes todavía no me llega 😟"),
            new Line(Kind.FOLLOW_UP, "Ya te envié el comprobante 🙌 ¿para cuándo me llegaría?"),
            new Line(Kind.NEW, "¿El pantalón cargo viene en tallas grandes? Busco una 38"),
            new Line(Kind.NEW, "¿Tienen la sudadera con capota en color beige?"));

    private LiveDemoScript() {
    }

    public static int size() {
        return LINES.size();
    }
}
