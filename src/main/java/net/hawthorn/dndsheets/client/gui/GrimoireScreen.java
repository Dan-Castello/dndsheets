package net.hawthorn.dndsheets.client.gui;

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

import java.util.ArrayList;
import java.util.List;

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
 * <p><b>The school goes inside each row's label instead of a separate filter button.</b>
 * {@link ListPickerScreen} already spawns a search box as soon as the list exceeds fourteen rows, and
 * that search box filters by the label's text: putting the school there means typing "evoc" already
 * filters by school without spending a screen row or a new widget. The same goes for "unprepared".</p>
 */
public class GrimoireScreen extends ListPickerScreen {
	private static final int SUBTITLE_Y = 30;
	/** Two rows: the spell slot levels on top, how many are left of each underneath. */
	private static final int SLOT_ROW_STEP = 10;
	/** Width of each column in the slot table: fits "9/9" and all nine levels fit in the panel. */
	private static final int SLOT_COL_WIDTH = 24;
	/** Fixed rows below the list: slot level, cast, prepare, and bind to staff. */
	private static final int FIXED_ROWS = 4;

	/**
	 * <p>{@code label} is how it appears in the LIST — with level, school, and whether it's unprepared,
	 * which is what sorts, distinguishes, and makes the rows searchable — and plain {@code name} is what
	 * goes on the buttons, where the level that matters is the chosen slot's, not the spell's own.</p>
	 */
	private record KnownSpell(String id, String name, String label, int level, boolean prepared) {}

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
	/** Slot level chosen for the selected spell; 0 = the lowest one that works. */
	private int chosenSlotLevel;

	protected GrimoireScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.grimoire.title"), parent);
	}

	/** {@code parent} is null when opened standalone (direct keybind): no "&lt; Back", Escape just closes. */
	public static void open(Screen parent) {
		Minecraft.getInstance().setScreen(new GrimoireScreen(parent));
	}

	/** Casts the last spell cast from the Grimoire again, without opening it. Null-safe: does nothing without a prior cast. */
	public static void castLast() {
		if (lastCastId == null) return;
		DndsheetsMod.PACKET_HANDLER.sendToServer(new SpellCastMessage(lastCastId, lastCastSlotLevel));
	}

	@Override
	protected int buttonWidth() {
		return 220;
	}

	@Override
	protected int listTop() {
		return SUBTITLE_Y + SLOT_ROW_STEP + 14;
	}

	//Leaves a fixed gap below the list for the rows that don't scroll with the spells.
	@Override
	protected int listHeight() {
		return super.listHeight() - FIXED_ROWS * (BUTTON_HEIGHT + SPACING);
	}

	@Override
	protected void init() {
		super.init();

		int left = (this.width - buttonWidth()) / 2;
		int slotY = listTop() + listHeight() + SPACING;
		int castY = slotY + BUTTON_HEIGHT + SPACING;
		int prepareY = castY + BUTTON_HEIGHT + SPACING;
		int bindY = prepareY + BUTTON_HEIGHT + SPACING;

		//Casting at a higher level is a DECISION, not something the server can guess: spending a 5th-level
		//slot on a Fireball in exchange for more dice is only known by the one casting it. A cycling
		//button instead of a dropdown because the possible levels are few and contiguous.
		slotLevelButton = this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.grimoire.slot_level"), button -> {
			chosenSlotLevel = nextUsableLevel();
			updateButtons();
		}, left, slotY, buttonWidth(), BUTTON_HEIGHT));

		//Casting no longer happens with a single click on the spell: a player who clicks to read what's in
		//the list doesn't want to spend a real spell slot out of curiosity.
		//Picking a spell only selects it; this separate button is the one that actually casts it.
		castButton = this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.grimoire.pick_spell"), button -> {
			if (selected == null) return;
			lastCastId = selected.id();
			lastCastSlotLevel = chosenSlotLevel;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new SpellCastMessage(selected.id(), chosenSlotLevel));
		}, left, castY, buttonWidth(), BUTTON_HEIGHT));

		//Prepare/unprepare. The limit is set and enforced by the SERVER (see
		//BrowseActionMessage.setPrepared): only the intent is sent here, so a modified client can't bypass
		//anything. The screen repaints itself once the updated sheet arrives.
		prepareButton = this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.grimoire.prepare_none"), button -> {
			if (selected == null || selected.level() <= 0) return;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(
				selected.prepared() ? BrowseActionMessage.Action.SPELL_UNPREPARE : BrowseActionMessage.Action.SPELL_PREPARE,
				selected.id()));
		}, left, prepareY, buttonWidth(), BUTTON_HEIGHT));

		//With hundreds of spells, a dedicated staff for each one isn't viable (see
		//SpellCommand.buildStaffStack): the one handed out is reconfigurable, and this button rewrites the
		//quickSpell of whatever you're holding in your main hand instead of creating a new item. Only
		//active if you're actually holding a configurable one.
		bindButton = this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.grimoire.bind_staff"), button -> {
			if (selected == null) return;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new StaffBindMessage(selected.id()));
		}, left, bindY, buttonWidth(), BUTTON_HEIGHT));

		updateButtons();
	}

	/**
	 * <p>Rows are grouped by level under a header ({@code addHeader}, unclickable and excluded from the
	 * search box). With 87 possible spells, a flat list forces you to read the whole thing to know what
	 * can be cast with what's left; grouped, the level is visible at a glance and matches the slot table
	 * right above it.</p>
	 */
	@Override
	protected void buildRows() {
		List<KnownSpell> spells = knownSpells();
		int lastLevel = -1;
		for (KnownSpell spell : spells) {
			if (spell.level() != lastLevel) {
				addHeader(spell.level() == 0
					? Component.translatable("gui.dndsheets.grimoire.cantrips")
					: Component.translatable("gui.dndsheets.grimoire.level", spell.level()));
				lastLevel = spell.level();
			}
			addRow(Component.literal(spell.label()), b -> { //i18n-ok: label() already comes resolved (see knownSpells)
				selected = spell;
				//The chosen level resets to the spell's own: inheriting the "lv. 5" from the previous
				//selection would spend an expensive slot on the wrong spell without anyone asking for it.
				chosenSlotLevel = spell.level();
				updateButtons();
			});
		}
	}

	private void updateButtons() {
		castButton.active = selected != null;
		//The level shown here is the CHOSEN one, not the spell's own. Showing its own, the button used to
		//say "Cast: Fireball (lv. 3)" right below "Slot: lv. 5": two adjacent numbers contradicting each
		//other, and the wrong one on the button that actually does something. It's also the same number
		//that later shows up in chat, so what's read before clicking matches what's read afterward.
		castButton.setMessage(selected == null ? Component.translatable("gui.dndsheets.grimoire.cast_none")
			: selected.level() == 0 ? Component.translatable("gui.dndsheets.grimoire.cast_cantrip", net.hawthorn.dndsheets.ContentNames.of(selected.name()))
			: Component.translatable("gui.dndsheets.grimoire.cast", net.hawthorn.dndsheets.ContentNames.of(selected.name()), chosenSlotLevel));

		//A cantrip doesn't spend a slot, so there's no level to choose; and if you have no slot above its
		//own, the button has nowhere to cycle to.
		boolean canChoose = selected != null && selected.level() > 0 && nextUsableLevel() != chosenSlotLevel;
		slotLevelButton.active = canChoose;
		slotLevelButton.setMessage(
			selected == null ? Component.translatable("gui.dndsheets.grimoire.slot_none")
			: selected.level() == 0 ? Component.translatable("gui.dndsheets.grimoire.slot_cantrip")
			//"change" and not "raise": the cycle wraps back to the spell's own level once it reaches the
			//top, and at the highest available level the only possible click is precisely the one that
			//lowers it.
			: canChoose ? Component.translatable("gui.dndsheets.grimoire.slot_change", chosenSlotLevel)
			: Component.translatable("gui.dndsheets.grimoire.slot_fixed", chosenSlotLevel));

		prepareButton.active = selected != null && selected.level() > 0;
		prepareButton.setMessage(
			selected == null ? Component.translatable("gui.dndsheets.grimoire.prepare_none")
			: selected.level() == 0 ? Component.translatable("gui.dndsheets.grimoire.prepare_cantrip")
			: selected.prepared() ? Component.translatable("gui.dndsheets.grimoire.unprepare", net.hawthorn.dndsheets.ContentNames.of(selected.name()))
			: Component.translatable("gui.dndsheets.grimoire.prepare", net.hawthorn.dndsheets.ContentNames.of(selected.name())));

		boolean holdingConfigurableStaff = isHoldingConfigurableStaff();
		bindButton.active = selected != null && holdingConfigurableStaff;
		bindButton.setMessage(
			!holdingConfigurableStaff ? Component.translatable("gui.dndsheets.grimoire.bind_none")
			: selected == null ? Component.translatable("gui.dndsheets.grimoire.bind_pick")
			: Component.translatable("gui.dndsheets.grimoire.bind_to", net.hawthorn.dndsheets.ContentNames.of(selected.name())));
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
		renderSlotTable(guiGraphics);
	}

	/**
	 * <p>Slots per level, in columns: the level number on top and how many are left underneath.</p>
	 *
	 * <p>This used to show a flat total ("Slots: 5/9"), which since slots became per-spell-level no
	 * longer answers the question whoever looks at this screen actually has. A wizard with 5 loose slots
	 * doesn't know whether they can cast their Fireball: that depends on whether any of them are 3rd level
	 * or higher, and the total says exactly the same thing whether they have five 1st-level slots left or
	 * one 5th-level slot.</p>
	 */
	/** An already-assembled column: the level tag, the "left/max" count, and its color. */
	private record SlotColumn(Component levelLabel, Component countLabel, int color) {}

	//The table only changes when the sheet changes, but renderSlotTable runs every FRAME while the
	//Grimoire is open: two new int[10] arrays (readSlots does 18 String.valueOf calls to look up its
	//keys), a List<Integer> with autoboxing, and up to 18 new Components, all to draw the same text. It's
	//assembled once per sheet version, same as ResourceHudOverlay.
	private int cachedSlotVersion = -1;
	private List<SlotColumn> cachedColumns;
	/** "Prepared: 5/8", or null for someone who isn't a spellcasting class and has no list to manage. */
	private Component cachedPrepared;

	private void rebuildSlotTable(JsonObject sheet) {
		int[] max = SpellSlots.maxSlotsOf(sheet);
		int[] current = SpellSlots.currentSlots(sheet);

		cachedColumns = new ArrayList<>();
		for (int level = 1; level <= SpellSlots.MAX_SPELL_LEVEL; level++) {
			if (max[level] <= 0) continue;
			//An exhausted level dims instead of disappearing: it keeps occupying its column, so the table
			//doesn't reshuffle under the cursor every time the last one of a level gets spent.
			int color = current[level] > 0 ? GuiStyle.SUBTITLE_COLOR : GuiStyle.MUTED_COLOR;
			cachedColumns.add(new SlotColumn(
				Component.translatable("gui.dndsheets.grimoire.slot_level", level),
				Component.literal(current[level] + "/" + max[level]),
				color));
		}

		int limit = SpellRegistry.preparedLimitFor(sheet);
		cachedPrepared = limit <= 0 ? null
			: Component.translatable("gui.dndsheets.grimoire.prepared_count", SpellRegistry.preparedCount(sheet), limit);
	}

	private void renderSlotTable(GuiGraphics guiGraphics) {
		JsonObject sheet = SheetLoader.getClientSheet();
		if (sheet == null) return;

		int version = SheetLoader.clientSheetVersion();
		if (version != cachedSlotVersion) {
			rebuildSlotTable(sheet);
			cachedSlotVersion = version;
		}

		if (cachedColumns.isEmpty()) {
			guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.grimoire.no_slots"), this.width / 2, SUBTITLE_Y, GuiStyle.MUTED_COLOR);
			return;
		}

		int firstColumnCenter = (this.width - cachedColumns.size() * SLOT_COL_WIDTH) / 2 + SLOT_COL_WIDTH / 2;
		for (int i = 0; i < cachedColumns.size(); i++) {
			SlotColumn column = cachedColumns.get(i);
			int x = firstColumnCenter + i * SLOT_COL_WIDTH;
			guiGraphics.drawCenteredString(this.font, column.levelLabel(), x, SUBTITLE_Y, GuiStyle.MUTED_COLOR);
			guiGraphics.drawCenteredString(this.font, column.countLabel(), x, SUBTITLE_Y + SLOT_ROW_STEP, column.color());
		}
		//To the right of the table, in the same band: how many are prepared out of how many can be.
		if (cachedPrepared != null) {
			guiGraphics.drawString(this.font, cachedPrepared,
				firstColumnCenter + cachedColumns.size() * SLOT_COL_WIDTH, SUBTITLE_Y + SLOT_ROW_STEP / 2, GuiStyle.MUTED_COLOR, false);
		}
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
			//Resolved here, not at paint time: the list's label is assembled by concatenating (level,
			//school, "unprepared") and ListPickerScreen's search box filters on that already-assembled
			//string, so the name has to go in translated. This runs on the CLIENT, so it comes out in its
			//language.
			String name = net.hawthorn.dndsheets.ContentNames.plain(
				entry.has("name") ? entry.get("name").getAsString() : id);
			int level = entry.has("level") ? entry.get("level").getAsInt() : 0;
			boolean prepared = SpellRegistry.isPrepared(sheet, id);
			//Same criterion updateButtons() already uses for the cast button: a cantrip doesn't have
			//"level 0", it has the tag 5e gives it. Before, the whole list used to say "(lv. 0)" for every
			//cantrip, which means nothing at the table and didn't distinguish a cantrip from a real spell
			//just by looking at the list.
			StringBuilder label = new StringBuilder(name);
			label.append(level == 0
				? Component.translatable("gui.dndsheets.grimoire.tag_cantrip").getString()
				: Component.translatable("gui.dndsheets.grimoire.tag_level", level).getString());
			//School and "unprepared" go IN the label because ListPickerScreen's search box filters on it:
			//typing "evoc" or "unprep" filters without spending a screen row on a filter button.
			if (entry.has("school")) label.append(" · ").append(entry.get("school").getAsString());
			if (!prepared) label.append(Component.translatable("gui.dndsheets.grimoire.tag_unprepared").getString());
			result.add(new KnownSpell(id, name, label.toString(), level, prepared));
		}
		result.sort(java.util.Comparator.comparingInt(KnownSpell::level).thenComparing(KnownSpell::name));
		return result;
	}
}
