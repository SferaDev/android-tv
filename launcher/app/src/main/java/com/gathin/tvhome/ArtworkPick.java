package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Pure choices for {@link Artwork}: name normalising and picking from the TVmaze and Steam JSON. */
final class ArtworkPick {
    private ArtworkPick() {}

    /** Lower case, trademark signs dropped, every other run of punctuation or spaces one space. */
    static String normalise(String name) {
        if (name == null) return "";
        StringBuilder out = new StringBuilder();
        boolean gap = false;
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '™' || c == '®' || c == '©') continue;
            if (Character.isLetterOrDigit(c)) {
                if (gap && out.length() > 0) out.append(' ');
                out.append(c);
                gap = false;
            } else {
                gap = true;
            }
        }
        return out.toString();
    }

    /** Desktop and Big Picture are Sunshine entries, not games. */
    static boolean isGame(String name) {
        String n = normalise(name);
        return !n.isEmpty() && !n.equals("desktop") && !n.equals("steam big picture");
    }

    /**
     * The URL of the widest landscape "background" in a TVmaze /shows/ID/images array, else the widest
     * landscape "banner", else null. Portrait posters never qualify.
     */
    static String backdropUrl(String imagesJson) {
        try {
            JSONArray images = new JSONArray(imagesJson);
            String background = widest(images, "background");
            return background != null ? background : widest(images, "banner");
        } catch (Exception e) {
            return null;
        }
    }

    private static String widest(JSONArray images, String type) {
        String best = null;
        long bestArea = 0;
        for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            if (image == null || !type.equals(image.optString("type"))) continue;
            JSONObject resolutions = image.optJSONObject("resolutions");
            JSONObject original = resolutions == null ? null : resolutions.optJSONObject("original");
            if (original == null) continue;
            int w = original.optInt("width", 0);
            int h = original.optInt("height", 0);
            String url = original.optString("url", "");
            if (w <= 0 || h <= 0 || w < h || url.isEmpty()) continue;
            long area = (long) w * h;
            if (area > bestArea) {
                bestArea = area;
                best = url;
            }
        }
        return best;
    }

    /** The app id in a Steam storesearch response that best matches name, or 0: exact, else the shortest name that starts with it. */
    static long steamAppId(String searchJson, String name) {
        String wanted = normalise(name);
        if (wanted.isEmpty()) return 0;
        try {
            JSONArray items = new JSONObject(searchJson).optJSONArray("items");
            if (items == null) return 0;
            long prefixId = 0;
            int prefixLength = Integer.MAX_VALUE;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || !"app".equals(item.optString("type"))) continue;
                long id = item.optLong("id", 0);
                String found = normalise(item.optString("name"));
                if (id <= 0 || found.isEmpty()) continue;
                if (found.equals(wanted)) return id;
                if (found.startsWith(wanted + " ") && found.length() < prefixLength) {
                    prefixId = id;
                    prefixLength = found.length();
                }
            }
            return prefixId;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Landscape images from a Steam appdetails response for app id, best first: background_raw, the first
     * 1920 wide screenshot, header_image. Empty when the app is unknown or has none.
     */
    static java.util.List<String> appDetailsUrls(String json, long id) {
        java.util.List<String> urls = new java.util.ArrayList<>();
        try {
            JSONObject app = new JSONObject(json).optJSONObject(String.valueOf(id));
            JSONObject data = app == null || !app.optBoolean("success") ? null : app.optJSONObject("data");
            if (data == null) return urls;
            add(urls, data.optString("background_raw", ""));
            JSONArray shots = data.optJSONArray("screenshots");
            if (shots != null && shots.length() > 0 && shots.optJSONObject(0) != null) {
                add(urls, shots.optJSONObject(0).optString("path_full", ""));
            }
            add(urls, data.optString("header_image", ""));
        } catch (Exception ignored) {
        }
        return urls;
    }

    private static void add(java.util.List<String> urls, String url) {
        if (url.startsWith("https://") && !urls.contains(url)) urls.add(url);
    }
}
