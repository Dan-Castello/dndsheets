package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.OptionsSaveMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Lista/añade/borra las opciones de Raza, Trasfondo o Clase que ofrece el selector de la hoja (ver
 * {@code CharacterOptionsRegistry}). A diferencia de {@code ContentEntryListScreen} (armas/hechizos/
 * presets, que se fusionan por id), acá cualquier cambio reemplaza la lista completa de la categoría —
 * mismo comportamiento que ya tiene {@code /dndoptions load}, solo que sin escribir el JSON a mano.</p>
 */
public class OptionsManageScreen extends ListPickerScreen {
	private final String category;
	private final List<String> values;

	private OptionsManageScreen(String category, List<String> values, Screen parent) {
		super(Component.translatable("gui.dndsheets.options_manage.title", category), parent);
		this.category = category;
		this.values = values;
	}

	public static void open(String category, String arrayJson) {
		List<String> values = new ArrayList<>();
		for (JsonElement el : JsonParser.parseString(arrayJson).getAsJsonArray()) values.add(el.getAsString());
		Minecraft.getInstance().setScreen(new OptionsManageScreen(category, values, Minecraft.getInstance().screen));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (String value : values) {
			addRow(Component.translatable("gui.dndsheets.options_manage.delete", value), b -> {
				JsonArray remaining = new JsonArray();
				for (String other : values) if (!other.equals(value)) remaining.add(other);
				DndsheetsMod.PACKET_HANDLER.sendToServer(new OptionsSaveMessage(category, remaining.toString()));
			});
		}
		addRow(Component.translatable("gui.dndsheets.options_manage.add"), b -> OptionsAddScreen.open(category, values));
	}

	@Override
	protected Component emptyMessage() {
		return values.isEmpty() ? Component.translatable("gui.dndsheets.options_manage.empty") : null;
	}
}
