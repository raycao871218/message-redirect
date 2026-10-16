package com.nas.messageredirect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

public final class SmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0) return;
        StringBuilder body = new StringBuilder();
        for (SmsMessage part : parts) if (part != null && part.getMessageBody() != null) body.append(part.getMessageBody());
        if (parts[0] == null) return;
        try (SmsStore store = new SmsStore(context)) {
            store.save(parts[0].getOriginatingAddress(), body.toString(), parts[0].getTimestampMillis(), "实时接收", new SyncConfig(context).receivingNumber(receivingSlot(intent)));
        }
        UploadJobService.schedule(context);
        context.sendBroadcast(new Intent(SmsStore.CHANGED).setPackage(context.getPackageName()));
    }
    static int receivingSlot(Intent intent) {
        for (String key : new String[]{"android.telephony.extra.SLOT_INDEX", "slot", "slot_id", "simSlot"}) {
            int slot = intent.getIntExtra(key, -1);
            if (slot == 0 || slot == 1) return slot;
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            int sub = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1));
            if (sub >= 0) {
                try { return android.telephony.SubscriptionManager.getSlotIndex(sub); }
                catch (SecurityException ignored) { /* Leave unknown slots unlabelled rather than guess. */ }
            }
        }
        return -1;
    }
}
