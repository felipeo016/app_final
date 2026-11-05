package com.unicanal.ai;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chequeo determinista (sin modelo) de tallas y colores. Un modelo pequeño a veces confirma una talla o un color
 * que no existe ("sí, la chaqueta viene en XL"); eso es lo más dañino en una venta. Si la información del negocio
 * tiene líneas tipo catálogo ({@code - Producto en negro y gris: $99.000. Tallas S, M y L.}) y la respuesta nombra
 * ese producto, cada talla y cada color que menciona la respuesta debe estar en la línea de ese producto.
 *
 * <p>Si la información no tiene ese formato, o la respuesta no nombra un producto de la lista, no se aplica: solo
 * descarta respuestas, nunca las cambia.
 */
public final class CatalogGuard {

    private static final Pattern SIZE_LIST = Pattern.compile("\\btallas?\\b([^.]*)");
    private static final Pattern SIZE = Pattern.compile("\\b(xxxl|xxl|xl|xxs|xs|s|m|l|\\d{1,2})\\b");
    private static final List<String> COLORS = List.of("negro", "negra", "blanco", "blanca", "gris", "azul", "rojo",
            "roja", "verde", "beige", "rosado", "rosada", "rosa", "amarillo", "amarilla", "morado", "morada", "cafe",
            "naranja", "vinotinto", "fucsia", "lila", "dorado", "plateado", "turquesa", "crema", "oliva");

    private static final List<String> OVERCLAIMS = List.of("todas las tallas", "todos los colores", "todas las talla",
            "cualquier talla", "cualquier color", "todos los tamanos", "en todos los colores");

    private CatalogGuard() {
    }

    /** false si la respuesta menciona una talla o color que no tiene el producto que nombra. */
    public static boolean consistent(String businessInfo, String answer) {
        if (businessInfo == null || answer == null) {
            return true;
        }
        String reply = normalize(answer);
        // Afirmaciones generales que un catálogo con listas nunca respalda
        if (OVERCLAIMS.stream().anyMatch(reply::contains)) {
            return false;
        }
        for (String rawLine : businessInfo.split("\\R")) {
            String line = normalize(rawLine);
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = productName(line.substring(0, colon));
            if (name.length() < 4 || !reply.contains(name)) {
                continue;
            }
            Set<String> lineSizes = sizes(line);
            Set<String> replySizes = sizes(reply);
            if (lineSizes.stream().noneMatch(CatalogGuard::numeric)) {
                // Tallas de letras: un número cerca de "talla" en la respuesta es un precio, no una talla
                replySizes.removeIf(CatalogGuard::numeric);
            }
            if (!lineSizes.isEmpty() && !lineSizes.containsAll(replySizes)) {
                return false;
            }
            Set<String> lineColors = colors(line);
            if (!lineColors.isEmpty() && !lineColors.containsAll(colors(reply))) {
                return false;
            }
        }
        return true;
    }

    /** "- Camiseta oversize en negro, blanco y gris" → "camiseta oversize"; "Sudadera (hoodie)" → "sudadera". */
    private static String productName(String beforeColon) {
        String name = beforeColon.replaceAll("\\([^)]*\\)", " ").replaceFirst("^[\\s\\-*•]+", "");
        int colors = name.indexOf(" en ");
        if (colors > 0) {
            name = name.substring(0, colors);
        }
        return name.replaceAll("\\s+", " ").strip();
    }

    /** Tallas que aparecen después de "talla"/"tallas" (hasta el punto). */
    private static Set<String> sizes(String text) {
        Set<String> found = new HashSet<>();
        Matcher list = SIZE_LIST.matcher(text);
        while (list.find()) {
            Matcher size = SIZE.matcher(list.group(1));
            while (size.find()) {
                found.add(size.group(1));
            }
        }
        return found;
    }

    private static boolean numeric(String size) {
        return Character.isDigit(size.charAt(0));
    }

    private static Set<String> colors(String text) {
        Set<String> found = new HashSet<>();
        for (String word : text.split("[^a-z]+")) {
            if (COLORS.contains(word)) {
                // "negra" y "negro" son el mismo color
                found.add(word.endsWith("a") && COLORS.contains(word.substring(0, word.length() - 1) + "o")
                        ? word.substring(0, word.length() - 1) + "o" : word);
            }
        }
        return found;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
