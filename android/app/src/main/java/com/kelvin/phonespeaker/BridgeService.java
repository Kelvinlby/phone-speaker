package com.kelvin.phonespeaker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.quicksettings.TileService;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Runs the audio bridge: connects to phone-speakerd through "adb reverse", plays PC audio and
 * sends microphone audio. Keeps retrying while enabled, so unplugging the cable is not fatal.
 * See linux/phone-speakerd.c for the protocol.
 */
public class BridgeService extends Service {
    private static final String TAG = "PhoneSpeaker";
    private static final String ACTION_APPLY = "apply";
    private static final String ACTION_STOP = "stop";
    private static final String CHANNEL = "bridge";
    private static final int NOTIFICATION_ID = 1;

    private static final int PORT = 47100;
    private static final int RATE = 48000;
    private static final int RETRY_MS = 1000;
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int HANDSHAKE_TIMEOUT_MS = 3000;
    private static final int SPEAKER_FRAME = 4;
    private static final int MIC_CHUNK_BYTES = 960;          // 10 ms
    private static final int SPEAKER_START_FRAMES = RATE / 25;   // 40 ms
    private static final int SPEAKER_MAX_FRAMES = RATE / 6;      // about 166 ms
    private static final int SPEAKER_STEP_FRAMES = RATE / 100;   // 10 ms
    private static final long UNDERRUN_CHECK_MS = 1000;

    private volatile boolean enabled;
    private volatile Socket socket;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    /** Must be called while this app is visible, as Android 17 audio rules require. */
    static void start(Context context) {
        context.startForegroundService(new Intent(context, BridgeService.class).setAction(ACTION_APPLY));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, BridgeService.class));
    }

    static void requestTileRefresh(Context context) {
        TileService.requestListeningState(context, new ComponentName(context, BridgeTileService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wakeLock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneSpeaker:session");
        wakeLock.setReferenceCounted(false);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), foregroundTypes());
        } catch (RuntimeException e) {
            Log.e(TAG, "Android refused the foreground service", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker == null) {
            enabled = true;
            setStatus(BridgeState.Status.WAITING);
            worker = new Thread(this::runLoop, "bridge");
            worker.start();
        } else {
            closeSocket(); // Settings changed: the loop reconnects and applies them.
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        enabled = false;
        closeSocket();
        if (worker != null) worker.interrupt();
        if (wakeLock.isHeld()) wakeLock.release();
        setStatus(BridgeState.Status.OFF);
        super.onDestroy();
    }

    private int foregroundTypes() {
        int types = 0;
        if (Prefs.speaker(this) || !Prefs.micUsable(this)) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
        }
        if (Prefs.micUsable(this)) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        return types;
    }

    private void setStatus(BridgeState.Status status) {
        BridgeState.setStatus(status);
        requestTileRefresh(this);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BridgeService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        boolean connected = BridgeState.status() == BridgeState.Status.CONNECTED;
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(getString(connected ? R.string.status_connected : R.string.status_waiting))
                .setContentText(getString(connected ? R.string.notification_connected : R.string.notification_waiting))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.action_stop), stop).build())
                .build();
    }

    private void refreshNotification() {
        if (enabled) getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    private void closeSocket() {
        Socket current = socket;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    // ------------------------------------------------------------------ connection loop

    private void runLoop() {
        while (enabled) {
            try (Socket connection = new Socket()) {
                socket = connection;
                session(connection);
            } catch (IOException e) {
                Log.d(TAG, "Not connected: " + e.getMessage());
            } finally {
                socket = null;
                if (wakeLock.isHeld()) wakeLock.release();
            }
            if (!enabled) break;
            if (BridgeState.status() != BridgeState.Status.WAITING) {
                setStatus(BridgeState.Status.WAITING);
                refreshNotification();
            }
            SystemClock.sleep(RETRY_MS);
        }
    }

    private void session(Socket connection) throws IOException {
        boolean speaker = Prefs.speaker(this);
        boolean mic = Prefs.micUsable(this);
        boolean callMode = Prefs.callMode(this);

        connection.setTcpNoDelay(true);
        connection.connect(new InetSocketAddress("127.0.0.1", PORT), CONNECT_TIMEOUT_MS);
        connection.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        OutputStream out = connection.getOutputStream();
        InputStream in = connection.getInputStream();
        out.write(new byte[] {'P', 'S', 'P', 'K', 1, (byte) ((speaker ? 1 : 0) | (mic ? 2 : 0))});
        out.flush();
        byte[] ack = new byte[4];
        for (int got = 0; got < ack.length; ) {
            int n = in.read(ack, got, ack.length - got);
            if (n < 0) throw new IOException("PC bridge is not running");
            got += n;
        }
        if (ack[0] != 'P' || ack[1] != 'S' || ack[2] != 'O' || ack[3] != 'K') {
            throw new IOException("Unexpected reply from the PC");
        }
        connection.setSoTimeout(0);

        wakeLock.acquire();
        setStatus(BridgeState.Status.CONNECTED);
        refreshNotification();

        AudioManager audio = getSystemService(AudioManager.class);
        if (callMode) enterCallMode(audio);
        AudioTrack track = speaker ? createTrack(callMode) : null;
        Thread micThread = null;
        try {
            if (track != null) track.play();
            if (mic) {
                micThread = new Thread(() -> pumpMic(callMode, connection, out), "mic");
                micThread.start();
            }
            pumpSpeaker(in, track);
        } finally {
            connection.close();
            if (micThread != null) {
                try {
                    micThread.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (track != null) track.release();
            if (callMode) leaveCallMode(audio);
            BridgeState.speakerLevel = 0;
            BridgeState.micLevel = 0;
        }
    }

    // ------------------------------------------------------------------ speaker

    private AudioTrack createTrack(boolean callMode) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(callMode ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA)
                .setContentType(callMode ? AudioAttributes.CONTENT_TYPE_SPEECH : AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        int minBytes = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(minBytes, SPEAKER_MAX_FRAMES * SPEAKER_FRAME))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.setBufferSizeInFrames(SPEAKER_START_FRAMES);
        return track;
    }

    /**
     * Reads until the connection ends. With the speaker off this just waits for the end. Writes
     * never block: audio that does not fit is dropped, so a slow phone cannot build up delay.
     */
    private void pumpSpeaker(InputStream in, AudioTrack track) throws IOException {
        byte[] buffer = new byte[SPEAKER_MAX_FRAMES * SPEAKER_FRAME];
        int held = 0;
        int lastUnderruns = 0;
        long nextCheck = SystemClock.elapsedRealtime() + UNDERRUN_CHECK_MS;
        while (true) {
            int n = in.read(buffer, held, buffer.length - held);
            if (n < 0) return;
            if (track == null) continue;
            int total = held + n;
            int whole = total - total % SPEAKER_FRAME;
            if (whole > 0) {
                track.write(buffer, 0, whole, AudioTrack.WRITE_NON_BLOCKING);
                BridgeState.speakerLevel = peak(buffer, whole);
            }
            held = total - whole;
            if (held > 0) System.arraycopy(buffer, whole, buffer, 0, held);

            long now = SystemClock.elapsedRealtime();
            if (now >= nextCheck) {
                nextCheck = now + UNDERRUN_CHECK_MS;
                int underruns = track.getUnderrunCount();
                if (underruns > lastUnderruns && track.getBufferSizeInFrames() < SPEAKER_MAX_FRAMES) {
                    track.setBufferSizeInFrames(track.getBufferSizeInFrames() + SPEAKER_STEP_FRAMES);
                }
                lastUnderruns = underruns;
            }
        }
    }

    // ------------------------------------------------------------------ microphone

    @SuppressWarnings("MissingPermission") // Callers check Prefs.micUsable.
    private void pumpMic(boolean callMode, Socket connection, OutputStream out) {
        int source = callMode ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.MIC;
        int minBytes = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record = null;
        try {
            record = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBytes, MIC_CHUNK_BYTES * 8));
            record.startRecording();
            byte[] buffer = new byte[MIC_CHUNK_BYTES];
            while (enabled && !connection.isClosed()) {
                int n = record.read(buffer, 0, buffer.length);
                if (n < 0) {
                    Log.e(TAG, "Microphone read failed: " + n);
                    return;
                }
                out.write(buffer, 0, n);
                BridgeState.micLevel = peak(buffer, n);
            }
        } catch (IOException e) {
            Log.d(TAG, "Mic stream ended: " + e.getMessage());
        } catch (RuntimeException e) {
            Log.e(TAG, "Microphone unavailable", e);
        } finally {
            if (record != null) record.release();
            BridgeState.micLevel = 0;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void enterCallMode(AudioManager audio) {
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices()) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    audio.setCommunicationDevice(device);
                    break;
                }
            }
        } else {
            audio.setSpeakerphoneOn(true);
        }
    }

    private static void leaveCallMode(AudioManager audio) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice();
        } else {
            audio.setSpeakerphoneOn(false);
        }
        audio.setMode(AudioManager.MODE_NORMAL);
    }

    /** Peak of 16-bit little-endian samples, 0..1, checking every 8th sample to save CPU. */
    private static float peak(byte[] data, int length) {
        int max = 0;
        for (int i = 0; i + 1 < length; i += 16) {
            int sample = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
            max = Math.max(max, Math.abs(sample));
        }
        return max / 32768f;
    }
}
