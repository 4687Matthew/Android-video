package org.video;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

public class VideoEditorUtils {
    /**
     * 从URI获取文件路径
     */
    public static String getPathFromUri(Context context, Uri uri) {
        String path = null;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT 
                && DocumentsContract.isDocumentUri(context, uri)) {
            // DocumentProvider
            String docId = DocumentsContract.getDocumentId(uri);
            
            if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                // ExternalStorageProvider
                String[] split = docId.split(":");
                String type = split[0];
                
                if ("primary".equalsIgnoreCase(type)) {
                    path = Environment.getExternalStorageDirectory() + "/" + split[1];
                }
            } else if ("com.android.providers.downloads.documents".equals(uri.getAuthority())) {
                // DownloadsProvider
                Uri contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), Long.parseLong(docId));
                path = getDataColumn(context, contentUri, null, null);
            } else if ("com.android.providers.media.documents".equals(uri.getAuthority())) {
                // MediaProvider
                String[] split = docId.split(":");
                String type = split[0];
                Uri contentUri = null;
                
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }
                
                String selection = "_id=?";
                String[] selectionArgs = new String[]{split[1]};
                path = getDataColumn(context, contentUri, selection, selectionArgs);
            }
        } else if ("content".equalsIgnoreCase(uri.getScheme())) {
            // MediaStore (and general)
            path = getDataColumn(context, uri, null, null);
        } else if ("file".equalsIgnoreCase(uri.getScheme())) {
            // File
            path = uri.getPath();
        }
        
        return path;
    }
    
    private static String getDataColumn(Context context, Uri uri, String selection, 
            String[] selectionArgs) {
        Cursor cursor = null;
        String column = MediaStore.Images.Media.DATA;
        String[] projection = {column};
        
        try {
            cursor = context.getContentResolver().query(uri, projection, selection, 
                    selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                int column_index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(column_index);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }
}

