package org.Cry_Legende.sHVotePunish.listeners;

import org.Cry_Legende.sHVotePunish.SHVotePunish;
import org.Cry_Legende.sHVotePunish.managers.ConfirmationGUIHolder;
import org.Cry_Legende.sHVotePunish.managers.GUIManager;
import org.Cry_Legende.sHVotePunish.managers.VoteGUIHolder;
import org.Cry_Legende.sHVotePunish.managers.VoteManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Gère toutes les interactions des joueurs avec les inventaires (GUI) du plugin.
 */
public class InventoryListener implements Listener {

    private final SHVotePunish plugin;
    private final VoteManager voteManager;

    public InventoryListener(SHVotePunish plugin) {
        this.plugin = plugin;
        this.voteManager = plugin.getVoteManager();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();
        InventoryHolder holder = event.getInventory().getHolder();

        // Vérifie si l'inventaire est un menu du plugin
        if (holder instanceof ConfirmationGUIHolder || holder instanceof VoteGUIHolder) {
            // Empêche le joueur de prendre l'item, quelle que soit l'action
            event.setCancelled(true);

            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getItemMeta() == null) return;
            PersistentDataContainer container = clickedItem.getItemMeta().getPersistentDataContainer();

            // Gestion du clic dans le menu de confirmation
            if (holder instanceof ConfirmationGUIHolder) {
                if (container.has(GUIManager.CONFIRM_BUTTON_KEY, PersistentDataType.STRING)) {
                    String targetUUIDString = container.get(GUIManager.CONFIRM_BUTTON_KEY, PersistentDataType.STRING);
                    if (targetUUIDString == null) return;

                    Player target = Bukkit.getPlayer(UUID.fromString(targetUUIDString));
                    if (target == null) {
                        player.sendMessage(plugin.getConfigManager().getPlayerNotFoundMessage());
                        player.closeInventory();
                        return;
                    }
                    player.closeInventory();
                    voteManager.startVote(player, target);
                }
            }
            // Gestion du clic dans le menu de vote
            else if (holder instanceof VoteGUIHolder) {
                if (container.has(GUIManager.VOTE_BUTTON_KEY, PersistentDataType.STRING)) {
                    String targetUUIDString = container.get(GUIManager.VOTE_BUTTON_KEY, PersistentDataType.STRING);
                    if (targetUUIDString == null) return;

                    Player target = Bukkit.getPlayer(UUID.fromString(targetUUIDString));
                    if (target == null || !voteManager.isVoteInProgress(target.getUniqueId())) {
                        player.sendMessage("§cCe vote est terminé.");
                        player.closeInventory();
                        return;
                    }
                    player.closeInventory();
                    voteManager.addVote(player, target);
                    player.sendMessage("§aVotre vote a été enregistré !");
                }
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        // Un objet glissé sur le menu y resterait et serait perdu à la fermeture
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof ConfirmationGUIHolder || holder instanceof VoteGUIHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // Si le joueur ferme le menu de vote, on le retire de la liste des spectateurs
        // pour qu'il ne reçoive plus de mises à jour inutiles.
        if (event.getInventory().getHolder() instanceof VoteGUIHolder) {
            Player player = (Player) event.getPlayer();

            // Trouve le vote que le joueur regardait et le retire de la liste des "viewers"
            for (VoteManager.ActiveVote vote : voteManager.getActiveVotes().values()) {
                if (vote.getViewers().contains(player.getUniqueId())) {
                    vote.removeViewer(player.getUniqueId());
                    return;
                }
            }
        }
    }
}