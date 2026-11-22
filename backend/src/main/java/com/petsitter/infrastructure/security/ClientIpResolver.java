package com.petsitter.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 限流計數鍵用的來源 IP（SD-GLOBAL-SPEC §2.8「來源 IP」、NFR-003 CQ-30）。
 * <ul>
 *   <li>取 {@code X-Forwarded-For} <b>最右項</b>（Cloud Run 前端附加的實際連線來源）；</li>
 *   <li>只有最右項落在 {@code app.security.trusted-proxy-cidrs}（Firebase Hosting 轉發出口網段）時才改取前一項，
 *       最多往前退一項，其餘項一律不採用；</li>
 *   <li>沒有 XFF（local 直連）或最右項不是合法 IP → {@code getRemoteAddr()}。</li>
 * </ul>
 * {@code server.forward-headers-strategy=none}，容器不改寫 remote address。只接受 IP 字面值，不做 DNS 解析。
 */
@Component
public class ClientIpResolver {

    private static final String XFF = "X-Forwarded-For";
    private static final Pattern IPV4 = Pattern.compile("^(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private final List<Cidr> trusted;

    public ClientIpResolver(@Value("${app.security.trusted-proxy-cidrs:}") List<String> trustedProxyCidrs) {
        List<Cidr> list = new ArrayList<>();
        for (String c : trustedProxyCidrs) {
            if (c != null && !c.isBlank()) {
                list.add(Cidr.parse(c.strip()));
            }
        }
        this.trusted = List.copyOf(list);
    }

    public String resolve(HttpServletRequest request) {
        return resolve(request.getHeader(XFF), request.getRemoteAddr());
    }

    /** 測試用：以 XFF 原文與連線來源計算。 */
    public String resolve(String xff, String remoteAddr) {
        if (xff == null || xff.isBlank()) {
            return remoteAddr;
        }
        String[] parts = xff.split(",");
        String rightmost = parts[parts.length - 1].strip();
        InetAddress right = parseLiteral(rightmost);
        if (right == null) {
            return remoteAddr;
        }
        if (parts.length >= 2 && isTrusted(right)) {
            String previous = parts[parts.length - 2].strip();
            InetAddress prev = parseLiteral(previous);
            if (prev != null) {
                return prev.getHostAddress();
            }
        }
        return right.getHostAddress();
    }

    private boolean isTrusted(InetAddress address) {
        for (Cidr c : trusted) {
            if (c.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /** 只接受 IPv4 / IPv6 字面值（含 ':' 的字串 JDK 只做字面解析，不查 DNS）。 */
    static InetAddress parseLiteral(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        boolean v4 = IPV4.matcher(s).matches();
        boolean v6 = !v4 && s.indexOf(':') >= 0 && IPV6_CHARS.matcher(s).matches();
        if (!v4 && !v6) {
            return null;
        }
        try {
            return InetAddress.getByName(s);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    record Cidr(byte[] network, int prefix) {

        static Cidr parse(String cidr) {
            int slash = cidr.indexOf('/');
            String addr = slash < 0 ? cidr : cidr.substring(0, slash);
            InetAddress a = parseLiteral(addr);
            if (a == null) {
                throw new IllegalStateException("app.security.trusted-proxy-cidrs contains an invalid entry");
            }
            int max = a.getAddress().length * 8;
            int prefix = slash < 0 ? max : Integer.parseInt(cidr.substring(slash + 1));
            if (prefix < 0 || prefix > max) {
                throw new IllegalStateException("app.security.trusted-proxy-cidrs contains an invalid prefix");
            }
            return new Cidr(a.getAddress(), prefix);
        }

        boolean contains(InetAddress address) {
            byte[] b = address.getAddress();
            if (b.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (b[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (b[full] & mask) == (network[full] & mask);
        }
    }
}
