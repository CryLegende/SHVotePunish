package org.Cry_Legende.sHVotePunish.data;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Décide qu'une sanction n'est appliquée qu'une fois pour le réseau : le serveur où le vote réussit inscrit d'abord le
 * joueur dans sa liste en mémoire, puis la base tranche par un INSERT IGNORE. Un seul appel reçoit « vrai », les autres
 * (même serveur ou autre serveur) reçoivent « faux » et n'appliquent rien.
 */
final class PunishClaim {

    private PunishClaim() {
    }

    /**
     * @param memory liste des sanctionnés de ce serveur (ensemble sûr entre fils)
     * @param lists  base partagée
     * @return vrai si la sanction doit être appliquée ; une base injoignable laisse passer la sanction plutôt que de la perdre
     */
    static CompletableFuture<Boolean> claim(Set<UUID> memory, SharedLists lists, UUID uuid, String name) {
        if (!memory.add(uuid)) {
            return CompletableFuture.completedFuture(false);
        }
        return lists.claimPunished(uuid, name).handle((won, error) -> error != null || Boolean.TRUE.equals(won));
    }
}
