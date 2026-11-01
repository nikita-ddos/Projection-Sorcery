// src/main/java/com/projectionsorcery/client/SettingsScreen.java
package com.projectionsorcery.client;

import com.projectionsorcery.ClientConfig;
import com.projectionsorcery.ProjectionSorcery;
import com.projectionsorcery.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class SettingsScreen extends Screen {
    
    public SettingsScreen() {
        super(Component.literal("Settings"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        
        Button themeButton = Button.builder(themeLabel(), b -> {
            Theme next = ClientConfig.THEME.get().next();
            ClientConfig.THEME.set(next);
            ClientConfig.THEME.save();
            b.setMessage(themeLabel());
        }).bounds(cx - 100, this.height / 2 - 24, 200, 20).build();
        addRenderableWidget(themeButton);
        
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(cx - 100, this.height / 2 + 20, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 60, ClientConfig.THEME.get().primary);
    }

    private Component themeLabel() {
        return Component.literal("Theme: " + ClientConfig.THEME.get().name());
    }
}