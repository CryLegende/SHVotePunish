package org.Cry_Legende.sHVotePunish.utils;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.URL;

/**
 * Une classe utilitaire simple pour envoyer des messages à un webhook Discord.
 */
public class DiscordWebhook {

    private final String url;
    private String content;

    /**
     * Construit une nouvelle instance de DiscordWebhook.
     *
     * @param url L'URL complète du webhook Discord.
     */
    public DiscordWebhook(String url) {
        this.url = url;
    }

    /**
     * Définit le contenu JSON à envoyer avec le webhook.
     *
     * @param content La chaîne de caractères JSON complète.
     *                Exemple: {"content": "Hello World", "username": "Bot"}
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * Exécute la requête et envoie le message au webhook.
     *
     * @throws IOException              Si une erreur d'entrée/sortie se produit lors de la connexion.
     * @throws IllegalArgumentException Si le contenu n'a pas été défini avant l'exécution.
     */
    public void execute() throws IOException {
        if (this.content == null || this.content.isEmpty()) {
            throw new IllegalArgumentException("Le contenu (JSON) ne peut pas être vide.");
        }

        URL urlObject = new URL(this.url);
        HttpsURLConnection connection = (HttpsURLConnection) urlObject.openConnection();

        try {
            // Configuration de la requête POST
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);

            // Définition des en-têtes de la requête
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("User-Agent", "SHVotePunish-Plugin");

            // Écriture du contenu JSON dans le corps de la requête
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = this.content.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Lecture de la réponse pour finaliser la requête (bonne pratique)
            // Un code de réponse 204 (No Content) est une réussite pour les webhooks Discord.
            int responseCode = connection.getResponseCode();
            if (responseCode >= 300) {
                // Gère les cas d'erreur comme une URL invalide (404) ou une mauvaise requête (400)
                try (java.io.InputStream errorStream = connection.getErrorStream()) {
                    // On pourrait lire l'erreur ici pour un logging plus détaillé si nécessaire
                    throw new IOException("La requête au webhook a échoué avec le code de réponse : " + responseCode);
                }
            } else {
                // Force la lecture du flux d'entrée pour s'assurer que la requête est bien envoyée.
                connection.getInputStream().close();
            }

        } finally {
            // S'assure que la connexion est toujours fermée, même en cas d'erreur.
            connection.disconnect();
        }
    }
}
