package com.projectionsorcery.client;

import com.projectionsorcery.ProjectionSorcery;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = ProjectionSorcery.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {
    private static final String CAT = "key.categories.projectionsorcery";
    public static final KeyMapping TOGGLE = new KeyMapping("key.projectionsorcery.toggle", GLFW.GLFW_KEY_R, CAT);
    public static final KeyMapping CANCEL = new KeyMapping("key.projectionsorcery.cancel", GLFW.GLFW_KEY_X, CAT);
    public static final KeyMapping QUICK = new KeyMapping("key.projectionsorcery.quick", GLFW.GLFW_KEY_V, CAT);
    public static final KeyMapping TOUCH = new KeyMapping("key.projectionsorcery.touch", GLFW.GLFW_KEY_C, CAT);
    public static final KeyMapping TARGET = new KeyMapping("key.projectionsorcery.target", GLFW.GLFW_KEY_Z, CAT);
    public static final KeyMapping QUICK_TARGET = new KeyMapping("key.projectionsorcery.quick_target", GLFW.GLFW_KEY_B, CAT);
    public static final KeyMapping SETTINGS = new KeyMapping("key.projectionsorcery.settings", GLFW.GLFW_KEY_K, CAT);

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
        event.register(CANCEL);
        event.register(QUICK);
        event.register(TOUCH);
        event.register(TARGET);
        event.register(QUICK_TARGET);
        event.register(SETTINGS);
    }

    @SubscribeEvent
    public static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(ProjectionSorcery.MODID, "hud"), ClientEvents::renderHud);
    }
}