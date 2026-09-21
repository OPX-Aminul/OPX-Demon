package com.opx.demon.ota;

import com.opx.demon.custom.News;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GitHubReleasesNews {

    private static final int MAX_RELEASES = 20;
    private static final int MAX_BODY_CHARS = 900;
    private static final String NEWS_SINCE = "2026-09-21T00:00:00Z";

    private GitHubReleasesNews() {
    }

    public static List<News> fetch() {
        try {
            String json = Net.getString(OpxDemonEndpoints.GITHUB_RELEASES_URL, 1024 * 1024);
            return fromJson(json);
        } catch (Exception e) {
            return null;
        }
    }

    static List<News> fromJson(String json) throws Exception {
        JSONArray array = new JSONArray(json);
        List<News> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null || o.optBoolean("draft", false)) {
                continue;
            }
            String tag = o.optString("tag_name", "");
            if (!isAppRelease(tag) || !isNewRelease(o.optString("published_at", ""))) {
                continue;
            }
            News news = toNews(o, list.size());
            if (news != null) {
                list.add(news);
            }
            if (list.size() >= MAX_RELEASES) {
                break;
            }
        }
        return list;
    }

    static boolean isAppRelease(String tag) {
        if (tag == null || tag.isEmpty()) {
            return false;
        }
        String lower = tag.toLowerCase(Locale.US);
        if ("chroot-main".equals(lower) || "all-core-file".equals(lower)) {
            return false;
        }
        return !lower.startsWith("chroot");
    }

    static boolean isNewRelease(String publishedAt) {
        return publishedAt != null && publishedAt.compareTo(NEWS_SINCE) > 0;
    }

    private static News toNews(JSONObject o, int index) {
        String tag = o.optString("tag_name", "");
        String name = o.optString("name", tag);
        if (name == null || name.isEmpty()) {
            name = tag;
        }
        String htmlUrl = o.optString("html_url", OpxDemonEndpoints.GITHUB_REPO);
        String published = o.optString("published_at", "");
        String body = o.optString("body", "");
        String apkUrl = pickApkUrl(o.optJSONArray("assets"));

        News news = new News();
        news.id = newsId(tag, o.optLong("id", 0));
        news.title = name;
        news.description = clipBody(body);
        news.newsDate = published.length() >= 10 ? published.substring(0, 10) : published;
        news.newsUrl = htmlUrl;
        news.pinned = index == 0;
        news.actionbutton1 = true;
        if (apkUrl != null) {
            news.actionbutton1text = "Download APK";
            news.actionbutton1url = apkUrl;
            news.actionbutton2 = true;
            news.actionbutton2text = "Open release";
            news.actionbutton2url = htmlUrl;
        } else {
            news.actionbutton1text = "Open release";
            news.actionbutton1url = htmlUrl;
            news.actionbutton2 = false;
        }
        return news;
    }

    private static String pickApkUrl(JSONArray assets) {
        if (assets == null) {
            return null;
        }
        String anyApk = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) {
                continue;
            }
            String assetName = a.optString("name", "");
            if (!assetName.toLowerCase(Locale.US).endsWith(".apk")) {
                continue;
            }
            String url = a.optString("browser_download_url", "");
            if (!url.startsWith("https://")) {
                continue;
            }
            String lower = assetName.toLowerCase(Locale.US);
            if (lower.contains("release") && !lower.contains("debug")) {
                return url;
            }
            if (anyApk == null) {
                anyApk = url;
            }
        }
        return anyApk;
    }

    private static int newsId(String tag, long githubId) {
        if (githubId > 0 && githubId < Integer.MAX_VALUE) {
            return (int) githubId;
        }
        return 100000 + Math.abs(tag.hashCode() % 900000);
    }

    private static String clipBody(String body) {
        if (body == null) {
            return "";
        }
        String text = body.replace("\r\n", "\n").trim();
        if (text.isEmpty()) {
            return "New GitHub release. Open it for notes and downloads.";
        }
        if (text.length() <= MAX_BODY_CHARS) {
            return text;
        }
        return text.substring(0, MAX_BODY_CHARS).trim() + "…";
    }
}
