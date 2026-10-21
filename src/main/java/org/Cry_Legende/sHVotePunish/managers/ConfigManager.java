package org.Cry_Legende.sHVotePunish.managers;

import org.Cry_Legende.sHVotePunish.SHVotePunish;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Gère le chargement et l'accès aux valeurs du fichier config.yml.
 * Met en cache les valeurs pour un accès performant.
 */
public class ConfigManager {

    private final SHVotePunish plugin;
    private FileConfiguration config;

    // --- Cached Values ---

    // Vote
    private String commandOnSuccess;
    private int voteDuration;
    private String requiredVotesType;
    private int requiredVotesValue;
    private List<String> blacklistedPlayers;

    // Confirmation Menu
    private String confirmationMenuTitle;
    private String confirmItemMaterial;
    private String confirmItemName;
    private List<String> confirmItemLore;
    private String cancelItemMaterial;
    private String cancelItemName;
    private List<String> cancelItemLore;

    // Vote Menu
    private String voteMenuTitle;
    private List<String> voteMenuPlayerHeadLore;

    // Discord
    private boolean webhookEnabled;
    private String webhookUrl;
    private int webhookLastMessagesLimit;
    private String webhookUsername;
    private String webhookAvatarUrl;
    private String webhookEmbedTitle;
    private int webhookEmbedColor;
    private String webhookEmbedDescription;
    private String fieldTitlePlayer;
    private String fieldTitleFirstJoin;
    private String fieldTitleLastMessages;
    private String webhookEmbedFooter;

    // Messages
    private String prefix;
    private String playerNotFound;
    private String noPermission;
    private String cannotPunishSelf;
    private String playerHasBypass;
    private String voteAlreadyInProgress;
    private String voteStartConfirmation;
    private String voteBroadcast;
    private String voteSuccess;
    private String voteFail;
    private String adminIgnoreAdd;
    private String adminIgnoreRemove;
    private String playerAlreadyPunished; // NOUVEAU
    private String adminUnpunish;         // NOUVEAU

    // Logs
    private String logVoteStart;
    private String logPlayerVote;
    private String logVoteEnd;

    // Permissions
    private String permUse;
    private String permBypass;
    private String permAdmin;
    private String permEligibilityBypass;


    public ConfigManager(SHVotePunish plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        loadValues();
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
        prefix = translateColors(config.getString("messages.prefix", "&8[&cVotePunish&8] "));
        playerNotFound = prefix + translateColors(config.getString("messages.player_not_found"));
        noPermission = prefix + translateColors(config.getString("messages.no_permission"));
        cannotPunishSelf = prefix + translateColors(config.getString("messages.cannot_punish_self"));
        playerHasBypass = prefix + translateColors(config.getString("messages.player_has_bypass"));
        voteAlreadyInProgress = prefix + translateColors(config.getString("messages.vote_already_in_progress"));
        voteStartConfirmation = prefix + translateColors(config.getString("messages.vote_start_confirmation"));
        voteBroadcast = prefix + translateColors(config.getString("messages.vote_broadcast"));
        voteSuccess = prefix + translateColors(config.getString("messages.vote_success"));
        voteFail = prefix + translateColors(config.getString("messages.vote_fail"));
        adminIgnoreAdd = prefix + translateColors(config.getString("messages.admin_ignore_add"));
        adminIgnoreRemove = prefix + translateColors(config.getString("messages.admin_ignore_remove"));
        // NOUVEAU
        playerAlreadyPunished = prefix + translateColors(config.getString("messages.player_already_punished", "&cCe joueur a déjà été sanctionné par un vote."));
        adminUnpunish = prefix + translateColors(config.getString("messages.admin_unpunish", "&aLe joueur {player} peut de nouveau être la cible d'un vote."));


        // Logs
        logVoteStart = config.getString("messages.log_vote_start");
        logPlayerVote = config.getString("messages.log_player_vote");
        logVoteEnd = config.getString("messages.log_vote_end");

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
