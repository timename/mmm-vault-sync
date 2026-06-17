package local.mmm.vaultsync;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Lang {
    private final JavaPlugin plugin;
    private FileConfiguration config;
    private String prefixInfo = "§6[MMMVaultSync] §e";
    private String prefixWarn = "§6[MMMVaultSync] §c";
    private String prefixOk = "§6[MMMVaultSync] §a";

    public Lang(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload(String languageCode) {
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists() && !langDir.mkdirs()) {
            plugin.getLogger().warning("无法创建语言目录: " + langDir.getAbsolutePath());
        }

        String fileName = languageCode + ".yml";
        File langFile = new File(langDir, fileName);
        if (!langFile.exists()) {
            plugin.saveResource("lang/" + fileName, false);
        }

        this.config = YamlConfiguration.loadConfiguration(langFile);
        this.prefixInfo = colorize(raw("prefix.info", prefixInfo));
        this.prefixWarn = colorize(raw("prefix.warn", prefixWarn));
        this.prefixOk = colorize(raw("prefix.ok", prefixOk));
    }

    public String info(String key, Map<String, String> placeholders) {
        return prefixInfo + text(key, placeholders);
    }

    public String warn(String key, Map<String, String> placeholders) {
        return prefixWarn + text(key, placeholders);
    }

    public String ok(String key, Map<String, String> placeholders) {
        return prefixOk + text(key, placeholders);
    }

    public String text(String key) {
        return text(key, Map.of());
    }

    public String text(String key, Map<String, String> placeholders) {
        return applyPlaceholders(colorize(raw(key, key)), placeholders);
    }

    public List<String> textList(String key, Map<String, String> placeholders) {
        List<String> values = config != null ? config.getStringList(key) : List.of();
        List<String> output = new ArrayList<>(values.size());
        for (String value : values) {
            output.add(applyPlaceholders(colorize(value), placeholders));
        }
        return output;
    }

    public String prefixInfo() {
        return prefixInfo;
    }

    public String prefixWarn() {
        return prefixWarn;
    }

    public String prefixOk() {
        return prefixOk;
    }

    private String raw(String key, String fallback) {
        if (config == null) {
            return fallback;
        }
        return config.getString(key, fallback);
    }

    private String applyPlaceholders(String input, Map<String, String> placeholders) {
        String output = input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            output = output.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return output;
    }

    private String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}
