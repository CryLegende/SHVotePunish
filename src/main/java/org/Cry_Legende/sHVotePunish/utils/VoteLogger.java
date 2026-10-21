package org.Cry_Legende.sHVotePunish.utils;

import org.Cry_Legende.sHVotePunish.SHVotePunish;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;

/**
 * Gère l'écriture des logs de vote dans un fichier dédié (vote_logs.txt).
 */
public class VoteLogger {

    private final SHVotePunish plugin;
    private final File logFile;

    public VoteLogger(SHVotePunish plugin) {
        this.plugin = plugin;
        // S'assure que le dossier du plugin existe
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        this.logFile = new File(plugin.getDataFolder(), "vote_logs.txt");
    }

    /**
     * Écrit un message de log dans le fichier.
     * Le message sera préfixé par la date et l'heure.
     *
     * @param message Le message à logger.
     */
    public void log(String message) {
        // Exécute l'écriture dans un thread séparé pour ne jamais ralentir le serveur.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                writer.write(String.format("[%s] %s%n", timestamp, message));
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Impossible d'écrire dans le fichier de log de vote.", e);
            }
        });
    }
}