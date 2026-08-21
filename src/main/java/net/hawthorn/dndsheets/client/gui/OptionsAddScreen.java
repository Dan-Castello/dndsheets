package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonArray;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.OptionsSaveMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

//Añade UNA opción a una categoría de CharacterOptionsRegistry — abierto desde OptionsManageScreen. Manda
//la lista COMPLETA (actual + la nueva) por OptionsSaveMessage, ver esa clase para el porqué.
public class OptionsAddScreen extends SmallFormScreen {
	private final String category;
	private final List<String> current;
	private EditBox valueBox;

	private OptionsAddScreen(String category, List<String> current, Screen parent) {
		super(Component.translatable("gui.dndsheets.options_add.title"), 1, parent);
		this.category = category;
		this.current = current;
	}

	public static void open(String category, List<String> current) {
		Minecraft.getInstance().setScreen(new OptionsAddScreen(category, current, Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		valueBox = addField("Nueva opción", "", 64);
	}

	@Override
	protected void onConfirm() {
		String value = valueBox.getValue().trim();
		if (value.isEmpty()) return;

		JsonArray array = new JsonArray();
		for (String existing : current) array.add(existing);
		array.add(value);
		DndsheetsMod.PACKET_HANDLER.sendToServer(new OptionsSaveMessage(category, array.toString()));
	}
}
