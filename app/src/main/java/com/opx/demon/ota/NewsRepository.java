package com.opx.demon.ota;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.opx.demon.custom.News;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class NewsRepository {

    public interface Callback {
        void onNews(List<News> news);
    }

    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

    private NewsRepository() {
    }

    public static List<News> defaults() {
        return new ArrayList<>();
    }

    public static void load(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<News> list = GitHubReleasesNews.fetch();
            if (list == null) {
                list = cachedReleases(appContext);
            } else {
                cacheReleases(appContext, list);
            }
            for (News item : list) {
                if (item.imageUrl != null && item.imageUrl.startsWith("https://")) {
                    item.image = Net.getBitmap(item.imageUrl, MAX_IMAGE_BYTES);
                }
            }
            final List<News> result = list;
            main.post(() -> callback.onNews(result));
        }).start();
    }

    private static final String KEY_RELEASES_CACHE = "github_releases_news_cache_v2";

    private static void cacheReleases(Context context, List<News> list) {
        try {
            JSONArray array = new JSONArray();
            for (News item : list) {
                JSONObject o = new JSONObject();
                o.put("id", item.id);
                o.put("title", item.title);
                o.put("description", item.description);
                o.put("newsDate", item.newsDate);
                o.put("newsUrl", item.newsUrl);
                o.put("pinned", item.pinned);
                o.put("actionbutton1", item.actionbutton1);
                o.put("actionbutton1text", item.actionbutton1text);
                o.put("actionbutton1url", item.actionbutton1url);
                o.put("actionbutton2", item.actionbutton2);
                o.put("actionbutton2text", item.actionbutton2text);
                o.put("actionbutton2url", item.actionbutton2url);
                array.put(o);
            }
            ManifestService.prefs(context).edit().putString(KEY_RELEASES_CACHE, array.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static List<News> cachedReleases(Context context) {
        String json = ManifestService.prefs(context).getString(KEY_RELEASES_CACHE, null);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            JSONArray array = new JSONArray(json);
            List<News> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                News n = new News();
                n.id = o.optInt("id", 0);
                n.title = o.optString("title", n.title);
                n.description = o.optString("description", n.description);
                n.newsDate = o.optString("newsDate", "");
                n.newsUrl = o.optString("newsUrl", "");
                n.pinned = o.optBoolean("pinned", false);
                n.actionbutton1 = o.optBoolean("actionbutton1", false);
                n.actionbutton1text = o.optString("actionbutton1text", "Open");
                n.actionbutton1url = o.optString("actionbutton1url", "");
                n.actionbutton2 = o.optBoolean("actionbutton2", false);
                n.actionbutton2text = o.optString("actionbutton2text", "");
                n.actionbutton2url = o.optString("actionbutton2url", "");
                list.add(n);
            }
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
