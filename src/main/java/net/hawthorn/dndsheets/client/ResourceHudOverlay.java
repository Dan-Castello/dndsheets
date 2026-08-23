package net.hawthorn.dndsheets.client;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.client.gui.GuiStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>Panel de efectos activos: siempre visible (no hace falta abrir el Grimorio ni la hoja, ni estar en
 * combate) con lo que Minecraft no muestra de forma nativa — condiciones, PG temporales, concentración,
 * inspiración/castigo/ventaja pendientes, espacios de conjuro y oro. Se lee directamente de la hoja del
 * cliente ({@link SheetLoader#getClientSheet()}), la misma que ya mantiene sincronizada cada comando que
 * la toca (espacios, descansos, condiciones, oro...), así que no necesita su propio mensaje de red.</p>
 *
 * <p>Es el complemento persistente de {@link TurnHudOverlay}, que solo existe mientras hay un combate
 * activo: la mitad de esto (condiciones, concentración) importa también fuera de turnos, así que vive en
 * su propio panel, en la esquina opuesta de la pantalla, con el mismo aspecto de tomo (ver
 * {@link GuiStyle}) para que las dos piezas se lean como una sola interfaz y no como dos mods distintos.</p>
 */
@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ResourceHudOverlay {

	//PADDING no baja de 7: con menos, el texto se mete debajo de la cantonera de latón de la esquina (ver
	//GuiStyle — mismo límite que TurnHudOverlay). El resto, más apretado a pedido.
	private static final int PADDING = 7;
	private static final int ROW_HEIGHT = 9;
	private static final int MIN_WIDTH = 95;

	@SubscribeEvent
	public static void registerOverlays(RegisterGuiOverlaysEvent event) {
		event.registerAboveAll("dndsheets_resources", (gui, guiGraphics, partialTick, width, height) -> render(guiGraphics));
	}

	//Las líneas del HUD, ya montadas, y la versión de hoja con la que se montaron. Este render corre por
	//FOTOGRAMA y sin necesidad de abrir nada, así que es el código que más veces por segundo se ejecuta del
	//mod; rehacer estas cadenas a 120 fps era ~2.500 asignaciones por segundo para un texto que solo cambia
	//cuando cambia la hoja. Cada línea lleva su color: no todas significan lo mismo (un estado no es un
	//recurso), así que un solo color para todo el panel las volvería indistinguibles de un vistazo.
	private record Line(String text, int color) {}

	private static int cachedVersion = -1;
	private static Line[] cachedLines = new Line[0];

	private static void rebuild(JsonObject sheet) {
		java.util.List<Line> lines = new java.util.ArrayList<>();

		int spellSlotsMax = sheet.has("spellSlotsMax") ? sheet.get("spellSlotsMax").getAsInt() : 0;
		if (spellSlotsMax > 0) {
			int slots = sheet.has("spellSlotsCurrent") ? sheet.get("spellSlotsCurrent").getAsInt() : 0;
			lines.add(new Line("Conjuros: " + slots + "/" + spellSlotsMax, 0xFF55FFFF));
		}

		//PG temporales: un colchón que Minecraft no representa (su barra de vida es solo la real), así que
		//sin esto un jugador con PG temporales no tenía forma de saber cuántos le quedan de ese colchón
		//hasta que un golpe se los empieza a comer.
		int temporaryHp = sheet.has("temporaryHp") ? sheet.get("temporaryHp").getAsInt() : 0;
		if (temporaryHp > 0) lines.add(new Line("PG temporales: " + temporaryHp, 0xFF7FE0A0));

		//Las condiciones activas, en rojo y arriba del todo de lo demás. Estaban SOLO en el Panel de DM, así
		//que un jugador paralizado no tenía forma de saberlo: sus clics dejaban de hacer nada y eso se lee
		//como que el mod está roto, no como la regla que es. Media docena de reglas del motor dependen de
		//condiciones y ninguna se veía desde el lado de quien las sufre.
		String conditions = activeConditionLabels(sheet);
		if (!conditions.isEmpty()) lines.add(new Line(conditions, 0xFFFF5555));

		//Lo que llevas ENCIMA y decide tu próxima tirada. Vivía todo en el servidor: recibías Inspiración
		//Bárdica y no lo sabías, armabas un Castigo y no sabías si seguía armado tres turnos después, y la
		//concentración —de lo que más se consulta en una mesa— solo existía como una línea de chat que se va
		//con el scroll. Un modificador que no se ve no se puede jugar; se descubre después, en el resultado.
		String held = heldEffects(sheet);
		if (!held.isEmpty()) lines.add(new Line(held, 0xFFFFD9A0));

		if (sheet.has("gold")) lines.add(new Line("Oro: " + sheet.get("gold").getAsInt(), 0xFFFFD700));

		cachedLines = lines.toArray(new Line[0]);
	}

	private static void render(GuiGraphics guiGraphics) {
		JsonObject sheet = SheetLoader.getClientSheet();
		if (sheet == null) return;

		int version = SheetLoader.clientSheetVersion();
		if (version != cachedVersion) {
			rebuild(sheet);
			cachedVersion = version;
		}
		if (cachedLines.length == 0) return; //Nada activo: mejor sin panel que un panel vacío ocupando esquina.

		Font font = Minecraft.getInstance().font;
		int contentWidth = MIN_WIDTH;
		for (Line line : cachedLines) contentWidth = Math.max(contentWidth, font.width(line.text()));

		int top = 8;
		int left = 8;
		int right = left + contentWidth + 2 * PADDING;
		int bottom = top + 2 * PADDING - 4 + cachedLines.length * ROW_HEIGHT;

		GuiStyle.panel(guiGraphics, left, top, right, bottom);

		int textX = left + PADDING;
		int y = top + PADDING - 2;
		for (Line line : cachedLines) {
			guiGraphics.drawString(font, line.text(), textX, y, line.color());
			y += ROW_HEIGHT;
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
}
