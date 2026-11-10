package org.Cry_Legende.sHVotePunish.managers;

import org.Cry_Legende.sHVotePunish.SHVotePunish;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Gère la création, l'ouverture et la mise à jour des interfaces graphiques (menus) du plugin.
 */
public class GUIManager {

    private final SHVotePunish plugin;
    private final ConfigManager configManager;

    // Clés uniques pour stocker des données sur les items, évitant les conflits avec d'autres plugins.
    public static final NamespacedKey CONFIRM_BUTTON_KEY = new NamespacedKey(SHVotePunish.getInstance(), "sv_confirm_vote_target");
    public static final NamespacedKey VOTE_BUTTON_KEY = new NamespacedKey(SHVotePunish.getInstance(), "sv_vote_yes_target");


    public GUIManager(SHVotePunish plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
    }

    /**
     * Ouvre le menu de confirmation pour l'initiateur du vote.
     *
     * @param initiator Le joueur qui lance la commande.
     * @param target    Le joueur ciblé par le vote.
     */
    public void openConfirmationMenu(Player initiator, Player target) {
        openConfirmationMenu(initiator, target.getUniqueId(), target.getName());
    }

    /**
     * Ouvre le menu de confirmation, la cible pouvant être sur un autre serveur (mode réseau).
     *
     * @param initiator  Le joueur qui lance la commande.
     * @param targetId   UUID du joueur ciblé par le vote.
     * @param targetName Pseudo du joueur ciblé.
     */
    public void openConfirmationMenu(Player initiator, UUID targetId, String targetName) {
        String title = configManager.getConfirmationMenuTitle().replace("{player}", targetName);
        Inventory inv = Bukkit.createInventory(new ConfirmationGUIHolder(), 27, title);

        // Tête du joueur cible
        inv.setItem(13, createPlayerHead(targetId, targetName, "§e§l" + targetName, new ArrayList<>()));

        // Bouton Confirmer
        ItemStack confirmItem = createGuiItem(
            Material.matchMaterial(configManager.getConfirmItemMaterial()),
            configManager.getConfirmItemName(),
            replacePlaceholders(configManager.getConfirmItemLore(), targetName)
        );
        ItemMeta confirmMeta = confirmItem.getItemMeta();
        confirmMeta.getPersistentDataContainer().set(CONFIRM_BUTTON_KEY, PersistentDataType.STRING, targetId.toString());
        confirmItem.setItemMeta(confirmMeta);
        inv.setItem(11, confirmItem);

        // Bouton Annuler
        ItemStack cancelItem = createGuiItem(
            Material.matchMaterial(configManager.getCancelItemMaterial()),
            configManager.getCancelItemName(),
            replacePlaceholders(configManager.getCancelItemLore(), targetName)
        );
        inv.setItem(15, cancelItem);

        initiator.openInventory(inv);
    }

    /**
     * Ouvre le menu de vote principal pour un joueur.
     *
     * @param player Le joueur qui veut voter.
     * @param vote   L'instance du vote en cours.
     */
    public void openVoteMenu(Player player, VoteManager.ActiveVote vote) {
        String title = configManager.getVoteMenuTitle().replace("{player}", vote.getTargetName());
        Inventory inv = Bukkit.createInventory(new VoteGUIHolder(), 36, title);

        updateVoteMenu(player, inv, vote); // Remplit les items pour ce joueur spécifique

        player.openInventory(inv);
        vote.addViewer(player.getUniqueId());
    }

    /**
     * Met à jour le contenu d'un menu de vote. Peut être appelé pour le remplissage initial ou pour un refresh.
     *
     * @param viewer Le joueur qui regarde l'inventaire.
     * @param inv    L'inventaire à mettre à jour.
     * @param vote   L'instance du vote en cours.
     */
    public void updateVoteMenu(Player viewer, Inventory inv, VoteManager.ActiveVote vote) {
        // Sans réseau, un vote dont la cible n'est plus là n'a plus de menu. Avec SHNetwork, la cible peut être sur un autre serveur.
        if (!vote.isNetworked() && Bukkit.getPlayer(vote.getTargetUUID()) == null) return;

        // Tête du joueur avec les stats du vote
        List<String> lore = configManager.getVoteMenuPlayerHeadLore().stream()
            .map(line -> line.replace("{player}", vote.getTargetName())
                .replace("{current_votes}", String.valueOf(vote.getCurrentVotes()))
                .replace("{required_votes}", String.valueOf(vote.getRequiredVotes())))
            .collect(Collectors.toList());
        inv.setItem(13, createPlayerHead(vote.getTargetUUID(), vote.getTargetName(), "§eInformations sur " + vote.getTargetName(), lore));

        // Bouton pour voter "OUI" ou indiquer que le joueur a déjà voté
        if (vote.hasVoted(viewer.getUniqueId())) {
            ItemStack alreadyVotedItem = createGuiItem(Material.LIME_DYE, "§a§lVous avez voté !", List.of("§7Votre vote a été enregistré."));
            inv.setItem(22, alreadyVotedItem);
        } else {
            ItemStack voteYesItem = createGuiItem(Material.GREEN_STAINED_GLASS_PANE, "§a§lVOTER OUI", List.of("§7Cliquez pour voter contre §e" + vote.getTargetName()));
            ItemMeta meta = voteYesItem.getItemMeta();
            meta.getPersistentDataContainer().set(VOTE_BUTTON_KEY, PersistentDataType.STRING, vote.getTargetUUID().toString());
            voteYesItem.setItemMeta(meta);
            inv.setItem(22, voteYesItem);
        }
    }


    // --- Méthodes utilitaires ---

    private ItemStack createGuiItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Tête du joueur : celle de sa peau s'il est sur ce serveur, sinon une tête à son nom (cible d'un autre serveur). */
    private ItemStack createPlayerHead(UUID id, String name, String title, List<String> lore) {
        Player player = Bukkit.getPlayer(id);
        ItemStack item = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            if (player != null) {
                meta.setOwningPlayer(player);
            } else {
                meta.setPlayerProfile(Bukkit.createProfile(id, name));
            }
            meta.setDisplayName(title);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private List<String> replacePlaceholders(List<String> lore, String targetName) {
        return lore.stream()
            .map(line -> line.replace("{player}", targetName))
            .collect(Collectors.toList());
    }
}
