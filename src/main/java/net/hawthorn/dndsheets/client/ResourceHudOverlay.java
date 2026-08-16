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

		//Las condiciones activas, en rojo y arriba del todo de lo demás. Estaban SOLO en el Panel de DM, así
		//que un jugador paralizado no tenía forma de saberlo: sus clics dejaban de hacer nada y eso se lee
		//como que el mod está roto, no como la regla que es. Media docena de reglas del motor dependen de
		//condiciones y ninguna se veía desde el lado de quien las sufre.
		String conditions = activeConditionLabels(sheet);
		if (!conditions.isEmpty()) {
			guiGraphics.drawString(font, conditions, x, y, 0xFF5555);
			y += lineHeight;
		}

		if (sheet.has("gold")) {
			guiGraphics.drawString(font, "Oro: " + sheet.get("gold").getAsInt(), x, y, 0xFFD700);
		}
	}

	/**
	 * <p>Etiquetas de las condiciones activas, separadas por coma. Se le quita el "@id" con el que viaja la
	 * fuente de cada una (ver {@code Combatant.formatEntry}): a quien la sufre le importa que está asustado,
	 * no el número de entidad que lo asustó.</p>
	 */
	private static String activeConditionLabels(JsonObject sheet) {
		if (!sheet.has("conditions")) return "";
		StringBuilder labels = new StringBuilder();
		for (var element : sheet.getAsJsonArray("conditions")) {
			String entry = element.getAsString();
			int at = entry.indexOf('@');
			if (labels.length() > 0) labels.append(", ");
			labels.append(at < 0 ? entry : entry.substring(0, at));
		}
		return labels.length() == 0 ? "" : "Estados: " + labels;
	}
}
