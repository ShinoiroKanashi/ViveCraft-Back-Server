package dev.vivecraft.backserver;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;

public final class BackServerConfigScreen extends Screen {
    private final Screen parent;

    private boolean enabled;

    public BackServerConfigScreen(Screen parent) {
        super(Component.translatable("vivecraft-back-server.config.title"));
        this.parent = parent;
        this.enabled = BackServerConfig.enabled;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 10;

        this.addRenderableWidget(CycleButton.onOffBuilder(this.enabled)
            .create(centerX - 100, y, 200, 20,
                Component.translatable("vivecraft-back-server.config.enabled"),
                (button, value) -> this.enabled = value));

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
            .bounds(centerX - 100, this.height - 27, 200, 20)
            .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 40, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        if (ViveCraftBackServer.CLIENT != null) {
            ViveCraftBackServer.CLIENT.setEnabled(this.enabled);
        } else {
            BackServerConfig.enabled = this.enabled;
            BackServerConfig.save();
        }

        Minecraft.getInstance().gui.setScreen(this.parent);
    }
}
