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
            store.save(parts[0].getOriginatingAddress(), body.toString(), parts[0].getTimestampMillis(), "实时接收");
        }
        UploadJobService.schedule(context);
        context.sendBroadcast(new Intent(SmsStore.CHANGED).setPackage(context.getPackageName()));
    }
}
