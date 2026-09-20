package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>The player-side mirror of {@link DmPanelScreen}. It exists because the player side was the only
 * one without a door: the DM had seventeen actions in a searchable panel, while the player had four
 * buttons at the bottom of the sheet — everything else of theirs (ability score improvement, journal,
 * compendium, magic items, roll log) only opened by typing the command, which is as good as not
 * existing for anyone who doesn't know them by heart.</p>
 *
 * <p>No row introduces new networking or a new screen: each one just fires the message or command
 * that already sat behind the corresponding shortcut. The sections and the auto search box past
 * fourteen rows are handled by {@link ListPickerScreen} on its own.</p>
 */
public class PlayerPanelScreen extends ListPickerScreen {
	private PlayerPanelScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.player_panel.title"), parent);
	}

	public static void open(Screen parent) {
		Minecraft.getInstance().setScreen(new PlayerPanelScreen(parent));
	}

	@Override
	protected void buildRows() {
		addHeader(Component.translatable("gui.dndsheets.player_panel.section_character"));
		addRow(Component.translatable("gui.dndsheets.character_sheet.characters"),
			b -> DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.LIST_MINE)));
		//Race, class, background, subclass, and proficiencies in one place. It used to be two clicks
		//from here (Characters -> Setup), i.e. hidden behind a list opened for something else.
		addRow(Component.translatable("gui.dndsheets.player_panel.setup"), b -> CharacterSetupScreen.open(this));
		addRow(Component.translatable("gui.dndsheets.character_sheet.presets"), b -> PresetActionMenuScreen.open(this));
		//The Ability Score Improvement is chosen by whoever owns the character (see CharacterCommand "improve"):
		//the command doesn't require permission, and the server already checks that one is pending.
		addRow(Component.translatable("gui.dndsheets.player_panel.improvement"), b -> command("dndchar improve"));

		addHeader(Component.translatable("gui.dndsheets.player_panel.section_magic"));
		addRow(Component.translatable("gui.dndsheets.character_sheet.grimoire"), b -> GrimoireScreen.open(this));

		addHeader(Component.translatable("gui.dndsheets.player_panel.section_table"));
		addRow(Component.translatable("gui.dndsheets.dm_panel.journal"), b -> command("dndjournal"));
		addRow(Component.translatable("gui.dndsheets.dm_panel.compendium"), b -> CompendiumScreen.open());
		//Output goes to chat, not to a screen: /dnditems has no GUI of its own. It's still included,
		//because attuning items is the player's business and until now there was no way to discover the mechanic existed.
		addRow(Component.translatable("gui.dndsheets.player_panel.items"), b -> command("dnditems list"));
		addRow(Component.translatable("gui.dndsheets.dm_panel.roll_log"), b -> command("dndrolls"));
		addRow(Component.translatable("gui.dndsheets.guide.button"),
			b -> GuideBook.open(this.minecraft.player != null && this.minecraft.player.hasPermissions(2)));

		//Not gated by permissions, for the same reason as the DM Panel keybind (see
		//DndsheetsModKeyMappings.DM_PANEL): the client doesn't know whether solo mode is on, and
		//whoever DMs in solo isn't an operator. Opening it grants nothing — every action inside is gated by the server.
		addHeader(Component.translatable("gui.dndsheets.player_panel.section_dm"));
		addRow(Component.translatable("gui.dndsheets.dm_panel.title"), b -> DmPanelScreen.open(this));
	}

	private static void command(String command) {
		Minecraft.getInstance().player.connection.sendCommand(command);
		Minecraft.getInstance().setScreen(null);
	}
}
