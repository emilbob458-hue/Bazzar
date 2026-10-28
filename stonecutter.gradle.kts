plugins {
    id("dev.kikugie.stonecutter")
    id("net.fabricmc.fabric-loom") version "1.19.3" apply false
    id("net.fabricmc.fabric-loom-remap") version "1.19.3" apply false
}

stonecutter active "1.21.11"

stonecutter parameters {
    replacements {
        string(current.parsed >= "26.1") {
            replace("GuiGraphics", "GuiGraphicsExtractor")
            replace("graphics.drawString(", "graphics.text(")
            replace("graphics.drawCenteredString(", "graphics.centeredText(")
            replace("graphics.renderItem(", "graphics.item(")
            replace("public void render(", "public void extractRenderState(")
            replace("super.render(graphics", "super.extractRenderState(graphics")
            replace("ScreenEvents.afterRender(", "ScreenEvents.afterExtract(")
            replace("ClientCommandManager", "ClientCommands")
            replace("keybinding.v1.KeyBindingHelper", "keymapping.v1.KeyMappingHelper")
            replace("KeyBindingHelper.registerKeyBinding", "KeyMappingHelper.registerKeyMapping")
            replace("ClickType", "ContainerInput")
            replace("handleInventoryMouseClick(", "handleContainerInput(")
            replace("getItemHolder()", "typeHolder()")
        }
        regex(current.parsed >= "26.1") {
            replace(
                "Items\\.([A-Z_]+?)_DYE\\b" to "Items.DYE.pick(DyeColor.$1)",
                "Items\\.DYE\\.pick\\(DyeColor\\.([A-Z_]+)\\)" to "Items.$1_DYE",
            )
            replace(
                "Items\\.([A-Z_]+?)_GLAZED_TERRACOTTA\\b" to "Items.GLAZED_TERRACOTTA.pick(DyeColor.$1)",
                "Items\\.GLAZED_TERRACOTTA\\.pick\\(DyeColor\\.([A-Z_]+)\\)" to "Items.$1_GLAZED_TERRACOTTA",
            )
        }
    }
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds the mod for every Minecraft version into build/libs/"
    dependsOn(stonecutter.tasks.named("buildAndCollect"))
}
