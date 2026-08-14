package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.network.SpellCastMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Ventana aparte para lanzar hechizos conocidos, en vez de una 4ª pestaña en la hoja de personaje
 * (no hay textura de fondo para eso todavía). Se abre desde el botón "Grimorio" de la pestaña Main.
 * Apunta al objetivo que estés mirando (ver {@link net.hawthorn.dndsheets.SpellCastManager}).</p>
 *
 * <p>El nombre y nivel de cada hechizo se leen directamente de la hoja (los guarda {@code /dndspells
 * learn} junto al id), no de {@code SpellRegistry}: ese registro solo vive en memoria del servidor, así
 * que en un servidor dedicado de verdad (cliente y servidor son procesos distintos) el cliente nunca
 * tendría su propia copia y todo se vería como "desconocido".</p>
 */
public class GrimoireScreen extends ListPickerScreen {
	private static final int SUBTITLE_Y = 30;

	private record KnownSpell(String id, String label) {}

	private KnownSpell selected;
	private Button castButton;

	protected GrimoireScreen(Screen parent) {
		super(Component.literal("Grimorio"), parent);
	}

	@Override
	protected int buttonWidth() {
		return 220;
	}

	@Override
	protected int listTop() {
		return SUBTITLE_Y + 14;
	}

	//Deja hueco fijo bajo la lista para el botón de lanzar, que no se desplaza con los hechizos.
	@Override
	protected int listHeight() {
		return super.listHeight() - BUTTON_HEIGHT - SPACING;
	}

	@Override
	protected void init() {
		super.init();

		int left = (this.width - buttonWidth()) / 2;
		int castY = listTop() + listHeight() + SPACING;

		//Lanzar ya no pasa por un solo clic sobre el hechizo: un jugador que clica para leer qué hay en la
		//lista no quiere gastar un espacio de conjuro real por curiosidad (ver AUDIT_UX.md, Jugador #2).
		//Elegir un hechizo solo lo selecciona; este botón aparte es el que de verdad lo lanza.
		castButton = this.addRenderableWidget(Button.builder(Component.literal("Elige un hechizo para lanzarlo"), button -> {
			if (selected == null) return;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new SpellCastMessage(selected.id()));
		}).bounds(left, castY, buttonWidth(), BUTTON_HEIGHT).build());
		castButton.active = false;
	}

	@Override
	protected void buildRows() {
		for (KnownSpell spell : knownSpells()) {
			addRow(Component.literal(spell.label()), b -> {
				selected = spell;
				castButton.active = true;
				castButton.setMessage(Component.literal("Lanzar: " + spell.label()));
			});
		}
	}

	@Override
	protected Component emptyMessage() {
		return hasNoKnownSpells() ? Component.literal("No conoces ningún hechizo. Pide al DM /dndspells learn.") : null;
	}

	//F9 del audit: evita reconstruir toda la lista de KnownSpell solo para saber si está vacía.
	private static boolean hasNoKnownSpells() {
		JsonObject sheet = SheetLoader.getClientSheet();
		return sheet == null || !sheet.has("spells") || sheet.getAsJsonArray("spells").isEmpty();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.render(guiGraphics, mouseX, mouseY, partialTicks);

		JsonObject sheet = SheetLoader.getClientSheet();
		int current = sheet != null && sheet.has("spellSlotsCurrent") ? sheet.get("spellSlotsCurrent").getAsInt() : 0;
		int max = sheet != null && sheet.has("spellSlotsMax") ? sheet.get("spellSlotsMax").getAsInt() : 0;
		guiGraphics.drawCenteredString(this.font, Component.literal("Espacios de conjuro: " + current + "/" + max), this.width / 2, SUBTITLE_Y, GuiStyle.SUBTITLE_COLOR);
	}

	private static List<KnownSpell> knownSpells() {
		List<KnownSpell> result = new ArrayList<>();
		JsonObject sheet = SheetLoader.getClientSheet();
		if (sheet == null || !sheet.has("spells")) return result;

		JsonArray spells = sheet.getAsJsonArray("spells");
		for (int i = 0; i < spells.size(); i++) {
			JsonObject entry = spells.get(i).getAsJsonObject();
			String id = entry.has("id") ? entry.get("id").getAsString() : "";
			String name = entry.has("name") ? entry.get("name").getAsString() : id;
			int level = entry.has("level") ? entry.get("level").getAsInt() : 0;
			result.add(new KnownSpell(id, name + " (nv. " + level + ")"));
		}
		return result;
	}
}
