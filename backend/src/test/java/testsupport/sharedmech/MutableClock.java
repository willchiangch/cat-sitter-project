package testsupport.sharedmech;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 可設定時間的 Clock（SD-ORDER §10.1「時間」）。預設跟隨系統時間 + 偏移量。 */
public class MutableClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

    public void advance(Duration d) {
        offset = offset.plus(d);
    }

    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }
}
