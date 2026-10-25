package bot.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * adb-only settings (the receiver requires DUMP, which the shell has and apps do not):
 *   am broadcast -a bot.tvhome.CONFIG -n bot.tvhome/.Config --es wifiPassword '...' [--es wifiSecurity WPA|WEP|nopass]
 *   am broadcast -a bot.tvhome.CONFIG -n bot.tvhome/.Config --es background life|fire|fractal|rd|ant|shuffle
 *   am broadcast -a bot.tvhome.CONFIG -n bot.tvhome/.Config --es lifeFirst 52513m.rle
 *   am broadcast -a bot.tvhome.CONFIG -n bot.tvhome/.Config --es lifeVerify 1|0
 * (all take effect the next time Home starts; lifeFirst only once. lifeVerify reads back a row
 * of Life tiles a frame and checks them against the simulation, logging to LifeVerify; it costs
 * frames, so turn it off after)
 */
public class Config extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        android.content.SharedPreferences.Editor e = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit();
        for (String key : new String[]{"wifiPassword", "wifiSecurity", "background", "lifeFirst", "lifeVerify"}) {
            if (intent.hasExtra(key)) e.putString(key, intent.getStringExtra(key));
        }
        e.apply();
        setResultData("ok");
    }
}
