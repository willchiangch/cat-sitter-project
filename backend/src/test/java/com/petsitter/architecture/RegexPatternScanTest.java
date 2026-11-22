package com.petsitter.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SD-GLOBAL-SPEC §2.8「文字與 regex」（NFR-003 SEC-INPUT-4）：掃描 {@code src/main/java} 中 {@code Pattern.compile}、
 * {@code @Pattern(regexp)}、{@code String.matches / replaceAll / replaceFirst / split} 的樣式字串，不得含巢狀量詞
 * （{@code (a+)+}、{@code (\w*)*}）或分支加無上限量詞（{@code (a|ab)+}）。
 */
@DisplayName("NFR-003 SEC-INPUT-4 RegexPatternScanTest")
class RegexPatternScanTest {

    private static final Pattern CALL = Pattern.compile(
            "(?:Pattern\\.compile|regexp\\s*=|\\.matches|\\.replaceAll|\\.replaceFirst|\\.split)\\s*\\(?\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    @DisplayName("§2.8 main 程式的 regex 樣式無巢狀量詞與分支加量詞")
    void noCatastrophicPatterns() throws IOException {
        List<String> found = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                Matcher m = CALL.matcher(src);
                while (m.find()) {
                    String regex = unescapeJava(m.group(1));
                    found.add(regex);
                    if (isDangerous(regex)) {
                        violations.add(f.getFileName() + ": " + regex);
                    }
                }
            }
        }
        assertThat(found).hasSizeGreaterThan(5);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("偵測器自我檢查：(a+)+、(\\w*)*、(a|ab)+、(x+){2,} 判為危險；(?:a|b){3}、[a+]+、\\(a+\\)+ 不判")
    void detectorSelfCheck() {
        assertThat(isDangerous("(a+)+")).isTrue();
        assertThat(isDangerous("^(\\w*)*$")).isTrue();
        assertThat(isDangerous("(a|ab)+")).isTrue();
        assertThat(isDangerous("(?:x+){2,}")).isTrue();
        assertThat(isDangerous("((a+)b)*")).isTrue();
        assertThat(isDangerous("^(?:25[0-5]|1\\d\\d)(?:\\.(?:25[0-5]|1\\d\\d)){3}$")).isFalse();
        assertThat(isDangerous("[a+]+")).isFalse();
        assertThat(isDangerous("\\(a+\\)+")).isFalse();
        assertThat(isDangerous("^(RADIO|CHECKBOX)$")).isFalse();
    }

    static String unescapeJava(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final class Group {
        boolean unbounded;
        boolean alternation;
    }

    /** 結構掃描：群組內有無上限量詞或分支，且群組本身再加無上限量詞 → 危險。 */
    static boolean isDangerous(String regex) {
        Deque<Group> stack = new ArrayDeque<>();
        stack.push(new Group());
        int i = 0;
        int n = regex.length();
        while (i < n) {
            char c = regex.charAt(i);
            if (c == '\\' || c == '[') {
                i = c == '[' ? skipClass(regex, i) : i + 2;
                int[] q = quantifier(regex, i);
                if (q[0] == 1) {
                    stack.peek().unbounded = true;
                }
                i = q[1];
                continue;
            }
            if (c == '(') {
                stack.push(new Group());
                i++;
                if (i < n && regex.charAt(i) == '?') {
                    while (i < n && regex.charAt(i) != ':' && regex.charAt(i) != ')' && regex.charAt(i) != '='
                            && regex.charAt(i) != '!' && regex.charAt(i) != '>') {
                        i++;
                    }
                    i++;
                }
                continue;
            }
            if (c == '|') {
                stack.peek().alternation = true;
                i++;
                continue;
            }
            if (c == ')') {
                Group g = stack.pop();
                i++;
                int[] q = quantifier(regex, i);
                boolean unboundedQ = q[0] == 1;
                if (unboundedQ && (g.unbounded || g.alternation)) {
                    return true;
                }
                if (g.unbounded || unboundedQ) {
                    stack.peek().unbounded = true;
                }
                i = q[1];
                continue;
            }
            int[] q = quantifier(regex, i + 1);
            if (q[0] == 1) {
                stack.peek().unbounded = true;
            }
            i = Math.max(q[1], i + 1);
        }
        return false;
    }

    private static int skipClass(String r, int i) {
        i++;
        if (i < r.length() && r.charAt(i) == '^') {
            i++;
        }
        if (i < r.length() && r.charAt(i) == ']') {
            i++;
        }
        while (i < r.length() && r.charAt(i) != ']') {
            if (r.charAt(i) == '\\') {
                i++;
            } else if (r.charAt(i) == '[') {
                i = skipClass(r, i) - 1;
            }
            i++;
        }
        return i + 1;
    }

    /** 回傳 {是否無上限量詞(1/0), 量詞結束位置}。 */
    private static int[] quantifier(String r, int i) {
        if (i >= r.length()) {
            return new int[]{0, i};
        }
        char c = r.charAt(i);
        if (c == '+' || c == '*') {
            int end = i + 1;
            if (end < r.length() && (r.charAt(end) == '?' || r.charAt(end) == '+')) {
                end++;
            }
            return new int[]{1, end};
        }
        if (c == '?') {
            return new int[]{0, i + 1};
        }
        if (c == '{') {
            int close = r.indexOf('}', i);
            if (close > i) {
                String body = r.substring(i + 1, close);
                if (body.matches("\\d+(,\\d*)?")) {
                    boolean unbounded = body.endsWith(",");
                    return new int[]{unbounded ? 1 : 0, close + 1};
                }
            }
        }
        return new int[]{0, i};
    }
}
