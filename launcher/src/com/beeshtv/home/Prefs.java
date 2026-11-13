package com.beeshtv.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Launcher settings, changed from the Settings panel on the TV. */
final class Prefs {

    /** World clock places: name, time zone, latitude, longitude, country code (for the flag). */
    static final String[][] CITIES = {
            {"Nepal", "Asia/Kathmandu", "27.72", "85.32", "NP"},
            {"Delhi", "Asia/Kolkata", "28.61", "77.21", "IN"},
            {"Dallas", "America/Chicago", "32.78", "-96.80", "US"},
            {"Melbourne", "Australia/Melbourne", "-37.81", "144.96", "AU"},
            {"Sydney", "Australia/Sydney", "-33.87", "151.21", "AU"},
            {"Brisbane", "Australia/Brisbane", "-27.47", "153.03", "AU"},
            {"Adelaide", "Australia/Adelaide", "-34.93", "138.60", "AU"},
            {"Perth", "Australia/Perth", "-31.95", "115.86", "AU"},
            {"Auckland", "Pacific/Auckland", "-36.85", "174.76", "NZ"},
            {"Tokyo", "Asia/Tokyo", "35.68", "139.69", "JP"},
            {"Singapore", "Asia/Singapore", "1.35", "103.82", "SG"},
            {"Dubai", "Asia/Dubai", "25.20", "55.27", "AE"},
            {"London", "Europe/London", "51.51", "-0.13", "GB"},
            {"Paris", "Europe/Paris", "48.86", "2.35", "FR"},
            {"New York", "America/New_York", "40.71", "-74.01", "US"},
            {"Los Angeles", "America/Los_Angeles", "34.05", "-118.24", "US"},
            {"Toronto", "America/Toronto", "43.65", "-79.38", "CA"},
    };
    /** The one world clock, top left; it can be "Off". */
    static final String OFF = "Off";
    static final String[] DEFAULT_CITIES = {"Nepal"};

    /** How Home lays out its apps; see MainActivity's layouts section. */
    static final String[] LAYOUTS = {"Tiles", "Pills", "Rows", "Metro", "Dock"};
    static final int TILES = 0, PILLS = 1, ROWS = 2, METRO = 3, DOCK = 4;
    static final String[] SIZES = {"Tiny", "Small", "Medium", "Large"};
    /** Space between tiles in the Tiles layout, across and down, in dp. */
    static final int[] TILE_GAPS = {0, 5, 10, 15, 20, 30};
    /** How blurred the section background is; the blurred photo covers the real one by this much. */
    static final String[] BLUR_LEVELS = {"Light", "Medium", "Hard"};
    static final int[] BLUR_ALPHA = {110, 190, 255};
    static final int[] WALL_MINUTES = {5, 10, 15, 30, 60};

    static final String[] WALL_STYLES = {"Landscapes", "Cityscapes", "Seasons"};

    /** Your apps, in order; apps that aren't installed are skipped. Everything else is in Other apps. */
    static final String DEFAULT_FAVOURITES = String.join(",",
            "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.apple.atve.androidtv.appletv",
            "com.google.android.youtube.tv", "org.smarttube.beta", "tv.standard.nebula", "au.com.kayosports.tv",
            "com.crunchyroll.crunchyroid", "au.com.tenplay", "com.swm.live", "au.com.ninenow.ctv",
            "au.net.abc.iview", "com.sbs.ondemand.tv", "au.com.stan.and", "com.disney.disneyplus",
            "com.spotify.tv.android", "com.plexapp.android", "org.jellyfin.androidtv");

    /** Never listed: system helpers, Settings (top bar), Live TV (Inputs), other launchers. */
    static final List<String> HIDDEN = Arrays.asList(
            "com.tcl.tv", "com.tcl.suspension", "com.tcl.dashboard", "com.android.tv.settings",
            "com.spocky.projengmenu", "com.wolf.google.lm");

    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getSharedPreferences("home", Context.MODE_PRIVATE); }

    SharedPreferences raw() { return sp; }

    /** A soft block behind each section's tiles. */
    // Icon size, section background and blur are kept per layout, so each one comes back as you left it.

    /** "icon_size.tiles", "icon_size.metro"… for the current layout. */
    private String perLayout(String key) { return key + "." + LAYOUTS[layout()].toLowerCase(java.util.Locale.ROOT); }

    boolean sectionWrap() { return sp.getBoolean(perLayout("section_wrap"), false); }
    void setSectionWrap(boolean v) { sp.edit().putBoolean(perLayout("section_wrap"), v).apply(); }

    int blurLevel() { return Math.floorMod(sp.getInt(perLayout("blur_level"), 1), BLUR_LEVELS.length); }
    void setBlurLevel(int v) { sp.edit().putInt(perLayout("blur_level"), Math.floorMod(v, BLUR_LEVELS.length)).apply(); }

    int layout() { return Math.floorMod(sp.getInt("layout", TILES), LAYOUTS.length); }
    void setLayout(int v) { sp.edit().putInt("layout", Math.floorMod(v, LAYOUTS.length)).apply(); }

    /**
     * 0 tiny, 1 small, 2 medium, 3 large, for the current layout. Saved as "size.<layout>"; sizes saved
     * before Tiny existed ("icon_size.<layout>", 0 small … 2 large) move up one so they stay the same.
     */
    int iconSize() {
        String key = perLayout("size");
        if (!sp.contains(key) && sp.contains(perLayout("icon_size"))) return sp.getInt(perLayout("icon_size"), 1) + 1;
        return Math.floorMod(sp.getInt(key, 2), SIZES.length);
    }
    void setIconSize(int v) { sp.edit().putInt(perLayout("size"), Math.floorMod(v, SIZES.length)).apply(); }

    /** Size of the icons in the Dock itself; Icon size is for the tiles under it. */
    int dockSize() { return Math.floorMod(sp.getInt("dock_size", 2), SIZES.length); }
    void setDockSize(int v) { sp.edit().putInt("dock_size", Math.floorMod(v, SIZES.length)).apply(); }

    /** Whether this layout has section backgrounds at all (Metro's tiles touch; Dock has its bar). */
    boolean hasSectionBackground() { return layout() != METRO && layout() != DOCK; }

    int tileGapX() { return sp.getInt("tile_gap_x", 5); }
    void setTileGapX(int v) { sp.edit().putInt("tile_gap_x", v).apply(); }
    int tileGapY() { return sp.getInt("tile_gap_y", 5); }
    void setTileGapY(int v) { sp.edit().putInt("tile_gap_y", v).apply(); }

    int wallStyle() { return sp.getInt("wall_style", 0); }
    void setWallStyle(int v) { sp.edit().putInt("wall_style", Math.floorMod(v, WALL_STYLES.length)).apply(); }

    int wallMinutes() { return sp.getInt("wall_minutes", 10); }
    void setWallMinutes(int v) { sp.edit().putInt("wall_minutes", v).apply(); }

    /** Photos change by themselves (on the interval and with the time of day). Off keeps one photo. */
    boolean wallShuffle() { return sp.getBoolean("wall_shuffle", true); }
    void setWallShuffle(boolean v) { sp.edit().putBoolean("wall_shuffle", v).apply(); }

    /** The photo kept while shuffle is off. */
    String wallPinned() { return sp.getString("wall_pinned", null); }
    void setWallPinned(String key) { sp.edit().putString("wall_pinned", key).apply(); }

    /** The world clock's city (slot 0), or null when it's off. */
    String[] city(int slot) {
        String name = sp.getString("wc" + slot, DEFAULT_CITIES[slot]);
        for (String[] c : CITIES) if (c[0].equals(name)) return c;
        return null;
    }

    String cityName(int slot) {
        String[] c = city(slot);
        return c == null ? OFF : c[0];
    }

    void stepCity(int slot, int dir) {
        List<String> names = new ArrayList<>();
        names.add(OFF);
        for (String[] c : CITIES) names.add(c[0]);
        int i = Math.max(0, names.indexOf(cityName(slot)));
        sp.edit().putString("wc" + slot, names.get(Math.floorMod(i + dir, names.size()))).apply();
    }

    /** A name you gave an input on the launcher, or null to use the TV's own. */
    String inputName(String id) { return sp.getString("in_" + id, null); }

    void setInputName(String id, String name) {
        if (name == null || name.trim().isEmpty()) sp.edit().remove("in_" + id).apply();
        else sp.edit().putString("in_" + id, name.trim()).apply();
    }

    List<String> favourites() {
        String s = sp.getString("favourites", DEFAULT_FAVOURITES);
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    void setFavourites(List<String> pkgs) { sp.edit().putString("favourites", String.join(",", pkgs)).apply(); }

    static int nextOf(int[] options, int current, int dir) {
        int i = 0;
        for (int k = 0; k < options.length; k++) if (options[k] == current) i = k;
        return options[Math.floorMod(i + dir, options.length)];
    }

    /** Country code to flag emoji (regional indicator pair). */
    static String flag(String cc) {
        if (cc == null || cc.length() != 2) return "";
        cc = cc.toUpperCase(java.util.Locale.ROOT);
        return new String(Character.toChars(0x1F1E6 + cc.charAt(0) - 'A'))
                + new String(Character.toChars(0x1F1E6 + cc.charAt(1) - 'A'));
    }
}
