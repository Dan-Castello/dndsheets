package net.hawthorn.dndsheets.client.gui;

import javax.annotation.Nullable;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.components.TomeButton;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.hawthorn.dndsheets.network.SpellGiveMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//Loaded spells for an already-chosen player (GUI equivalent of /dndspells learn and /dndspells staff),
//opened via BrowseActionMessage.GIVE_SPELLS/BrowseListMessage. Same layout as the Grimoire: list grouped by
//level on the left, the picked spell and its actions on the right — it used to be two rows per spell
//("Learn: X", "Staff: X"), 174 rows for the SRD. The data comes from the server ("level|school|name" per
//id): the registry only lives there, and a DM on a separate client (LAN) would always see it empty.
public class SpellGiveListScreen extends ListPickerScreen {
	private static final int PANE_MARGIN = 6;

	private record Entry(String id, String name, int level, String school) {}

	private final String targetUuid;
	private final List<Entry> spells = new ArrayList<>();
	private final Map<String, Button> rowsById = new HashMap<>();
	private Entry selected;
	private Button learnButton;
	private Button staffButton;
	private Button detailsButton;

	private SpellGiveListScreen(String targetUuid, List<String> ids, List<String> data, Screen parent) {
		super(Component.translatable("gui.dndsheets.spell_give.title"), parent);
		this.targetUuid = targetUuid;
		for (int i = 0; i < ids.size(); i++) {
			String[] parts = i < data.size() ? data.get(i).split("\\|", 3) : new String[0];
			int level = 0;
			try { level = parts.length > 0 ? Integer.parseInt(parts[0]) : 0; } catch (NumberFormatException ignored) {}
			String school = parts.length > 1 ? parts[1] : "";
			String name = net.hawthorn.dndsheets.ContentNames.plain(parts.length > 2 ? parts[2] : ids.get(i));
			spells.add(new Entry(ids.get(i), name, level, school));
		}
		spells.sort(java.util.Comparator.comparingInt(Entry::level).thenComparing(Entry::name));
	}

	public static void open(String targetUuid, List<String> ids, List<String> data) {
		Minecraft.getInstance().setScreen(new SpellGiveListScreen(targetUuid, ids, data, Minecraft.getInstance().screen));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected int sidePaneWidth() {
		return Math.max(120, Math.min(170, this.width - buttonWidth() - 60));
	}

	@Override
	protected void init() {
		super.init();
		int left = sidePaneLeft() + PANE_MARGIN;
		int width = sidePaneWidth() - 2 * PANE_MARGIN;
		int y = listTop() + 62;
		//The screen stays open after giving: a DM handing out a whole class list does it in one visit.
		learnButton = this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.spell_give.learn_button"),
			b -> send(false), left, y, width, BUTTON_HEIGHT));
		y += BUTTON_HEIGHT + SPACING;
		staffButton = this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.spell_give.staff_button"),
			b -> send(true), left, y, width, BUTTON_HEIGHT));
		y += BUTTON_HEIGHT + SPACING;
		detailsButton = this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.grimoire.details"), b -> {
			if (selected != null) DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.CONTENT_DETAIL, "spells|" + selected.id()));
		}, left, y, width, BUTTON_HEIGHT));
		updateButtons();
	}

	@Override
	protected void buildRows() {
		rowsById.clear();
		int lastLevel = -1;
		for (Entry spell : spells) {
			if (spell.level() != lastLevel) {
				addHeader(spell.level() == 0 ? Component.translatable("gui.dndsheets.grimoire.cantrips")
					: Component.translatable("gui.dndsheets.grimoire.level", spell.level()));
				lastLevel = spell.level();
			}
			//School in the label so the search box filters by it ("evoc"), as in the Grimoire.
			Component label = Component.literal(spell.name()); //i18n-ok: name already resolved
			if (!spell.school().isEmpty()) label = label.copy().append(Component.literal(" · " + OptionLabels.of(spell.school())).withStyle(ChatFormatting.DARK_GRAY));
			rowsById.put(spell.id(), addRow(label, spell.school().isEmpty() ? null : Icons.school(spell.school()), b -> {
				selected = spell;
				updateButtons();
			}));
		}
	}

	private void updateButtons() {
		boolean any = selected != null;
		learnButton.visible = any;
		staffButton.visible = any;
		detailsButton.visible = any;
	}

	private void send(boolean asStaff) {
		if (selected != null) DndsheetsMod.PACKET_HANDLER.sendToServer(new SpellGiveMessage(targetUuid, selected.id(), asStaff));
	}

	@Nullable
	@Override
	protected Component emptyMessage() {
		return spells.isEmpty() ? Component.translatable("gui.dndsheets.spell_give.empty") : null;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
		int left = sidePaneLeft(), top = listTop(), width = sidePaneWidth();
		if (selected == null) {
			GuiStyle.inset(guiGraphics, left, top, left + width, top + listHeight());
			int y = top + listHeight() / 2 - 10;
			for (FormattedCharSequence line : this.font.split(Component.translatable("gui.dndsheets.spell_give.pick_hint"), width - 2 * PANE_MARGIN)) {
				guiGraphics.drawCenteredString(this.font, line, left + width / 2, y, GuiStyle.MUTED_COLOR);
				y += 10;
			}
			return;
		}
		int y = GrimoireScreen.drawSpellHeader(guiGraphics, this.font, left, top, width, selected.name(), selected.level(), selected.school());
		guiGraphics.drawString(this.font, this.font.plainSubstrByWidth(selected.id(), width - 2 * PANE_MARGIN), left + PANE_MARGIN, y, GuiStyle.MUTED_COLOR, false);

		Button row = rowsById.get(selected.id());
		if (row != null && row.visible && row.getY() >= listTop() && row.getY() + row.getHeight() <= listTop() + listHeight()) {
			guiGraphics.renderOutline(row.getX(), row.getY(), row.getWidth(), row.getHeight(), GuiStyle.ACCENT_COLOR);
		}
	}
}
