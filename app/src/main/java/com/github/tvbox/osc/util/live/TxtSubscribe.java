package com.github.tvbox.osc.util.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.StringReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;

public class TxtSubscribe {

    /**
     * 解析直播源文本。
     *
     * <p>同时支持两种常见格式：
     * <ul>
     *   <li><b>txt</b>：{@code 分组名,#genre#} 换行 {@code 频道名,地址1#地址2}</li>
     *   <li><b>m3u</b>：{@code #EXTINF:-1 tvg-url="..." group-title="分组",频道名} 换行 {@code 地址}</li>
     * </ul>
     *
     * <p>此前只实现 txt 分支，遇到 m3u 源时会把整个
     * {@code #EXTINF:-1 tvg-url="https://..."} 当成频道名显示（界面出现裸标签文本，
     * 且真正的地址行因不含逗号被丢弃），表现为"直播用不了"。
     */
    public static void parse(LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>> linkedHashMap, String str) {
        try {
            BufferedReader bufferedReader = new BufferedReader(new StringReader(str));
            LinkedHashMap<String, ArrayList<String>> defaultGroup = new LinkedHashMap<>();
            LinkedHashMap<String, ArrayList<String>> currentGroup = defaultGroup;

            // m3u：解析到 #EXTINF 后暂存频道名/分组，等下一行的地址配对
            String pendingName = null;
            String pendingGroup = null;

            String readLine;
            while ((readLine = bufferedReader.readLine()) != null) {
                String line = readLine.trim();
                if (line.isEmpty()) {
                    continue;
                }

                // ---------- m3u 分支 ----------
                if (line.startsWith("#EXTINF")) {
                    int comma = line.lastIndexOf(',');
                    pendingName = (comma >= 0 && comma < line.length() - 1)
                            ? line.substring(comma + 1).trim() : null;
                    pendingGroup = attribute(line, "group-title");
                    continue;
                }
                if (line.startsWith("#")) {
                    // #EXTM3U 及其他标签，忽略
                    continue;
                }
                if (pendingName != null && isStreamUrl(line)) {
                    String groupName = (pendingGroup == null || pendingGroup.isEmpty())
                            ? "未分组" : pendingGroup;
                    LinkedHashMap<String, ArrayList<String>> group = linkedHashMap.get(groupName);
                    if (group == null) {
                        group = new LinkedHashMap<>();
                        linkedHashMap.put(groupName, group);
                    }
                    ArrayList<String> urls = group.get(pendingName);
                    if (urls == null) {
                        urls = new ArrayList<>();
                        group.put(pendingName, urls);
                    }
                    if (!urls.contains(line)) {
                        urls.add(line);
                    }
                    pendingName = null;
                    pendingGroup = null;
                    continue;
                }

                // ---------- txt 分支（原有逻辑） ----------
                String[] split = line.split(",");
                if (split.length < 2) {
                    continue;
                }
                if (line.contains("#genre#")) {
                    String trim = split[0].trim();
                    if (!linkedHashMap.containsKey(trim)) {
                        LinkedHashMap<String, ArrayList<String>> group = new LinkedHashMap<>();
                        linkedHashMap.put(trim, group);
                        currentGroup = group;
                    } else {
                        currentGroup = linkedHashMap.get(trim);
                    }
                } else {
                    String channelName = split[0].trim();
                    for (String part : split[1].trim().split("#")) {
                        String url = part.trim();
                        if (url.isEmpty() || !isStreamUrl(url)) {
                            continue;
                        }
                        ArrayList<String> urls = currentGroup.get(channelName);
                        if (urls == null) {
                            urls = new ArrayList<>();
                            currentGroup.put(channelName, urls);
                        }
                        if (!urls.contains(url)) {
                            urls.add(url);
                        }
                    }
                }
            }
            bufferedReader.close();

            if (!defaultGroup.isEmpty() && !linkedHashMap.containsKey("未分组")) {
                linkedHashMap.put("未分组", defaultGroup);
            }
        } catch (Throwable unused) {
        }
    }

    /** 是否为可播放的流地址 */
    private static boolean isStreamUrl(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        String lower = s.toLowerCase();
        return lower.startsWith("http") || lower.startsWith("rtp")
                || lower.startsWith("rtsp") || lower.startsWith("rtmp")
                || lower.startsWith("udp") || lower.startsWith("p3p")
                || lower.startsWith("mms") || lower.startsWith("p2p");
    }

    /** 取 m3u 标签中的属性值，如 group-title="央视" */
    private static String attribute(String line, String key) {
        String prefix = key + "=\"";
        int start = line.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        start += prefix.length();
        int end = line.indexOf('"', start);
        return end > start ? line.substring(start, end) : null;
    }

    public static JsonArray live2JsonArray(LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>> linkedHashMap) {
        JsonArray jsonarr = new JsonArray();
        for (String str : linkedHashMap.keySet()) {
            JsonArray jsonarr2 = new JsonArray();
            LinkedHashMap<String, ArrayList<String>> linkedHashMap2 = linkedHashMap.get(str);
            if (!linkedHashMap2.isEmpty()) {
                for (String str2 : linkedHashMap2.keySet()) {
                    ArrayList<String> arrayList = linkedHashMap2.get(str2);
                    if (!arrayList.isEmpty()) {
                        JsonArray jsonarr3 = new JsonArray();
                        for (int i = 0; i < arrayList.size(); i++) {
                            jsonarr3.add(arrayList.get(i));
                        }
                        JsonObject jsonobj = new JsonObject();
                        try {
                            jsonobj.addProperty("name", str2);
                            jsonobj.add("urls", jsonarr3);
                        } catch (Throwable e) {
                        }
                        jsonarr2.add(jsonobj);
                    }
                }
                JsonObject jsonobj2 = new JsonObject();
                try {
                    jsonobj2.addProperty("group", str);
                    jsonobj2.add("channels", jsonarr2);
                } catch (Throwable e) {
                }
                jsonarr.add(jsonobj2);
            }
        }
        return jsonarr;
    }
}
