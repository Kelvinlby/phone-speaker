package com.kelvin.phonespeaker;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** The Quick Settings button: one tap turns the bridge on or off. */
public class BridgeTileService extends TileService {
    private final BridgeState.Listener listener = this::render;

    @Override
    public void onStartListening() {
        BridgeState.addListener(listener);
        render();
    }

    @Override
    public void onStopListening() {
        BridgeState.removeListener(listener);
    }

    @Override
    public void onTileAdded() {
        Prefs.set(this, Prefs.TILE_ADDED, true);
    }

    @Override
    public void onTileRemoved() {
        Prefs.set(this, Prefs.TILE_ADDED, false);
    }

    @Override
    public void onClick() {
        if (BridgeState.status() != BridgeState.Status.OFF) {
            BridgeService.stop(this);
        } else if (isLocked()) {
            unlockAndRun(this::launchStarter);
        } else {
            launchStarter();
        }
    }

    /** Audio must start from a visible activity on Android 17, even when triggered by a tile. */
    @SuppressWarnings("deprecation") // The Intent overload is the only option below Android 14.
    private void launchStarter() {
        Intent intent = new Intent(this, TileStartActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(intent);
        }
    }

    private void render() {
        Tile tile = getQsTile();
        if (tile == null) return;
        BridgeState.Status status = BridgeState.status();
        tile.setState(status == BridgeState.Status.OFF ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        tile.setSubtitle(getString(switch (status) {
            case OFF -> R.string.status_off;
            case WAITING -> R.string.status_waiting;
            case CONNECTED -> R.string.status_connected;
        }));
        tile.updateTile();
    }
}
