package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.client.gui.components.ButtonListWidget;
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
public class GrimoireScreen extends Screen {
	private static final int WIDTH = 220;
	private static final int BUTTON_HEIGHT = 20;
	private static final int SPACING = 4;
	private static final int LIST_TOP = 40;

	private record KnownSpell(String id, String label) {}

	private KnownSpell selected;
	private Button castButton;
	private ButtonListWidget list;

	protected GrimoireScreen() {
		super(Component.literal("Grimorio"));
	}

	@Override
	protected void init() {
		List<KnownSpell> knownSpells = knownSpells();
		int left = (this.width - WIDTH) / 2;

		//Botón de lanzar fijo cerca del final de la pantalla, y la lista de hechizos con scroll ocupando
		//el resto: antes la lista entera (más el botón de lanzar) se centraba a mano sin ningún tope, así
		//que un grimorio con muchos hechizos aprendidos empujaba botones —incluido el de lanzar— fuera de
		//pantalla sin forma de alcanzarlos (ver AUDIT_UX.md).
		int listBottom = this.height - (BUTTON_HEIGHT + SPACING * 2);
		int listHeight = Math.max(BUTTON_HEIGHT, listBottom - LIST_TOP);
		list = new ButtonListWidget(left, LIST_TOP, WIDTH, listHeight, BUTTON_HEIGHT + SPACING);
		for (KnownSpell spell : knownSpells) {
			Button button = Button.builder(Component.literal(spell.label()), b -> {
				selected = spell;
				castButton.active = true;
				castButton.setMessage(Component.literal("Lanzar: " + spell.label()));
			}).bounds(0, 0, WIDTH, BUTTON_HEIGHT).build();
			this.addWidget(button);
			list.addRow(button);
		}
		this.addRenderableWidget(list);

		//Lanzar ya no pasa por un solo clic sobre el hechizo: un jugador que clica para leer qué hay en la
		//lista no quiere gastar un espacio de conjuro real por curiosidad (ver AUDIT_UX.md, Jugador #2).
		//Elegir un hechizo solo lo selecciona; este botón aparte es el que de verdad lo lanza.
		castButton = this.addRenderableWidget(Button.builder(Component.literal("Elige un hechizo para lanzarlo"), button -> {
			if (selected == null) return;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new SpellCastMessage(selected.id()));
		}).bounds(left, LIST_TOP + listHeight + SPACING, WIDTH, BUTTON_HEIGHT).build());
		castButton.active = false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	//Ver PresetScreen.mouseScrolled: sin esto, el scroll solo funciona pasando el mouse por huecos sin
	//botón, se detiene en cuanto queda sobre una fila.
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		return list.mouseScrolled(mouseX, mouseY, delta) || super.mouseScrolled(mouseX, mouseY, delta);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);

		JsonObject sheet = SheetLoader.getClientSheet();
		int current = sheet != null && sheet.has("spellSlotsCurrent") ? sheet.get("spellSlotsCurrent").getAsInt() : 0;
		int max = sheet != null && sheet.has("spellSlotsMax") ? sheet.get("spellSlotsMax").getAsInt() : 0;

		guiGraphics.drawCenteredString(this.font, Component.literal("Grimorio"), this.width / 2, 12, 0xFFFFFF);
		guiGraphics.drawCenteredString(this.font, Component.literal("Espacios de conjuro: " + current + "/" + max), this.width / 2, 26, 0xAAAAAA);

		if (knownSpells().isEmpty()) {
			guiGraphics.drawCenteredString(this.font, Component.literal("No conoces ningún hechizo. Pide al DM /dndspells learn."), this.width / 2, this.height / 2, 0x888888);
		}

		super.render(guiGraphics, mouseX, mouseY, partialTicks);
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
