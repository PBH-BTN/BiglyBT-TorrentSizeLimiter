package com.ghostchu.biglyplug.torrentsizelimiter;

import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public class TelegramHook {
    public static void send(String apiKey, String chatId, String message) {
        CompletableFuture.runAsync(()->{
            try {
                URL url = new java.net.URL("https://api.telegram.org/bot" + apiKey + "/sendMessage");
                HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                OutputStreamWriter out = new java.io.OutputStreamWriter(conn.getOutputStream());
                out.write("chat_id=" + chatId + "&text=" + java.net.URLEncoder.encode(message, StandardCharsets.UTF_8));
                out.flush();
                out.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}
