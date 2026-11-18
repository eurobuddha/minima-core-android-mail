package com.eurobuddha.mail;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.eurobuddha.comms.NodeApi;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One unique periodic job per explicitly enabled public card. Workers never open a private key. */
public final class AddressRenewal extends Worker {
    private final Handler main = new Handler(Looper.getMainLooper());
    private NodeApi node;
    public AddressRenewal(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("address_renewal", Context.MODE_PRIVATE); }
    static boolean enabled(Context c, String key) { return prefs(c).contains(key.toLowerCase(java.util.Locale.ROOT)); }
    static void enable(Context c, MailAddress.Card card) {
        prefs(c).edit().putString(card.key, card.link()).apply();
        Data data = new Data.Builder().putString("key", card.key).build();
        PeriodicWorkRequest job = new PeriodicWorkRequest.Builder(AddressRenewal.class, 4, TimeUnit.HOURS)
                .setInputData(data).build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("mail-card-" + card.key, ExistingPeriodicWorkPolicy.KEEP, job);
    }
    static void disable(Context c, String key) {
        key = key.toLowerCase(java.util.Locale.ROOT);
        prefs(c).edit().remove(key).remove("status-" + key).remove("pending-" + key).apply();
        WorkManager.getInstance(c).cancelUniqueWork("mail-card-" + key);
    }
    @NonNull @Override public Result doWork() {
        Context c = getApplicationContext();
        MailAddress.init(c);
        String key = getInputData().getString("key");
        if (!MailAddress.isKey(key) || !enabled(c,key)) return Result.success();
        MailAddress.Card card = MailAddress.link(prefs(c).getString(key, ""));
        if (card == null || !card.key.equals(key)) return Result.failure();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean started = new AtomicBoolean();
        main.post(() -> {
            if (!active.get() || isStopped()) { finished.countDown(); return; }
            node = new NodeApi(c, (paired, admin) -> {
                if (!active.get() || !started.compareAndSet(false, true)) return;
                if (!paired || !admin) { record(key, "Enable Mail and grant Admin in Core"); finished.countDown(); return; }
                MailBeacon.renew(node, card, () -> active.get() && !isStopped() && enabled(c,key),
                        prefs(c).getBoolean("pending-" + key, false), status -> {
                    record(key, status); finished.countDown();
                });
            });
        });
        try {
            if (!finished.await(8, TimeUnit.MINUTES)) record(key, "Core did not respond");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally {
            active.set(false);
            main.post(() -> { if (node != null) node.onDestroy(); });
        }
        return Result.success(); // next scheduled window retries; never loop on an ambiguous post
    }
    private void record(String key, String status) {
        if (isStopped() || !enabled(getApplicationContext(), key)) return;
        SharedPreferences.Editor edit = prefs(getApplicationContext()).edit().putString("status-" + key, status);
        if (MailBeacon.PENDING_STATUS.equals(status)) edit.putBoolean("pending-" + key, true);
        if (MailBeacon.FRESH_STATUS.equals(status)) edit.remove("pending-" + key);
        edit.apply();
    }
}
