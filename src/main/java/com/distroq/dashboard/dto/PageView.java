package com.distroq.dashboard.dto;

import java.util.List;

/**
 * One page of a table, with the numbers a pager needs and nothing else.
 *
 * <p>{@code size} is what the server actually used, not what the caller asked for. The dashboard
 * clamps every page size to its own maximum, and a client that asked for ten thousand rows needs
 * to be told it got two hundred rather than left to infer it from the array length.
 */
public record PageView<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageView<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceilDiv(totalElements, (long) size);
        return new PageView<>(content, page, size, totalElements, totalPages);
    }
}
