package net.hawthorn.dndsheets.client;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>HUD siempre visible (no hace falta abrir el Grimorio ni la hoja) con los recursos que Minecraft no
 * muestra de forma nativa: espacios de conjuro actuales/máx y oro. Se lee directamente de la hoja del
 * cliente ({@link SheetLoader#getClientSheet()}), la misma que ya mantiene sincronizada cada comando que
 * toca la hoja (spellslots, descansos, oro...), así que no necesita su propio mensaje de red.</p>
 */
@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ResourceHudOverlay {

	@SubscribeEvent
	public static void registerOverlays(RegisterGuiOverlaysEvent event) {
		event.registerAboveAll("dndsheets_resources", (gui, guiGraphics, partialTick, width, height) -> render(guiGraphics));
	}

	private static void render(GuiGraphics guiGraphics) {
		JsonObject sheet = SheetLoader.getClientSheet();
		if (sheet == null) return;

		int x = 6;
		int y = 6;
		int lineHeight = 10;
		var font = Minecraft.getInstance().font;

		int spellSlotsMax = sheet.has("spellSlotsMax") ? sheet.get("spellSlotsMax").getAsInt() : 0;
		if (spellSlotsMax > 0) {
			int current = sheet.has("spellSlotsCurrent") ? sheet.get("spellSlotsCurrent").getAsInt() : 0;
			guiGraphics.drawString(font, "Conjuros: " + current + "/" + spellSlotsMax, x, y, 0x55FFFF);
			y += lineHeight;
		}

		if (sheet.has("gold")) {
			guiGraphics.drawString(font, "Oro: " + sheet.get("gold").getAsInt(), x, y, 0xFFD700);
		}
	}
}
