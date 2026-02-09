package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.content.SharedPreferences;
public final class CampaignStore {

    private static final String PREF = "positionme_campaign";
    private static final String KEY = "campaign";

    private CampaignStore() {}

    public static void set(Context ctx, String campaign) {
        if (ctx == null) return;
        if (campaign == null) campaign = "";
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putString(KEY, campaign).apply();
    }

    public static String get(Context ctx) {
        if (ctx == null) return "";
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return sp.getString(KEY, "");
    }

    public static void clear(Context ctx) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().remove(KEY).apply();
    }
}
