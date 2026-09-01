package com.thierry.programme;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import java.util.Calendar;
public class ProgrammeWidget extends AppWidgetProvider {
    static final String[] SEANCES = {"Récupération","Dos & Gainage","Abdos & Lombaires","Circuit Métabolique","Haut du Corps","Circuit Métabolique","Full Body + Mobilité"};
    static final String[] EMOJIS = {"🌿","💪","🎯","⚡","🏋️","⚡","🌟"};
    static final String[] HORAIRES = {"Marche douce","7h15 · 30 min","7h15 · 30 min","7h15 · 30 min","7h15 · 30 min","7h15 · 30 min","8h30 · 45 min"};
    static final int[] BG = {R.drawable.widget_bg_green, R.drawable.widget_bg_indigo, R.drawable.widget_bg_teal, R.drawable.widget_bg_violet, R.drawable.widget_bg_red, R.drawable.widget_bg_violet, R.drawable.widget_bg_yellow};
    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) { updateWidget(context, mgr, id); }
    }
    static void updateWidget(Context ctx, AppWidgetManager mgr, int id) {
        int jour = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1;
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_layout);
        views.setInt(R.id.widget_root, "setBackgroundResource", BG[jour]);
        views.setTextViewText(R.id.widget_emoji, EMOJIS[jour]);
        views.setTextViewText(R.id.widget_seance, SEANCES[jour]);
        views.setTextViewText(R.id.widget_horaire, HORAIRES[jour]);
                             Intent intent = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_frame, pi);
        mgr.updateAppWidget(id, views);
    }
}
