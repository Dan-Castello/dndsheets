package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Create a character by typing its name. This was the last piece of character management that still
 * required a command: you could switch, delete, and level up from the screen, but to get a new one you
 * had to know {@code /dndchar new}.</p>
 *
 * <p>Only asks for the name. Class, abilities, and everything else come from the preset chosen
 * afterward from the sheet, so asking for it here would be asking for the same thing twice — with worse
 * information, since the presets screen shows what each one grants.</p>
 */
public class NewCharacterScreen extends SmallFormScreen {

	private EditBox nameBox;

	private NewCharacterScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.new_character.title"), 1, parent);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new NewCharacterScreen(Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		//No default value: a pre-filled "Character" would stick as-is the moment someone hits
		//Confirm without looking, and two characters with the same name are exactly what's hard to tell apart.
		nameBox = addField(Component.translatable("gui.dndsheets.new_character.name").getString(), "", 40);
	}

	@Override
	protected void onConfirm() {
		String name = nameBox.getValue().trim();
		//An empty name isn't sent: the server would reject it anyway, and a round trip that does
		//nothing reads as if the button is broken.
		if (name.isEmpty()) return;
		DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.CREATE, name));
	}
}
