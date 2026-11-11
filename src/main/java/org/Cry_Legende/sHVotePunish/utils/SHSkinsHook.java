package org.Cry_Legende.sHVotePunish.utils;

import org.Cry_Legende.sHSkins.api.SHSkinsAPI;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.UUID;

/** Pont vers SHSkins. Cette classe n'est chargée que si SHSkins est installé : seule {@link PlayerHeads} l'appelle. */
final class SHSkinsHook {

    private SHSkinsHook() {
    }

    static void apply(SkullMeta meta, UUID uuid, String name) {
        SHSkinsAPI.applyHead(meta, uuid, name);
    }
}
