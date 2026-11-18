package app.littlemoon.assistant;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public final class AssistantMessagingService extends FirebaseMessagingService {
    @Override public void onRegistered(String installationId) {
        AssistantApi.rememberPushRegistration(this, installationId);
    }

    @Override public void onMessageReceived(RemoteMessage message) {
        Map<String, String> data = message.getData();
        if ("media_volume".equals(data.get("kind"))) { applyMediaVolume(data); return; }
        if ("tool_request".equals(data.get("kind"))) {
            RemoteTermux.accept(this, data, message.getSentTime());
            return;
        }

        String conversation = data.get("conversation_id");
        String messageId = data.get("message_id");
        if (conversation == null || conversation.isEmpty()) return;
        sendBroadcast(new Intent("app.littlemoon.assistant.MESSAGE_READY")
            .setPackage(getPackageName()).putExtra("conversation_id", conversation)
            .putExtra("message_id", messageId));
        if (PushUiState.isVisible(conversation)) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;

        String title = "小月助手", body = "你有一条新消息";
        RemoteMessage.Notification n = message.getNotification();
        if (n != null) {
            if (n.getTitle() != null && !n.getTitle().isEmpty()) title = n.getTitle();
            if (n.getBody() != null && !n.getBody().isEmpty()) body = n.getBody();
        }
        Intent open = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(new Uri.Builder().scheme("littlemoon").authority("conversation")
                .appendPath(conversation).build())
            .putExtra("conversation_id", conversation).putExtra("message_id", messageId);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notice = new Notification.Builder(this, PushApplication.REPLIES)
            .setSmallIcon(R.drawable.ic_reply_notice)
            .setContentTitle(title).setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true).setContentIntent(tap).build();
        getSystemService(NotificationManager.class).notify("chat_" + conversation, 0, notice);
    }

    /** Silent data message from the server: {kind: media_volume, percent: "0".."100"} or {kind: media_volume, step: "-3"}. */
    private void applyMediaVolume(Map<String, String> data) {
        try {
            AudioManager audio = getSystemService(AudioManager.class);
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            int target = now;
            String percent = data.get("percent"), step = data.get("step");
            if (percent != null && !percent.isEmpty()) {
                int p = Math.max(0, Math.min(100, Integer.parseInt(percent.trim())));
                target = Math.round(max * p / 100f);
            } else if (step != null && !step.isEmpty()) {
                target = now + Integer.parseInt(step.trim());
            }
            target = Math.max(0, Math.min(max, target));
            int volumeStep = 1;
            String how = "set";
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            int after = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (after != target) {
                how = "set+ui";
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
                after = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            }
            if (after != target) {
                how = "adjust";
                int dir = target > after ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
                for (int i = 0; i < max && after != target; i++) {
                    int before = after;
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
                    after = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                    if (after == before) break;
                    // Some devices represent one press as several volume indices.
                    volumeStep = Math.abs(after - before);
                    if ((dir == AudioManager.ADJUST_RAISE) ? after > target : after < target) break;
                }
            }
            Log.i("LittleMoon", "media volume " + now + " -> " + target + " got " + after + " / " + max + " via " + how);
            if (Math.abs(after - target) > volumeStep) {
                notifyVolumeFailure("媒体音量目前是 " + after + "，目标是 " + target + "，满格为 " + max + "。");
            }
        } catch (RuntimeException error) {
            Log.w("LittleMoon", "media volume failed: " + error.getClass().getSimpleName());
            notifyVolumeFailure("系统未能调节媒体音量。");
        }
    }

    private void notifyVolumeFailure(String body) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        Notification n = new Notification.Builder(this, PushApplication.REPLIES)
            .setSmallIcon(R.drawable.ic_reply_notice).setContentTitle("调音量失败").setContentText(body)
            .setAutoCancel(true).build();
        getSystemService(NotificationManager.class).notify("volume", 0, n);
    }
}
