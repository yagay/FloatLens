package com.yagay.floatlens;

import android.app.SearchManager;
import android.content.Intent;
import android.net.Uri;

import java.util.List;

/** Standard intent families FloatLens can discover without asking the user to type parameters. */
public final class IntentTypeCatalog {
    public static final class Spec {
        public final String type;
        public final String title;
        public final String description;

        Spec(String type, String title, String description) {
            this.type = type;
            this.title = title;
            this.description = description;
        }

        public Intent probeIntent() {
            return switch (type) {
                case CustomMenuActionStore.TYPE_LAUNCH -> new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER);
                case CustomMenuActionStore.TYPE_SEND_TEXT -> new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "FloatLens");
                case CustomMenuActionStore.TYPE_PROCESS_TEXT -> new Intent(Intent.ACTION_PROCESS_TEXT)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_PROCESS_TEXT, "FloatLens")
                        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
                case CustomMenuActionStore.TYPE_VIEW_WEB -> new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://example.com"));
                case CustomMenuActionStore.TYPE_WEB_SEARCH -> new Intent(Intent.ACTION_WEB_SEARCH)
                        .putExtra(SearchManager.QUERY, "FloatLens");
                case CustomMenuActionStore.TYPE_DIAL -> new Intent(Intent.ACTION_DIAL,
                        Uri.parse("tel:10086"));
                case CustomMenuActionStore.TYPE_SMS -> new Intent(Intent.ACTION_SENDTO,
                        Uri.parse("smsto:10086"));
                case CustomMenuActionStore.TYPE_EMAIL -> new Intent(Intent.ACTION_SENDTO,
                        Uri.parse("mailto:test@example.com"));
                case CustomMenuActionStore.TYPE_MAP -> new Intent(Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=FloatLens"));
                case CustomMenuActionStore.TYPE_TRANSLATE -> new Intent("android.intent.action.TRANSLATE")
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "FloatLens");
                case CustomMenuActionStore.TYPE_VIEW_TEXT -> new Intent(Intent.ACTION_VIEW)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "FloatLens");
                default -> new Intent();
            };
        }
    }

    private static final List<Spec> ALL = List.of(
            new Spec(CustomMenuActionStore.TYPE_LAUNCH, "启动应用", "应用主启动入口"),
            new Spec(CustomMenuActionStore.TYPE_SEND_TEXT, "分享文字", "ACTION_SEND · text/plain"),
            new Spec(CustomMenuActionStore.TYPE_PROCESS_TEXT, "处理文字", "ACTION_PROCESS_TEXT"),
            new Spec(CustomMenuActionStore.TYPE_VIEW_WEB, "网页打开/搜索", "ACTION_VIEW · http/https"),
            new Spec(CustomMenuActionStore.TYPE_WEB_SEARCH, "网页搜索", "ACTION_WEB_SEARCH"),
            new Spec(CustomMenuActionStore.TYPE_DIAL, "拨号", "ACTION_DIAL"),
            new Spec(CustomMenuActionStore.TYPE_SMS, "短信", "ACTION_SENDTO · sms"),
            new Spec(CustomMenuActionStore.TYPE_EMAIL, "邮件", "ACTION_SENDTO · mail"),
            new Spec(CustomMenuActionStore.TYPE_MAP, "地图搜索", "ACTION_VIEW · geo"),
            new Spec(CustomMenuActionStore.TYPE_TRANSLATE, "翻译", "ACTION_TRANSLATE"),
            new Spec(CustomMenuActionStore.TYPE_VIEW_TEXT, "打开纯文本", "ACTION_VIEW · text/plain")
    );

    public static List<Spec> all() { return ALL; }

    public static Spec find(String type) {
        for (Spec s : ALL) if (s.type.equals(type)) return s;
        return null;
    }

    private IntentTypeCatalog() {}
}
