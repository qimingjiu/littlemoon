package app.littlemoon.assistant;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;

/** Sends result records when Android grants network access, even without a chat Activity. */
public final class RemoteTermuxUploadJob extends JobService {
    private JobParameters running;

    @Override public boolean onStartJob(JobParameters params) {
        running = params;
        // Return to the scheduler before calling jobFinished, including when the queue is empty.
        new Handler(Looper.getMainLooper()).post(() -> sendNext(params));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (running == params) running = null;
        return true;
    }

    private void sendNext(JobParameters params) {
        if (running != params) return;
        JSONObject entry = RemoteTermux.nextResult(this);
        if (entry == null) {
            running = null;
            jobFinished(params, false);
            RemoteTermux.schedule(this);
            return;
        }
        AssistantApi api = new AssistantApi(this);
        api.uploadRemoteTermuxResult(entry, retry -> {
            if (!retry) RemoteTermux.delivered(this, entry.optString("execution"));
            if (running != params) return;
            if (retry) {
                running = null;
                jobFinished(params, true);
            } else {
                sendNext(params);
            }
        });
    }
}
