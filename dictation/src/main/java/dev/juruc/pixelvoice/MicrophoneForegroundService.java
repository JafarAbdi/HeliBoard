package dev.juruc.pixelvoice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ResultReceiver;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MicrophoneForegroundService extends Service {
    static final int READY = 1;
    static final int STOP = 2;
    static final int CANCEL = 3;
    static final int LOST = 4;
    private static final String ACQUIRE = "dev.juruc.pixelvoice.ACQUIRE_MICROPHONE";
    private static final String STOP_ACTION = "dev.juruc.pixelvoice.STOP_INLINE";
    private static final String CANCEL_ACTION = "dev.juruc.pixelvoice.CANCEL_INLINE";
    private static final String SESSION = "session";
    private static final String RECEIVER = "receiver";
    private static final String RETIRE = "retire";
    private static final String CHANNEL = "inline-microphone";
    private static final int NOTIFICATION = 1;
    private final Map<String, Entry> tickets = new LinkedHashMap<>();
    private int lastStartId;

    private record Entry(ResultReceiver owner, ResultReceiver retire) {}

    static final class Ticket implements AutoCloseable {
        private ResultReceiver retire;

        @androidx.annotation.RequiresApi(33)
        Ticket(Bundle ready) {
            retire = ready.getParcelable(RETIRE, ResultReceiver.class);
        }

        @Override
        public void close() {
            ResultReceiver receiver = retire;
            retire = null;
            if (receiver != null) {
                receiver.send(0, Bundle.EMPTY);
            }
        }
    }

    @androidx.annotation.RequiresApi(33)
    static void acquire(Context context, String session, ResultReceiver receiver) {
        context.startForegroundService(command(context, ACQUIRE, session).putExtra(RECEIVER, receiver));
    }

    private static Intent command(Context context, String action, String session) {
        return new Intent(context, MicrophoneForegroundService.class)
                .setAction(action).putExtra(SESSION, session)
                .setData(new Uri.Builder().scheme("pixelvoice").authority("microphone")
                        .appendPath(session).appendPath(action).build());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT < 33) {
            stopSelf();
            return;
        }
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, getString(R.string.inline_notification_channel),
                        NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    @androidx.annotation.RequiresApi(33)
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT < 33) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        lastStartId = startId;
        String session = intent == null ? null : intent.getStringExtra(SESSION);
        String action = intent == null ? null : intent.getAction();
        if (session != null && ACQUIRE.equals(action)) {
            ResultReceiver receiver = intent.getParcelableExtra(RECEIVER, ResultReceiver.class);
            if (receiver != null) {
                Entry entry = tickets.get(session);
                boolean newTicket = entry == null;
                if (newTicket) {
                    ResultReceiver retire = new ResultReceiver(new Handler(getMainLooper())) {
                        @Override
                        @androidx.annotation.RequiresApi(33)
                        protected void onReceiveResult(int result, Bundle data) {
                            Entry current = tickets.get(session);
                            if (current != null && current.retire == this) {
                                tickets.remove(session);
                                updateNotification();
                            }
                        }
                    };
                    entry = new Entry(receiver, retire);
                    tickets.put(session, entry);
                }
                if (updateNotification()) {
                    Bundle ready = new Bundle();
                    ready.putParcelable(RETIRE, entry.retire);
                    entry.owner.send(READY, ready);
                } else if (newTicket) {
                    tickets.remove(session, entry);
                    if (tickets.isEmpty()) {
                        updateNotification();
                    }
                }
                return START_NOT_STICKY;
            }
        } else if (session != null) {
            Entry entry = tickets.get(session);
            if (entry != null && (STOP_ACTION.equals(action) || CANCEL_ACTION.equals(action))) {
                entry.owner.send(STOP_ACTION.equals(action) ? STOP : CANCEL, Bundle.EMPTY);
            }
        }
        updateNotification();
        return START_NOT_STICKY;
    }

    @androidx.annotation.RequiresApi(33)
    private boolean updateNotification() {
        if (tickets.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelfResult(lastStartId);
            return false;
        }
        String latest = null;
        for (String id : tickets.keySet()) {
            latest = id;
        }
        try {
            startForeground(NOTIFICATION, notification(latest),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            return true;
        } catch (RuntimeException error) {
            loseTickets();
            return false;
        }
    }

    private void loseTickets() {
        for (Entry entry : new ArrayList<>(tickets.values())) {
            entry.owner.send(LOST, Bundle.EMPTY);
        }
    }

    private Notification notification(String session) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.inline_notification_text))
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop),
                        pending(STOP_ACTION, session)).build())
                .addAction(new Notification.Action.Builder(null, getString(R.string.cancel),
                        pending(CANCEL_ACTION, session)).build())
                .build();
    }

    private PendingIntent pending(String action, String session) {
        return PendingIntent.getService(this, 0, command(this, action, session),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("ticketCount=" + tickets.size() + " ticketIds=" + tickets.keySet()
                + " lastStartId=" + lastStartId);
        if (Build.VERSION.SDK_INT >= 29) {
            writer.println("foregroundType=" + getForegroundServiceType());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        loseTickets();
        tickets.clear();
        super.onDestroy();
    }
}
