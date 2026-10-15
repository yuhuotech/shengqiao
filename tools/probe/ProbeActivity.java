package local.xiaomi.audiosync.probe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.io.InputStream;
import java.security.MessageDigest;

/** Normal app UID, read-only access checks. No network permission or content logging. */
public class ProbeActivity extends Activity {
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setPadding(24, 24, 24, 24);
        setContentView(view);
        if (Intent.ACTION_SEND.equals(getIntent().getAction())
                || Intent.ACTION_SEND_MULTIPLE.equals(getIntent().getAction())) {
            receiveShare(view);
            return;
        }
        new Thread(() -> {
            line("uid=" + android.os.Process.myUid());
            for (String permission : new String[]{"miui.permission.ACCESS_RECORDS",
                    "com.hyperos.permission.READ_RECORD", "android.permission.READ_MEDIA_AUDIO"}) {
                line(permission + "=" + checkSelfPermission(permission));
            }
            for (String path : new String[]{"records", "recognize_text", "audio_recognize",
                    "recognition_sentence", "realtime_recog_sentence_table", "summary"}) {
                query("content://records/" + path);
            }
            try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{"_id", "relative_path"}, null, null, null)) {
                int total = 0, recorder = 0;
                if (c != null) while (c.moveToNext()) {
                    total++;
                    String path = c.getString(1);
                    if (path != null && path.contains("sound_recorder")) recorder++;
                }
                line("MediaStore audio rows=" + total + ", recorder rows=" + recorder);
            } catch (Exception e) { line("MediaStore " + e.getClass().getSimpleName()); }
            File dir = new File("/sdcard/Android/data/com.android.soundrecorder/files");
            File[] files = dir.listFiles();
            line("recorder external dir readable=" + dir.canRead() + ", listing="
                    + (files == null ? "null" : files.length));
            // Known small existing test recording; read only a header, never persist audio.
            try (FileInputStream in = new FileInputStream(new File(dir, "录音测试2.mp3"))) {
                line("direct audio header bytes=" + in.read(new byte[16]));
            } catch (Exception e) { line("direct audio " + e.getClass().getSimpleName()); }
            line("complete");
            try (FileOutputStream out = openFileOutput("report.txt", MODE_PRIVATE)) {
                out.write(report.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { line("report save " + e.getClass().getSimpleName()); }
            runOnUiThread(() -> view.setText(report.toString()));
        }).start();
    }

    private void query(String uri) {
        // An empty selection inspects schema/access only, never requests recording contents.
        try (Cursor c = getContentResolver().query(Uri.parse(uri), null, "1=0", null, null)) {
            line(uri + (c == null ? " -> NULL cursor" : " -> columns="
                    + Arrays.toString(c.getColumnNames()) + ", rows=" + c.getCount()));
        } catch (Exception e) { line(uri + " -> " + e.getClass().getSimpleName()); }
    }

    private void line(String text) { report.append(text).append('\n'); }

    private void receiveShare(TextView view) {
        new Thread(() -> {
            Intent intent = getIntent();
            line("share action=" + intent.getAction() + ", type=" + intent.getType());
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                String value = text.toString();
                line("shared text chars=" + value.length());
                line("shared text lines=" + value.split("\\n", -1).length);
                line("shared text sha256=" + digest(value.getBytes(StandardCharsets.UTF_8)));
                java.util.regex.Matcher times = java.util.regex.Pattern
                        .compile("(?<![0-9])[0-9]{2}:[0-9]{2}:[0-9]{2}(?![0-9])").matcher(value);
                String last = "none";
                while (times.find()) last = times.group();
                line("shared text last timestamp=" + last);
            }
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
                java.util.ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (list != null) uris.addAll(list);
            } else {
                Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) uris.add(uri);
            }
            if (uris.isEmpty() && intent.getClipData() != null) {
                for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                    Uri uri = intent.getClipData().getItemAt(i).getUri();
                    if (uri != null) uris.add(uri);
                }
            }
            line("shared uri count=" + uris.size());
            for (Uri uri : uris) {
                line("shared uri authority=" + uri.getAuthority());
                try (Cursor c = getContentResolver().query(uri,
                        new String[]{"_display_name", "_size"}, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        String name = c.getString(0);
                        line("shared file extension=" + (name.contains(".")
                                ? name.substring(name.lastIndexOf('.')) : "none"));
                        line("shared file size=" + c.getLong(1));
                    }
                } catch (Exception e) { line("shared metadata " + e.getClass().getSimpleName()); }
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    byte[] buffer = new byte[65536];
                    MessageDigest hash = MessageDigest.getInstance("SHA-256");
                    long bytes = 0;
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        bytes += count;
                        hash.update(buffer, 0, count);
                    }
                    line("shared file fully read bytes=" + bytes);
                    StringBuilder hex = new StringBuilder();
                    for (byte b : hash.digest()) hex.append(String.format("%02x", b & 255));
                    line("shared file sha256=" + hex);
                } catch (Exception e) { line("shared file " + e.getClass().getSimpleName()); }
            }
            line("complete");
            try (FileOutputStream out = openFileOutput("share-report.txt", MODE_PRIVATE)) {
                out.write(report.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { line("report save " + e.getClass().getSimpleName()); }
            runOnUiThread(() -> view.setText(report.toString()));
        }).start();
    }

    private static String digest(byte[] value) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(value)) {
                result.append(String.format("%02x", b & 255));
            }
            return result.toString();
        } catch (Exception e) { return e.getClass().getSimpleName(); }
    }
}
