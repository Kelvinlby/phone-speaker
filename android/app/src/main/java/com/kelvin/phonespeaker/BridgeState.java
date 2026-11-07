package com.kelvin.phonespeaker;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-process state shared by the service, the activity and the tile. */
final class BridgeState {
    enum Status { OFF, WAITING, CONNECTED }

    interface Listener {
        void onBridgeChanged();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Status status = Status.OFF;

    /** Most recent peak levels, 0..1, for the activity's meters. */
    static volatile float speakerLevel;
    static volatile float micLevel;

    private BridgeState() {}

    static Status status() {
        return status;
    }

    static void setStatus(Status next) {
        if (status == next) return;
        status = next;
        if (next != Status.CONNECTED) {
            speakerLevel = 0;
            micLevel = 0;
        }
        MAIN.post(() -> {
            for (Listener listener : LISTENERS) listener.onBridgeChanged();
        });
    }

    static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }
}
