package com.catsitter.api.dto.sitter;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record AvailabilityResponse(
    LocalDate bookingOpenStart,
    LocalDate bookingOpenEnd,
    Map<DayOfWeek, List<String>> weeklyAvailability,
    List<LocalDate> specificExclusions
) {}
