package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.view.View;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/** The header search button: the system's voice or text search, the first of a few intents something answers. */
final class HeaderSearch {
    static final String GLOBAL_SEARCH = "android.search.action.GLOBAL_SEARCH";
    /** Google TV's assistant first, then plain search, then the speech and web search fallbacks. */
    static final List<String> ACTIONS = Arrays.asList(Intent.ACTION_ASSIST, GLOBAL_SEARCH,
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH, Intent.ACTION_WEB_SEARCH);

    private HeaderSearch() { }

    static void bind(Activity activity, View button) {
        button.setOnClickListener(v -> start(activity));
    }

    /** The first action that resolves, or null. */
    static String pick(List<String> actions, Predicate<String> resolves) {
        for (String action : actions) if (resolves.test(action)) return action;
        return null;
    }

    private static void start(Activity activity) {
        String action = pick(ACTIONS, a -> new Intent(a).resolveActivity(activity.getPackageManager()) != null);
        try {
            if (action == null) throw new ActivityNotFoundException();
            activity.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
