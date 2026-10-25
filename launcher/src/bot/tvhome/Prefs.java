package bot.tvhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Read-only view of the home screen's screen-off setting for our other apps (same signing key),
 * so an ambient scene honours the same timer: content://bot.tvhome.prefs/timer
 */
public class Prefs extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        boolean alwaysOn = getContext().getSharedPreferences("home", android.content.Context.MODE_PRIVATE)
                .getBoolean("alwaysOn", false);
        MatrixCursor c = new MatrixCursor(new String[]{"alwaysOn", "idleMinutes"});
        c.addRow(new Object[]{alwaysOn ? 1 : 0, 5});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
