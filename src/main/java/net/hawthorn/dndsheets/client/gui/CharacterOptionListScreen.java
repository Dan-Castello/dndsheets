package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.CharacterOptionsRegistry;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.client.gui.components.ButtonListWidget;
import net.hawthorn.dndsheets.network.SheetServerMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Selector de Raza/Trasfondo/Clase: la lista la manda el servidor en {@code
 * CharacterOptionsListMessage} (pedida por {@code CharacterOptionsRequestMessage} al clicar el campo en
 * la hoja), porque el registro de opciones solo vive en memoria del servidor — mismo patrón que
 * {@link PresetScreen}/{@link TraitGrantScreen}. Elegir una escribe el valor directo en la hoja del
 * cliente y la sincroniza, sin pasar por el servidor para validarla (a diferencia de un preset, esto no
 * toca estadísticas): "characterClass"/"characterRace"/"background" ya son campos de edición libre del
 * jugador según {@code SheetServerMessage.PLAYER_EDITABLE_KEYS}, elegir de una lista fija en vez de
 * escribirlos a mano no cambia ese contrato.</p>
 *
 * <p>A diferencia de Presets (que sí cierra todo, ver AUDIT_UX.md Jugador fricción media #7), aquí se
 * vuelve a la MISMA hoja que pidió la lista tanto al elegir como al cancelar/Escape: {@code returnTo} se
 * captura en {@code CharacterOptionsListMessage} en el instante justo antes de navegar (la pantalla
 * activa en ese momento es la hoja), y {@link #onClose()} vuelve ahí en vez de cerrar todo. El
 * contenedor nunca se cerró (solo cambió qué {@code Screen} se dibuja), así que reabrir la MISMA
 * instancia es seguro; su {@code init()} se vuelve a ejecutar solo, releyendo de {@code
 * SheetLoader.getClientSheet()} (ya actualizada aquí) sin perder el resto de campos gracias al guardado
 * completo que {@code CharacterSheetScreen.requestOptionPicker} hace antes de abrir este selector.</p>
 */
public class CharacterOptionListScreen extends Screen {
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int SPACING = 4;

	private final CharacterSheetScreen returnTo;
	private final String category;
	private final List<String> options;
	private ButtonListWidget list;

	private CharacterOptionListScreen(CharacterSheetScreen returnTo, String category, List<String> options) {
		super(Component.literal(titleFor(category)));
		this.returnTo = returnTo;
		this.category = category;
		this.options = options;
	}

	public static void open(CharacterSheetScreen returnTo, String category, List<String> options) {
		Minecraft.getInstance().setScreen(new CharacterOptionListScreen(returnTo, category, options));
	}

	private static String titleFor(String category) {
		return switch (category) {
			case CharacterOptionsRegistry.RACE -> "Elige una raza";
			case CharacterOptionsRegistry.BACKGROUND -> "Elige un trasfondo";
			case CharacterOptionsRegistry.CLASS -> "Elige una clase";
			default -> "Elige una opción";
		};
	}

	private static String sheetFieldFor(String category) {
		return switch (category) {
			case CharacterOptionsRegistry.RACE -> "characterRace";
			case CharacterOptionsRegistry.CLASS -> "characterClass";
			default -> "background";
		};
	}

	@Override
	protected void init() {
		list = new ButtonListWidget((this.width - BUTTON_WIDTH) / 2, 30, BUTTON_WIDTH, this.height - 44 - BUTTON_HEIGHT - SPACING, BUTTON_HEIGHT + SPACING);
		for (String option : options) {
			Button button = Button.builder(Component.literal(option), b -> {
				JsonObject sheet = SheetLoader.getClientSheet();
				if (sheet != null) {
					sheet.addProperty(sheetFieldFor(category), option);
					DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetServerMessage(sheet.toString().getBytes()));
				}
				this.onClose();
			}).bounds(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT).build();
			this.addWidget(button);
			list.addRow(button);
		}
		this.addRenderableWidget(list);

		this.addRenderableWidget(Button.builder(Component.literal("Cancelar"), b -> this.onClose())
			.bounds((this.width - BUTTON_WIDTH) / 2, this.height - BUTTON_HEIGHT - 8, BUTTON_WIDTH, BUTTON_HEIGHT).build());
	}

	@Override
	public void onClose() {
		if (returnTo != null) Minecraft.getInstance().setScreen(returnTo);
		else super.onClose();
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
		guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);

		if (options.isEmpty()) {
			guiGraphics.drawCenteredString(this.font, Component.literal("No hay opciones cargadas (pide al DM /dndoptions load)."), this.width / 2, this.height / 2, 0x888888);
		}

		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
