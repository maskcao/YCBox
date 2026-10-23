package com.github.tvbox.osc.util;


import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class StringUtils {

    public static boolean isEmpty( CharSequence str) {
        return str == null || str.length() == 0;
    }

    public static boolean isNotEmpty( CharSequence str) {
        return !isEmpty(str);
    }

    public static boolean isNull(Object obj) {
        return obj == null;
    }

    public static boolean isNotNull(Object obj) {
        return !isNull(obj);
    }

    public static boolean isEmpty(Object obj) {
        if (obj == null) return true;
        else if (obj instanceof CharSequence) return ((CharSequence) obj).length() == 0;
        else if (obj instanceof Collection) return ((Collection) obj).isEmpty();
        else if (obj instanceof Map) return ((Map) obj).isEmpty();
        else if (obj.getClass().isArray()) return Array.getLength(obj) == 0;

        return false;
    }

    public static boolean isNotEmpty(Object obj) {
        return !isEmpty(obj);
    }

    private static final String U2028 = new String(new byte[]{ (byte)0xE2, (byte)0x80, (byte)0xA8 });
    private static final String U2029 = new String(new byte[]{ (byte)0xE2, (byte)0x80, (byte)0xA9 });

    /**
     * Escape JavaString string
     * @param line unescaped string
     * @return escaped string
     */
    public static String escapeJavaScriptString(final String line)
    {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++)
        {
            char c = line.charAt(i);
            switch (c)
            {
                case '"':
                case '\'':
                case '\\':
                    sb.append('\\');
                    sb.append(c);
                    break;

                case '\n':
                    sb.append("\\n");
                    break;

                case '\r':
                    sb.append("\\r");
                    break;

                default:
                    sb.append(c);
            }
        }

        return sb.toString()
                .replace(U2028, "\u2028")
                .replace(U2029, "\u2029");
    }

    public static String getBaseUrl(String url) {
        if (isEmpty(url)) {
            return url;
        }
        String baseUrls = url.replace("http://", "").replace("https://", "");
        String baseUrl2 = baseUrls.split("/")[0];
        String baseUrl;
        if (url.startsWith("https")) {
            baseUrl = "https://" + baseUrl2;
        } else {
            baseUrl = "http://" + baseUrl2;
        }
        return baseUrl;
    }


    public static String arrayToString(String[] list, int fromIndex, String cha) {
        return arrayToString(list, fromIndex, list == null ? 0 : list.length, cha);
    }

    public static String arrayToString(String[] list, int fromIndex, int endIndex, String cha) {
        StringBuilder builder = new StringBuilder();
        if (list == null || list.length <= fromIndex) {
            return "";
        } else if (list.length <= 1) {
            return list[0];
        } else {
            builder.append(list[fromIndex]);
        }
        for (int i = 1 + fromIndex; i < list.length && i < endIndex; i++) {
            builder.append(cha).append(list[i]);
        }
        return builder.toString();
    }

    public static String listToString(List<String> list, String cha) {
        StringBuilder builder = new StringBuilder();
        if (list == null || list.size() <= 0) {
            return "";
        } else if (list.size() <= 1) {
            return list.get(0);
        } else {
            builder.append(list.get(0));
        }
        for (int i = 1; i < list.size(); i++) {
            builder.append(cha).append(list.get(i));
        }
        return builder.toString();
    }

    public static String listToString(List<String> list, int fromIndex, String cha) {
        StringBuilder builder = new StringBuilder();
        if (list == null || list.size() <= fromIndex) {
            return "";
        } else if (list.size() <= 1) {
            return list.get(0);
        } else {
            builder.append(list.get(fromIndex));
        }
        for (int i = fromIndex + 1; i < list.size(); i++) {
            builder.append(cha).append(list.get(i));
        }
        return builder.toString();
    }

    public static String listToString(List<String> list) {
        return listToString(list, "&&");
    }

    /**
     * 去掉字符串里的 emoji / 装饰性图案符号（含 ZWJ 组合与变体选择符）。
     *
     * <p>背景：订阅源配置里的 name 常带 emoji 装饰，例如 "🐮【免费分享】🐮"。
     * 这类符号在本项目 UI 上有两个硬伤：
     * <ol>
     *   <li><b>模糊</b>：Android 的彩色 emoji 是位图字体（NotoColorEmoji），
     *       在 16sp 的小字号下渲染发虚，高 DPI 屏幕上尤其明显；</li>
     *   <li><b>突兀</b>：彩色卡通 emoji 与本项目扁平化的顶栏完全不搭。</li>
     * </ol>
     *
     * <p>这里只做**展示层**清洗，不修改配置本身；整串都是 emoji 时回退原文，
     * 避免顶栏被清成空白。
     */
    public static String stripEmoji(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        StringBuilder sb = new StringBuilder(str.length());
        int i = 0;
        while (i < str.length()) {
            int cp = str.codePointAt(i);
            if (!isEmojiCodePoint(cp)) {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? str.trim() : out;
    }

    private static boolean isEmojiCodePoint(int cp) {
        // 变体选择符 FE0E/FE0F、零宽连接符 ZWJ、键帽组合符
        if (cp == 0xFE0E || cp == 0xFE0F || cp == 0x200D || cp == 0x20E3) {
            return true;
        }
        // 区域旗帜（🇨🇳 等，由两个区域指示符组成）
        if (cp >= 0x1F1E6 && cp <= 0x1F1FF) {
            return true;
        }
        // emoji 主体区（含肤色修饰符 1F3FB-1F3FF）
        if (cp >= 0x1F000 && cp <= 0x1FAFF) {
            return true;
        }
        // 杂项符号与 Dingbats：☀ ✅ ❌ ✨ ⭐ 等
        if (cp >= 0x2600 && cp <= 0x27BF) {
            return true;
        }
        // 杂项符号与箭头：⬆ ⬇ 等源名常见装饰
        if (cp >= 0x2B00 && cp <= 0x2BFF) {
            return true;
        }
        // 私用区（部分源用自定义图标字体）
        if (cp >= 0xE000 && cp <= 0xF8FF) {
            return true;
        }
        // 兜底：Unicode 类别为 So（Other Symbol）的图案符号
        return Character.getType(cp) == Character.OTHER_SYMBOL;
    }

    public static String trimBlanks(String str) {
        if (str == null || str.length() == 0) {
            return str;
        }
        int len = str.length();
        int st = 0;

        while ((st < len) && (str.charAt(st) == '\n' || str.charAt(st) == '\r' || str.charAt(st) == '\f' || str.charAt(st) == '\t')) {
            st++;
        }
        while ((st < len) && (str.charAt(len - 1) == '\n' || str.charAt(len - 1) == '\r' || str.charAt(len - 1) == '\f' || str.charAt(len - 1) == '\t')) {
            len--;
        }
        return ((st > 0) || (len < str.length())) ? str.substring(st, len) : str;
    }

}
