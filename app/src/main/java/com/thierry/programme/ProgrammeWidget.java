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

    static final int[] COULEURS = {
        0xFF66BB6A, // Dim — vert
        0xFF5C6BC0, // Lun — indigo
        0xFF26A69A, // Mar — teal
        0xFF7E57C2, // Mer — violet
        0xFFEF5350, // Jeu — rouge
        0xFF7E57C2, // Ven — violet
        0xFFF9A825  // Sam — jaune
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

        views.setInt(R.id.widget_bg, "setBackgroundColor", COULEURS[jour]);
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
