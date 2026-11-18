package app.littlemoon.assistant;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

public final class PushApplication extends Application {
    static final String REPLIES = "assistant_replies";

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(REPLIES, "聊天回复", NotificationManager.IMPORTANCE_HIGH);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        PushSetup.restore(this);
    }
}
