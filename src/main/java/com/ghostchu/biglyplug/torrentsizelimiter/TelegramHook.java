package com.ghostchu.biglyplug.torrentsizelimiter;

import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;

public class TelegramHook {
    public static boolean send(String apiKey, String chatId, String message) {
        try {
            URL url = new java.net.URL("https://api.telegram.org/bot" + apiKey + "/sendMessage");
            HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            OutputStreamWriter out = new java.io.OutputStreamWriter(conn.getOutputStream());
            out.write("chat_id=" + chatId + "&text=" + java.net.URLEncoder.encode(message, "UTF-8"));
            out.flush();
            out.close();
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
