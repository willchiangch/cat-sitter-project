package com.petsitter.application.dto;

import lombok.Data;
import java.util.List;

@Data
public class CareNoteRequest {
    private List<CareNoteItemDto> items;
}
