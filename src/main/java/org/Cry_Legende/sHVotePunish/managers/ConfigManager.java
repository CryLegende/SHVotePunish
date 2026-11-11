package org.Cry_Legende.sHVotePunish.managers;

import org.Cry_Legende.sHVotePunish.SHVotePunish;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Gère le chargement et l'accès aux valeurs de config.yml et de messages.yml.
 * Met en cache les valeurs pour un accès performant ; /shvotepunish reload les remplace.
 */
public class ConfigManager {

    private final SHVotePunish plugin;
    private volatile FileConfiguration config;
    // messages.yml, avec les textes du jar pour les clés qu'il n'a pas
    private volatile FileConfiguration messages;

    // --- Cached Values ---

    // Vote
    private volatile String commandOnSuccess;
    private volatile int voteDuration;
    private volatile String requiredVotesType;
    private volatile int requiredVotesValue;
    private volatile List<String> blacklistedPlayers;

    // Confirmation Menu
    private volatile String confirmationMenuTitle;
    private volatile String confirmItemMaterial;
    private volatile String confirmItemName;
    private volatile List<String> confirmItemLore;
    private volatile String cancelItemMaterial;
    private volatile String cancelItemName;
    private volatile List<String> cancelItemLore;

    // Vote Menu
    private volatile String voteMenuTitle;
    private volatile List<String> voteMenuPlayerHeadLore;

    // Discord
    private volatile boolean webhookEnabled;
    private volatile String webhookUrl;
    private volatile int webhookLastMessagesLimit;
    private volatile String webhookUsername;
    private volatile String webhookAvatarUrl;
    private volatile String webhookEmbedTitle;
    private volatile int webhookEmbedColor;
    private volatile String webhookEmbedDescription;
    private volatile String fieldTitlePlayer;
    private volatile String fieldTitleFirstJoin;
    private volatile String fieldTitleLastMessages;
    private volatile String webhookEmbedFooter;

    // Messages
    private volatile String prefix;
    private volatile String playerNotFound;
    private volatile String noPermission;
    private volatile String cannotPunishSelf;
    private volatile String playerHasBypass;
    private volatile String voteAlreadyInProgress;
    private volatile String voteStartConfirmation;
    private volatile String voteBroadcast;
    private volatile String voteSuccess;
    private volatile String voteFail;
    private volatile String adminIgnoreAdd;
    private volatile String adminIgnoreRemove;
    private volatile String playerAlreadyPunished; // NOUVEAU
    private volatile String adminUnpunish;         // NOUVEAU
    private volatile String importSuccess;
    private volatile String importUnavailable;
    private volatile String importError;
    private volatile String unknownPlayerNetwork;

    // Logs
    private volatile String logVoteStart;
    private volatile String logPlayerVote;
    private volatile String logVoteEnd;

    // Permissions
    private volatile String permUse;
    private volatile String permBypass;
    private volatile String permAdmin;
    private volatile String permEligibilityBypass;


    public ConfigManager(SHVotePunish plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        migrateMessages();
        this.messages = withDefaults(YamlConfiguration.loadConfiguration(messagesFile()));
        loadValues();
    }

    private File messagesFile() {
        return new File(plugin.getDataFolder(), "messages.yml");
    }

    /** Ajoute au fichier lu les textes livrés avec le plugin, pour les clés qu'il n'a pas. */
    private YamlConfiguration withDefaults(YamlConfiguration yaml) {
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Textes de messages.yml livrés avec le plugin illisibles : " + e.getMessage());
        }
        return yaml;
    }

    /**
     * Les messages étaient dans la section messages de config.yml. Quand messages.yml n'existe pas encore, il est créé
     * avec les textes du jar, puis chaque texte du serveur écrit dans config.yml le remplace : les textes en place sont
     * gardés. config.yml n'est plus lu pour les messages ensuite.
     */
    private void migrateMessages() {
        File file = messagesFile();
        if (file.exists()) {
            return;
        }
        plugin.saveResource("messages.yml", false);
        ConfigurationSection legacy = config.getConfigurationSection("messages");
        if (legacy == null) {
            return;
        }
        try {
            YamlConfiguration jar = YamlConfiguration.loadConfiguration(file);
            List<String> lines = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            boolean changed = false;
            for (String key : legacy.getKeys(false)) {
                String text = legacy.getString(key);
                if (text != null && !text.equals(jar.getString(key))) {
                    setText(lines, key, text);
                    changed = true;
                }
            }
            if (changed) {
                Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
                plugin.getLogger().info("Messages de config.yml repris dans messages.yml.");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Messages de config.yml non repris dans messages.yml : " + e.getMessage());
        }
    }

    /** Remplace le texte de la clé (sur plusieurs lignes ou non) par une valeur entre guillemets, ou l'ajoute. */
    private static void setText(List<String> lines, String key, String text) {
        String value = key + ": \"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(key + ":")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            lines.add(value);
            return;
        }
        int end = start + 1;
        while (end < lines.size() && (lines.get(end).isEmpty() || Character.isWhitespace(lines.get(end).charAt(0)))) {
            end++;
        }
        while (end > start + 1 && lines.get(end - 1).isBlank()) {
            end--;
        }
        lines.subList(start, end).clear();
        lines.add(start, value);
    }

    /**
     * Relit config.yml et messages.yml. Les deux sont lus avant tout remplacement : si l'un est illisible, rien ne change.
     *
     * @return null si tout est rechargé, sinon {fichier, erreur}
     */
    public String[] reload() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        migrateMessages();
        String name = configFile.getName();
        YamlConfiguration newMessages = new YamlConfiguration();
        try {
            new YamlConfiguration().load(configFile);
            name = "messages.yml";
            newMessages.load(messagesFile());
        } catch (IOException | InvalidConfigurationException e) {
            return new String[]{name, String.valueOf(e.getMessage()).replace("&", "").replaceAll("\\s+", " ").trim()};
        }
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        this.messages = withDefaults(newMessages);
        loadValues();
        return null;
    }

    /**
     * Message de messages.yml, préfixe ajouté, couleurs appliquées ; chaque paire de {@code values} remplace {nom} par
     * sa valeur (après les couleurs : une valeur n'est jamais lue comme un code couleur).
     */
    public String get(String key, String... values) {
        String text = prefix + translateColors(messages.getString(key, key));
        for (int i = 0; i + 1 < values.length; i += 2) {
            text = text.replace("{" + values[i] + "}", values[i + 1]);
        }
        return text;
    }

    /** Texte de messages.yml sans préfixe, couleurs appliquées. */
    public String text(String key) {
        return translateColors(messages.getString(key, key));
    }

    private void loadValues() {
        // Vote
        commandOnSuccess = config.getString("vote.command_on_success", "mute {player} 24h [VoteBan] Sanctionné par la communauté.");
        voteDuration = config.getInt("vote.duration", 120);
        requiredVotesType = config.getString("vote.required_votes.type", "PERCENTAGE").toUpperCase();
        requiredVotesValue = config.getInt("vote.required_votes.value", 50);
        blacklistedPlayers = config.getStringList("blacklisted_players");

        // Confirmation Menu
        confirmationMenuTitle = translateColors(config.getString("confirmation_menu.title", "&cConfirmer la sanction pour {player}"));
        confirmItemMaterial = config.getString("confirmation_menu.confirm_item.material", "GREEN_STAINED_GLASS_PANE");
        confirmItemName = translateColors(config.getString("confirmation_menu.confirm_item.name", "&a&lVALIDER"));
        confirmItemLore = translateColors(config.getStringList("confirmation_menu.confirm_item.lore"));
        cancelItemMaterial = config.getString("confirmation_menu.cancel_item.material", "RED_STAINED_GLASS_PANE");
        cancelItemName = translateColors(config.getString("confirmation_menu.cancel_item.name", "&c&lANNULER"));
        cancelItemLore = translateColors(config.getStringList("confirmation_menu.cancel_item.lore"));

        // Vote Menu
        voteMenuTitle = translateColors(config.getString("vote_menu.title", "&lVote pour sanctionner {player}"));
        voteMenuPlayerHeadLore = translateColors(config.getStringList("vote_menu.player_head_lore"));

        // Discord
        webhookEnabled = config.getBoolean("discord_webhook.enabled");
        webhookUrl = config.getString("discord_webhook.url", "");
        webhookLastMessagesLimit = config.getInt("discord_webhook.last_messages_limit", 10);
        webhookUsername = config.getString("discord_webhook.content.username", "Alerte VotePunish");
        webhookAvatarUrl = config.getString("discord_webhook.content.avatar_url", "");
        webhookEmbedTitle = config.getString("discord_webhook.content.embeds.0.title", "Sanction par Vote Appliquée");
        webhookEmbedColor = config.getInt("discord_webhook.content.embeds.0.color", 15158332);
        webhookEmbedDescription = config.getString("discord_webhook.content.embeds.0.description", "");
        fieldTitlePlayer = config.getString("discord_webhook.content.embeds.0.field_title_player", "Joueur sanctionné");
        fieldTitleFirstJoin = config.getString("discord_webhook.content.embeds.0.field_title_first_join", "Première connexion");
        fieldTitleLastMessages = config.getString("discord_webhook.content.embeds.0.field_title_last_messages", "Derniers messages");
        webhookEmbedFooter = config.getString("discord_webhook.content.embeds.0.footer.text", "SHVotePunish");

        // Messages
        prefix = translateColors(messages.getString("prefix", ""));
        playerNotFound = prefix + translateColors(messages.getString("player_not_found"));
        noPermission = prefix + translateColors(messages.getString("no_permission"));
        cannotPunishSelf = prefix + translateColors(messages.getString("cannot_punish_self"));
        playerHasBypass = prefix + translateColors(messages.getString("player_has_bypass"));
        voteAlreadyInProgress = prefix + translateColors(messages.getString("vote_already_in_progress"));
        voteStartConfirmation = prefix + translateColors(messages.getString("vote_start_confirmation"));
        voteBroadcast = prefix + translateColors(messages.getString("vote_broadcast"));
        voteSuccess = prefix + translateColors(messages.getString("vote_success"));
        voteFail = prefix + translateColors(messages.getString("vote_fail"));
        adminIgnoreAdd = prefix + translateColors(messages.getString("admin_ignore_add"));
        adminIgnoreRemove = prefix + translateColors(messages.getString("admin_ignore_remove"));
        playerAlreadyPunished = prefix + translateColors(messages.getString("player_already_punished"));
        adminUnpunish = prefix + translateColors(messages.getString("admin_unpunish"));
        // Mode réseau (SHNetwork)
        importSuccess = prefix + translateColors(messages.getString("import_success"));
        importUnavailable = prefix + translateColors(messages.getString("import_unavailable"));
        importError = prefix + translateColors(messages.getString("import_error"));
        unknownPlayerNetwork = prefix + translateColors(messages.getString("unknown_player_network"));

        // Logs
        logVoteStart = messages.getString("log_vote_start");
        logPlayerVote = messages.getString("log_player_vote");
        logVoteEnd = messages.getString("log_vote_end");

        // Permissions
        permUse = config.getString("permissions.use", "shvotepunish.use");
        permBypass = config.getString("permissions.bypass", "shvotepunish.bypass");
        permAdmin = config.getString("permissions.admin", "shvotepunish.admin");
        permEligibilityBypass = config.getString("permissions.eligibility_bypass", "thesearch.search.*");
    }

    private String translateColors(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private List<String> translateColors(List<String> list) {
        return list.stream()
            .map(this::translateColors)
            .collect(Collectors.toList());
    }

    // --- Getters ---

    // Vote
    public String getCommandOnSuccess() {
        return commandOnSuccess;
    }

    public int getVoteDuration() {
        return voteDuration;
    }

    public String getRequiredVotesType() {
        return requiredVotesType;
    }

    public int getRequiredVotesValue() {
        return requiredVotesValue;
    }

    public List<String> getBlacklistedPlayers() {
        return blacklistedPlayers;
    }

    // Confirmation Menu
    public String getConfirmationMenuTitle() {
        return confirmationMenuTitle;
    }

    public String getConfirmItemMaterial() {
        return confirmItemMaterial;
    }

    public String getConfirmItemName() {
        return confirmItemName;
    }

    public List<String> getConfirmItemLore() {
        return confirmItemLore;
    }

    public String getCancelItemMaterial() {
        return cancelItemMaterial;
    }

    public String getCancelItemName() {
        return cancelItemName;
    }

    public List<String> getCancelItemLore() {
        return cancelItemLore;
    }

    // Vote Menu
    public String getVoteMenuTitle() {
        return voteMenuTitle;
    }

    public List<String> getVoteMenuPlayerHeadLore() {
        return voteMenuPlayerHeadLore;
    }

    // Discord
    public boolean isWebhookEnabled() {
        return webhookEnabled;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public int getWebhookLastMessagesLimit() {
        return webhookLastMessagesLimit;
    }

    public String getWebhookUsername() {
        return webhookUsername;
    }

    public String getWebhookAvatarUrl() {
        return webhookAvatarUrl;
    }

    public String getWebhookEmbedTitle() {
        return webhookEmbedTitle;
    }

    public int getWebhookEmbedColor() {
        return webhookEmbedColor;
    }

    public String getWebhookEmbedDescription() {
        return webhookEmbedDescription;
    }

    public String getFieldTitlePlayer() {
        return fieldTitlePlayer;
    }

    public String getFieldTitleFirstJoin() {
        return fieldTitleFirstJoin;
    }

    public String getFieldTitleLastMessages() {
        return fieldTitleLastMessages;
    }

    public String getWebhookEmbedFooter() {
        return webhookEmbedFooter;
    }

    // Messages
    public String getPrefix() {
        return prefix;
    }

    public String getPlayerNotFoundMessage() {
        return playerNotFound;
    }

    public String getNoPermissionMessage() {
        return noPermission;
    }

    public String getCannotPunishSelfMessage() {
        return cannotPunishSelf;
    }

    public String getPlayerHasBypassMessage() {
        return playerHasBypass;
    }

    public String getVoteAlreadyInProgressMessage() {
        return voteAlreadyInProgress;
    }

    public String getVoteStartConfirmationMessage() {
        return voteStartConfirmation;
    }

    public String getVoteBroadcastMessage() {
        return voteBroadcast;
    }

    public String getVoteSuccessMessage() {
        return voteSuccess;
    }

    public String getVoteFailMessage() {
        return voteFail;
    }

    public String getAdminIgnoreAddMessage() {
        return adminIgnoreAdd;
    }

    public String getAdminIgnoreRemoveMessage() {
        return adminIgnoreRemove;
    }

    public String getPlayerAlreadyPunishedMessage() {
        return playerAlreadyPunished;
    } // NOUVEAU

    public String getAdminUnpunishMessage() {
        return adminUnpunish;
    }                 // NOUVEAU

    public String getImportSuccessMessage() {
        return importSuccess;
    }

    public String getImportUnavailableMessage() {
        return importUnavailable;
    }

    public String getImportErrorMessage() {
        return importError;
    }

    public String getUnknownPlayerNetworkMessage() {
        return unknownPlayerNetwork;
    }

    // Logs
    public String getLogVoteStart() {
        return logVoteStart;
    }

    public String getLogPlayerVote() {
        return logPlayerVote;
    }

    public String getLogVoteEnd() {
        return logVoteEnd;
    }

    // Permissions
    public String getUsePermission() {
        return permUse;
    }

    public String getBypassPermission() {
        return permBypass;
    }

    public String getAdminPermission() {
        return permAdmin;
    }

    public String getEligibilityBypassPermission() {
        return permEligibilityBypass;
    }
}
