package com.ghostchu.biglyplug.torrentsizelimiter;

import com.google.gson.Gson;

import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

public class TelegramHook {
    private static final Gson GSON = new Gson();

    public static void send(String apiKey, String chatId, String message) {
        CompletableFuture.runAsync(() -> {
            try (HttpClient client = HttpClient.newHttpClient()) {
                Map<String, Object> jsonBody = Map.of(
                        "chat_id", chatId,
                        "text", message
                );
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URL("https://api.telegram.org/bot" + apiKey + "/sendMessage").toURI())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(jsonBody)))
                        .build();
                var resp = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                System.out.println(resp.body());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}
