package com.aquagrid.platform.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * The platform's stable pagination envelope.
 *
 * <p>Spring's {@code Page} is deliberately not serialised directly: its JSON shape is an
 * implementation detail of Spring Data and has changed between versions. This record is our
 * contract.
 */
@Schema(name = "PageResponse", description = "Paginated result envelope")
public record PageResponse<T>(
        @Schema(description = "Items on the current page") List<T> content,
        @Schema(description = "Zero-based page index", example = "0") int page,
        @Schema(description = "Requested page size", example = "25") int size,
        @Schema(description = "Total matching items", example = "1043") long totalElements,
        @Schema(description = "Total number of pages", example = "42") int totalPages,
        @Schema(description = "True when this is the first page") boolean first,
        @Schema(description = "True when this is the last page") boolean last
) {
    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isFirst(), page.isLast());
    }

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(),
                page.getSize(), page.getTotalElements(), page.getTotalPages(), page.isFirst(),
                page.isLast());
    }
}
