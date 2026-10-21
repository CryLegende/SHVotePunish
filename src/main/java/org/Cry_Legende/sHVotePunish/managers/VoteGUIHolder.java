package org.Cry_Legende.sHVotePunish.managers;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Holder personnalisé pour identifier les inventaires de vote.
 * Permet de différencier notre menu des autres inventaires dans les listeners.
 */
public class VoteGUIHolder implements InventoryHolder {
    @Override
    public Inventory getInventory() {
        return null;
    }
}