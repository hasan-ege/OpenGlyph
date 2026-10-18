package com.nothing.thirdparty.matrix.toys.manager;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * The SDK README tells toy apps to open this activity to send users to the toy manager.
 * On OpenGlyph that is Settings > Glyph Matrix.
 */
public final class ToysManagerActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            startActivity(new Intent("org.openglyph.SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
        finish();
    }
}
