package com.thierry.programme;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import java.util.Calendar;

public class ProgrammeWidget extends AppWidgetProvider {

    static final String[] SEANCES = {
        "Récupération",      // Dimanche 0
        "Dos & Gainage",     // Lundi 1
        "Abdos & Lombaires", // Mardi 2
        "Circuit Métabolique", // Mercredi 3
        "Haut du Corps",     // Jeudi 4
        "Circuit Métabolique", // Vendredi 5
        "Full Body + Mobilité" // Samedi 6
    };

    static final String[] EMOJIS = {
        "🌿", "💪", "🎯", "⚡", "🏋️", "⚡", "🌟"
    };

    static final String[] HORAIRES = {
        "Marche douce", "7h15 · 30 min", "7h15 · 30 min",
        "7h15 · 30 min", "7h15 · 30 min", "7h15 · 30 min", "8h30 · 45 min"
    };

    static final int[] BG = {
        R.drawable.widget_bg_green,  // Dim
        R.drawable.widget_bg_indigo, // Lun
        R.drawable.widget_bg_teal,   // Mar
        R.drawable.widget_bg_violet, // Mer
        R.drawable.widget_bg_red,    // Jeu
        R.drawable.widget_bg_violet, // Ven
        R.drawable.widget_bg_yellow  // Sam
    };

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            updateWidget(context, mgr, id);
        }
    }

    static void updateWidget(Context ctx, AppWidgetManager mgr, int id) {
        int jour = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1; // 0=Dim

        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_layout);

        views.setInt(R.id.widget_root, "setBackgroundResource", BG[jour]);
        views.setTextViewText(R.id.widget_emoji, EMOJIS[jour]);
        views.setTextViewText(R.id.widget_seance, SEANCES[jour]);
        views.setTextViewText(R.id.widget_horaire, HORAIRES[jour]);

        // Tap → ouvrir l'appli
        Intent intent = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pi);

        mgr.updateAppWidget(id, views);
    }
}
