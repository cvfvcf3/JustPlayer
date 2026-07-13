package com.brouken.player;

import android.Manifest;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoListActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PERMISSION = 1001;
    private static final int REQUEST_CODE_OPEN_TREE = 1002;

    private static final String PREFS_NAME = "video_library_prefs";
    private static final String KEY_TREE_URIS = "tree_uris";

    private GridView gridFolders;
    private ListView listVideos;
    private TextView textFolderTitle;
    private View videoListContainer;

    private final List<FolderEntry> folders = new ArrayList<>();
    private final List<VideoEntry> currentFolderVideos = new ArrayList<>();

    private boolean showingVideoList = false;

    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static class VideoEntry {
        String key;
        String displayName;
        long durationMs;
        long sizeBytes;
        Uri contentUri;
        String bucketId;
        boolean fromSaf;
        long mediaStoreId;
    }

    private static class FolderEntry {
        String bucketId;
        String bucketName;
        int videoCount;
        VideoEntry representative;
        final List<VideoEntry> videos = new ArrayList<>();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_list);

        gridFolders = findViewById(R.id.grid_folders);
        listVideos = findViewById(R.id.list_videos);
        textFolderTitle = findViewById(R.id.text_folder_title);
        videoListContainer = findViewById(R.id.video_list_container);

        checkPermissionAndLoad();
    }

    @Override
    public void onBackPressed() {
        if (showingVideoList) {
            showFolderGrid();
        } else {
            super.onBackPressed();
        }
    }

    private String requiredPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return "android.permission.READ_MEDIA_VIDEO";
        } else {
            return Manifest.permission.READ_EXTERNAL_STORAGE;
        }
    }

    private void checkPermissionAndLoad() {
        String permission = requiredPermission();
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadFolders();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{permission}, REQUEST_CODE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadFolders();
            } else {
                Toast.makeText(this, R.string.videos_permission_needed, Toast.LENGTH_LONG).show();
                loadFolders();
            }
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private Set<String> getSavedTreeUris() {
        return new HashSet<>(prefs().getStringSet(KEY_TREE_URIS, new HashSet<>()));
    }

    private void addSavedTreeUri(String uriString) {
        Set<String> current = getSavedTreeUris();
        current.add(uriString);
        prefs().edit().putStringSet(KEY_TREE_URIS, current).apply();
    }

    private void launchFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CODE_OPEN_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_OPEN_TREE && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                addSavedTreeUri(treeUri.toString());
                Toast.makeText(this, "Folder added — scanning for videos…", Toast.LENGTH_SHORT).show();
                loadFolders();
            }
        }
    }

    private void loadFolders() {
        folders.clear();
        Map<String, FolderEntry> byBucket = new LinkedHashMap<>();

        loadFromMediaStore(byBucket);
        loadFromSavedTrees(byBucket);

        folders.addAll(byBucket.values());

        gridFolders.setAdapter(new FolderAdapter());
        showFolderGrid();
        gridFolders.post(() -> gridFolders.requestFocus());
    }

    private void loadFromMediaStore(Map<String, FolderEntry> byBucket) {
        String permission = requiredPermission();
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[]{
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.BUCKET_ID,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        };
        String sortOrder = MediaStore.Video.Media.DATE_ADDED + " DESC";

        try (Cursor cursor = getContentResolver().query(collection, projection, null, null, sortOrder)) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
                int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                int bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID);
                int bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);

                while (cursor.moveToNext()) {
                    VideoEntry entry = new VideoEntry();
                    entry.mediaStoreId = cursor.getLong(idCol);
                    entry.key = "media:" + entry.mediaStoreId;
                    entry.displayName = cursor.getString(nameCol);
                    entry.durationMs = cursor.getLong(durationCol);
                    entry.sizeBytes = cursor.getLong(sizeCol);
                    entry.contentUri = ContentUris.withAppendedId(collection, entry.mediaStoreId);
                    entry.bucketId = "media:" + cursor.getString(bucketIdCol);
                    entry.fromSaf = false;
                    String bucketName = cursor.getString(bucketNameCol);
                    if (bucketName == null) bucketName = "Unknown";

                    addToFolder(byBucket, entry, bucketName);
                }
            }
        }
    }

    private void loadFromSavedTrees(Map<String, FolderEntry> byBucket) {
        for (String uriString : getSavedTreeUris()) {
            Uri treeUri = Uri.parse(uriString);
            DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
            if (root == null || !root.exists()) continue;
            scanSafFolder(root, root.getName() != null ? root.getName() : "USB", byBucket);
        }
    }

    private void scanSafFolder(DocumentFile dir, String rootLabel, Map<String, FolderEntry> byBucket) {
        DocumentFile[] children = dir.listFiles();
        if (children == null) return;

        for (DocumentFile child : children) {
            if (child.isDirectory()) {
                scanSafFolder(child, rootLabel, byBucket);
            } else if (child.isFile() && child.getType() != null && child.getType().startsWith("video/")) {
                VideoEntry entry = new VideoEntry();
                entry.key = "saf:" + child.getUri().toString();
                entry.displayName = child.getName() != null ? child.getName() : "Video";
                entry.sizeBytes = child.length();
                entry.contentUri = child.getUri();
                entry.fromSaf = true;
                entry.durationMs = 0;

                String parentName = dir.getName() != null ? dir.getName() : rootLabel;
                entry.bucketId = "saf:" + dir.getUri().toString();

                addToFolder(byBucket, entry, parentName);
            }
        }
    }

    private void addToFolder(Map<String, FolderEntry> byBucket, VideoEntry entry, String bucketName) {
        FolderEntry folder = byBucket.get(entry.bucketId);
        if (folder == null) {
            folder = new FolderEntry();
            folder.bucketId = entry.bucketId;
            folder.bucketName = bucketName;
            folder.representative = entry;
            byBucket.put(entry.bucketId, folder);
        }
        folder.videoCount++;
        folder.videos.add(entry);
    }

    private void openFolder(FolderEntry folder) {
        currentFolderVideos.clear();
        currentFolderVideos.addAll(folder.videos);
        textFolderTitle.setText(folder.bucketName);
        listVideos.setAdapter(new VideoAdapter());
        showVideoList();
        listVideos.post(() -> listVideos.requestFocus());
    }

    private void showFolderGrid() {
        showingVideoList = false;
        gridFolders.setVisibility(View.VISIBLE);
        videoListContainer.setVisibility(View.GONE);
    }

    private void showVideoList() {
        showingVideoList = true;
        gridFolders.setVisibility(View.GONE);
        videoListContainer.setVisibility(View.VISIBLE);
    }

    private void loadThumbnailAsync(ImageView imageView, VideoEntry entry) {
        imageView.setImageDrawable(null);
        imageView.setTag(entry.key);
        thumbExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (!entry.fromSaf) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        bitmap = getContentResolver().loadThumbnail(entry.contentUri,
                                new android.util.Size(200, 120), null);
                    } else {
                        bitmap = MediaStore.Video.Thumbnails.getThumbnail(
                                getContentResolver(), entry.mediaStoreId, MediaStore.Video.Thumbnails.MINI_KIND, null);
                    }
                } else {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    try {
                        retriever.setDataSource(VideoListActivity.this, entry.contentUri);
                        bitmap = retriever.getFrameAtTime(1_000_000);
                    } finally {
                        retriever.release();
                    }
                }
            } catch (Exception ignored) {
            }
            Bitmap finalBitmap = bitmap;
            mainHandler.post(() -> {
                if (finalBitmap != null && entry.key.equals(imageView.getTag())) {
                    imageView.setImageBitmap(finalBitmap);
                }
            });
        });
    }

    private String formatDuration(long ms) {
        if (ms <= 0) return "";
        long totalSeconds = ms / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "0 MB";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024) {
            return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0);
        }
        return String.format(Locale.getDefault(), "%.1f MB", mb);
    }

    private class FolderAdapter extends BaseAdapter {
        @Override public int getCount() { return folders.size() + 1; }
        @Override public Object getItem(int position) { return position < folders.size() ? folders.get(position) : null; }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(VideoListActivity.this)
                        .inflate(R.layout.item_folder, parent, false);
            }
            ImageView thumb = convertView.findViewById(R.id.image_folder_thumb);
            TextView name = convertView.findViewById(R.id.text_folder_name);
            TextView count = convertView.findViewById(R.id.text_folder_count);

            if (position == folders.size()) {
                thumb.setImageDrawable(null);
                thumb.setTag(null);
                name.setText("+ Add USB / Folder");
                count.setText("Tap to browse once");
                convertView.setOnClickListener(v -> launchFolderPicker());
                return convertView;
            }

            FolderEntry folder = folders.get(position);
            name.setText(folder.bucketName);
            count.setText(getResources().getQuantityString(
                    R.plurals.video_count, folder.videoCount, folder.videoCount));
            loadThumbnailAsync(thumb, folder.representative);

            convertView.setOnClickListener(v -> openFolder(folder));

            return convertView;
        }
    }

    private class VideoAdapter extends ArrayAdapter<VideoEntry> {
        VideoAdapter() {
            super(VideoListActivity.this, 0, currentFolderVideos);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext())
                        .inflate(R.layout.item_video, parent, false);
            }
            VideoEntry entry = currentFolderVideos.get(position);
            ImageView thumb = convertView.findViewById(R.id.image_video_thumb);
            TextView title = convertView.findViewById(R.id.text_title);
            TextView meta = convertView.findViewById(R.id.text_meta);

            title.setText(entry.displayName);
            String durationText = formatDuration(entry.durationMs);
            meta.setText((durationText.isEmpty() ? "" : durationText + " · ") + formatSize(entry.sizeBytes));
            loadThumbnailAsync(thumb, entry);

            convertView.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(entry.contentUri, "video/*");
                intent.setClass(VideoListActivity.this, PlayerActivity.class);
                if (entry.fromSaf) {
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                startActivity(intent);
            });

            return convertView;
        }
    }
    }
