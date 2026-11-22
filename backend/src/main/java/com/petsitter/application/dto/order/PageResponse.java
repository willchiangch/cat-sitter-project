package com.petsitter.application.dto.order;

import java.util.List;

/** 分頁回應（SD-ORDER §6.1）。 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements) {
}
