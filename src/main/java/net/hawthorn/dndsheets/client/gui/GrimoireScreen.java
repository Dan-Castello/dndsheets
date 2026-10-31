package net.hawthorn.dndsheets.client.gui;

import javax.annotation.Nullable;
import net.hawthorn.dndsheets.client.gui.components.TomeButton;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.SpellRegistry;
import net.hawthorn.dndsheets.SpellSlots;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.hawthorn.dndsheets.network.SpellCastMessage;
import net.hawthorn.dndsheets.network.StaffBindMessage;
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

/**
 * <p>A separate window for casting known spells, instead of a 4th tab on the character sheet (there's no
 * background texture for that yet). Opened from the "Grimoire" button on the Main tab, or directly with
 * its own keybind (see {@code DndsheetsModKeyMappings.GRIMOIRE}) without going through the sheet — it
 * doesn't depend on anything that only lives there, it reads everything from
 * {@link SheetLoader#getClientSheet}. Targets whatever you're looking at (see
 * {@link net.hawthorn.dndsheets.SpellCastManager}).</p>
 *
 * <p>Each spell's name, level, and school are read directly from the sheet (saved by
 * {@code SpellRegistry.learn} alongside the id), not from {@code SpellRegistry}: that registry only lives
 * in the server's memory, so on a real dedicated server (client and server are separate processes) the
 * client would never have its own copy and everything would show up as "unknown".</p>
 *
 * <p><b>Layout:</b> the spell list on the left, grouped by level; a detail pane on the right (see
 * {@link ListPickerScreen#sidePaneWidth()}) with the selected spell and the actions that apply to it. The
 * actions used to be five fixed rows under the list — four of them disabled until something was picked,
 * and drawn past the bottom of the panel. Now they only exist once there is a spell to act on.</p>
 *
 * <p><b>The school goes inside each row's label instead of a separate filter button.</b>
 * {@link ListPickerScreen} already spawns a search box as soon as the list exceeds fourteen rows, and
 * that search box filters by the label's text: putting the school there means typing "evoc" already
 * filters by school without spending a screen row or a new widget. The same goes for "not prepared".</p>
 */
public class GrimoireScreen extends ListPickerScreen {
	private static final int STRIP_Y = 34;
	private static final int PIP = 5;
	private static final int PANE_MARGIN = 6;

	/**
	 * <p>{@code label} is how it appears in the LIST — with school and whether it's unprepared, which is
	 * what distinguishes and makes the rows searchable (the level is the section header above it) — and
	 * plain {@code name} is what goes on the buttons and the detail pane.</p>
	 */
	private record KnownSpell(String id, String name, String label, int level, boolean prepared, String school) {}

	/**
	 * <p>The last spell cast from here, for the quick-cast keybind (see
	 * {@code DndsheetsModKeyMappings.QUICK_CAST}). Deliberately static and client-side: it's a convenience
	 * for whoever's playing, not character state — it doesn't deserve a sheet field or a trip over the
	 * network.</p>
	 */
	private static String lastCastId;
	private static int lastCastSlotLevel;

	private KnownSpell selected;
	private Button castButton;
	private Button slotLevelButton;
	private Button prepareButton;
	private Button bindButton;
	private Button detailsButton;
	/** Slot level chosen for the selected spell; 0 = the lowest one that works. */
	private int chosenSlotLevel;
	/** Each spell's list row, to outline the selected one. */
	private final Map<String, Button> rowsById = new HashMap<>();

	protected GrimoireScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.grimoire.title"), parent);
	}

	/** {@code parent} is null when opened standalone (direct keybind): no "&lt; Back", Escape just closes. */
	public static void open(Screen parent) {
		Minecraft.getInstance().setScreen(new GrimoireScreen(parent));
	}

	/** Called when a complete sheet arrives (prepare, rest, slot spent...): the list and pane show it at once. */
	public static void refreshIfOpen() {
		if (Minecraft.getInstance().screen instanceof GrimoireScreen screen) screen.rebuildWidgets();
	}

	/** Casts the last spell cast from the Grimoire again, without opening it. Null-safe: does nothing without a prior cast. */
	public static void castLast() {
		if (lastCastId == null) return;
		DndsheetsMod.PACKET_HANDLER.sendToServer(new SpellCastMessage(lastCastId, lastCastSlotLevel));
	}

	//Narrow windows (large GUI scale) shrink the pane before they shrink the list.
	@Override
	protected int sidePaneWidth() {
		return Math.max(120, Math.min(170, this.width - buttonWidth() - 60));
	}

	//A band for the slot strip under the title, above the search box (when there is one) and the list.
	@Override
	protected int contentTop() {
		return super.contentTop() + 18;
	}

	@Override
	protected void init() {
		super.init();

		int left = sidePaneLeft() + PANE_MARGIN;
		int width = sidePaneWidth() - 2 * PANE_MARGIN;
		int y = listTop() + 62;

		//Casting at a higher level is a DECISION, not something the server can guess: spending a 5th-level
		//slot on a Fireball in exchange for more dice is only known by the one casting it. A cycling
		//button instead of a dropdown because the possible levels are few and contiguous.
		slotLevelButton = this.addRenderableWidget(TomeButton.of(Component.empty(), button -> {
			chosenSlotLevel = nextUsableLevel();
			updateButtons();
		}, left, y, width, BUTTON_HEIGHT));
		y += BUTTON_HEIGHT + SPACING;

		//Casting doesn't happen with a click on the spell: a player who clicks to read what's in the list
		//doesn't want to spend a real spell slot out of curiosity. Picking only selects; this casts.
		castButton = this.addRenderableWidget(TomeButton.of(Component.empty(), button -> {
			if (selected == null) return;
			lastCastId = selected.id();
			lastCastSlotLevel = chosenSlotLevel;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new SpellCastMessage(selected.id(), chosenSlotLevel));
		}, left, y, width, BUTTON_HEIGHT));
		y += BUTTON_HEIGHT + SPACING;

		//Prepare/unprepare. The limit is set and enforced by the SERVER (see
		//BrowseActionMessage.setPrepared): only the intent is sent here, so a modified client can't bypass
		//anything. The screen repaints itself once the updated sheet arrives (refreshIfOpen).
		prepareButton = this.addRenderableWidget(TomeButton.of(Component.empty(), button -> {
			if (selected == null || selected.level() <= 0) return;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(
				selected.prepared() ? BrowseActionMessage.Action.SPELL_UNPREPARE : BrowseActionMessage.Action.SPELL_PREPARE,
				selected.id()));
		}, left, y, width, BUTTON_HEIGHT));
		y += BUTTON_HEIGHT + SPACING;

		//The spell's rules text (casting time, range, components, duration, dice): it lives on the server, so this
		//asks for the same detail card the Compendium shows.
		detailsButton = this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.grimoire.details"), button -> {
			if (selected == null) return;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.CONTENT_DETAIL, "spells|" + selected.id()));
		}, left, y, width, BUTTON_HEIGHT));
		y += BUTTON_HEIGHT + SPACING;

		//With hundreds of spells, a dedicated staff for each one isn't viable (see
		//SpellCommand.buildStaffStack): the one handed out is reconfigurable, and this button rewrites the
		//quickSpell of whatever you're holding in your main hand instead of creating a new item. Last in the
		//stack because it only shows while holding one.
		bindButton = this.addRenderableWidget(TomeButton.of(Component.empty(), button -> {
			if (selected == null) return;
			//Toggle: adds the spell to the staff, or removes it if it's already on it.
			DndsheetsMod.PACKET_HANDLER.sendToServer(new StaffBindMessage(selected.id(), staffHas(selected.id()) ? StaffBindMessage.Action.REMOVE : StaffBindMessage.Action.ADD));
		}, left, y, width, BUTTON_HEIGHT));

		updateButtons();
	}

	/**
	 * <p>Rows are grouped by level under a header ({@code addHeader}, unclickable and excluded from the
	 * search box). With 87 possible spells, a flat list forces you to read the whole thing to know what
	 * can be cast with what's left; grouped, the level is visible at a glance and matches the slot strip
	 * right above it.</p>
	 */
	@Override
	protected void buildRows() {
		rowsById.clear();
		List<KnownSpell> spells = knownSpells();
		//A rebuild (sheet arrived) keeps the selection, re-read from the new sheet so "prepared" is current.
		if (selected != null) {
			String id = selected.id();
			selected = spells.stream().filter(spell -> spell.id().equals(id)).findFirst().orElse(null);
		}
		int lastLevel = -1;
		for (KnownSpell spell : spells) {
			if (spell.level() != lastLevel) {
				addHeader(spell.level() == 0
					? Component.translatable("gui.dndsheets.grimoire.cantrips")
					: Component.translatable("gui.dndsheets.grimoire.level", spell.level()));
				lastLevel = spell.level();
			}
			Button row = addRow(Component.literal(spell.label()), spell.school().isEmpty() ? null : Icons.school(spell.school()), b -> { //i18n-ok: label() already comes resolved (see knownSpells)
				selected = spell;
				//The chosen level resets to the spell's own: inheriting the "lv. 5" from the previous
				//selection would spend an expensive slot on the wrong spell without anyone asking for it.
				chosenSlotLevel = spell.level();
				updateButtons();
			});
			rowsById.put(spell.id(), row);
		}
	}

	private void updateButtons() {
		boolean any = selected != null;
		boolean cantrip = any && selected.level() == 0;
		boolean holdingConfigurableStaff = isHoldingConfigurableStaff();
		castButton.visible = any;
		detailsButton.visible = any;
		//A cantrip spends no slot and is never prepared: those two rows would only say so, disabled.
		slotLevelButton.visible = any && !cantrip;
		prepareButton.visible = any && !cantrip;
		bindButton.visible = any && holdingConfigurableStaff;
		if (!any) return;

		//The level shown here is the CHOSEN one, not the spell's own. Showing its own, the button used to
		//say "Cast: Fireball (lv. 3)" right below "Slot: lv. 5": two adjacent numbers contradicting each
		//other, and the wrong one on the button that actually does something. It's also the same number
		//that later shows up in chat, so what's read before clicking matches what's read afterward.
		castButton.setMessage(cantrip ? Component.translatable("gui.dndsheets.grimoire.cast_cantrip", net.hawthorn.dndsheets.ContentNames.of(selected.name()))
			: Component.translatable("gui.dndsheets.grimoire.cast", net.hawthorn.dndsheets.ContentNames.of(selected.name()), chosenSlotLevel));

		//If you have no slot above its own, the button has nowhere to cycle to.
		boolean canChoose = !cantrip && nextUsableLevel() != chosenSlotLevel;
		slotLevelButton.active = canChoose;
		//"change" and not "raise": the cycle wraps back to the spell's own level once it reaches the top,
		//and at the highest available level the only possible click is precisely the one that lowers it.
		slotLevelButton.setMessage(canChoose ? Component.translatable("gui.dndsheets.grimoire.slot_change", chosenSlotLevel)
			: Component.translatable("gui.dndsheets.grimoire.slot_fixed", chosenSlotLevel));

		prepareButton.setMessage(selected.prepared() ? Component.translatable("gui.dndsheets.grimoire.unprepare", net.hawthorn.dndsheets.ContentNames.of(selected.name()))
			: Component.translatable("gui.dndsheets.grimoire.prepare", net.hawthorn.dndsheets.ContentNames.of(selected.name())));

		bindButton.setMessage(Component.translatable(staffHas(selected.id()) ? "gui.dndsheets.grimoire.unbind_from" : "gui.dndsheets.grimoire.bind_to", net.hawthorn.dndsheets.ContentNames.of(selected.name())));
	}

	private static boolean staffHas(String spellId) {
		net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
		return p != null && net.hawthorn.dndsheets.SpellRegistry.staffSpells(p.getMainHandItem()).contains(spellId);
	}

	//Only checked when building the buttons and when picking a spell (see init/buildRows), not every
	//frame: swapping staffs with the Grimoire open isn't a case worth chasing live.
	private static boolean isHoldingConfigurableStaff() {
		return SpellRegistry.isConfigurableStaff(Minecraft.getInstance().player.getMainHandItem());
	}

	/**
	 * <p>Next slot level the chosen spell can be cast with, wrapping back to its own once it reaches the
	 * top. Only counts what the character has LEFT: offering an empty level would be a click that leads
	 * to a cast the server would resolve with a different slot than the one shown on screen.</p>
	 */
	private int nextUsableLevel() {
		if (selected == null || selected.level() <= 0) return 0;
		JsonObject sheet = SheetLoader.getClientSheet();
		int[] current = sheet != null ? SpellSlots.currentSlots(sheet) : new int[SpellSlots.MAX_SPELL_LEVEL + 1];
		for (int step = 1; step <= SpellSlots.MAX_SPELL_LEVEL; step++) {
			//Walks in a circle from the current level back to itself, skipping the exhausted ones.
			int level = selected.level() + ((chosenSlotLevel - selected.level() + step) % (SpellSlots.MAX_SPELL_LEVEL + 1 - selected.level()));
			if (current[level] > 0) return level;
		}
		return chosenSlotLevel;
	}

	@Nullable
	@Override
	protected Component emptyMessage() {
		return hasNoKnownSpells() ? Component.translatable("gui.dndsheets.grimoire.empty") : null;
	}

	//Audit item F9: avoids rebuilding the whole KnownSpell list just to know whether it's empty.
	private static boolean hasNoKnownSpells() {
		JsonObject sheet = SheetLoader.getClientSheet();
		return sheet == null || !sheet.has("spells") || sheet.getAsJsonArray("spells").isEmpty();
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
		renderSlotStrip(guiGraphics);
		renderPane(guiGraphics);
		outlineSelectedRow(guiGraphics);
	}

	/**
	 * <p>The detail pane: what the selected spell is, before the buttons that act on it. Drawn over its
	 * engraved card; the buttons (widgets) were already painted by super.render, so the card only goes
	 * behind the header area above them.</p>
	 */
	private void renderPane(GuiGraphics guiGraphics) {
		int left = sidePaneLeft();
		int top = listTop();
		int width = sidePaneWidth();
		int textLeft = left + PANE_MARGIN;
		int textWidth = width - 2 * PANE_MARGIN;
		if (selected == null) {
			GuiStyle.inset(guiGraphics, left, top, left + width, top + listHeight());
			int y = top + listHeight() / 2 - 10;
			for (FormattedCharSequence line : this.font.split(Component.translatable("gui.dndsheets.grimoire.pick_hint"), textWidth)) {
				guiGraphics.drawCenteredString(this.font, line, left + width / 2, y, GuiStyle.MUTED_COLOR);
				y += 10;
			}
			return;
		}
		int y = drawSpellHeader(guiGraphics, this.font, left, top, width, selected.name(), selected.level(), selected.school());
		Component status = selected.level() == 0 ? Component.translatable("gui.dndsheets.grimoire.status_at_will")
			: selected.prepared() ? Component.translatable("gui.dndsheets.grimoire.status_prepared")
			: Component.translatable("gui.dndsheets.grimoire.status_unprepared");
		int statusColor = selected.level() == 0 ? GuiStyle.INFO_COLOR : selected.prepared() ? GuiStyle.GOOD_COLOR : GuiStyle.MUTED_COLOR;
		guiGraphics.drawString(this.font, status, textLeft, y, statusColor, false);
	}

	/**
	 * <p>The top card of a spell detail pane: school glyph, name (two lines at most) and "Level 3 · Evocation"
	 * over an engraved card 56px tall. Shared with {@link SpellGiveListScreen}; returns the Y of the free line
	 * left at the bottom of the card, for the caller's own status text.</p>
	 */
	static int drawSpellHeader(GuiGraphics guiGraphics, net.minecraft.client.gui.Font font, int left, int top, int width, String name, int level, String school) {
		int textLeft = left + PANE_MARGIN;
		GuiStyle.inset(guiGraphics, left, top, left + width, top + 56);
		int nameLeft = textLeft;
		if (!school.isEmpty()) {
			Icons.draw(guiGraphics, Icons.school(school), textLeft, top + 6, 20);
			nameLeft += 26;
		}
		int y = top + 7;
		List<FormattedCharSequence> nameLines = font.split(GuiStyle.title(net.hawthorn.dndsheets.ContentNames.of(name)), left + width - PANE_MARGIN - nameLeft);
		for (int i = 0; i < Math.min(2, nameLines.size()); i++) {
			guiGraphics.drawString(font, nameLines.get(i), nameLeft, y, GuiStyle.TITLE_COLOR, false);
			y += 10;
		}
		y = Math.max(y, top + 28) + 2;
		Component levelText = level == 0 ? Component.translatable("gui.dndsheets.grimoire.cantrips")
			: Component.translatable("gui.dndsheets.grimoire.level", level);
		String kind = school.isEmpty() ? levelText.getString() : levelText.getString() + " · " + OptionLabels.of(school);
		guiGraphics.drawString(font, font.plainSubstrByWidth(kind, width - 2 * PANE_MARGIN), textLeft, y, GuiStyle.SUBTITLE_COLOR, false);
		return y + 11;
	}

	//Selected state (DESIGN.md §4): a brass ring on the picked row, only while it's fully inside the list.
	private void outlineSelectedRow(GuiGraphics guiGraphics) {
		if (selected == null) return;
		Button row = rowsById.get(selected.id());
		if (row == null || !row.visible || row.getY() < listTop() || row.getY() + row.getHeight() > listTop() + listHeight()) return;
		guiGraphics.renderOutline(row.getX(), row.getY(), row.getWidth(), row.getHeight(), GuiStyle.ACCENT_COLOR);
	}

	/**
	 * <p>Slots per level as pips (filled = left), plus the prepared count: "1 ●● 2 ●○ … Prepared 3/5".</p>
	 *
	 * <p>This used to show a flat total ("Slots: 5/9"), which since slots became per-spell-level no
	 * longer answers the question whoever looks at this screen actually has. A wizard with 5 loose slots
	 * doesn't know whether they can cast their Fireball: that depends on whether any of them are 3rd level
	 * or higher.</p>
	 */
	private record SlotColumn(String level, int left, int max) {}

	//The strip only changes when the sheet changes, but it's drawn every FRAME while the Grimoire is open:
	//assembled once per sheet version, same as ResourceHudOverlay.
	private int cachedSlotVersion = -1;
	private List<SlotColumn> cachedColumns;
	/** "Prepared: 5/8", or null for someone who isn't a spellcasting class and has no list to manage. */
	private Component cachedPrepared;

	private void rebuildSlotStrip(JsonObject sheet) {
		int[] max = SpellSlots.maxSlotsOf(sheet);
		int[] current = SpellSlots.currentSlots(sheet);
		cachedColumns = new ArrayList<>();
		for (int level = 1; level <= SpellSlots.MAX_SPELL_LEVEL; level++) {
			//An exhausted level keeps its column (empty pips) so the strip doesn't reshuffle under the cursor.
			if (max[level] > 0) cachedColumns.add(new SlotColumn(Integer.toString(level), current[level], max[level]));
		}
		int limit = SpellRegistry.preparedLimitFor(sheet);
		cachedPrepared = limit <= 0 ? null
			: Component.translatable("gui.dndsheets.grimoire.prepared_count", SpellRegistry.preparedCount(sheet), limit);
	}

	private int columnWidth(SlotColumn column) {
		return this.font.width(column.level()) + 3 + column.max() * (PIP + 2) + 8;
	}

	private void renderSlotStrip(GuiGraphics guiGraphics) {
		JsonObject sheet = SheetLoader.getClientSheet();
		if (sheet == null) return;
		int version = SheetLoader.clientSheetVersion();
		if (version != cachedSlotVersion) {
			rebuildSlotStrip(sheet);
			cachedSlotVersion = version;
		}

		int center = (listLeft() + sidePaneLeft() + sidePaneWidth()) / 2;
		if (cachedColumns.isEmpty()) {
			guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.grimoire.no_slots"), center, STRIP_Y, GuiStyle.MUTED_COLOR);
			return;
		}
		int total = 0;
		for (SlotColumn column : cachedColumns) total += columnWidth(column);
		if (cachedPrepared != null) total += 6 + this.font.width(cachedPrepared);
		int x = center - total / 2;
		for (SlotColumn column : cachedColumns) {
			guiGraphics.drawString(this.font, column.level(), x, STRIP_Y, column.left() > 0 ? GuiStyle.SUBTITLE_COLOR : GuiStyle.MUTED_COLOR, false);
			int pipX = x + this.font.width(column.level()) + 3;
			for (int i = 0; i < column.max(); i++) {
				guiGraphics.renderOutline(pipX, STRIP_Y + 1, PIP, PIP, GuiStyle.ACCENT_COLOR);
				if (i < column.left()) guiGraphics.fill(pipX + 1, STRIP_Y + 2, pipX + PIP - 1, STRIP_Y + PIP, GuiStyle.ACCENT_COLOR);
				pipX += PIP + 2;
			}
			x += columnWidth(column);
		}
		if (cachedPrepared != null) guiGraphics.drawString(this.font, cachedPrepared, x + 6, STRIP_Y, GuiStyle.MUTED_COLOR, false);
	}

	/** Sorted by level (cantrips first) so {@link #buildRows}'s headers fall into place on their own. */
	private static List<KnownSpell> knownSpells() {
		List<KnownSpell> result = new ArrayList<>();
		JsonObject sheet = SheetLoader.getClientSheet();
		if (sheet == null || !sheet.has("spells")) return result;

		JsonArray spells = sheet.getAsJsonArray("spells");
		for (int i = 0; i < spells.size(); i++) {
			JsonObject entry = spells.get(i).getAsJsonObject();
			String id = entry.has("id") ? entry.get("id").getAsString() : "";
			//Resolved here, not at paint time: ListPickerScreen's search box filters on the assembled label,
			//so the name has to go in translated. This runs on the CLIENT, so it comes out in its language.
			String name = net.hawthorn.dndsheets.ContentNames.plain(
				entry.has("name") ? entry.get("name").getAsString() : id);
			int level = entry.has("level") ? entry.get("level").getAsInt() : 0;
			boolean prepared = SpellRegistry.isPrepared(sheet, id);
			//No "(lv. N)" in the row any more: the section header above it already says the level, and the
			//detail pane repeats it for the selected spell.
			StringBuilder label = new StringBuilder(name);
			//School and "not prepared" go IN the label because ListPickerScreen's search box filters on it:
			//typing "evoc" or "not prep" filters without spending a screen row on a filter button.
			if (entry.has("school")) label.append(" · ").append(entry.get("school").getAsString());
			if (!prepared && level > 0) label.append(Component.translatable("gui.dndsheets.grimoire.tag_unprepared").getString());
			result.add(new KnownSpell(id, name, label.toString(), level, prepared, entry.has("school") ? entry.get("school").getAsString().toLowerCase(java.util.Locale.ROOT) : ""));
		}
		result.sort(java.util.Comparator.comparingInt(KnownSpell::level).thenComparing(KnownSpell::name));
		return result;
	}
}
