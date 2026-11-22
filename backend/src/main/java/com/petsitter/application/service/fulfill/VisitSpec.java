package com.petsitter.application.service.fulfill;

import java.time.LocalDate;
import java.util.UUID;

/** {@code createVisits} 的一筆規格（SD-ORDER §2.4.2）；{@code sequenceInDay} 1–3。 */
public record VisitSpec(UUID orderItemId, UUID planId, String planTitle, LocalDate serviceDate, int sequenceInDay) {
}
