package com.gathin.tvhome;

import android.app.Activity;
import android.app.PendingIntent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** The header bell: a count while notifications are waiting, and a glass list to open, clear or dismiss them. */
final class NotificationBell implements Notifications.Listener {
    private final Activity activity;
    private final TextView bell;
    private final float density;
    private PopupWindow popup;

    NotificationBell(Activity activity, TextView bell) {
        this.activity = activity;
        this.bell = bell;
        this.density = activity.getResources().getDisplayMetrics().density;
        bell.setOnClickListener(v -> show());
    }

    void start() {
        Notifications.addListener(this);
        onNotificationsChanged();
    }

    void stop() {
        Notifications.removeListener(this);
        if (popup != null) popup.dismiss();
    }

    @Override
    public void onNotificationsChanged() {
        int count = Notifications.items().size();
        String badge = Notifications.badge(count);
        boolean counted = !badge.isEmpty();
        bell.setCompoundDrawablePadding(counted ? (int) (6 * density) : 0);
        bell.setPaddingRelative((int) (8 * density), 0, (int) ((counted ? 14 : 8) * density), 0);
        bell.setText(badge);
        bell.setContentDescription(counted ? activity.getString(R.string.notifications_count, count)
                : activity.getString(R.string.notifications));
    }

    private void show() {
        List<Notifications.Item> items = Notifications.items();
        if (items.isEmpty()) {
            Toast.makeText(activity, R.string.notifications_none, Toast.LENGTH_SHORT).show();
            return;
        }
        View panel = LayoutInflater.from(activity).inflate(R.layout.popup_notifications, null, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow window = new PopupWindow(panel, (int) (380 * density), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        window.setOutsideTouchable(true);
        window.setAnimationStyle(0);
        window.setElevation(0f);
        window.setOnDismissListener(() -> {
            if (popup == window) popup = null;
        });

        View clear = row(rows, activity.getString(R.string.notifications_clear), "", "");
        clear.setOnClickListener(v -> {
            window.dismiss();
            Notifications.dismissAll();
        });
        for (Notifications.Item item : items) {
            String when = DateUtils.getRelativeTimeSpanString(item.time, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS).toString();
            View r = row(rows, item.title.isEmpty() ? item.app : item.title, item.text, item.app + " · " + when);
            r.setOnClickListener(v -> {
                window.dismiss();
                send(item.contentIntent);
                Notifications.dismiss(item.key);
            });
        }

        // Taller lists scroll: keep the panel within the screen's title-safe height.
        panel.measure(View.MeasureSpec.makeMeasureSpec((int) (380 * density), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        int room = activity.getResources().getDisplayMetrics().heightPixels - (int) (150 * density);
        window.setHeight(Math.min(panel.getMeasuredHeight(), room));
        // Header buttons sit at the right of the screen (the left in Hebrew): hang the list inward from the bell.
        int gravity = activity.getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.START : Gravity.END;
        window.showAsDropDown(bell, 0, (int) (10 * density), gravity);
        popup = window;
        rows.getChildAt(1).requestFocus();
    }

    private View row(LinearLayout rows, String title, String text, String meta) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_notification, rows, false);
        setLine(row.findViewById(R.id.title), title);
        setLine(row.findViewById(R.id.text), text);
        setLine(row.findViewById(R.id.meta), meta);
        rows.addView(row);
        return row;
    }

    private static void setLine(TextView view, String value) {
        view.setText(value);
        view.setVisibility(TextUtils.isEmpty(value) ? View.GONE : View.VISIBLE);
    }

    private void send(PendingIntent intent) {
        if (intent == null) return;
        try {
            intent.send();
        } catch (PendingIntent.CanceledException gone) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
