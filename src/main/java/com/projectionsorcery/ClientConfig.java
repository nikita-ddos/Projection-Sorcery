package com.projectionsorcery;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ClientConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();
    public static final ModConfigSpec.EnumValue<Theme> THEME = B
            .comment("Color theme for afterimages, paths and HUD")
            .defineEnum("theme", Theme.NAOYA);
    public static final ModConfigSpec SPEC = B.build();
}
