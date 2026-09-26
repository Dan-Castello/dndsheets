package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.hawthorn.dndsheets.network.SheetSummaryRequestMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>The DM's entry point to everything that used to be commands only: turns, spawning a blank NPC,
 * granting a trait. Opened with the quick-access key (see
 * {@link net.hawthorn.dndsheets.init.DndsheetsModKeyMappings#DM_PANEL}), which already checks operator
 * permissions before opening this — giving/removing attacks on a specific monster still lives in its
 * own menu (right-click with the DM Wand, see {@link MonsterActionScreen}), because that one already
 * needs the targeted monster and it makes no sense to request it separately here.</p>
 */
public class DmPanelScreen extends ListPickerScreen {
	private DmPanelScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.dm_panel.title"), parent);
	}

	/** From the quick-access key: root screen, Escape closes the menu. */
	public static void open() {
		open(null);
	}

	/** From the Player Menu (see {@link PlayerPanelScreen}): "&lt; Back" returns there. */
	public static void open(Screen parent) {
		Minecraft.getInstance().setScreen(new DmPanelScreen(parent));
	}

	//Five headed sections (see ListPickerScreen.addHeader) instead of 17 flat rows: with this many
	//actions, "everything together in arrival order" forced reading the whole list to find one.
	//The section order follows in-session frequency: what gets checked every combat comes first.
	@Override
	protected void buildRows() {
		addHeader(Component.translatable("gui.dndsheets.dm_panel.section_party"));
		//This is what a DM checks most often per session, and until now each player's Sheet Adjust screen
		//had to be opened separately to see their HP.
		addRow(Component.translatable("gui.dndsheets.dm_panel.party"),
			b -> DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.LIST_PARTY)));
		addRow(Component.translatable("gui.dndsheets.dm_panel.turn_mode"), b -> TurnControlScreen.open());
		addRow(Component.translatable("gui.dndsheets.dm_panel.rules"), b -> send(BrowseActionMessage.Action.RULES_LIST, ""));

		addHeader(Component.translatable("gui.dndsheets.dm_panel.section_spawn"));
		addRow(Component.translatable("gui.dndsheets.dm_panel.spawn_npc"), b -> SpawnGenericScreen.open());
		addRow(Component.translatable("gui.dndsheets.dm_panel.spawn_monster"),
			b -> send(BrowseActionMessage.Action.SPAWN_MONSTERS, ""));
		//Full encounters ("goblin x4, wolf x2"): the list comes from the server and each row triggers the
		//usual /dndencounters spawn — before, this only existed typed by hand.
		addRow(Component.translatable("gui.dndsheets.dm_panel.encounters"),
			b -> send(BrowseActionMessage.Action.LIST_ENCOUNTERS, ""));
		//Build a new one while watching the difficulty it produces for the party, instead of writing
		//"goblin x4" blindly in the content creator — see EncounterDesignerScreen.
		addRow(Component.translatable("gui.dndsheets.dm_panel.design_encounter"),
			b -> send(BrowseActionMessage.Action.DESIGN_ENCOUNTER, ""));

		addHeader(Component.translatable("gui.dndsheets.dm_panel.section_give"));
		addRow(Component.translatable("gui.dndsheets.dm_panel.grant_trait"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_trait"),
			uuid -> send(BrowseActionMessage.Action.GRANT_TRAITS, uuid)));
		addRow(Component.translatable("gui.dndsheets.dm_panel.give_item"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_item"), GiveItemListScreen::open));
		addRow(Component.translatable("gui.dndsheets.dm_panel.give_weapon"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_weapon"),
			uuid -> send(BrowseActionMessage.Action.GIVE_WEAPONS, uuid)));
		addRow(Component.translatable("gui.dndsheets.dm_panel.give_spell"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_spell"),
			uuid -> send(BrowseActionMessage.Action.GIVE_SPELLS, uuid)));
		addRow(Component.translatable("gui.dndsheets.dm_panel.sheet_adjust"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_sheet"),
			uuid -> DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetSummaryRequestMessage(uuid))));
		addRow(Component.translatable("gui.dndsheets.dm_panel.give_magic"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_magic"),
			uuid -> send(BrowseActionMessage.Action.GIVE_MAGIC, uuid)));
		addRow(Component.translatable("gui.dndsheets.dm_panel.apply_preset"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_preset"),
			uuid -> send(BrowseActionMessage.Action.LIST_PRESETS, uuid)));

		addHeader(Component.translatable("gui.dndsheets.dm_panel.section_world"));
		//The dungeon toolkit lives in its own addon (dndsheets_dungeon) since PROJECT_CONTEXT.md /
		//Modularity Map: this panel no longer knows its network messages, it only triggers its command —
		//same pattern as "journal" below. If the addon isn't installed, Brigadier already rejects the command.
		addRow(Component.translatable("gui.dndsheets.dm_panel.dungeons"),
			b -> Minecraft.getInstance().player.connection.sendCommand("dnddungeon gui"));
		//Monster difficulty no longer has its own command: it reads from the WORLD's difficulty (see
		//Config.difficultyMultiplier), so these rows send vanilla's /difficulty. There used to be two
		//commands for the same question and they could contradict each other — world on Hard, the mod's
		//monsters on Easy, with nothing warning about it. It's still offered here because that's where the DM will look for it.
		addRow(Component.translatable("gui.dndsheets.dm_panel.difficulty"), b -> CommandListScreen.open(
			Component.translatable("gui.dndsheets.dm_panel.difficulty"),
			java.util.List.of(
				new CommandListScreen.Row(Component.translatable("gui.dndsheets.difficulty.easy"), "difficulty easy"),
				new CommandListScreen.Row(Component.translatable("gui.dndsheets.difficulty.normal"), "difficulty normal"),
				new CommandListScreen.Row(Component.translatable("gui.dndsheets.difficulty.hard"), "difficulty hard"))));
		//The roll log already prints nicely to chat; the row just avoids having to know the command.
		addRow(Component.translatable("gui.dndsheets.dm_panel.roll_log"), b -> {
			Minecraft.getInstance().player.connection.sendCommand("dndrolls");
			Minecraft.getInstance().setScreen(null);
		});
		//The journal opens via command (/dndjournal) and not from here with a message: the server already
		//knows what each person can read, and requesting it from the client would be an extra round trip for the same result.
		addRow(Component.translatable("gui.dndsheets.dm_panel.journal"), b -> {
			net.minecraft.client.Minecraft.getInstance().player.connection.sendCommand("dndjournal");
		});
		addRow(Component.translatable("gui.dndsheets.dm_panel.compendium"), b -> CompendiumScreen.open());

		addHeader(Component.translatable("gui.dndsheets.dm_panel.section_content"));
		addRow(Component.translatable("gui.dndsheets.dm_panel.create_content"), b -> ContentTypeListScreen.open());
		addRow(Component.translatable("gui.dndsheets.guide.button"), b -> GuideBook.open(true));
	}

	private static void send(BrowseActionMessage.Action action, String argument) {
		DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(action, argument));
	}
}
