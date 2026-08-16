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

		//Lo que llevas ENCIMA y decide tu próxima tirada. Vivía todo en el servidor: recibías Inspiración
		//Bárdica y no lo sabías, armabas un Castigo y no sabías si seguía armado tres turnos después, y la
		//concentración —de lo que más se consulta en una mesa— solo existía como una línea de chat que se va
		//con el scroll. Un modificador que no se ve no se puede jugar; se descubre después, en el resultado.
		String held = heldEffects(sheet);
		if (!held.isEmpty()) {
			guiGraphics.drawString(font, held, x, y, 0xFFD9A0);
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
	/** Los "llevo esto encima" que cambian la próxima tirada: concentración, dado de inspiración, castigo armado, ventaja pendiente. */
	private static String heldEffects(JsonObject sheet) {
		StringBuilder held = new StringBuilder();
		if (sheet.has("concentratingOn")) append(held, "Concentrado: " + sheet.get("concentratingOn").getAsString());
		if (sheet.has("bardicInspiration")) append(held, "Inspiración +" + sheet.get("bardicInspiration").getAsInt());
		if (sheet.has("smitePending")) append(held, "Castigo armado");
		//"normal" es el valor de reposo, no una ventaja pendiente: enseñarlo sería una línea permanente que
		//no dice nada y que acabaría ignorándose junto con las que sí importan.
		if (sheet.has("nextAttackAdvantage")) {
			String advantage = sheet.get("nextAttackAdvantage").getAsString();
			if ("advantage".equals(advantage)) append(held, "Ventaja");
			else if ("disadvantage".equals(advantage)) append(held, "Desventaja");
		}
		return held.toString();
	}

	private static void append(StringBuilder to, String text) {
		if (to.length() > 0) to.append(" · ");
		to.append(text);
	}

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
