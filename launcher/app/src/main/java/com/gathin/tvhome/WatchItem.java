package com.gathin.tvhome;

/** A Continue watching card: a title in an app, a link into the app, or the app alone when title is null. */
final class WatchItem {
    final String pkg;
    /** Watch Next content_id, else internal_provider_id; null when the app sets neither. */
    final String id;
    final String title;
    final String subtitle;
    /** A file path or a remote URI; null when there is no picture. */
    final String artwork;
    final String deepLink;
    final long lastWatched;
    /** Fraction watched, 0..1; -1 when unknown. */
    final float progress;

    WatchItem(String pkg, String title, String subtitle, String artwork, String deepLink, long lastWatched) {
        this(pkg, title, subtitle, artwork, deepLink, lastWatched, -1f);
    }

    WatchItem(String pkg, String title, String subtitle, String artwork, String deepLink, long lastWatched,
              float progress) {
        this(pkg, null, title, subtitle, artwork, deepLink, lastWatched, progress);
    }

    WatchItem(String pkg, String id, String title, String subtitle, String artwork, String deepLink, long lastWatched,
              float progress) {
        this.pkg = pkg;
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.artwork = artwork;
        this.deepLink = deepLink;
        this.lastWatched = lastWatched;
        this.progress = progress;
    }
}
