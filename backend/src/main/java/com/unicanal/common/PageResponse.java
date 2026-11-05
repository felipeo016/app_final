package com.unicanal.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Página de resultados con el formato estable de la API (no se expone el Page de Spring).
 *
 * @param nextCursor solo en listados por cursor: valor de {@code before} para pedir lo siguiente, o nulo si no
 *                   hay más
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages, String nextCursor) {

    public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), null);
    }

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
        return new PageResponse<>(items, page, size, totalItems, totalPages(totalItems, size), null);
    }

    /** Listado por cursor: {@code page} es 0 en la primera petición y -1 en las siguientes (no aplica). */
    public static <T> PageResponse<T> byCursor(List<T> items, boolean first, int size, long totalItems,
            String nextCursor) {
        return new PageResponse<>(items, first ? 0 : -1, size, totalItems, totalPages(totalItems, size), nextCursor);
    }

    private static int totalPages(long totalItems, int size) {
        return size == 0 ? 0 : (int) ((totalItems + size - 1) / size);
    }
}
