package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.Condition;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.SheetAdjustMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * <p>A player's 14 5e conditions, from the DM Panel: each row shows whether it's active on them and
 * toggles it when clicked. A single list for both directions instead of separate "apply" and "remove"
 * — the DM sees the real state and acts on it, which was exactly what was missing: without this,
 * conditions could only be touched via {@code /dndturns effect} and there was no way to check them.</p>
 *
 * <p>The starting state arrives in the same {@code SheetSummaryMessage} that already carried gold/HP/AC
 * when opening {@link SheetAdjustScreen}, so no new message or extra round trip is needed. It's kept
 * locally when toggling instead of re-requesting it: the server is the authority, but repainting a
 * checkmark doesn't warrant a network trip per click.</p>
 */
public class ConditionListScreen extends ListPickerScreen {

	private final String targetUuid;
	private final Set<Condition> active;

	private ConditionListScreen(String targetUuid, String targetName, Set<Condition> active, Screen parent) {
		super(Component.translatable("gui.dndsheets.condition_list.title", targetName), parent);
		this.targetUuid = targetUuid;
		this.active = active;
	}

	/** @param conditionsCsv comma-separated labels, exactly as they travel in {@code SheetSummaryMessage}. */
	public static void open(String targetUuid, String targetName, String conditionsCsv) {
		Set<Condition> active = EnumSet.noneOf(Condition.class);
		if (conditionsCsv != null && !conditionsCsv.isEmpty()) {
			for (String label : conditionsCsv.split(",")) {
				Condition condition = Condition.fromLabel(label.trim());
				if (condition != null) active.add(condition);
			}
		}
		Minecraft.getInstance().setScreen(new ConditionListScreen(targetUuid, targetName, active, Minecraft.getInstance().screen));
	}

	//No search box on purpose, even though the base class offers one: there are 14 fixed rows, and toggling
	//one rebuilds the whole screen, which would clear the search box on every click. Searching 14 rows isn't worth that.
	@Override
	protected void buildRows() {
		for (Condition condition : Condition.values()) {
			addRow(rowLabel(condition), button -> toggle(condition));
		}
	}

	private Component rowLabel(Condition condition) {
		boolean on = active.contains(condition);
		return Component.literal((on ? "✔ " : "  ") + condition.displayLabel())
			.withStyle(on ? ChatFormatting.RED : ChatFormatting.GRAY);
	}

	private void toggle(Condition condition) {
		boolean apply = !active.contains(condition);
		if (apply) active.add(condition);
		else active.remove(condition);
		DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.condition(targetUuid, condition.label(), apply));
		//Vanilla rebuildWidgets(): calls init() again, which in turn calls buildRows() again with the
		//state already toggled. Rebuilding 14 rows to repaint a checkmark doesn't need anything finer.
		this.rebuildWidgets();
	}
}
