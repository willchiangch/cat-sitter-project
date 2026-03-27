package com.catsitter.api.dto.sitter;

import java.util.List;
import java.util.UUID;

public record ReorderQuestionRequest(
    List<UUID> questionIds
) {}
