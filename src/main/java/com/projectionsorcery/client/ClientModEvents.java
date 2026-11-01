// src/main/java/com/projectionsorcery/client/ClientModEvents.java
package com.projectionsorcery.client;

import com.projectionsorcery.ProjectionSorcery;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent; // Assuming you have this, adjust if needed

@EventBusSubscriber(modid = ProjectionSorcery.MODID, value = Dist.CLIENT)
public class ClientModEvents {

    // Your existing event methods go here. 
    // For example, if you are using this class to register screens or keybindings:
    
    /*
    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        // ...
    }
    */
}