package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>A player's characters, with the one currently worn marked; clicking one switches to it. Opened with
 * {@code /dndchar} without arguments, which is a layout-risk-free entry point — the character sheet is
 * one of the three screens that ARE an {@code AbstractContainerScreen}, and squeezing in one more button
 * there is a considerably more expensive operation than a command.</p>
 *
 * <p>Doesn't rebuild locally on click: the server sends the list again with the marker already moved,
 * because it's the server that decides whether the switch was valid (the character has to be yours).
 * Repainting a marker here that the server could reject is exactly how you end up showing a state that
 * doesn't exist.</p>
 */
public class CharacterListScreen extends ListPickerScreen {

	private final List<String> ids;
	private final List<Component> labels;
	/**
	 * <p>Delete mode: it has to be turned on before a row deletes anything. This is the confirmation, and
	 * it lives here instead of in a per-row dialog because deleting already leaves a copy on disk
	 * ({@code .json.deleted}) — the protection actually needed is against an absent-minded click, not a
	 * yes/no prompt that gets answered "yes" without reading it.</p>
	 */
	private boolean deleteMode;

	private CharacterListScreen(List<String> ids, List<Component> labels) {
		super(Component.translatable("gui.dndsheets.character_list.title"));
		this.ids = ids;
		this.labels = labels;
	}

	public static void open(List<String> ids, List<Component> labels) {
		Minecraft.getInstance().setScreen(new CharacterListScreen(ids, labels));
	}

	//Leaves room below the list for the three fixed rows: proficiencies, create, and delete.
	@Override
	protected int listHeight() {
		return super.listHeight() - 3 * (BUTTON_HEIGHT + SPACING);
	}

	@Override
	protected void init() {
		super.init();
		int left = (this.width - buttonWidth()) / 2;
		int y = listTop() + listHeight() + SPACING;
		//Create sits ABOVE delete and looks like a normal row: it's the more frequently used of the two
		//actions, and the destructive one shouldn't be the one that's easiest to reach.
		this.addRenderableWidget(net.hawthorn.dndsheets.client.gui.components.TomeButton.of(
			Component.translatable("gui.dndsheets.character_list.new"), button -> NewCharacterScreen.open(),
			left, y, buttonWidth(), BUTTON_HEIGHT));
		y += BUTTON_HEIGHT + SPACING;
		//Setup belongs to a character, so it lives where a character gets chosen. The sheet can't open it:
		//it's one of the three screens that ARE an AbstractContainerScreen and its button row is already
		//full (see point 25 of PROJECT_CONTEXT.md), and this screen is opened from it.
		this.addRenderableWidget(net.hawthorn.dndsheets.client.gui.components.TomeButton.of(
			Component.translatable("gui.dndsheets.character_list.setup"), button -> CharacterSetupScreen.open(this),
			left, y, buttonWidth(), BUTTON_HEIGHT));
		y += BUTTON_HEIGHT + SPACING;
		net.minecraft.client.gui.components.Button toggle = this.addRenderableWidget(
			net.hawthorn.dndsheets.client.gui.components.TomeButton.of(deleteLabel(), button -> {
				deleteMode = !deleteMode;
				//Rebuilds the entire screen: the rows change color and action, and repainting only the
				//toggle would leave buttons doing something different from what they say.
				this.rebuildWidgets();
			}, left, y, buttonWidth(), BUTTON_HEIGHT));
		toggle.setMessage(deleteLabel());
	}

	private Component deleteLabel() {
		return deleteMode
			? Component.translatable("gui.dndsheets.character_list.delete_cancel").withStyle(ChatFormatting.RED)
			: Component.translatable("gui.dndsheets.character_list.delete").withStyle(ChatFormatting.GRAY);
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < ids.size(); i++) {
			String characterId = ids.get(i);
			//The active character comes marked with "▶" from the server (see BrowseActionMessage.listMine).
			//The already-resolved text is still what gets checked because the marker is a symbol, not a
			//word: it doesn't change with the language.
			boolean active = labels.get(i).getString().startsWith("▶");
			ChatFormatting color = deleteMode ? ChatFormatting.RED : (active ? ChatFormatting.GREEN : ChatFormatting.GRAY);
			Component label = deleteMode
				? Component.translatable("gui.dndsheets.character_list.delete_row", labels.get(i))
				: labels.get(i).copy();
			addRow(label.copy().withStyle(color),
				button -> DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(
					deleteMode ? BrowseActionMessage.Action.DELETE : BrowseActionMessage.Action.SWITCH, characterId)));
		}
	}

	@Override
	protected Component emptyMessage() {
		//A player always has at least their default sheet, so this is only ever seen if something went
		//wrong loading them — saying so is more useful than an empty list with no explanation.
		return ids.isEmpty() ? Component.translatable("gui.dndsheets.character_list.empty") : null;
	}
}
