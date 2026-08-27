package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.CharacterOptionsRegistry;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.network.SheetServerMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Selector de Raza/Trasfondo/Clase del propio mod: la lista la manda el servidor en el case
 * CHARACTER_OPTION de {@code BrowseListMessage} (pedida con {@code BrowseActionMessage.CHARACTER_OPTIONS}),
 * porque el registro de opciones solo vive en memoria del servidor — mismo patrón que
 * {@link PresetScreen}/{@link TraitGrantScreen}. Con el addon species instalado este selector no se usa
 * (Origins elige y el addon escribe la hoja); es el camino de RESPALDO del core solo — ver
 * {@code CharacterSetupScreen.openOriginPicker}. Elegir una opción escribe el valor directo en la hoja
 * del cliente y la sincroniza, sin pasar por el servidor para validarla (a diferencia de un preset, esto
 * no toca estadísticas): "characterClass"/"characterRace"/"background" ya son campos de edición libre del
 * jugador según {@code SheetServerMessage.PLAYER_EDITABLE_KEYS}, elegir de una lista fija en vez de
 * escribirlos a mano no cambia ese contrato.</p>
 *
 * <p>Vuelve a la MISMA pantalla que pidió la lista tanto al elegir una opción como al pulsar "&lt; Atrás"
 * o Escape — se pasa como {@code parent} a {@link ListPickerScreen}, capturado en el handler del mensaje
 * en el instante justo antes de navegar, cuando la pantalla activa es quien pidió.</p>
 */
public class CharacterOptionListScreen extends ListPickerScreen {
	private final String category;
	private final List<String> options;

	private CharacterOptionListScreen(Screen returnTo, String category, List<String> options) {
		super(Component.literal(titleFor(category)), returnTo);
		this.category = category;
		this.options = options;
	}

	public static void open(Screen returnTo, String category, List<String> options) {
		Minecraft.getInstance().setScreen(new CharacterOptionListScreen(returnTo, category, options));
	}

	private static String titleFor(String category) {
		return switch (category) {
			case CharacterOptionsRegistry.CLASS -> "Elige una clase";
			default -> "Elige una opción";
		};
	}

	private static String sheetFieldFor(String category) {
		return switch (category) {
			case CharacterOptionsRegistry.CLASS -> "characterClass";
			default -> "background";
		};
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (String option : options) {
			addRow(Component.literal(option), b -> {
				JsonObject sheet = SheetLoader.getClientSheet();
				if (sheet != null) {
					sheet.addProperty(sheetFieldFor(category), option);
					DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetServerMessage(sheet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
				}
				this.onClose();
			});
		}
	}

	@Override
	protected Component emptyMessage() {
		return options.isEmpty() ? Component.translatable("gui.dndsheets.character_option.empty") : null;
	}
}
