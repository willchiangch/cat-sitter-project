package com.petsitter.fulfill;

import com.petsitter.application.service.fulfill.MediaInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static testsupport.fulfill.MediaFiles.*;

@DisplayName("SD-FULFILL MediaInspectorTest（單元）")
class MediaInspectorTest {

    private final MediaInspector inspector = new MediaInspector();

    private MediaInspector.Result inspect(byte[] b) throws IOException {
        return inspector.inspect(new ByteArrayInputStream(b));
    }

    @Test
    @DisplayName("PRD-022 AC-2.3 檔頭判定 JPEG / PNG / WebP；GIF / HEIC 不屬允許格式；SHA-256 與實際大小正確")
    void imageHeaders() throws IOException {
        byte[] j = jpeg(900_000);
        MediaInspector.Result r = inspect(j);
        assertThat(r.contentType()).isEqualTo("image/jpeg");
        assertThat(r.sizeBytes()).isEqualTo(900_000);
        assertThat(r.sha256()).isEqualTo(sha256(j));
        assertThat(inspect(png(100)).contentType()).isEqualTo("image/png");
        assertThat(inspect(webp(100)).contentType()).isEqualTo("image/webp");
        assertThat(inspect(gif(100)).contentType()).isNull();
        assertThat(inspect(heic(100)).contentType()).isNull();
        assertThat(inspect(new byte[0]).contentType()).isNull();
    }

    @Test
    @DisplayName("PRD-022 AC-2.4 MP4 moov 在前 / 在後、MOV（ftyp qt）、mvhd v0 / v1 皆可取時長；雜湊涵蓋整個檔案")
    void videoContainers() throws IOException {
        byte[] front = video("isom", 20, 0, true, 300_000, 0);
        MediaInspector.Result r = inspect(front);
        assertThat(r.contentType()).isEqualTo("video/mp4");
        assertThat(r.durationSeconds()).isEqualTo(20);
        assertThat(r.sha256()).isEqualTo(sha256(front));
        assertThat(r.sizeBytes()).isEqualTo(front.length);
        byte[] tail = video("isom", 15, 0, false, 300_000, 0);
        assertThat(inspect(tail).durationSeconds()).isEqualTo(15);
        assertThat(inspect(tail).sha256()).isEqualTo(sha256(tail));
        assertThat(inspect(video("qt  ", 30, 0, true, 1000, 0)).contentType()).isEqualTo("video/quicktime");
        assertThat(inspect(video("isom", 25, 1, true, 1000, 0)).durationSeconds()).isEqualTo(25);
    }

    @ParameterizedTest
    @CsvSource({"14.4,14", "14.5,15", "30.4,30", "30.5,31"})
    @DisplayName("PRD-022 AC-2.4 時長 half-up 取整數秒：14.4 → 14、14.5 → 15、30.4 → 30、30.5 → 31")
    void halfUp(double seconds, int expected) throws IOException {
        assertThat(inspect(mp4(seconds, 2000)).durationSeconds()).isEqualTo(expected);
    }

    @Test
    @DisplayName("PRD-022 AC-2.4 moov 超過 8 MB 或缺 mvhd → 時長無法取得（視為格式不支援）")
    void moovTooLarge() throws IOException {
        byte[] big = video("isom", 20, 0, true, 0, 8 * 1024 * 1024 + 10);
        MediaInspector.Result r = inspect(big);
        assertThat(r.durationSeconds()).isNull();
        assertThat(r.sha256()).isEqualTo(sha256(big));
        byte[] noMvhd = concat(new byte[]{0, 0, 0, 20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0, 'i', 's', 'o', 'm'},
                new byte[]{0, 0, 0, 16, 'm', 'o', 'o', 'v', 0, 0, 0, 8, 'f', 'r', 'e', 'e'});
        assertThat(inspect(noMvhd).durationSeconds()).isNull();
    }
}
