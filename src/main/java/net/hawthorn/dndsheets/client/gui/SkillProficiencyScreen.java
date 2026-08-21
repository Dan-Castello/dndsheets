package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.RollIndex;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>En qué habilidades es competente este personaje. Hasta ahora la competencia se conseguía escribiendo
 * {@code + $prof} a mano en la expresión de la tirada, cosa que además <b>un jugador no puede hacer</b>:
 * {@code "skills"} es una clave de solo-operador en {@code SheetServerMessage} desde que se cerró el
 * agujero de que cualquiera se reescribiera sus propias tiradas. O sea que la mitad de una ficha de nivel 1
 * dependía de que el DM la escribiera por ti.</p>
 *
 * <p>Aquí el cliente solo manda <b>qué habilidad</b>, y la expresión la escribe el servidor: se puede pedir
 * competencia, no un {@code +99}. Es la misma frontera de siempre —el cliente pide, el servidor decide— y
 * es lo que permite que esto sea una acción del jugador y no otra cosa que tenga que hacer el DM.</p>
 *
 * <p>No se repinta al pulsar: la fila cambia cuando llega la hoja nueva del servidor
 * ({@link #refreshIfOpen}), por el mismo motivo que {@link CharacterListScreen} tampoco lo hace — pintar
 * una marca que el servidor todavía no ha concedido es enseñar un estado que no existe.</p>
 *
 * <p><b>No limita cuántas ni cuáles</b>, a propósito: en 5e el número y la lista salen de la clase y el
 * trasfondo, y esa parte todavía no está (ver Fase 5.2). Un límite inventado sería peor que ninguno —
 * bloquearía mesas legítimas, y en la mesa el DM ya mira la ficha.</p>
 */
public class SkillProficiencyScreen extends ListPickerScreen {

	private SkillProficiencyScreen(Screen parent) {
		super(Component.literal("Competencias de habilidad"), parent);
	}

	public static void open(Screen parent) {
		Minecraft.getInstance().setScreen(new SkillProficiencyScreen(parent));
	}

	/** La llama {@code SheetClientMessage} cuando llega una hoja completa: ver el comentario de la clase. */
	public static void refreshIfOpen() {
		if (Minecraft.getInstance().screen instanceof SkillProficiencyScreen screen) {
			screen.rebuildWidgets();
		}
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		JsonObject sheet = SheetLoader.getClientSheet();
		for (int index = 0; index < RollIndex.SKILL_COUNT; index++) {
			int skill = index;
			boolean proficient = RollIndex.isSkillProficient(sheet, index);
			Component label = Component.translatable(RollIndex.skillLangKey(index))
				.copy()
				.append(Component.literal(proficient ? "  ✔" : "").withStyle(ChatFormatting.GREEN));
			addRow(label, button -> DndsheetsMod.PACKET_HANDLER.sendToServer(
				new BrowseActionMessage(BrowseActionMessage.Action.SKILL_TOGGLE, String.valueOf(skill))));
		}
	}
}
