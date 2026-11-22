package testsupport.sharedmech;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 可設定時間的 Clock（SD-ORDER §10.1「時間」）。預設跟隨系統時間 + 偏移量。 */
public class MutableClock extends Clock {

    private volatile Duration offset = Duration.ZERO;
    private volatile Instant frozen;

    public void advance(Duration d) {
        if (frozen != null) {
            frozen = frozen.plus(d);
        } else {
            offset = offset.plus(d);
        }
    }

    /** 固定在指定時刻（邊界測試用；{@link #advance} 以此為基準推進，{@link #reset} 解除）。 */
    public void freezeAt(Instant at) {
        frozen = at;
    }

    public void reset() {
        offset = Duration.ZERO;
        frozen = null;
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
        Instant f = frozen;
        if (f != null) {
            return f;
        }
        return Instant.now().plus(offset);
    }
}
