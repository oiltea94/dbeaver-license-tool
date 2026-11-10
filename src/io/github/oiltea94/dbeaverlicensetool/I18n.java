package io.github.oiltea94.dbeaverlicensetool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Properties;

/** Locale selection shared by the UI and worker threads; no registration data is stored. */
public final class I18n {
    public enum Language {
        ZH("zh", "中文"), EN("en", "English");
        final String code;
        private final String label;
        Language(String code, String label) { this.code = code; this.label = label; }
        @Override public String toString() { return label; }
    }

    private static volatile Language language = initialLanguage();
    private I18n() {}

    static Path settingsPath() {
        String override = System.getProperty("locallicense.settings");
        return override == null
                ? Path.of(System.getProperty("user.home"), ".config", "dbeaver-local-license-tool", "settings.properties")
                : Path.of(override);
    }

    static Language initialLanguage() {
        Language fallback = Locale.getDefault().getLanguage().equals("zh") ? Language.ZH : Language.EN;
        try {
            Properties p = new Properties();
            try (var input = Files.newInputStream(settingsPath())) { p.load(input); }
            return switch (p.getProperty("language", "")) {
                case "zh" -> Language.ZH;
                case "en" -> Language.EN;
                default -> fallback;
            };
        } catch (IOException | IllegalArgumentException | SecurityException ex) { return fallback; }
    }

    public static Language language() { return language; }
    public static Locale locale() { return language == Language.ZH ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH; }
    public static String t(String key) { return language == Language.ZH ? key : Messages.EN.getOrDefault(key, key); }

    static void select(Language selected, boolean persist) throws IOException {
        if (selected == null) throw new IllegalArgumentException("Language is required");
        language = selected;
        if (!persist) return;
        Path file = settingsPath().toAbsolutePath();
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (var input = Files.newInputStream(file)) { p.load(input); }
        }
        p.setProperty("language", selected.code);
        Path temporary = Files.createTempFile(file.getParent(), "language-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) { p.store(output, "Display language"); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    /** Removes the optional global language flag without changing saved preferences. */
    static String[] arguments(String[] args) throws IOException {
        var remaining = new ArrayList<String>();
        Language requested = null;
        for (int i = 0; i < args.length; i++) {
            if (!args[i].equals("--lang")) { remaining.add(args[i]); continue; }
            if (requested != null) throw new IllegalArgumentException(t("--lang 参数重复。"));
            if (++i == args.length) throw new IllegalArgumentException(t("--lang 缺少值。"));
            requested = switch (args[i]) {
                case "zh" -> Language.ZH;
                case "en" -> Language.EN;
                default -> throw new IllegalArgumentException(t("语言仅支持 zh 或 en。"));
            };
        }
        if (requested != null) select(requested, false);
        return remaining.toArray(String[]::new);
    }
}
