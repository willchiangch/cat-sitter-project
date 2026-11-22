package com.petsitter.application.service.fulfill;

import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 日誌媒體檢驗（SD-FULFILL §8.2、§11 決策 2 / 3）：交易外、單次串流同時計算 SHA-256、以前 16 bytes 判定格式、累計實際大小；
 * 影片逐一走頂層 atom，遇 {@code moov} 時緩衝（上限 8 MB，超過視為格式不支援）並解析 {@code mvhd}（version 0 / 1），
 * 時長 = duration ÷ timescale 以 half-up 取整數秒。不信任宣告與 GCS metadata。
 */
@Component
public class MediaInspector {

    static final int MOOV_LIMIT = 8 * 1024 * 1024;
    private static final java.util.Set<String> IMAGE_BRANDS = java.util.Set.of("heic", "heix", "hevc", "hevx", "heim", "heis",
            "mif1", "msf1", "avif", "avis");

    /**
     * @param contentType 由檔頭判定的類型（{@code image/jpeg} / {@code image/png} / {@code image/webp} / {@code video/mp4} /
     *                    {@code video/quicktime}）；null = 不屬允許格式
     * @param durationSeconds 影片時長（half-up 整數秒）；影片缺 {@code mvhd} 或 {@code moov} 過大 → null
     */
    public record Result(String sha256, String contentType, long sizeBytes, Integer durationSeconds) {

        public boolean video() {
            return contentType != null && contentType.startsWith("video/");
        }
    }

    public Result inspect(InputStream raw) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        CountingInputStream counting = new CountingInputStream(new DigestInputStream(raw, sha));
        BufferedInputStream in = new BufferedInputStream(counting, 64 * 1024);
        in.mark(16);
        byte[] head = in.readNBytes(16);
        in.reset();
        String type = detect(head);
        Integer duration = null;
        if (type != null && type.startsWith("video/")) {
            duration = parseTopLevel(in);
        }
        in.transferTo(OutputStreamSink.INSTANCE);
        return new Result(HexFormat.of().formatHex(sha.digest()), type, counting.count, duration);
    }

    static String detect(byte[] h) {
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A) {
            return "image/png";
        }
        if (h.length >= 12 && ascii(h, 0, 4).equals("RIFF") && ascii(h, 8, 4).equals("WEBP")) {
            return "image/webp";
        }
        if (h.length >= 12 && ascii(h, 4, 4).equals("ftyp")) {
            String brand = ascii(h, 8, 4);
            if (IMAGE_BRANDS.contains(brand)) {
                return null; // HEIF / AVIF 圖片同為 ISO BMFF 容器，不屬允許格式
            }
            return brand.equals("qt  ") ? "video/quicktime" : "video/mp4";
        }
        if (h.length >= 8) {
            String atom = ascii(h, 4, 4);
            if (atom.equals("moov") || atom.equals("wide") || atom.equals("mdat") || atom.equals("free")) {
                return "video/quicktime";
            }
        }
        return null;
    }

    /** 走頂層 atom：遇 moov 緩衝解析 mvhd；其他 atom 讀過丟棄（仍計入雜湊與大小）。 */
    private static Integer parseTopLevel(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        while (true) {
            long size;
            String type;
            try {
                size = Integer.toUnsignedLong(data.readInt());
                byte[] t = new byte[4];
                data.readFully(t);
                type = new String(t, StandardCharsets.ISO_8859_1);
            } catch (EOFException e) {
                return null;
            }
            long header = 8;
            if (size == 1) {
                size = data.readLong();
                header = 16;
            }
            if (size == 0) {
                // 延伸到檔尾：moov 不可能在其後
                return type.equals("moov") ? readMoov(data, -1) : null;
            }
            long payload = size - header;
            if (payload < 0) {
                return null;
            }
            if (type.equals("moov")) {
                return readMoov(data, payload);
            }
            skipFully(data, payload);
        }
    }

    private static Integer readMoov(DataInputStream data, long payload) throws IOException {
        if (payload < 0 || payload > MOOV_LIMIT) {
            return null;
        }
        byte[] moov = new byte[(int) payload];
        data.readFully(moov);
        return mvhdSeconds(moov);
    }

    static Integer mvhdSeconds(byte[] moov) throws IOException {
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(moov));
        int remaining = moov.length;
        while (remaining >= 8) {
            long size = Integer.toUnsignedLong(d.readInt());
            byte[] t = new byte[4];
            d.readFully(t);
            String type = new String(t, StandardCharsets.ISO_8859_1);
            long header = 8;
            if (size == 1) {
                size = d.readLong();
                header = 16;
            }
            if (size < header || size > remaining) {
                return null;
            }
            if (type.equals("mvhd")) {
                int version = d.readUnsignedByte();
                d.skipBytes(3);
                long timescale;
                long duration;
                if (version == 1) {
                    d.skipBytes(16);
                    timescale = Integer.toUnsignedLong(d.readInt());
                    duration = d.readLong();
                } else {
                    d.skipBytes(8);
                    timescale = Integer.toUnsignedLong(d.readInt());
                    duration = Integer.toUnsignedLong(d.readInt());
                }
                if (timescale == 0) {
                    return null;
                }
                return BigDecimal.valueOf(duration).divide(BigDecimal.valueOf(timescale), 0, RoundingMode.HALF_UP).intValueExact();
            }
            d.skipBytes((int) (size - header));
            remaining -= (int) size;
        }
        return null;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        byte[] buf = new byte[8192];
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) {
                return;
            }
            left -= r;
        }
    }

    private static String ascii(byte[] b, int off, int len) {
        return new String(b, off, len, StandardCharsets.ISO_8859_1);
    }

    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int r = super.read(b, off, len);
            if (r > 0) {
                count += r;
            }
            return r;
        }

        @Override
        public long skip(long n) throws IOException {
            // 一律以讀取取代 skip，確保每個 byte 都進入雜湊
            byte[] buf = new byte[(int) Math.min(8192, Math.max(1, n))];
            int r = read(buf, 0, (int) Math.min(buf.length, n));
            return Math.max(r, 0);
        }
    }

    private static final class OutputStreamSink extends java.io.OutputStream {
        static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
