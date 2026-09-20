package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.resources.language.I18n;
import com.google.gson.JsonArray;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.OptionsSaveMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

//Adds ONE option to a CharacterOptionsRegistry category — opened from OptionsManageScreen. Sends
//the COMPLETE list (current + the new one) via OptionsSaveMessage, see that class for why.
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
		valueBox = addField(I18n.get("gui.dndsheets.form.new_option"), "", 64);
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
