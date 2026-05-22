package com.petsitter.application.dto;

import lombok.Data;
import java.util.List;

@Data
public class CareNoteTemplateRequest {
    private String name;
    private List<CareNoteItemDto> items;
}
