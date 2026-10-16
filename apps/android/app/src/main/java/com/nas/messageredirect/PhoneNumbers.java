package com.nas.messageredirect;

import android.content.Context;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import java.util.List;

/** Best-effort SIM metadata, read only on an explicit user request. Never infer from IMSI/ICCID. */
final class PhoneNumbers {
    @android.annotation.SuppressLint("MissingPermission")
    static String[] read(Context context) {
        String[] numbers = {"", ""};
        SubscriptionManager manager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (manager == null) return numbers;
        List<SubscriptionInfo> subscriptions = manager.getActiveSubscriptionInfoList();
        if (subscriptions == null) return numbers;
        for (SubscriptionInfo subscription : subscriptions) {
            int slot = subscription.getSimSlotIndex();
            if (slot < 0 || slot > 1) continue;
            String raw;
            if (Build.VERSION.SDK_INT >= 33) raw = manager.getPhoneNumber(subscription.getSubscriptionId());
            else if (Build.VERSION.SDK_INT >= 24) {
                TelephonyManager telephony = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                raw = telephony == null ? "" : telephony.createForSubscriptionId(subscription.getSubscriptionId()).getLine1Number();
            } else raw = subscription.getNumber();
            numbers[slot] = clean(raw);
        }
        return numbers;
    }
    static String clean(String raw) {
        if (raw == null) return "";
        try { return SyncConfig.normalizePhone(raw); } catch (IllegalArgumentException error) { return ""; }
    }
}
