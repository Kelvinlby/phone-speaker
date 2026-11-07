package com.kelvin.phonespeaker;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.res.ColorStateList;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.kelvin.phonespeaker.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final long METER_INTERVAL_MS = 66;
    private static final long COLOR_ANIMATION_MS = 320;
    private static final long PULSE_MS = 1800;

    private ActivityMainBinding ui;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BridgeState.Listener stateListener = this::render;
    private final Runnable meterTick = new Runnable() {
        @Override
        public void run() {
            setMeter(ui.speakerMeter, BridgeState.speakerLevel);
            setMeter(ui.micMeter, BridgeState.micLevel);
            handler.postDelayed(this, METER_INTERVAL_MS);
        }
    };

    private ValueAnimator pulse;
    private ValueAnimator heroColor;
    private int shownHeroColor;
    private Runnable afterPermissions;

    private final ActivityResultLauncher<String[]> permissionRequest = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionsResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        ui = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(ui.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            ui.content.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        shownHeroColor = MaterialColors.getColor(ui.hero, com.google.android.material.R.attr.colorSurfaceContainerHigh);
        ui.power.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            togglePower();
        });
        ui.speakerSwitch.setChecked(Prefs.speaker(this));
        ui.micSwitch.setChecked(Prefs.micUsable(this) || (Prefs.mic(this) && !hasAskedForMic()));
        ui.callSwitch.setChecked(Prefs.callMode(this));
        bindRow(ui.speakerRow, ui.speakerSwitch);
        bindRow(ui.micRow, ui.micSwitch);
        bindRow(ui.callRow, ui.callSwitch);
        ui.speakerSwitch.setOnCheckedChangeListener((view, on) -> changeSetting(Prefs.SPEAKER, on));
        ui.micSwitch.setOnCheckedChangeListener((view, on) -> changeMic(on));
        ui.callSwitch.setOnCheckedChangeListener((view, on) -> changeSetting(Prefs.CALL_MODE, on));
        ui.addTile.setOnClickListener(view -> requestAddTile());
    }

    private boolean hasAskedForMic() {
        return Prefs.get(this, "asked_mic", false);
    }

    private static void bindRow(View row, MaterialSwitch toggle) {
        row.setOnClickListener(view -> toggle.toggle());
    }

    @Override
    protected void onStart() {
        super.onStart();
        BridgeState.addListener(stateListener);
        render();
        handler.post(meterTick);
    }

    @Override
    protected void onStop() {
        BridgeState.removeListener(stateListener);
        handler.removeCallbacks(meterTick);
        stopPulse();
        super.onStop();
    }

    // ------------------------------------------------------------------ actions

    private void togglePower() {
        if (BridgeState.status() != BridgeState.Status.OFF) {
            BridgeService.stop(this);
            return;
        }
        if (!Prefs.speaker(this) && !ui.micSwitch.isChecked()) {
            Snackbar.make(ui.getRoot(), R.string.need_one_route, Snackbar.LENGTH_SHORT).show();
            return;
        }
        requestPermissionsThen(() -> BridgeService.start(this));
    }

    private void requestPermissionsThen(Runnable next) {
        List<String> missing = new ArrayList<>();
        if (ui.micSwitch.isChecked() && !Prefs.hasMicPermission(this)) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !Prefs.get(this, "asked_notifications", false)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (missing.isEmpty()) {
            next.run();
            return;
        }
        afterPermissions = next;
        permissionRequest.launch(missing.toArray(new String[0]));
    }

    private void onPermissionsResult(Map<String, Boolean> results) {
        if (results.containsKey(Manifest.permission.POST_NOTIFICATIONS)) {
            Prefs.set(this, "asked_notifications", true);
        }
        if (results.containsKey(Manifest.permission.RECORD_AUDIO)) {
            Prefs.set(this, "asked_mic", true);
            if (!Boolean.TRUE.equals(results.get(Manifest.permission.RECORD_AUDIO))) {
                ui.micSwitch.setChecked(false);
                Prefs.set(this, Prefs.MIC, false);
                Snackbar.make(ui.getRoot(), R.string.mic_denied, Snackbar.LENGTH_LONG).show();
            } else {
                Prefs.set(this, Prefs.MIC, true);
            }
        }
        Runnable next = afterPermissions;
        afterPermissions = null;
        if (next != null && (Prefs.speaker(this) || Prefs.micUsable(this))) next.run();
    }

    private void changeSetting(String key, boolean on) {
        Prefs.set(this, key, on);
        applyWhileRunning();
    }

    private void changeMic(boolean on) {
        Prefs.set(this, Prefs.MIC, on);
        if (on && !Prefs.hasMicPermission(this)) {
            requestPermissionsThen(this::applyWhileRunning);
        } else {
            applyWhileRunning();
        }
    }

    /** Restarts the running session with the new settings, or stops if no route is left. */
    private void applyWhileRunning() {
        if (BridgeState.status() == BridgeState.Status.OFF) return;
        if (Prefs.speaker(this) || Prefs.micUsable(this)) {
            BridgeService.start(this);
        } else {
            BridgeService.stop(this);
        }
    }

    private void requestAddTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        getSystemService(StatusBarManager.class).requestAddTileService(
                new ComponentName(this, BridgeTileService.class),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_tile),
                getMainExecutor(),
                result -> {
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                            || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                        Prefs.set(this, Prefs.TILE_ADDED, true);
                        render();
                    }
                });
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        BridgeState.Status status = BridgeState.status();
        boolean on = status != BridgeState.Status.OFF;
        boolean connected = status == BridgeState.Status.CONNECTED;

        ui.statusTitle.setText(switch (status) {
            case OFF -> R.string.status_off;
            case WAITING -> R.string.status_waiting;
            case CONNECTED -> R.string.status_connected;
        });
        ui.statusDetail.setText(switch (status) {
            case OFF -> R.string.detail_off;
            case WAITING -> R.string.detail_waiting;
            case CONNECTED -> R.string.detail_connected;
        });
        ui.power.setContentDescription(getString(on ? R.string.action_stop : R.string.action_start));

        int heroAttr = connected ? androidx.appcompat.R.attr.colorPrimary
                : on ? com.google.android.material.R.attr.colorSecondaryContainer
                : com.google.android.material.R.attr.colorSurfaceContainerHigh;
        int textAttr = connected ? com.google.android.material.R.attr.colorOnPrimary
                : on ? com.google.android.material.R.attr.colorOnSecondaryContainer
                : com.google.android.material.R.attr.colorOnSurface;
        int buttonAttr = connected ? com.google.android.material.R.attr.colorOnPrimary
                : on ? com.google.android.material.R.attr.colorSecondary
                : androidx.appcompat.R.attr.colorPrimary;
        int iconAttr = connected ? androidx.appcompat.R.attr.colorPrimary
                : on ? com.google.android.material.R.attr.colorOnSecondary
                : com.google.android.material.R.attr.colorOnPrimary;
        animateHero(MaterialColors.getColor(ui.hero, heroAttr));
        int text = MaterialColors.getColor(ui.hero, textAttr);
        ui.statusTitle.setTextColor(text);
        ui.statusDetail.setTextColor(text);
        int button = MaterialColors.getColor(ui.hero, buttonAttr);
        ui.power.setBackgroundTintList(ColorStateList.valueOf(button));
        ui.power.setIconTint(ColorStateList.valueOf(MaterialColors.getColor(ui.hero, iconAttr)));
        ui.powerRing.getBackground().setTint(button);

        if (connected) startPulse(); else stopPulse();

        ui.setupCard.setVisibility(status == BridgeState.Status.WAITING ? View.VISIBLE : View.GONE);
        ui.speakerMeter.setVisibility(connected && Prefs.speaker(this) ? View.VISIBLE : View.GONE);
        ui.micMeter.setVisibility(connected && Prefs.micUsable(this) ? View.VISIBLE : View.GONE);

        boolean canAddTile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !Prefs.get(this, Prefs.TILE_ADDED, false);
        ui.tileCard.setVisibility(canAddTile ? View.VISIBLE : View.GONE);
    }

    private void animateHero(int target) {
        if (heroColor != null) heroColor.cancel();
        heroColor = ValueAnimator.ofObject(new ArgbEvaluator(), shownHeroColor, target);
        heroColor.setDuration(COLOR_ANIMATION_MS);
        heroColor.addUpdateListener(animator -> {
            shownHeroColor = (int) animator.getAnimatedValue();
            ui.hero.setCardBackgroundColor(shownHeroColor);
        });
        heroColor.start();
    }

    private void startPulse() {
        if (pulse != null) return;
        pulse = ObjectAnimator.ofFloat(0f, 1f);
        pulse.setDuration(PULSE_MS);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new LinearInterpolator());
        pulse.addUpdateListener(animator -> {
            float t = (float) animator.getAnimatedValue();
            ui.powerRing.setScaleX(1f + 0.45f * t);
            ui.powerRing.setScaleY(1f + 0.45f * t);
            ui.powerRing.setAlpha(0.35f * (1f - t));
        });
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        ui.powerRing.setAlpha(0f);
    }

    private static void setMeter(com.google.android.material.progressindicator.LinearProgressIndicator meter, float level) {
        if (meter.getVisibility() == View.VISIBLE) {
            meter.setProgressCompat(Math.round(Math.min(1f, level) * 100), true);
        }
    }
}
