package org.openglyph;

import android.content.ContentResolver;
import android.provider.Settings;

/**
 * Essential Key actions and their settings. The device KeyHandler (system_server) reads the
 * same Settings.Secure keys and runs the action; keep the ids in step with it.
 */
final class EssentialKey {
    static final String ACTION = "essential_key_action";
    static final String CAMERA = "essential_key_camera";   // photo | selfie | video
    static final String SILENT = "essential_key_silent";   // silent | vibrate
    static final String APP = "essential_key_app";         // flattened ComponentName
    static final String TRIGGER = "essential_key_trigger"; // hold | press
    static final String TRIGGER_HOLD = "hold", TRIGGER_PRESS = "press";
    static final String DEFAULT_ACTION = "torch";

    static final class Action {
        final String id;
        final int icon, title, desc;
        final String optionKey;
        final String[] optionValues;
        final int[] optionLabels;
        final boolean needsApp;

        Action(String id, int icon, int title, int desc) {
            this(id, icon, title, desc, null, null, null, false);
        }

        Action(String id, int icon, int title, int desc, String optionKey, String[] values,
                int[] labels, boolean needsApp) {
            this.id = id;
            this.icon = icon;
            this.title = title;
            this.desc = desc;
            this.optionKey = optionKey;
            this.optionValues = values;
            this.optionLabels = labels;
            this.needsApp = needsApp;
        }
    }

    static final Action[] ACTIONS = {
        new Action("torch", R.drawable.ic_ek_torch, R.string.ek_torch, R.string.ek_torch_desc),
        new Action("silent", R.drawable.ic_ek_silent, R.string.ek_silent, R.string.ek_silent_desc,
                SILENT, new String[] {"vibrate", "silent"},
                new int[] {R.string.ek_silent_vibrate, R.string.ek_silent_silent}, false),
        new Action("dnd", R.drawable.ic_ek_dnd, R.string.ek_dnd, R.string.ek_dnd_desc),
        new Action("camera", R.drawable.ic_ek_camera, R.string.ek_camera, R.string.ek_camera_desc,
                CAMERA, new String[] {"photo", "selfie", "video"},
                new int[] {R.string.ek_camera_photo, R.string.ek_camera_selfie, R.string.ek_camera_video}, false),
        new Action("recorder", R.drawable.ic_ek_mic, R.string.ek_recorder, R.string.ek_recorder_desc),
        new Action("screenshot", R.drawable.ic_ek_screenshot, R.string.ek_screenshot, R.string.ek_screenshot_desc),
        new Action("glyph", R.drawable.ic_ek_glyph, R.string.ek_glyph, R.string.ek_glyph_desc),
        new Action("media", R.drawable.ic_ek_play, R.string.ek_media, R.string.ek_media_desc),
        new Action("app", R.drawable.ic_ek_app, R.string.ek_app, R.string.ek_app_desc, null, null, null, true),
        new Action("none", R.drawable.ic_ek_none, R.string.ek_none, R.string.ek_none_desc),
    };

    private EssentialKey() {}

    static String action(ContentResolver cr) {
        String v = Settings.Secure.getString(cr, ACTION);
        return v == null ? DEFAULT_ACTION : v;
    }

    static int indexOf(String id) {
        for (int i = 0; i < ACTIONS.length; i++) if (ACTIONS[i].id.equals(id)) return i;
        return 0;
    }
}
