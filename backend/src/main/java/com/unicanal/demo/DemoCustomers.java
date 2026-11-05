package com.unicanal.demo;

import com.unicanal.channel.ChannelType;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * Personas de la demo en vivo: nombres y apellidos colombianos combinados al azar, con un teléfono (WhatsApp) o
 * un usuario (Instagram/Facebook) también aleatorio. Separadas de los mensajes del guion: así cada demo trae
 * gente distinta aunque las preguntas se repitan.
 */
public final class DemoCustomers {

    public static final List<String> FIRST_NAMES = List.of(
            "Valentina", "Andrés", "Camila", "Santiago", "Daniela", "Juan Pablo", "Mariana", "Sebastián", "Laura",
            "Felipe", "Natalia", "Mateo", "Paula", "Alejandro", "Juliana", "Nicolás", "Carolina", "David", "Sofía",
            "Esteban", "Isabella", "Julián", "Manuela", "Cristian", "Luisa", "Jhon", "Gabriela", "Óscar", "Paola",
            "Miguel Ángel", "Diana", "Jorge", "Ximena", "Kevin", "Lina", "Brayan");

    public static final List<String> LAST_NAMES = List.of(
            "Rodríguez", "Gómez", "González", "Martínez", "García", "López", "Hernández", "Sánchez", "Ramírez",
            "Pérez", "Díaz", "Moreno", "Rojas", "Cardona", "Restrepo", "Ospina", "Castaño", "Vargas", "Jiménez",
            "Muñoz", "Ríos", "Gutiérrez", "Zapata", "Arango", "Correa", "Mejía", "Quintero", "Salazar", "Valencia",
            "Henao", "Álvarez", "Londoño", "Agudelo", "Bedoya", "Montoya", "Rincón");

    /** Combinaciones al azar que se prueban antes de recorrerlas todas en orden. */
    private static final int RANDOM_ATTEMPTS = 60;

    private DemoCustomers() {
    }

    /** Cliente generado: canal, identificador en el canal y nombre del perfil. */
    public record Customer(ChannelType channel, String externalId, String name) {
    }

    /**
     * Un cliente nuevo con un nombre que NO está en {@code takenNames} (los de la bandeja de la empresa más los ya
     * usados por la demo) y un identificador que {@code identityTaken} no reconozca. Primero prueba combinaciones
     * al azar; si no encuentra libre, recorre todas; si las 1.296 están usadas, agrega un segundo apellido.
     */
    public static Customer newCustomer(ChannelType channel, Set<String> takenNames,
            Predicate<String> identityTaken, RandomGenerator random) {
        String name = freeName(takenNames, random);
        String externalId;
        do {
            externalId = externalId(channel, name, random);
        } while (identityTaken.test(externalId));
        return new Customer(channel, externalId, name);
    }

    static String freeName(Set<String> takenNames, RandomGenerator random) {
        for (int i = 0; i < RANDOM_ATTEMPTS; i++) {
            String candidate = pick(FIRST_NAMES, random) + " " + pick(LAST_NAMES, random);
            if (!takenNames.contains(candidate)) {
                return candidate;
            }
        }
        for (String first : FIRST_NAMES) {
            for (String last : LAST_NAMES) {
                if (!takenNames.contains(first + " " + last)) {
                    return first + " " + last;
                }
            }
        }
        // Todas las combinaciones simples ya existen: nombre con dos apellidos (sin repetir tampoco)
        String candidate;
        do {
            candidate = pick(FIRST_NAMES, random) + " " + pick(LAST_NAMES, random) + " " + pick(LAST_NAMES, random);
        } while (takenNames.contains(candidate));
        return candidate;
    }

    /** WhatsApp: celular colombiano (57 + 3xx + 7 dígitos). Instagram/Facebook: usuario derivado del nombre. */
    static String externalId(ChannelType channel, String name, RandomGenerator random) {
        if (channel == ChannelType.WHATSAPP) {
            return "573" + (random.nextInt(10, 60)) + String.format("%07d", random.nextInt(10_000_000));
        }
        String handle = slug(name).replace(' ', '.') + random.nextInt(10, 100);
        return channel == ChannelType.FACEBOOK ? "fb." + handle : handle;
    }

    private static String slug(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "");
    }

    private static String pick(List<String> values, RandomGenerator random) {
        return values.get(random.nextInt(values.size()));
    }
}
