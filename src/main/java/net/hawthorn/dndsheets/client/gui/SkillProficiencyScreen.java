package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.RollIndex;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Which skills this character is proficient in. Until now, proficiency was obtained by hand-typing
 * {@code + $prof} into the roll expression, which <b>a player can't even do</b> in the first place:
 * {@code "skills"} has been an operator-only key in {@code SheetServerMessage} ever since the hole that
 * let anyone rewrite their own rolls was closed. Which meant half of a level 1 sheet depended on the DM
 * typing it in for you.</p>
 *
 * <p>Here the client only sends <b>which skill</b>, and the server writes the expression: you can request
 * proficiency, not a {@code +99}. It's the same boundary as always —the client requests, the server
 * decides— and that's what lets this be a player action instead of something the DM has to do.</p>
 *
 * <p>It doesn't repaint on click: the row changes when the new sheet arrives from the server
 * ({@link #refreshIfOpen}), for the same reason {@link CharacterListScreen} doesn't either — painting
 * a checkmark the server hasn't granted yet would be showing a state that doesn't exist.</p>
 *
 * <p><b>It doesn't limit how many or which ones</b>, on purpose: in 5e the number and the list come from
 * class and background, and that part isn't implemented yet (see Phase 5.2). A made-up limit would be
 * worse than none — it would block legitimate tables, and at the table the DM already looks at the sheet.</p>
 */
public class SkillProficiencyScreen extends ListPickerScreen {

	private SkillProficiencyScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.character_setup.skills"), parent);
	}

	public static void open(Screen parent) {
		Minecraft.getInstance().setScreen(new SkillProficiencyScreen(parent));
	}

	/** Called by {@code SheetClientMessage} when a full sheet arrives: see the class comment. */
	public static void refreshIfOpen() {
		if (Minecraft.getInstance().screen instanceof SkillProficiencyScreen screen) {
			screen.rebuildWidgets();
		}
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		JsonObject sheet = SheetLoader.getClientSheet();
		for (int index = 0; index < RollIndex.SKILL_COUNT; index++) {
			int skill = index;
			boolean proficient = RollIndex.isSkillProficient(sheet, index);
			Component label = Component.translatable(RollIndex.skillLangKey(index))
				.copy()
				.append(Component.literal(proficient ? "  ✔" : "").withStyle(ChatFormatting.GREEN));
			addRow(label, button -> DndsheetsMod.PACKET_HANDLER.sendToServer(
				new BrowseActionMessage(BrowseActionMessage.Action.SKILL_TOGGLE, String.valueOf(skill))));
		}
	}
}
