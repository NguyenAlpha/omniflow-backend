package com.quiktech.pos.dto.response.catalog;

import java.util.List;

public record ProductImportResponse(
    int imported,
    int skipped,
    List<String> errors
) {
}
