package com.petsitter.application.service.order;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 注入 {@link Clock} 的時間工具（SD-ORDER §11.4）；日界一律 Asia/Taipei。 */
@Component
@RequiredArgsConstructor
public class OrderClock {

    public static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final Clock clock;

    public OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    public LocalDate todayTaipei() {
        return now().atZoneSameInstant(TAIPEI).toLocalDate();
    }

    public YearMonth monthTaipei() {
        return YearMonth.from(todayTaipei());
    }

    /** 日期 00:00 Asia/Taipei（UTC 表示）。 */
    public static OffsetDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay(TAIPEI).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    public static OffsetDateTime startOfMonth(YearMonth month) {
        return startOfDay(month.atDay(1));
    }

    public static OffsetDateTime max(OffsetDateTime a, OffsetDateTime b) {
        return a.isAfter(b) ? a : b;
    }
}
