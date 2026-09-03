package dev.vivecraft.backserver;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BackServerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String endpoint = "wss://you-project.workers.dev";

    public static boolean enabled = true;

    private static Path path;

    private BackServerConfig() {}

    public static void load() {
        path = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getConfigDir().resolve("vivecraft-back-server.json");

        if (!Files.exists(path)) {
            save();
            return;
        }

        try {
            JsonObject o = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("enabled")) enabled = o.get("enabled").getAsBoolean();
            save();
        } catch (Exception e) {
            System.err.println("[ViveCraft Back Server] Could not read config: " + e);
        }
    }

    public static void save() {
        if (path == null) {
            path = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir().resolve("vivecraft-back-server.json");
        }

        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[ViveCraft Back Server] Could not save config: " + e);
        }
    }
}
