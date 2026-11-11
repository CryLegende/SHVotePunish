package org.Cry_Legende.sHVotePunish.utils;

import org.bukkit.Bukkit;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Tête d'un joueur du réseau. Le skin vient de SHSkins quand il est installé ; sans lui, la tête n'a que l'UUID
 * et le pseudo (le client affiche alors Steve ou Alex), mais le serveur ne fait jamais de recherche Mojang.
 */
public final class PlayerHeads {

    private PlayerHeads() {
    }

    /**
     * Pose le profil du joueur sur la tête (le nom, la lore et le reste de l'objet ne changent pas).
     *
     * @param uuid UUID du joueur, ou null pour celui de son pseudo sur le réseau
     * @param name pseudo du joueur, ou null si seul l'UUID est connu
     */
    public static void apply(SkullMeta meta, UUID uuid, String name) {
        if (uuid == null && name == null) {
            return;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("SHSkins")) {
            SHSkinsHook.apply(meta, uuid, name);
            return;
        }
        UUID id = uuid != null ? uuid : offlineUuid(name);
        meta.setPlayerProfile(Bukkit.createProfile(id, name));
    }

    /** UUID d'un joueur sur le réseau SkyHand (mode offline) : md5 de « OfflinePlayer:pseudo ». */
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
