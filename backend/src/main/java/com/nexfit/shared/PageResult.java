package com.nexfit.shared;

import java.util.List;

/**
 * Página de un listado: mismo contrato que {@code /api/public/gyms} y {@code /api/clients}
 * ({@code content, page, size, totalElements, totalPages}). La página empieza en 0; el tamaño por defecto es 20
 * y el máximo 50 (BE-12, NEX-106).
 */
public record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    /** Tamaño pedido acotado: menor que 1 → por defecto; mayor que el máximo → el máximo. */
    public static int sizeOf(int requested) {
        return requested < 1 ? DEFAULT_SIZE : Math.min(requested, MAX_SIZE);
    }

    /** Página pedida: nunca negativa. */
    public static int pageOf(int requested) {
        return Math.max(requested, 0);
    }

    public static <T> PageResult<T> of(List<T> content, int page, int size, long totalElements) {
        return new PageResult<>(content, page, size, totalElements, (int) ((totalElements + size - 1) / size));
    }
}
