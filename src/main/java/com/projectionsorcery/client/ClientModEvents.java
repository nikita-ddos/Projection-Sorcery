// src/main/java/com/projectionsorcery/client/ClientModEvents.java
package com.projectionsorcery.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.projectionsorcery.ProjectionSorcery;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = ProjectionSorcery.MODID, value = Dist.CLIENT)
public class ClientModEvents {
    private static final String CATEGORY = "key.categories.projectionsorcery";

    public static final KeyMapping SETTINGS = new KeyMapping("key.projectionsorcery.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY);
    public static final KeyMapping TARGET = new KeyMapping("key.projectionsorcery.target", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);
    public static final KeyMapping TOGGLE = new KeyMapping("key.projectionsorcery.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, CATEGORY);
    public static final KeyMapping CANCEL = new KeyMapping("key.projectionsorcery.cancel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY);
    public static final KeyMapping TOUCH = new KeyMapping("key.projectionsorcery.touch", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY);
    public static final KeyMapping QUICK = new KeyMapping("key.projectionsorcery.quick", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, CATEGORY);
    public static final KeyMapping QUICK_TARGET = new KeyMapping("key.projectionsorcery.quick_target", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, CATEGORY);

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(SETTINGS);
        event.register(TARGET);
        event.register(TOGGLE);
        event.register(CANCEL);
        event.register(TOUCH);
        event.register(QUICK);
        event.register(QUICK_TARGET);
    }
}