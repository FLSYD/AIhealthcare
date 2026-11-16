package com.aaa.aihealthcare.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 根据医生姓名生成稳定的账号基础名。
 */
public final class DoctorUsername {
    private static final String USERNAME_PREFIX = "doctor";
    private static final String DICTIONARY_RESOURCE = "/doctor-name-initials.txt";
    private static final int MIDDLE_DOT = 0x00B7;

    private static final List<CompoundSurname> COMPOUND_SURNAMES = List.of(
            new CompoundSurname("尉迟", "yc"),
            new CompoundSurname("万俟", "mq"),
            new CompoundSurname("长孙", "zs"),
            new CompoundSurname("澹台", "tt"),
            new CompoundSurname("单于", "cy"),
            new CompoundSurname("乐正", "yz"));

    private static final Map<Integer, Character> INITIALS = loadInitials();

    private DoctorUsername() {
    }

    /**
     * 将医生姓名转换为 {@code doctor} 加小写拼音首字母。
     *
     * <p>汉字按 Unihan 的首选普通话读音取首字母，ASCII 英文字母逐字转为小写，
     * 空白与中点 {@code ·} 会被忽略。姓名开头的有限多音姓及复姓覆盖优先于普通读音；
     * 姓名中其他多音字仍按字典默认读音处理。</p>
     *
     * @param name 医生姓名
     * @return 以 {@code doctor} 开头的小写账号基础名
     * @throws IllegalArgumentException 姓名为空、没有可用字母，或包含字典不支持的字符
     */
    public static String baseName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("医生姓名不能为空");
        }

        int[] codePoints = name.codePoints()
                .filter(codePoint -> !isIgnored(codePoint))
                .toArray();
        if (codePoints.length == 0) {
            throw new IllegalArgumentException("医生姓名缺少可利用的汉字或英文字母");
        }

        StringBuilder result = new StringBuilder(USERNAME_PREFIX);
        int index = 0;
        String compoundInitials = compoundSurnameInitials(codePoints);
        if (compoundInitials != null) {
            result.append(compoundInitials);
            index = 2;
        } else {
            String surnameInitial = singleSurnameInitial(codePoints[0]);
            if (surnameInitial != null) {
                result.append(surnameInitial);
                index = 1;
            }
        }

        while (index < codePoints.length) {
            int codePoint = codePoints[index++];
            if (isAsciiLetter(codePoint)) {
                result.appendCodePoint(Character.toLowerCase(codePoint));
                continue;
            }

            Character initial = INITIALS.get(codePoint);
            if (initial == null) {
                throw unsupportedCharacter(codePoint);
            }
            result.append(initial.charValue());
        }

        if (result.length() == USERNAME_PREFIX.length()) {
            throw new IllegalArgumentException("医生姓名缺少可利用的汉字或英文字母");
        }
        return result.toString();
    }

    private static boolean isIgnored(int codePoint) {
        return codePoint == MIDDLE_DOT
                || Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint);
    }

    private static boolean isAsciiLetter(int codePoint) {
        return (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= 'a' && codePoint <= 'z');
    }

    private static String compoundSurnameInitials(int[] codePoints) {
        if (codePoints.length < 2) {
            return null;
        }
        String prefix = new String(codePoints, 0, 2);
        for (CompoundSurname surname : COMPOUND_SURNAMES) {
            if (surname.characters().equals(prefix)) {
                return surname.initials();
            }
        }
        return null;
    }

    private static String singleSurnameInitial(int codePoint) {
        return switch (codePoint) {
            case 0x5355 -> "s"; // 单，Shàn
            case 0x66FE -> "z"; // 曾，Zēng
            case 0x89E3 -> "x"; // 解，Xiè
            case 0x4EC7 -> "q"; // 仇，Qiú
            case 0x533A -> "o"; // 区，Ōu
            case 0x4E50 -> "y"; // 乐，Yuè
            case 0x67E5 -> "z"; // 查，Zhā
            case 0x7FDF -> "z"; // 翟，Zhái
            case 0x5C09 -> "y"; // 尉，Yù
            case 0x6734 -> "p"; // 朴，Piáo
            case 0x91CD -> "c"; // 重，Chóng
            case 0x8983 -> "q"; // 覃，Qín
            case 0x79CD -> "c"; // 种，Chóng
            case 0x79D8 -> "b"; // 秘，Bì
            case 0x7E41 -> "p"; // 繁，Pó
            case 0x53E5 -> "g"; // 句，Gōu
            case 0x796D -> "z"; // 祭，Zhài
            case 0x6298 -> "s"; // 折，Shé
            case 0x90C7 -> "x"; // 郇，Xún
            default -> null;
        };
    }

    private static IllegalArgumentException unsupportedCharacter(int codePoint) {
        String character = new String(Character.toChars(codePoint));
        String unicodeName = Character.getName(codePoint);
        String suffix = unicodeName == null ? "" : " " + unicodeName;
        return new IllegalArgumentException(
                "医生姓名包含不支持的字符 '" + character + "' ("
                        + codePointLabel(codePoint) + suffix + ")");
    }

    private static String codePointLabel(int codePoint) {
        return "U+" + String.format("%04X", codePoint);
    }

    private static Map<Integer, Character> loadInitials() {
        InputStream resource = DoctorUsername.class.getResourceAsStream(DICTIONARY_RESOURCE);
        if (resource == null) {
            throw new IllegalStateException("医生姓名拼音首字母字典缺失: " + DICTIONARY_RESOURCE);
        }

        Map<Integer, Character> initials = new HashMap<>(60000);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1 && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                String content = line.strip();
                if (content.isEmpty() || content.startsWith("#")) {
                    continue;
                }

                int separator = content.indexOf('\t');
                if (separator < 0 || content.indexOf('\t', separator + 1) >= 0) {
                    throw dictionaryFormatError(lineNumber, "应包含以制表符分隔的两个字段");
                }
                String codePointText = content.substring(0, separator);
                String initialText = content.substring(separator + 1);
                int codePoint = parseCodePoint(codePointText, lineNumber);
                if (initialText.length() != 1
                        || initialText.charAt(0) < 'a'
                        || initialText.charAt(0) > 'z') {
                    throw dictionaryFormatError(lineNumber, "首字母必须为单个小写 ASCII 字母");
                }

                Character previous = initials.put(codePoint, initialText.charAt(0));
                if (previous != null) {
                    throw dictionaryFormatError(lineNumber, "码点重复: " + codePointText);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取医生姓名拼音首字母字典失败: " + DICTIONARY_RESOURCE,
                    exception);
        }

        if (initials.isEmpty()) {
            throw new IllegalStateException("医生姓名拼音首字母字典为空: " + DICTIONARY_RESOURCE);
        }
        return Map.copyOf(initials);
    }

    private static int parseCodePoint(String value, int lineNumber) {
        if (value.length() < 6 || value.length() > 8 || !value.startsWith("U+")) {
            throw dictionaryFormatError(lineNumber, "码点格式必须为 U+XXXX");
        }
        for (int index = 2; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (!(digit >= '0' && digit <= '9')
                    && !(digit >= 'A' && digit <= 'F')) {
                throw dictionaryFormatError(lineNumber, "码点格式必须为大写十六进制");
            }
        }
        int codePoint;
        try {
            codePoint = Integer.parseInt(value.substring(2), 16);
        } catch (NumberFormatException exception) {
            throw dictionaryFormatError(lineNumber, "码点无法解析");
        }
        if (!Character.isValidCodePoint(codePoint)) {
            throw dictionaryFormatError(lineNumber, "码点超出 Unicode 范围");
        }
        return codePoint;
    }

    private static IllegalStateException dictionaryFormatError(int lineNumber, String reason) {
        return new IllegalStateException("医生姓名拼音首字母字典格式错误，第 "
                + lineNumber + " 行: " + reason);
    }

    private record CompoundSurname(String characters, String initials) {
    }
}
