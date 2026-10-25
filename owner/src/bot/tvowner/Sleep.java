package bot.tvowner;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Turns the screen off for the launcher's idle timer. Only apps signed like this one may send it. */
public class Sleep extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        DevicePolicyManager dpm = ctx.getSystemService(DevicePolicyManager.class);
        try {
            dpm.lockNow();
        } catch (SecurityException e) {
            android.util.Log.w("TvOwner", "lockNow refused", e);
        }
    }
}
