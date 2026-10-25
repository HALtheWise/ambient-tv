package bot.tvowner;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.Arrays;

/**
 * adb shell am broadcast -a bot.tvowner.CMD -n bot.tvowner/.Cmd --es op <op> [--es pkg <a,b,..>] [--es home <pkg/.Activity>]
 *
 * ops: status, hide, unhide, enable-system, suspend, unsuspend, set-home, clear-home
 */
public class Cmd extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        DevicePolicyManager dpm = ctx.getSystemService(DevicePolicyManager.class);
        ComponentName admin = new ComponentName(ctx, Admin.class);
        String op = intent.getStringExtra("op");
        String pkgArg = intent.getStringExtra("pkg");
        String[] pkgs = pkgArg == null ? new String[0] : pkgArg.split(",");
        StringBuilder out = new StringBuilder();

        try {
            if (!dpm.isDeviceOwnerApp(ctx.getPackageName())) {
                out.append("ERROR not device owner");
            } else if ("status".equals(op)) {
                for (String p : pkgs) out.append(status(ctx, dpm, admin, p)).append('\n');
            } else if ("hide".equals(op) || "unhide".equals(op)) {
                boolean hide = "hide".equals(op);
                for (String p : pkgs) {
                    boolean ok = dpm.setApplicationHidden(admin, p, hide);
                    out.append(p).append(' ').append(op).append('=').append(ok).append('\n');
                }
            } else if ("enable-system".equals(op)) {
                for (String p : pkgs) {
                    dpm.enableSystemApp(admin, p);
                    out.append(p).append(" enable-system done\n");
                }
            } else if ("suspend".equals(op) || "unsuspend".equals(op)) {
                String[] failed = dpm.setPackagesSuspended(admin, pkgs, "suspend".equals(op));
                out.append(op).append(" failed=").append(Arrays.toString(failed));
            } else if ("set-home".equals(op)) {
                IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
                f.addCategory(Intent.CATEGORY_HOME);
                f.addCategory(Intent.CATEGORY_DEFAULT);
                ComponentName home = ComponentName.unflattenFromString(intent.getStringExtra("home"));
                dpm.addPersistentPreferredActivity(admin, f, home);
                out.append("home=").append(home.flattenToShortString());
            } else if ("clear-home".equals(op)) {
                for (String p : pkgs) dpm.clearPackagePersistentPreferredActivities(admin, p);
                out.append("cleared ").append(pkgArg);
            } else if ("sleep".equals(op)) {
                dpm.lockNow();
                out.append("sleeping");
            } else {
                out.append("ERROR unknown op ").append(op);
            }
        } catch (Exception e) {
            out.append("ERROR ").append(e);
        }
        setResultCode(1);
        setResultData(out.toString().trim());
    }

    private static String status(Context ctx, DevicePolicyManager dpm, ComponentName admin, String p) {
        PackageManager pm = ctx.getPackageManager();
        StringBuilder s = new StringBuilder(p);
        try {
            ApplicationInfo ai = pm.getApplicationInfo(p, PackageManager.MATCH_UNINSTALLED_PACKAGES
                    | PackageManager.MATCH_DISABLED_COMPONENTS);
            s.append(" installed=").append((ai.flags & ApplicationInfo.FLAG_INSTALLED) != 0)
                    .append(" enabled=").append(ai.enabled)
                    .append(" state=").append(pm.getApplicationEnabledSetting(p));
        } catch (Exception e) {
            s.append(" lookup=").append(e.getClass().getSimpleName());
        }
        s.append(" hidden=").append(dpm.isApplicationHidden(admin, p));
        try {
            s.append(" suspended=").append(dpm.isPackageSuspended(admin, p));
        } catch (Exception e) {
            s.append(" suspended=?");
        }
        return s.toString();
    }
}
