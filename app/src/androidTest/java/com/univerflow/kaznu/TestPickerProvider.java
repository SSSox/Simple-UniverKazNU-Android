package com.univerflow.kaznu;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Test-APK ContentProvider running in the test package's own process/UID. Protected by a permission the app under test
 * does not hold so only explicit Android URI grants allow the app to read its URIs. Written in Java because the test
 * APK's standalone process does not load the target APK's kotlin-stdlib classes.
 */
public final class TestPickerProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return new MatrixCursor(new String[]{"_id"});
    }

    @Override
    public String getType(Uri uri) {
        return "application/pdf";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        String targetPackage = getCallingPackage();
        if (ctx == null || targetPackage == null || arg == null) {
            return null;
        }
        Uri uri = Uri.parse(arg);
        if ("grant".equals(method)) {
            ctx.grantUriPermission(targetPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if ("revoke".equals(method)) {
            ctx.revokeUriPermission(targetPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        return Bundle.EMPTY;
    }
}
