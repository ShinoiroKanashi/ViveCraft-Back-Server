package dev.vivecraft.backserver;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class ViveCraftBackServer implements ClientModInitializer {
    public static final String MOD_ID = "vivecraft-back-server";
    public static BackServerClient CLIENT;

    @Override
    public void onInitializeClient() {
        BackServerConfig.load();
        CLIENT = new BackServerClient();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> CLIENT.onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CLIENT.onDisconnect());
        ClientTickEvents.END_CLIENT_TICK.register(CLIENT::tick);
    }
}
