package com.yagay.floatlens;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local English -> Chinese dictionary backed by ECDICT.
 *
 * The dictionary data is intentionally NOT bundled in the APK. FloatLens downloads the upstream
 * ECDICT CSV on demand, converts only the fields needed by the app into an indexed SQLite database,
 * then deletes the temporary CSV. Existing installed data stays usable until a replacement has
 * been completely downloaded, imported and validated.
 */
public final class DictionaryManager {
    private static final String SOURCE_URL =
            "https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv";
    private static final String DIR_NAME = "dictionary";
    private static final String DB_NAME = "ecdict.db";
    private static final String TEMP_DB_NAME = "ecdict.db.tmp";
    private static final String TEMP_CSV_NAME = "ecdict.csv.part";
    private static final int MIN_VALID_ENTRIES = 500_000;
    private static final long MIN_DOWNLOAD_BYTES = 10L * 1024L * 1024L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean downloading;

    public interface Callback {
        void onProgress(String stage, int percent);
        void onSuccess();
        void onFailure(String message);
    }

    public static final class Entry {
        public final String word;
        public final String phonetic;
        public final String definition;
        public final String translation;
        public final String pos;
        public final int collins;
        public final boolean oxford;
        public final String tag;
        public final int bnc;
        public final int frq;
        public final String exchange;

        Entry(String word, String phonetic, String definition, String translation, String pos,
              int collins, boolean oxford, String tag, int bnc, int frq, String exchange) {
            this.word = safe(word);
            this.phonetic = safe(phonetic);
            this.definition = safe(definition);
            this.translation = safe(translation);
            this.pos = safe(pos);
            this.collins = collins;
            this.oxford = oxford;
            this.tag = safe(tag);
            this.bnc = bnc;
            this.frq = frq;
            this.exchange = safe(exchange);
        }
    }

    public static boolean isDownloading() {
        return downloading;
    }

    public static File databaseFile(Context context) {
        return new File(dictionaryDir(context), DB_NAME);
    }

    public static long installedBytes(Context context) {
        File f = databaseFile(context);
        return f.isFile() ? f.length() : 0L;
    }

    public static boolean isReady(Context context) {
        if (context == null) return false;
        File dbFile = databaseFile(context);
        if (!dbFile.isFile() || dbFile.length() < 1024L * 1024L) return false;
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            cursor = db.rawQuery("SELECT value FROM meta WHERE key='ready' LIMIT 1", null);
            return cursor.moveToFirst() && "1".equals(cursor.getString(0));
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
        }
    }

    public static void delete(Context context) {
        if (context == null || downloading) return;
        File dir = dictionaryDir(context);
        deleteQuietly(new File(dir, DB_NAME));
        deleteQuietly(new File(dir, DB_NAME + "-journal"));
        deleteQuietly(new File(dir, DB_NAME + "-wal"));
        deleteQuietly(new File(dir, DB_NAME + "-shm"));
        deleteQuietly(new File(dir, TEMP_DB_NAME));
        deleteQuietly(new File(dir, TEMP_CSV_NAME));
    }

    public static void download(Context context, Callback callback) {
        if (context == null) {
            failure(callback, "无效的应用上下文");
            return;
        }
        synchronized (DictionaryManager.class) {
            if (downloading) {
                failure(callback, "词典正在下载或构建");
                return;
            }
            downloading = true;
        }

        Context app = context.getApplicationContext();
        new Thread(() -> {
            File dir = dictionaryDir(app);
            File csv = new File(dir, TEMP_CSV_NAME);
            File tempDb = new File(dir, TEMP_DB_NAME);
            File finalDb = new File(dir, DB_NAME);
            try {
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建词典目录");
                deleteQuietly(csv);
                deleteQuietly(tempDb);
                progress(callback, "下载", 0);
                downloadCsv(csv, callback);
                if (csv.length() < MIN_DOWNLOAD_BYTES) {
                    throw new IOException("下载文件异常，大小仅 " + csv.length() + " bytes");
                }

                progress(callback, "建立本地索引", 70);
                importCsv(csv, tempDb, callback);
                validateDatabase(tempDb);

                // Keep the old database available until the new one is fully valid.
                File backup = new File(dir, DB_NAME + ".old");
                deleteQuietly(backup);
                if (finalDb.exists() && !finalDb.renameTo(backup)) {
                    throw new IOException("无法替换旧词典");
                }
                if (!tempDb.renameTo(finalDb)) {
                    if (backup.exists()) backup.renameTo(finalDb);
                    throw new IOException("无法安装新词典");
                }
                deleteQuietly(backup);
                deleteQuietly(csv);
                progress(callback, "完成", 100);
                success(callback);
            } catch (Throwable t) {
                deleteQuietly(tempDb);
                deleteQuietly(csv);
                DiagnosticLog.i(app, "DICTIONARY", "download/import failed=" + t);
                failure(callback, friendlyMessage(t));
            } finally {
                downloading = false;
            }
        }, "FloatLens-Dictionary-Download").start();
    }

    public static Entry lookup(Context context, String rawQuery) throws IOException {
        if (context == null) throw new IOException("无效的应用上下文");
        if (!isReady(context)) throw new IOException("本地英汉词典尚未下载");
        String query = normalizeQuery(rawQuery);
        if (query.isEmpty()) return null;

        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(databaseFile(context).getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,exchange "
                    + "FROM entries WHERE word = ? COLLATE NOCASE LIMIT 1", new String[]{query});
            if (c.moveToFirst()) return fromCursor(c);
            c.close();
            c = null;

            String sw = stripWord(query);
            if (!sw.isEmpty()) {
                c = db.rawQuery("SELECT word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,exchange "
                                + "FROM entries WHERE sw = ? ORDER BY LENGTH(word), word COLLATE NOCASE LIMIT 1",
                        new String[]{sw});
                if (c.moveToFirst()) return fromCursor(c);
            }
            return null;
        } catch (Throwable t) {
            throw new IOException("词典查询失败: " + friendlyMessage(t), t);
        } finally {
            if (c != null) c.close();
            if (db != null) db.close();
        }
    }

    public static String normalizeQuery(String raw) {
        if (raw == null) return "";
        String value = raw.replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u2013', '-').replace('\u2014', '-')
                .trim().replaceAll("\\s+", " ");
        value = value.replaceAll("^[^\\p{L}\\p{N}'-]+", "")
                .replaceAll("[^\\p{L}\\p{N}'-]+$", "");
        return value.length() > 160 ? value.substring(0, 160).trim() : value;
    }

    public static String sourceUrl() {
        return SOURCE_URL;
    }

    private static Entry fromCursor(Cursor c) {
        return new Entry(c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                c.getString(4), c.getInt(5), c.getInt(6) != 0, c.getString(7), c.getInt(8),
                c.getInt(9), c.getString(10));
    }

    private static void downloadCsv(File output, Callback callback) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(SOURCE_URL).openConnection();
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(60_000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", "FloatLens/1 ECDICT downloader");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            long total = conn.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(conn.getInputStream(), 128 * 1024);
                 FileOutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[128 * 1024];
                long done = 0L;
                int last = -1;
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (n == 0) continue;
                    out.write(buffer, 0, n);
                    done += n;
                    int percent = total > 0 ? (int) Math.min(69L, done * 69L / total) : 0;
                    if (percent != last) {
                        last = percent;
                        progress(callback, "下载", percent);
                    }
                }
                out.getFD().sync();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void importCsv(File csv, File dbFile, Callback callback) throws IOException {
        deleteQuietly(dbFile);
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        try {
            // Android treats PRAGMA statements as queries. execSQL() throws
            // "Queries can be performed using query or rawQuery only" even for assignment pragmas.
            applyPragma(db, "PRAGMA journal_mode=OFF");
            applyPragma(db, "PRAGMA synchronous=OFF");
            applyPragma(db, "PRAGMA temp_store=MEMORY");
            db.execSQL("CREATE TABLE entries ("
                    + "word TEXT NOT NULL COLLATE NOCASE PRIMARY KEY,"
                    + "phonetic TEXT, definition TEXT, translation TEXT, pos TEXT,"
                    + "collins INTEGER NOT NULL DEFAULT 0, oxford INTEGER NOT NULL DEFAULT 0,"
                    + "tag TEXT, bnc INTEGER NOT NULL DEFAULT 0, frq INTEGER NOT NULL DEFAULT 0,"
                    + "exchange TEXT, sw TEXT NOT NULL) WITHOUT ROWID");
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)");

            CountingInputStream counter = new CountingInputStream(new FileInputStream(csv));
            long totalBytes = Math.max(1L, csv.length());
            try (CsvReader reader = new CsvReader(counter)) {
                List<String> header = reader.nextRow();
                if (header == null || header.isEmpty()) throw new IOException("ECDICT CSV 缺少表头");
                header.set(0, header.get(0).replace("\uFEFF", ""));
                Map<String, Integer> columns = columnMap(header);
                requireColumn(columns, "word");
                requireColumn(columns, "translation");

                SQLiteStatement insert = db.compileStatement(
                        "INSERT OR REPLACE INTO entries "
                                + "(word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,exchange,sw) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)");
                int rows = 0;
                db.beginTransaction();
                try {
                    List<String> row;
                    while ((row = reader.nextRow()) != null) {
                        String word = field(row, columns, "word").trim();
                        if (word.isEmpty()) continue;
                        insert.clearBindings();
                        bind(insert, 1, word);
                        bind(insert, 2, field(row, columns, "phonetic"));
                        bind(insert, 3, field(row, columns, "definition"));
                        bind(insert, 4, field(row, columns, "translation"));
                        bind(insert, 5, field(row, columns, "pos"));
                        insert.bindLong(6, intField(row, columns, "collins"));
                        insert.bindLong(7, intField(row, columns, "oxford"));
                        bind(insert, 8, field(row, columns, "tag"));
                        insert.bindLong(9, intField(row, columns, "bnc"));
                        insert.bindLong(10, intField(row, columns, "frq"));
                        bind(insert, 11, field(row, columns, "exchange"));
                        bind(insert, 12, stripWord(word));
                        insert.executeInsert();
                        rows++;
                        if ((rows % 2000) == 0) {
                            int percent = 70 + (int) Math.min(27L,
                                    counter.count() * 27L / totalBytes);
                            progress(callback, "建立本地索引", percent);
                        }
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }

            progress(callback, "优化索引", 98);
            db.execSQL("CREATE INDEX idx_entries_sw ON entries(sw)");
            db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES('source','ECDICT master ecdict.csv')");
            db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES('ready','1')");
            db.execSQL("ANALYZE");
            progress(callback, "校验", 99);
        } finally {
            db.close();
        }
    }

    private static void applyPragma(SQLiteDatabase db, String sql) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(sql, null);
            cursor.moveToFirst();
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static void validateDatabase(File dbFile) throws IOException {
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT COUNT(*) FROM entries", null);
            if (!c.moveToFirst() || c.getLong(0) < MIN_VALID_ENTRIES) {
                throw new IOException("词典条目数量异常");
            }
            c.close();
            c = db.rawQuery("SELECT translation FROM entries WHERE word='hello' COLLATE NOCASE LIMIT 1", null);
            if (!c.moveToFirst() || safe(c.getString(0)).isBlank()) {
                throw new IOException("词典内容校验失败");
            }
            c.close();
            c = db.rawQuery("PRAGMA quick_check", null);
            if (!c.moveToFirst() || !"ok".equalsIgnoreCase(c.getString(0))) {
                throw new IOException("SQLite 完整性校验失败");
            }
        } finally {
            if (c != null) c.close();
            if (db != null) db.close();
        }
    }

    private static Map<String, Integer> columnMap(List<String> header) {
        HashMap<String, Integer> map = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            map.put(safe(header.get(i)).trim().toLowerCase(Locale.ROOT), i);
        }
        return map;
    }

    private static void requireColumn(Map<String, Integer> map, String name) throws IOException {
        if (!map.containsKey(name)) throw new IOException("ECDICT CSV 缺少字段: " + name);
    }

    private static String field(List<String> row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || index < 0 || index >= row.size() ? "" : safe(row.get(index));
    }

    private static int intField(List<String> row, Map<String, Integer> columns, String name) {
        String s = field(row, columns, name).trim();
        if (s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (Throwable ignored) { return 0; }
    }

    private static void bind(SQLiteStatement statement, int index, String value) {
        if (value == null) statement.bindNull(index);
        else statement.bindString(index, value);
    }

    private static String stripWord(String word) {
        if (word == null || word.isEmpty()) return "";
        StringBuilder out = new StringBuilder(word.length());
        for (int i = 0; i < word.length();) {
            int cp = word.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) out.appendCodePoint(Character.toLowerCase(cp));
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static File dictionaryDir(Context context) {
        Context app = context.getApplicationContext();
        return new File(app.getFilesDir(), DIR_NAME);
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            try { file.delete(); } catch (Throwable ignored) { }
        }
    }

    private static String friendlyMessage(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        if (m == null || m.isBlank()) m = t.getClass().getSimpleName();
        return m;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void progress(Callback callback, String stage, int percent) {
        if (callback == null) return;
        int p = Math.max(0, Math.min(100, percent));
        MAIN.post(() -> callback.onProgress(stage, p));
    }

    private static void success(Callback callback) {
        if (callback != null) MAIN.post(callback::onSuccess);
    }

    private static void failure(Callback callback, String message) {
        if (callback != null) MAIN.post(() -> callback.onFailure(message));
    }

    private static final class CountingInputStream extends InputStream {
        private final InputStream in;
        private long count;

        CountingInputStream(InputStream in) { this.in = in; }
        long count() { return count; }
        @Override public int read() throws IOException {
            int v = in.read();
            if (v >= 0) count++;
            return v;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
        @Override public void close() throws IOException { in.close(); }
    }

    /** Small RFC-4180 style streaming reader; supports quoted commas, quotes and embedded newlines. */
    private static final class CsvReader implements Closeable {
        private final PushbackReader reader;

        CsvReader(InputStream in) {
            reader = new PushbackReader(new BufferedReader(
                    new InputStreamReader(new BufferedInputStream(in, 128 * 1024),
                            StandardCharsets.UTF_8), 128 * 1024), 1);
        }

        List<String> nextRow() throws IOException {
            ArrayList<String> row = new ArrayList<>(16);
            StringBuilder field = new StringBuilder(128);
            boolean quoted = false;
            boolean sawAny = false;
            while (true) {
                int n = reader.read();
                if (n < 0) {
                    if (!sawAny && row.isEmpty() && field.length() == 0) return null;
                    row.add(field.toString());
                    return row;
                }
                sawAny = true;
                char ch = (char) n;
                if (quoted) {
                    if (ch == '"') {
                        int next = reader.read();
                        if (next == '"') field.append('"');
                        else {
                            quoted = false;
                            if (next >= 0) reader.unread(next);
                        }
                    } else {
                        field.append(ch);
                    }
                    continue;
                }

                if (ch == '"' && field.length() == 0) {
                    quoted = true;
                } else if (ch == ',') {
                    row.add(field.toString());
                    field.setLength(0);
                } else if (ch == '\n') {
                    row.add(field.toString());
                    return row;
                } else if (ch == '\r') {
                    int next = reader.read();
                    if (next != '\n' && next >= 0) reader.unread(next);
                    row.add(field.toString());
                    return row;
                } else {
                    field.append(ch);
                }
            }
        }

        @Override public void close() throws IOException { reader.close(); }
    }

    private DictionaryManager() { }
}
