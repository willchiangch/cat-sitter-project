package testsupport.fulfill;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 測試用媒體檔：以 magic bytes 與 ISO BMFF atom 組出指定大小 / 時長的檔案（SD-FULFILL §8.2 MediaInspector）。 */
public final class MediaFiles {

    private MediaFiles() {
    }

    private static byte[] pad(byte[] head, int size) {
        byte[] out = new byte[Math.max(size, head.length)];
        System.arraycopy(head, 0, out, 0, head.length);
        for (int i = head.length; i < out.length; i++) {
            out[i] = (byte) (i * 31);
        }
        return out;
    }

    public static byte[] jpeg(int size) {
        return pad(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, size);
    }

    public static byte[] png(int size) {
        return pad(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, size);
    }

    public static byte[] webp(int size) {
        return pad("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1), size);
    }

    public static byte[] gif(int size) {
        return pad("GIF89a".getBytes(StandardCharsets.ISO_8859_1), size);
    }

    public static byte[] heic(int size) {
        return pad(new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'}, size);
    }

    private static byte[] atom(String type, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(8 + payload.length);
        b.putInt(8 + payload.length).put(type.getBytes(StandardCharsets.ISO_8859_1)).put(payload);
        return b.array();
    }

    private static byte[] mvhd(int version, long timescale, long duration) {
        ByteBuffer b = ByteBuffer.allocate(version == 1 ? 112 : 100);
        b.put((byte) version).put(new byte[3]);
        if (version == 1) {
            b.putLong(0).putLong(0).putInt((int) timescale).putLong(duration);
        } else {
            b.putInt(0).putInt(0).putInt((int) timescale).putInt((int) duration);
        }
        return atom("mvhd", b.array());
    }

    /**
     * MP4 / MOV：ftyp（brand {@code isom} 或 {@code qt  }）+ moov（含 mvhd）+ mdat 填充至 {@code size}；
     * {@code moovFirst=false} 時 moov 放在 mdat 之後；{@code extraMoovBytes} 讓 moov 變大（測 8 MB 上限）。
     */
    public static byte[] video(String brand, double seconds, int version, boolean moovFirst, int size, int extraMoovBytes) {
        long timescale = 1000;
        long duration = Math.round(seconds * timescale);
        byte[] ftyp = atom("ftyp", (brand + "\0\0\0\0isomiso2").getBytes(StandardCharsets.ISO_8859_1));
        byte[] trak = atom("trak", new byte[Math.max(0, extraMoovBytes)]);
        byte[] moovPayload = concat(mvhd(version, timescale, duration), trak);
        byte[] moov = atom("moov", moovPayload);
        int mdatPayload = Math.max(0, size - ftyp.length - moov.length - 8);
        byte[] mdat = atom("mdat", new byte[mdatPayload]);
        return moovFirst ? concat(ftyp, moov, mdat) : concat(ftyp, mdat, moov);
    }

    public static byte[] mp4(double seconds, int size) {
        return video("isom", seconds, 0, true, size, 0);
    }

    public static byte[] mov(double seconds, int size) {
        return video("qt  ", seconds, 0, true, size, 0);
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
