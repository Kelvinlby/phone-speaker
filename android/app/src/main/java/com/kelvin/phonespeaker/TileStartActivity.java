package com.kelvin.phonespeaker;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Invisible activity that starts the bridge with the rights only a visible app is given. */
public class TileStartActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean micNeedsAsking = Prefs.mic(this) && !Prefs.hasMicPermission(this);
        boolean nothingToDo = !Prefs.speaker(this) && !Prefs.mic(this);
        if (micNeedsAsking || nothingToDo) {
            // These need a decision from the user, which only the main screen can ask for.
            startActivity(new Intent(this, MainActivity.class));
        } else {
            BridgeService.start(this);
        }
        finish();
    }
}
