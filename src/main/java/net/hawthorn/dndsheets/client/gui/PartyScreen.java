package net.hawthorn.dndsheets.client.gui;

import javax.annotation.Nullable;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.SheetSummaryRequestMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>DM's party view: every connected player with their character, HP, AC, and active conditions, at
 * a glance. Until now that could only be checked one at a time, opening each player's Sheet Settings
 * separately — useless in the middle of combat, which is exactly when it's needed.</p>
 *
 * <p>Clicking a player's row opens their Sheet Settings directly (the same path as DM Panel →
 * Settings → choose player, without the choosing step): this is the screen the DM looks at most
 * during combat, and "I can see it but to touch it I have to back out and navigate three lists" was
 * the specific complaint. It's a SHORTCUT to the existing flow, not a second path to maintain — the
 * row sends the same {@link SheetSummaryRequestMessage} the player selector used to send. NPCs arrive
 * with an empty id (they're not a connected player that Settings can resolve) and their row stays
 * read-only.</p>
 */
public class PartyScreen extends ListPickerScreen {

	private final List<String> ids;
	private final List<Component> rows;

	private PartyScreen(List<String> ids, List<Component> rows, Screen parent) {
		super(Component.translatable("gui.dndsheets.party.title"), parent);
		this.ids = ids;
		this.rows = rows;
	}

	public static void open(List<String> ids, List<Component> rows) {
		Minecraft.getInstance().setScreen(new PartyScreen(ids, rows, Minecraft.getInstance().screen));
	}

	//Wider than the standard list: each row carries name, HP, AC, and conditions, and at 200px it got cut off.
	@Override
	protected int buttonWidth() {
		return 260;
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < rows.size(); i++) {
			String id = i < ids.size() ? ids.get(i) : "";
			if (id.isEmpty()) {
				addRow(rows.get(i).copy().withStyle(ChatFormatting.GRAY), button -> {});
			} else {
				addRow(rows.get(i), button -> DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetSummaryRequestMessage(id)));
			}
		}
	}

	@Nullable
	@Override
	protected Component emptyMessage() {
		return rows.isEmpty() ? Component.translatable("gui.dndsheets.party.empty") : null;
	}
}
