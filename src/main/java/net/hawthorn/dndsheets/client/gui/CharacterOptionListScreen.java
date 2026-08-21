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
 * <p>Selector de Raza/Trasfondo/Clase: la lista la manda el servidor en {@code
 * CharacterOptionsListMessage} (pedida por {@code CharacterOptionsRequestMessage} al clicar el campo en
 * la hoja), porque el registro de opciones solo vive en memoria del servidor — mismo patrón que
 * {@link PresetScreen}/{@link TraitGrantScreen}. Elegir una escribe el valor directo en la hoja del
 * cliente y la sincroniza, sin pasar por el servidor para validarla (a diferencia de un preset, esto no
 * toca estadísticas): "characterClass"/"characterRace"/"background" ya son campos de edición libre del
 * jugador según {@code SheetServerMessage.PLAYER_EDITABLE_KEYS}, elegir de una lista fija en vez de
 * escribirlos a mano no cambia ese contrato.</p>
 *
 * <p>Vuelve a la MISMA hoja que pidió la lista tanto al elegir una opción como al pulsar "&lt; Atrás"
 * o Escape — se pasa como {@code parent} a {@link ListPickerScreen} (capturado en {@code
 * CharacterOptionsListMessage} en el instante justo antes de navegar, cuando la pantalla activa es la
 * hoja). El contenedor nunca se cerró (solo cambió qué {@code Screen} se dibuja), así que reabrir la
 * MISMA instancia es seguro; su {@code init()} se vuelve a ejecutar solo, releyendo de {@code
 * SheetLoader.getClientSheet()} (ya actualizada aquí) sin perder el resto de campos gracias al guardado
 * completo que {@code CharacterSheetScreen.requestOptionPicker} hace antes de abrir este selector.</p>
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
					DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetServerMessage(sheet.toString().getBytes()));
				}
				this.onClose();
			});
		}
	}

	@Override
	protected Component emptyMessage() {
		return options.isEmpty() ? Component.literal("No hay opciones cargadas (pide al DM /dndoptions load).") : null;
	}
}
