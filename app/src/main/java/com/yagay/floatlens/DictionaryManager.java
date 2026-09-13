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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Offline English <-> Chinese dictionary backed by ECCEDICT. */
public final class DictionaryManager {
    private static final String SOURCE_URL = "https://raw.githubusercontent.com/H1DDENADM1N/ECCEDICT/master/ecdict.csv";
    private static final String DIR_NAME = "dictionary";
    private static final String DB_NAME = "eccedict.db";
    private static final String TEMP_DB_NAME = "eccedict.db.tmp";
    private static final String TEMP_CSV_NAME = "eccedict.csv.part";
    private static final String LEGACY_DB_NAME = "ecdict.db";
    private static final String SCHEMA_ID = "eccedict_bilingual_v1";
    private static final int MIN_VALID_ENTRIES = 500_000;
    private static final int MIN_REVERSE_TERMS = 50_000;
    private static final long MIN_DOWNLOAD_BYTES = 10L * 1024L * 1024L;
    private static final int MAX_CHINESE_RESULTS = 16;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean downloading;
    private static final String SELECT_FIELDS = "e.word,e.phonetic,e.definition,e.translation,e.pos,e.collins,e.oxford,e.tag,e.bnc,e.frq,e.exchange";

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

    public static final class LookupResult {
        public final String query;
        public final boolean chineseQuery;
        public final List<Entry> entries;

        LookupResult(String query, boolean chineseQuery, List<Entry> entries) {
            this.query = safe(query);
            this.chineseQuery = chineseQuery;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        }

        public boolean isEmpty() { return entries.isEmpty(); }
    }

    public static boolean isDownloading() { return downloading; }
    public static File databaseFile(Context context) { return new File(dictionaryDir(context), DB_NAME); }
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
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            cursor = db.rawQuery("SELECT value FROM meta WHERE key='schema' LIMIT 1", null);
            if (!cursor.moveToFirst() || !SCHEMA_ID.equals(cursor.getString(0))) return false;
            cursor.close();
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
        deleteDatabaseFamily(new File(dir, DB_NAME));
        deleteDatabaseFamily(new File(dir, TEMP_DB_NAME));
        deleteDatabaseFamily(new File(dir, LEGACY_DB_NAME));
        deleteQuietly(new File(dir, DB_NAME + ".old"));
        deleteQuietly(new File(dir, TEMP_CSV_NAME));
        deleteQuietly(new File(dir, "ecdict.csv.part"));
    }

    public static void download(Context context, Callback callback) {
        if (context == null) {
            failure(callback, "无效的应用上下文");
            return;
        }
        synchronized (DictionaryManager.class) {
            if (downloading) {
                failure(callback, "词典正在下载或建立索引");
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
            File backup = new File(dir, DB_NAME + ".old");
            try {
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建词典目录");
                deleteQuietly(csv);
                deleteDatabaseFamily(tempDb);
                deleteQuietly(backup);
                progress(callback, "下载 ECCEDICT", 0);
                downloadCsv(csv, callback);
                DiagnosticLog.i(app, "DICTIONARY", "ECCEDICT csv bytes=" + csv.length());
                if (csv.length() < MIN_DOWNLOAD_BYTES) throw new IOException("下载文件异常，大小仅 " + csv.length() + " bytes");
                progress(callback, "建立中英双向索引", 70);
                importCsv(csv, tempDb, callback);
                validateDatabase(tempDb);
                if (finalDb.exists() && !finalDb.renameTo(backup)) throw new IOException("无法备份旧词典");
                if (!tempDb.renameTo(finalDb)) {
                    if (backup.exists()) backup.renameTo(finalDb);
                    throw new IOException("无法安装新词典");
                }
                deleteQuietly(backup);
                deleteQuietly(csv);
                deleteDatabaseFamily(new File(dir, LEGACY_DB_NAME));
                deleteQuietly(new File(dir, "ecdict.csv.part"));
                progress(callback, "完成", 100);
                DiagnosticLog.i(app, "DICTIONARY", "ECCEDICT installed bytes=" + finalDb.length());
                success(callback);
            } catch (Throwable t) {
                deleteDatabaseFamily(tempDb);
                deleteQuietly(csv);
                DiagnosticLog.i(app, "DICTIONARY", "download/import failed=" + t);
                failure(callback, friendlyMessage(t));
            } finally {
                downloading = false;
            }
        }, "FloatLens-ECCEDICT-Download").start();
    }

    public static LookupResult lookup(Context context, String rawQuery) throws IOException {
        if (context == null) throw new IOException("无效的应用上下文");
        if (!isReady(context)) throw new IOException("本地 ECCEDICT 词典尚未下载");
        String query = normalizeQuery(rawQuery);
        if (query.isEmpty()) return new LookupResult("", false, Collections.emptyList());
        boolean chinese = containsHan(query);
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(databaseFile(context).getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            List<Entry> entries = chinese ? lookupChinese(db, onlyHan(query)) : lookupEnglish(db, query);
            return new LookupResult(query, chinese, entries);
        } catch (Throwable t) {
            throw new IOException("词典查询失败: " + friendlyMessage(t), t);
        } finally {
            if (db != null) db.close();
        }
    }

    public static String normalizeQuery(String raw) {
        if (raw == null) return "";
        String value = raw.replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u2013', '-').replace('\u2014', '-')
                .trim().replaceAll("\\s+", " ");
        value = value.replaceAll("^[^\\p{L}\\p{N}'-]+", "").replaceAll("[^\\p{L}\\p{N}'-]+$", "");
        return value.length() > 160 ? value.substring(0, 160).trim() : value;
    }

    public static String sourceUrl() { return SOURCE_URL; }
    public static String sourceName() { return "ECCEDICT"; }

    private static List<Entry> lookupEnglish(SQLiteDatabase db, String query) {
        ArrayList<Entry> out = new ArrayList<>(1);
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT " + SELECT_FIELDS + " FROM entries e WHERE e.word = ? COLLATE NOCASE LIMIT 1", new String[]{query});
            if (c.moveToFirst()) {
                out.add(fromCursor(c));
                return out;
            }
            c.close();
            c = null;
            String sw = stripWord(query);
            if (!sw.isEmpty()) {
                c = db.rawQuery("SELECT " + SELECT_FIELDS + " FROM entries e WHERE e.sw = ? ORDER BY LENGTH(e.word), e.word COLLATE NOCASE LIMIT 1", new String[]{sw});
                if (c.moveToFirst()) out.add(fromCursor(c));
            }
            return out;
        } finally {
            if (c != null) c.close();
        }
    }

    private static List<Entry> lookupChinese(SQLiteDatabase db, String query) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        List<Entry> exact = queryChinese(db, "r.term = ?", new String[]{query});
        if (!exact.isEmpty()) return exact;
        return queryChinese(db, "r.term LIKE ?", new String[]{query + "%"});
    }

    private static List<Entry> queryChinese(SQLiteDatabase db, String where, String[] args) {
        ArrayList<Entry> out = new ArrayList<>();
        Cursor c = null;
        try {
            String sql = "SELECT DISTINCT " + SELECT_FIELDS
                    + " FROM reverse_terms r JOIN entries e ON e.word = r.word COLLATE NOCASE WHERE " + where
                    + " ORDER BY e.collins DESC, CASE WHEN e.frq > 0 THEN e.frq ELSE 2147483647 END,"
                    + " CASE WHEN e.bnc > 0 THEN e.bnc ELSE 2147483647 END, LENGTH(e.word), e.word COLLATE NOCASE"
                    + " LIMIT " + MAX_CHINESE_RESULTS;
            c = db.rawQuery(sql, args);
            while (c.moveToNext()) out.add(fromCursor(c));
            return out;
        } finally {
            if (c != null) c.close();
        }
    }

    private static Entry fromCursor(Cursor c) {
        return new Entry(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                c.getInt(5), c.getInt(6) != 0, c.getString(7), c.getInt(8), c.getInt(9), c.getString(10));
    }

    private static void downloadCsv(File output, Callback callback) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(SOURCE_URL).openConnection();
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(60_000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", "FloatLens/1 ECCEDICT downloader");
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
                        progress(callback, "下载 ECCEDICT", percent);
                    }
                }
                out.getFD().sync();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void importCsv(File csv, File dbFile, Callback callback) throws IOException {
        deleteDatabaseFamily(dbFile);
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        SQLiteStatement insertEntry = null;
        SQLiteStatement insertReverse = null;
        try {
            applyPragma(db, "PRAGMA journal_mode=OFF");
            applyPragma(db, "PRAGMA synchronous=OFF");
            applyPragma(db, "PRAGMA temp_store=MEMORY");
            db.execSQL("CREATE TABLE entries (word TEXT NOT NULL COLLATE NOCASE PRIMARY KEY, phonetic TEXT, definition TEXT, translation TEXT, pos TEXT, collins INTEGER NOT NULL DEFAULT 0, oxford INTEGER NOT NULL DEFAULT 0, tag TEXT, bnc INTEGER NOT NULL DEFAULT 0, frq INTEGER NOT NULL DEFAULT 0, exchange TEXT, sw TEXT NOT NULL) WITHOUT ROWID");
            db.execSQL("CREATE INDEX idx_entries_sw ON entries(sw)");
            db.execSQL("CREATE TABLE reverse_terms (term TEXT NOT NULL, word TEXT NOT NULL COLLATE NOCASE, PRIMARY KEY(term, word)) WITHOUT ROWID");
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)");
            CountingInputStream counter = new CountingInputStream(new FileInputStream(csv));
            long totalBytes = Math.max(1L, csv.length());
            try (CsvReader reader = new CsvReader(counter)) {
                List<String> header = reader.nextRow();
                if (header == null || header.isEmpty()) throw new IOException("ECCEDICT CSV 缺少表头");
                header.set(0, header.get(0).replace("\uFEFF", ""));
                Map<String, Integer> columns = columnMap(header);
                requireColumn(columns, "word");
                requireColumn(columns, "translation");
                insertEntry = db.compileStatement("INSERT OR REPLACE INTO entries (word,phonetic,definition,translation,pos,collins,oxford,tag,bnc,frq,exchange,sw) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)");
                insertReverse = db.compileStatement("INSERT OR IGNORE INTO reverse_terms(term,word) VALUES (?,?)");
                int rows = 0;
                long reverseRows = 0L;
                db.beginTransaction();
                try {
                    List<String> row;
                    while ((row = reader.nextRow()) != null) {
                        String word = field(row, columns, "word").trim();
                        if (word.isEmpty()) continue;
                        String translation = field(row, columns, "translation");
                        insertEntry.clearBindings();
                        bind(insertEntry, 1, word);
                        bind(insertEntry, 2, field(row, columns, "phonetic"));
                        bind(insertEntry, 3, field(row, columns, "definition"));
                        bind(insertEntry, 4, translation);
                        bind(insertEntry, 5, field(row, columns, "pos"));
                        insertEntry.bindLong(6, intField(row, columns, "collins"));
                        insertEntry.bindLong(7, intField(row, columns, "oxford"));
                        bind(insertEntry, 8, field(row, columns, "tag"));
                        insertEntry.bindLong(9, intField(row, columns, "bnc"));
                        insertEntry.bindLong(10, intField(row, columns, "frq"));
                        bind(insertEntry, 11, field(row, columns, "exchange"));
                        bind(insertEntry, 12, stripWord(word));
                        insertEntry.executeInsert();
                        for (String term : extractChineseTerms(translation)) {
                            insertReverse.clearBindings();
                            insertReverse.bindString(1, term);
                            insertReverse.bindString(2, word);
                            if (insertReverse.executeInsert() != -1L) reverseRows++;
                        }
                        rows++;
                        if ((rows % 2000) == 0) {
                            int percent = 70 + (int) Math.min(26L, counter.count() * 26L / totalBytes);
                            progress(callback, "建立中英双向索引", percent);
                        }
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                if (rows < MIN_VALID_ENTRIES) throw new IOException("ECCEDICT 词条数量异常: " + rows);
                if (reverseRows < MIN_REVERSE_TERMS) throw new IOException("ECCEDICT 中文反向索引数量异常: " + reverseRows);
            }
            progress(callback, "优化索引", 97);
            db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES('source','H1DDENADM1N/ECCEDICT ecdict.csv')");
            db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES('schema','" + SCHEMA_ID + "')");
            db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES('ready','1')");
            db.execSQL("ANALYZE");
            progress(callback, "校验", 99);
        } finally {
            if (insertEntry != null) insertEntry.close();
            if (insertReverse != null) insertReverse.close();
            db.close();
        }
    }

    private static List<String> extractChineseTerms(String translation) {
        if (translation == null || translation.isBlank()) return Collections.emptyList();
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < translation.length();) {
            int cp = translation.codePointAt(i);
            if (isHan(cp)) current.appendCodePoint(cp); else addChineseTerm(terms, current);
            i += Character.charCount(cp);
        }
        addChineseTerm(terms, current);
        return new ArrayList<>(terms);
    }

    private static void addChineseTerm(Set<String> out, StringBuilder current) {
        if (current.length() == 0) return;
        String term = current.toString().trim();
        current.setLength(0);
        int length = term.codePointCount(0, term.length());
        if (length == 0 || length > 32) return;
        out.add(term);
        if (length > 2 && (term.endsWith("的") || term.endsWith("地") || term.endsWith("得"))) {
            int cut = term.offsetByCodePoints(0, length - 1);
            String stem = term.substring(0, cut);
            if (!stem.isBlank()) out.add(stem);
        }
    }

    private static boolean containsHan(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (isHan(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static String onlyHan(String text) {
        StringBuilder out = new StringBuilder();
        if (text == null) return "";
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (isHan(cp)) out.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static boolean isHan(int cp) { return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN; }

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
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT COUNT(*) FROM entries", null);
            if (!c.moveToFirst() || c.getLong(0) < MIN_VALID_ENTRIES) throw new IOException("词典条目数量异常");
            c.close();
            c = db.rawQuery("SELECT COUNT(*) FROM reverse_terms", null);
            if (!c.moveToFirst() || c.getLong(0) < MIN_REVERSE_TERMS) throw new IOException("中文反向索引数量异常");
            c.close();
            c = db.rawQuery("SELECT translation FROM entries WHERE word='hello' COLLATE NOCASE LIMIT 1", null);
            if (!c.moveToFirst() || safe(c.getString(0)).isBlank()) throw new IOException("词典内容校验失败");
            c.close();
            c = db.rawQuery("SELECT value FROM meta WHERE key='schema' LIMIT 1", null);
            if (!c.moveToFirst() || !SCHEMA_ID.equals(c.getString(0))) throw new IOException("词典索引版本校验失败");
            c.close();
            c = db.rawQuery("PRAGMA quick_check", null);
            if (!c.moveToFirst() || !"ok".equalsIgnoreCase(c.getString(0))) throw new IOException("SQLite 完整性校验失败");
        } finally {
            if (c != null) c.close();
            if (db != null) db.close();
        }
    }

    private static Map<String, Integer> columnMap(List<String> header) {
        HashMap<String, Integer> map = new HashMap<>();
        for (int i = 0; i < header.size(); i++) map.put(safe(header.get(i)).trim().toLowerCase(Locale.ROOT), i);
        return map;
    }
    private static void requireColumn(Map<String, Integer> map, String name) throws IOException {
        if (!map.containsKey(name)) throw new IOException("ECCEDICT CSV 缺少字段: " + name);
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
        if (value == null) statement.bindNull(index); else statement.bindString(index, value);
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
    private static File dictionaryDir(Context context) { return new File(context.getApplicationContext().getFilesDir(), DIR_NAME); }
    private static void deleteDatabaseFamily(File file) {
        if (file == null) return;
        deleteQuietly(file);
        deleteQuietly(new File(file.getAbsolutePath() + "-journal"));
        deleteQuietly(new File(file.getAbsolutePath() + "-wal"));
        deleteQuietly(new File(file.getAbsolutePath() + "-shm"));
    }
    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) try { file.delete(); } catch (Throwable ignored) { }
    }
    private static String friendlyMessage(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
    private static String safe(String s) { return s == null ? "" : s; }
    private static void progress(Callback callback, String stage, int percent) {
        if (callback == null) return;
        int p = Math.max(0, Math.min(100, percent));
        MAIN.post(() -> callback.onProgress(stage, p));
    }
    private static void success(Callback callback) { if (callback != null) MAIN.post(callback::onSuccess); }
    private static void failure(Callback callback, String message) { if (callback != null) MAIN.post(() -> callback.onFailure(message)); }

    private static final class CountingInputStream extends InputStream {
        private final InputStream in;
        private long count;
        CountingInputStream(InputStream in) { this.in = in; }
        long count() { return count; }
        @Override public int read() throws IOException { int v = in.read(); if (v >= 0) count++; return v; }
        @Override public int read(byte[] b, int off, int len) throws IOException { int n = in.read(b, off, len); if (n > 0) count += n; return n; }
        @Override public void close() throws IOException { in.close(); }
    }

    private static final class CsvReader implements Closeable {
        private final PushbackReader reader;
        CsvReader(InputStream in) {
            reader = new PushbackReader(new BufferedReader(new InputStreamReader(new BufferedInputStream(in, 128 * 1024), StandardCharsets.UTF_8), 128 * 1024), 1);
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
                        else { quoted = false; if (next >= 0) reader.unread(next); }
                    } else field.append(ch);
                    continue;
                }
                if (ch == '"' && field.length() == 0) quoted = true;
                else if (ch == ',') { row.add(field.toString()); field.setLength(0); }
                else if (ch == '\n') { row.add(field.toString()); return row; }
                else if (ch == '\r') {
                    int next = reader.read();
                    if (next != '\n' && next >= 0) reader.unread(next);
                    row.add(field.toString());
                    return row;
                } else field.append(ch);
            }
        }
        @Override public void close() throws IOException { reader.close(); }
    }

    private DictionaryManager() { }
}
