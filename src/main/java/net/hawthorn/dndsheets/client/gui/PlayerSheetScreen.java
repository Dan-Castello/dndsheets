package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.CombatManager;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.PassiveScores;
import net.hawthorn.dndsheets.ResourceView;
import net.hawthorn.dndsheets.RollIndex;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.client.gui.components.IconButton;
import net.hawthorn.dndsheets.client.gui.components.TomeButton;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.hawthorn.dndsheets.network.SheetRollButtonMessage;
import net.hawthorn.dndsheets.network.SheetServerMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>The character sheet, rebuilt. It reads the synced client sheet (the same JSON as always: no key is renamed or
 * added) and lays itself out from the window size instead of from a texture, so it fits any GUI scale. Four tabs:
 * Overview (vitals, the six abilities with their modifiers, check and save rolls), Skills (all eighteen, proficiency
 * pips and bonuses), Combat (conditions, class resources, attacks) and Spells (slots, concentration, the Grimoire).</p>
 *
 * <p>Nothing here decides a rule: it shows numbers derived from the sheet the same way the server derives them
 * ({@code CombatManager.armorClassOf}, {@code PassiveScores}, the class tables through {@code ResourceView}) and sends
 * the same messages the old sheet sent (roll buttons, the whole-sheet save, the editors). The only things typed here are
 * the name, speed and the six scores, saved when the screen closes or before a roll, as the old sheet did.</p>
 */
public class PlayerSheetScreen extends ModalDialogScreen {
	private enum Tab { OVERVIEW, SKILLS, COMBAT, SPELLS }

	//Remembered between openings: reopening the sheet in the middle of a fight lands on the Combat tab you left.
	private static Tab lastTab = Tab.OVERVIEW;

	private static final String[] ABILITY_KEYS = {"strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma"};
	private static final String[] ABBR_KEYS = {"str", "dex", "con", "int", "wis", "cha"};

	private static final int PAD = 14;
	private static final int TAB_Y = 44;
	private static final int CONTENT_Y = 66;
	private static final int FOOTER = 26;
	private static final int PIP = 5;

	private static final int PANEL_DARK = 0xAA140F09;
	private static final int PANEL_EDGE = 0xFF3A2D1C;
	private static final int GOOD = 0xFF7FD68F;
	private static final int BAD = 0xFFE35A4B;

	private final int dialogW;
	private final int dialogH;
	private Tab tab = lastTab;
	private int attackScroll = 0;
	private boolean dirty = false;

	private EditBox nameBox;
	private EditBox speedBox;
	private final EditBox[] scoreBoxes = new EditBox[6];

	private PlayerSheetScreen(int w, int h) {
		super(Component.translatable("gui.dndsheets.sheet.title"), w, h);
		this.dialogW = w;
		this.dialogH = h;
	}

	public static void open() {
		Minecraft minecraft = Minecraft.getInstance();
		if (SheetLoader.getClientSheet() == null) return; //No sheet yet (still joining): nothing to show.
		int w = Math.min(430, minecraft.getWindow().getGuiScaledWidth() - 16);
		int h = Math.min(290, minecraft.getWindow().getGuiScaledHeight() - 16);
		minecraft.setScreen(new PlayerSheetScreen(w, h));
	}

	/** The H key: opens the sheet, or closes it if it is already the open screen. */
	public static void toggle() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen instanceof PlayerSheetScreen sheet) sheet.onClose();
		else if (minecraft.screen == null) open();
	}

	/** Called when a complete sheet arrives from the server (rest, preset, level up...): repaint from it. */
	public static void refreshIfOpen() {
		if (Minecraft.getInstance().screen instanceof PlayerSheetScreen screen) {
			//The server's sheet wins: a half-typed box must not be written back over what just arrived (a preset or a
			//level up may have changed those very scores), the same race the old sheet avoided by not saving here.
			screen.dirty = false;
			screen.rebuildWidgets();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		saveFields();
		lastTab = tab;
		super.onClose();
	}

	// --- data helpers (all from the client sheet) ---

	private static JsonObject sheet() {
		return SheetLoader.getClientSheet();
	}

	private static int intOf(JsonObject sheet, String key, int fallback) {
		if (sheet == null || !sheet.has(key)) return fallback;
		try {
			return Integer.parseInt(sheet.get(key).getAsString().trim());
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private static String textOf(JsonObject sheet, String key) {
		if (sheet == null || !sheet.has(key)) return "";
		try {
			return sheet.get(key).getAsString();
		} catch (RuntimeException e) {
			return "";
		}
	}

	private static int modOf(int score) {
		return SheetMath.modOf(score);
	}

	private static String signed(int value) {
		return SheetMath.signed(value);
	}

	private int level() {
		Player player = Minecraft.getInstance().player;
		return player == null ? 1 : SheetLoader.characterLevelOf(sheet(), player);
	}

	private int proficiency() {
		return 2 + (level() - 1) / 4;
	}

	private int abilityMod(String key) {
		return modOf(intOf(sheet(), key, 10));
	}

	private int bonusOf(String expression) {
		return SheetMath.bonusOf(sheet(), level(), expression);
	}

	private String expressionAt(String array, int index) {
		JsonObject sheet = sheet();
		if (sheet == null || !sheet.has(array)) return null;
		JsonArray values = sheet.getAsJsonArray(array);
		if (index >= values.size()) return null;
		try {
			return values.get(index).getAsString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	// --- layout ---

	private int contentLeft() {
		return dialogLeft() + PAD;
	}

	private int contentTop() {
		return dialogTop() + CONTENT_Y;
	}

	private int contentW() {
		return dialogW - 2 * PAD;
	}

	private int contentH() {
		return dialogH - CONTENT_Y - FOOTER;
	}

	@Override
	protected void init() {
		JsonObject sheet = sheet();
		if (sheet == null) {
			this.onClose();
			return;
		}
		nameBox = null;
		speedBox = null;
		java.util.Arrays.fill(scoreBoxes, null);

		//Header: name, editable.
		nameBox = new EditBox(this.font, dialogLeft() + PAD, dialogTop() + 8, Math.min(190, dialogW / 2 - PAD), 16,
			Component.translatable("gui.dndsheets.character_sheet.name"));
		nameBox.setMaxLength(40);
		nameBox.setValue(textOf(sheet, "characterName"));
		nameBox.setResponder(text -> dirty = true);
		this.addRenderableWidget(nameBox);

		//Header right: opens the character setup (race, class, background, skills) that the sheet used to hold as three free-text boxes.
		addModalButton(dialogW - PAD - 118, 7, 118, 18, Component.translatable("gui.dndsheets.sheet.setup"), b -> {
			saveFields();
			CharacterSetupScreen.open(this);
		});

		//Tabs.
		Tab[] tabs = Tab.values();
		int gap = 4;
		int tabW = (contentW() - gap * (tabs.length - 1)) / tabs.length;
		for (int i = 0; i < tabs.length; i++) {
			Tab target = tabs[i];
			Component label = Component.translatable("gui.dndsheets.sheet.tab_" + target.name().toLowerCase(java.util.Locale.ROOT));
			if (target == tab) label = label.copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
			addModalButton(PAD + i * (tabW + gap), TAB_Y, tabW, 16, label, b -> {
				saveFields();
				tab = target;
				attackScroll = 0;
				this.rebuildWidgets();
			});
		}

		switch (tab) {
			case OVERVIEW -> initOverview(sheet);
			case SKILLS -> initSkills(sheet);
			case COMBAT -> initCombat(sheet);
			case SPELLS -> initSpells(sheet);
		}

		//Footer.
		int footerY = dialogH - 22;
		int buttonW = (contentW() - 8) / 3;
		addModalButton(PAD, footerY, buttonW, 18, Component.translatable("gui.dndsheets.character_sheet.grimoire"), b -> {
			saveFields();
			GrimoireScreen.open(this);
		});
		addModalButton(PAD + buttonW + 4, footerY, buttonW, 18, Component.translatable("gui.dndsheets.character_sheet.menu"), b -> {
			saveFields();
			PlayerPanelScreen.open(this);
		});
		addModalButton(PAD + 2 * (buttonW + 4), footerY, buttonW, 18, Component.translatable("gui.dndsheets.sheet.close"), b -> this.onClose());
	}

	private IconButton rollButton(int x, int y, int size, ResourceLocation icon, Component tooltip, int category, int index, int subIndex) {
		IconButton button = new IconButton(dialogLeft() + x, dialogTop() + y, size, icon, tooltip.copy().append(Component.literal("\n").append(Component.translatable("gui.dndsheets.sheet.ctrl_edit").withStyle(ChatFormatting.GRAY))),
			b -> { if (hasControlDown()) editFormula(category, index, subIndex); else sendRoll(category, index, subIndex); });
		this.addRenderableWidget(button);
		return button;
	}

	private void sendRoll(int category, int index, int subIndex) {
		saveFields();
		//Shift+click = private roll (Stealth, Investigation...): only reaches whoever rolled and connected operators.
		DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetRollButtonMessage(category, index, subIndex, hasShiftDown()));
	}

	/** Ctrl+click on any die opens its formula. */
	private void editFormula(int category, int index, int subIndex) {
		saveFields();
		RollFormulaScreen.open(this, category, index, subIndex);
	}

	// --- OVERVIEW ---

	private static final int PLATE_H = 30;

	private void initOverview(JsonObject sheet) {
		int plateW = (contentW() - 8) / 3;
		int top = CONTENT_Y;

		//Speed: the one vital that is typed.
		int speedX = PAD + (plateW + 4) * 2 + 22;
		speedBox = new EditBox(this.font, dialogLeft() + speedX, dialogTop() + top + 12, plateW - 26, 14, Component.translatable("gui.dndsheets.character_sheet.label_speed"));
		speedBox.setMaxLength(24);
		speedBox.setValue(textOf(sheet, "speed"));
		speedBox.setResponder(text -> dirty = true);
		this.addRenderableWidget(speedBox);

		//Initiative roll sits in plate row 2, column 1.
		rollButton(PAD + plateW - 20, top + PLATE_H + 4 + 11, 14, Icons.stat("d20"), Component.translatable("gui.dndsheets.character_sheet.roll_of", Component.translatable("gui.dndsheets.character_sheet.label_initiative")), 0, 6, 0);

		//Ability tiles.
		int tilesTop = top + 2 * (PLATE_H + 4) + 6;
		int tileW = (contentW() - 5 * 4) / 6;
		for (int i = 0; i < 6; i++) {
			int x = PAD + i * (tileW + 4);
			final int index = i;
			EditBox box = new EditBox(this.font, dialogLeft() + x + (tileW - 26) / 2, dialogTop() + tilesTop + 12, 26, 14,
				Component.translatable("gui.dndsheets.character_sheet.ability_" + ABBR_KEYS[i]));
			box.setMaxLength(2);
			box.setFilter(text -> text.matches("\\d{0,2}"));
			box.setValue(Integer.toString(intOf(sheet, ABILITY_KEYS[i], 10)));
			box.setResponder(text -> dirty = true);
			scoreBoxes[i] = box;
			this.addRenderableWidget(box);

			Component abilityName = Component.translatable("gui.dndsheets.character_sheet.ability_" + ABBR_KEYS[i]);
			int rollY = tilesTop + 12 + 14 + 14;
			rollButton(x + tileW / 2 - 17, rollY, 15, Icons.stat("d20"), Component.translatable("gui.dndsheets.character_sheet.roll_of", abilityName), 0, index, 0);
			rollButton(x + tileW / 2 + 2, rollY, 15, Icons.stat("ac"), Component.translatable("gui.dndsheets.character_sheet.save_tooltip", abilityName), 1, index, 0);
		}
	}

	private void renderOverview(GuiGraphics g, JsonObject sheet) {
		Player player = Minecraft.getInstance().player;
		int plateW = (contentW() - 8) / 3;
		int left = contentLeft();
		int top = contentTop();

		//Row 1: HP (with a bar), AC, Speed.
		int hp = player == null ? 0 : (int) Math.ceil(player.getHealth());
		int maxHp = player == null ? 0 : (int) Math.ceil(player.getMaxHealth());
		int temp = player == null ? 0 : (int) Math.ceil(player.getAbsorptionAmount());
		plate(g, left, top, plateW, Icons.stat("hp"), Component.translatable("gui.dndsheets.sheet.hp"));
		int barX = left + 24;
		int barY = top + 17;
		int barW = plateW - 30;
		g.fill(barX, barY, barX + barW, barY + 6, 0xFF0B0906);
		double fraction = maxHp <= 0 ? 0 : Math.min(1.0, hp / (double) maxHp);
		g.fill(barX + 1, barY + 1, barX + 1 + (int) ((barW - 2) * fraction), barY + 5, fraction > 0.5 ? GOOD : fraction > 0.25 ? 0xFFE0B63F : BAD);
		g.drawString(this.font, hp + " / " + maxHp + (temp > 0 ? "  +" + temp : ""), barX, top + 8, GuiStyle.TITLE_COLOR, false);

		int ac = player == null ? 10 : CombatManager.armorClassOf(player, sheet);
		plate(g, left + plateW + 4, top, plateW, Icons.stat("ac"), Component.translatable("gui.dndsheets.sheet.ac"));
		g.drawString(this.font, Integer.toString(ac), left + plateW + 4 + 26, top + 14, GuiStyle.TITLE_COLOR, false);

		plate(g, left + 2 * (plateW + 4), top, plateW, Icons.stat("speed"), Component.translatable("gui.dndsheets.character_sheet.label_speed"));

		//Row 2: Initiative, Proficiency, Passive Perception.
		int row2 = top + PLATE_H + 4;
		plate(g, left, row2, plateW, Icons.stat("initiative"), Component.translatable("gui.dndsheets.character_sheet.label_initiative"));
		g.drawString(this.font, signed(bonusOf(expressionAt("checks", 6))), left + 26, row2 + 14, GuiStyle.TITLE_COLOR, false);
		plate(g, left + plateW + 4, row2, plateW, Icons.stat("spell"), Component.translatable("gui.dndsheets.character_sheet.label_proficiency_bonus"));
		g.drawString(this.font, signed(proficiency()) + "   " + Component.translatable("gui.dndsheets.sheet.level", level()).getString(), left + plateW + 4 + 26, row2 + 14, GuiStyle.TITLE_COLOR, false);
		plate(g, left + 2 * (plateW + 4), row2, plateW, Icons.byPath("action/search"), Component.translatable("gui.dndsheets.sheet.passive"));
		g.drawString(this.font, Integer.toString(PassiveScores.passivePerception(sheet)), left + 2 * (plateW + 4) + 26, row2 + 14, GuiStyle.TITLE_COLOR, false);

		//Ability tiles.
		int tilesTop = top + 2 * (PLATE_H + 4) + 6;
		int tileW = (contentW() - 5 * 4) / 6;
		for (int i = 0; i < 6; i++) {
			int x = left + i * (tileW + 4);
			g.fill(x, tilesTop, x + tileW, tilesTop + 62, PANEL_DARK);
			g.renderOutline(x, tilesTop, tileW, 62, PANEL_EDGE);
			Component name = Component.translatable("gui.dndsheets.sheet.abbr_" + ABBR_KEYS[i]);
			g.drawCenteredString(this.font, name, x + tileW / 2, tilesTop + 3, GuiStyle.ACCENT_COLOR);
			int mod = modOf(intOf(sheet, ABILITY_KEYS[i], 10));
			g.drawCenteredString(this.font, signed(mod), x + tileW / 2, tilesTop + 12 + 14 + 2, GuiStyle.TITLE_COLOR);
			//Save proficiency pip, under the save button.
			boolean saveProf = RollIndex.isProficient(expressionAt("saves", i));
			pip(g, x + tileW / 2 + 2 + 5, tilesTop + 12 + 14 + 14 + 17, saveProf);
		}
		g.drawString(this.font, Component.translatable("gui.dndsheets.sheet.hint_overview"), left, tilesTop + 62 + 5, GuiStyle.MUTED_COLOR, false);
	}

	private void plate(GuiGraphics g, int x, int y, int w, ResourceLocation icon, Component label) {
		g.fill(x, y, x + w, y + PLATE_H, PANEL_DARK);
		g.renderOutline(x, y, w, PLATE_H, PANEL_EDGE);
		Icons.draw(g, icon, x + 4, y + 7, 16);
		g.drawString(this.font, label, x + 26, y + 3, GuiStyle.MUTED_COLOR, false);
	}

	private static void pip(GuiGraphics g, int x, int y, boolean filled) {
		g.renderOutline(x, y, PIP, PIP, GuiStyle.ACCENT_COLOR);
		if (filled) g.fill(x + 1, y + 1, x + PIP - 1, y + PIP - 1, GuiStyle.ACCENT_COLOR);
	}

	/** The proficiency pip of a skill row, clickable: it asks the server to toggle it. */
	private static final class PipButton extends Button {
		private final int skill;

		PipButton(int x, int y, int skill, OnPress onPress) {
			super(x, y, 12, 14, Component.empty(), onPress, DEFAULT_NARRATION);
			this.skill = skill;
		}

		@Override
		public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
			pip(guiGraphics, this.getX() + 3, this.getY() + 4, RollIndex.isSkillProficient(sheet(), skill));
			if (this.isHoveredOrFocused()) guiGraphics.renderOutline(this.getX(), this.getY(), this.width, this.height, GuiStyle.ACCENT_COLOR);
		}
	}

	// --- SKILLS ---

	private static final int SKILL_ROW = 18;

	private void initSkills(JsonObject sheet) {
		int colW = (contentW() - 6) / 2;
		for (int i = 0; i < RollIndex.SKILL_COUNT; i++) {
			int col = i < 9 ? 0 : 1;
			int row = i < 9 ? i : i - 9;
			int x = PAD + col * (colW + 6);
			int y = CONTENT_Y + 4 + row * SKILL_ROW;
			final int index = i;
			//Pip as a click target: asks the server to toggle the proficiency (the server writes the expression).
			Button toggle = new PipButton(dialogLeft() + x, dialogTop() + y, index,
				b -> DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.SKILL_TOGGLE, String.valueOf(index))));
			toggle.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet.toggle_proficiency")));
			this.addRenderableWidget(toggle);
			rollButton(x + colW - 16, y - 1, 15, Icons.stat("d20"), Component.translatable("gui.dndsheets.character_sheet.roll_of", Component.translatable(RollIndex.skillLangKey(i))), 2, i, 0);
		}
	}

	private void renderSkills(GuiGraphics g, JsonObject sheet) {
		int colW = (contentW() - 6) / 2;
		for (int i = 0; i < RollIndex.SKILL_COUNT; i++) {
			int col = i < 9 ? 0 : 1;
			int row = i < 9 ? i : i - 9;
			int x = contentLeft() + col * (colW + 6);
			int y = dialogTop() + CONTENT_Y + 4 + row * SKILL_ROW;
			Component name = Component.translatable(RollIndex.skillLangKey(i));
			boolean proficient = RollIndex.isSkillProficient(sheet, i);
			g.drawString(this.font, name, x + 16, y + 3, proficient ? GuiStyle.TITLE_COLOR : GuiStyle.SUBTITLE_COLOR, false);
			String abbr = Component.translatable("gui.dndsheets.sheet.abbr_" + RollIndex.skillAbility(i)).getString();
			g.drawString(this.font, abbr, x + colW - 66, y + 3, GuiStyle.MUTED_COLOR, false);
			g.drawString(this.font, signed(bonusOf(expressionAt("skills", i))), x + colW - 40, y + 3, proficient ? GuiStyle.ACCENT_COLOR : GuiStyle.TITLE_COLOR, false);
		}
	}

	// --- COMBAT ---

	private void initCombat(JsonObject sheet) {
		int top = CONTENT_Y + conditionsBlockHeight(sheet) + resourcesBlockHeight(sheet);
		JsonArray attacks = sheet.has("attacks") ? sheet.getAsJsonArray("attacks") : new JsonArray();
		int rows = attackRows();
		int rowW = contentW();
		for (int slot = 0; slot < rows; slot++) {
			int index = slot + attackScroll;
			if (index >= attacks.size()) break;
			int y = top + 16 + slot * 18;
			final int attackIndex = index;
			int buttonsX = rowW + PAD - 3 * 17 - 17 - 2;
			rollButton(buttonsX, y, 15, Icons.stat("damage"), Component.translatable("gui.dndsheets.character_sheet.roll_tooltip", Component.translatable("gui.dndsheets.sheet.damage")), 3, attackIndex, 0);
			rollButton(buttonsX + 17, y, 15, Icons.stat("attack"), Component.translatable("gui.dndsheets.character_sheet.roll_tooltip", Component.translatable("gui.dndsheets.sheet.attack")), 3, attackIndex, 1);
			IconButton edit = new IconButton(dialogLeft() + buttonsX + 34, dialogTop() + y, 15, Icons.byPath("ui/edit"),
				Component.translatable("gui.dndsheets.character_sheet.edit_formula", Component.translatable("gui.dndsheets.sheet.attack")),
				b -> editFormula(3, attackIndex, 1));
			this.addRenderableWidget(edit);
			IconButton delete = new IconButton(dialogLeft() + buttonsX + 51, dialogTop() + y, 15, Icons.byPath("ui/delete"),
				Component.translatable("gui.dndsheets.character_sheet.delete_row"), b -> ConfirmScreen.ask(Component.literal(attackName(attacks, attackIndex)), () -> {
					JsonObject current = sheet();
					SheetLoader.validateSheet(current);
					JsonArray array = current.getAsJsonArray("attacks");
					if (attackIndex < array.size()) array.remove(attackIndex);
					sendWholeSheet();
					this.rebuildWidgets();
				}));
			this.addRenderableWidget(delete);
		}
		//Add attack: same default rows the old sheet created.
		addModalButton(dialogW - PAD - 118, top - 2, 118, 14, Component.translatable("gui.dndsheets.sheet.add_attack"), b -> {
			JsonObject current = sheet();
			SheetLoader.validateSheet(current);
			current.getAsJsonArray("attacks").add(newAttack());
			sendWholeSheet();
			attackScroll = Math.max(0, current.getAsJsonArray("attacks").size() - attackRows());
			this.rebuildWidgets();
		});
	}

	private static String attackName(JsonArray attacks, int index) {
		try {
			return attacks.get(index).getAsJsonObject().get("name").getAsString();
		} catch (RuntimeException e) {
			return "?";
		}
	}

	private static JsonObject newAttack() {
		JsonObject form = new JsonObject();
		form.addProperty("name", Component.translatable("gui.dndsheets.sheet.new_attack").getString());
		JsonArray set = new JsonArray();
		set.add(rollGroup("Damage Roll", "2d6 + $str"));
		set.add(rollGroup("Attack Roll", "1d20 + $str + $prof"));
		form.add("rolls", set);
		return form;
	}

	private static JsonArray rollGroup(String context, String expression) {
		JsonArray group = new JsonArray();
		JsonObject first = new JsonObject();
		first.addProperty("context", context);
		first.addProperty("expression", expression);
		JsonObject second = new JsonObject();
		second.addProperty("context", "");
		second.addProperty("expression", "");
		group.add(first);
		group.add(second);
		return group;
	}

	private int conditionsBlockHeight(JsonObject sheet) {
		return 22;
	}

	private int resourcesBlockHeight(JsonObject sheet) {
		int pools = ResourceView.poolsFor(sheet).size();
		return pools == 0 ? 0 : 16 + pools * 14 + 4;
	}

	private int attackRows() {
		int available = contentH() - conditionsBlockHeight(sheet()) - resourcesBlockHeight(sheet()) - 16 - 4;
		return Math.max(1, available / 18);
	}

	private void renderCombat(GuiGraphics g, JsonObject sheet) {
		int left = contentLeft();
		int y = dialogTop() + CONTENT_Y;

		//Conditions: glyphs with the name on hover (the HUD does the same), or "none".
		g.drawString(this.font, Component.translatable("gui.dndsheets.sheet.conditions"), left, y + 2, GuiStyle.ACCENT_COLOR, false);
		int x = left + this.font.width(Component.translatable("gui.dndsheets.sheet.conditions")) + 8;
		boolean any = false;
		if (sheet.has("conditions")) {
			for (var element : sheet.getAsJsonArray("conditions")) {
				String entry = element.getAsString();
				int at = entry.indexOf('@');
				String label = at < 0 ? entry : entry.substring(0, at);
				ResourceLocation glyph = Icons.condition(label);
				if (glyph != null) {
					Icons.draw(g, glyph, x, y - 1, 16);
					x += 19;
				} else {
					g.drawString(this.font, label, x, y + 2, GuiStyle.SUBTITLE_COLOR, false);
					x += this.font.width(label) + 6;
				}
				any = true;
			}
		}
		if (!any) g.drawString(this.font, Component.translatable("gui.dndsheets.sheet.none"), x, y + 2, GuiStyle.MUTED_COLOR, false);
		y += conditionsBlockHeight(sheet);

		//Class resources as pips.
		List<ResourceView.Pool> pools = ResourceView.poolsFor(sheet);
		if (!pools.isEmpty()) {
			g.drawString(this.font, Component.translatable("gui.dndsheets.sheet.resources"), left, y, GuiStyle.ACCENT_COLOR, false);
			int rowY = y + 14;
			for (ResourceView.Pool pool : pools) {
				Icons.draw(g, Icons.byPath(pool.icon()), left, rowY - 2, 12);
				g.drawString(this.font, Component.translatable(pool.nameKey()), left + 16, rowY, GuiStyle.SUBTITLE_COLOR, false);
				int pipX = left + 16 + 110;
				if (pool.max() < 0) {
					g.drawString(this.font, "∞", pipX, rowY, GuiStyle.TITLE_COLOR, false);
				} else if (pool.max() <= 10) {
					for (int i = 0; i < pool.max(); i++) pip(g, pipX + i * (PIP + 3), rowY + 1, i < pool.left());
				} else {
					g.drawString(this.font, pool.left() + " / " + pool.max(), pipX, rowY, GuiStyle.TITLE_COLOR, false);
				}
				rowY += 14;
			}
			y += resourcesBlockHeight(sheet);
		}

		//Attacks.
		g.drawString(this.font, Component.translatable("gui.dndsheets.sheet.attacks"), left, y + 2, GuiStyle.ACCENT_COLOR, false);
		JsonArray attacks = sheet.has("attacks") ? sheet.getAsJsonArray("attacks") : new JsonArray();
		if (attacks.isEmpty()) {
			g.drawString(this.font, Component.translatable("gui.dndsheets.sheet.no_attacks"), left, y + 18, GuiStyle.MUTED_COLOR, false);
		}
		for (int slot = 0; slot < attackRows(); slot++) {
			int index = slot + attackScroll;
			if (index >= attacks.size()) break;
			String name = attackName(attacks, index);
			g.drawString(this.font, name, left + 2, y + 16 + slot * 18 + 4, GuiStyle.TITLE_COLOR, false);
		}
		if (attacks.size() > attackRows()) {
			g.drawString(this.font, (attackScroll + 1) + "-" + Math.min(attacks.size(), attackScroll + attackRows()) + " / " + attacks.size(),
				left, dialogTop() + dialogH - FOOTER - 8, GuiStyle.MUTED_COLOR, false);
		}
	}

	//The H key closes the sheet again, unless a box is being typed in (an H in the name must stay an H).
	@Override
	public boolean keyPressed(int key, int scanCode, int modifiers) {
		boolean typing = (nameBox != null && nameBox.isFocused()) || (speedBox != null && speedBox.isFocused());
		for (EditBox box : scoreBoxes) typing |= box != null && box.isFocused();
		if (!typing && net.hawthorn.dndsheets.init.DndsheetsModKeyMappings.CHARACTER.matches(key, scanCode)) {
			this.onClose();
			return true;
		}
		return super.keyPressed(key, scanCode, modifiers);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		if (tab == Tab.COMBAT && sheet() != null && sheet().has("attacks")) {
			int total = sheet().getAsJsonArray("attacks").size();
			int next = Math.max(0, Math.min(Math.max(0, total - attackRows()), attackScroll - (int) Math.signum(delta)));
			if (next != attackScroll) {
				attackScroll = next;
				this.rebuildWidgets();
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, delta);
	}

	// --- SPELLS ---

	private void initSpells(JsonObject sheet) {
		addModalButton(PAD, dialogH - FOOTER - 20, contentW(), 16, Component.translatable("gui.dndsheets.sheet.open_grimoire"), b -> {
			saveFields();
			GrimoireScreen.open(this);
		});
	}

	private void renderSpells(GuiGraphics g, JsonObject sheet) {
		int left = contentLeft();
		int y = dialogTop() + CONTENT_Y + 2;

		g.drawString(this.font, Component.translatable("gui.dndsheets.sheet.slots"), left, y, GuiStyle.ACCENT_COLOR, false);
		int[] max = slotArray(sheet, "spellSlotsMaxByLevel");
		int[] cur = slotArray(sheet, "spellSlotsByLevel");
		boolean any = false;
		int rowY = y + 14;
		for (int level = 1; level <= 9; level++) {
			if (max[level] <= 0) continue;
			any = true;
			g.drawString(this.font, Component.translatable("gui.dndsheets.grimoire.level", level), left, rowY, GuiStyle.SUBTITLE_COLOR, false);
			for (int i = 0; i < max[level]; i++) pip(g, left + 60 + i * (PIP + 3), rowY + 1, i < cur[level]);
			g.drawString(this.font, cur[level] + " / " + max[level], left + 60 + max[level] * (PIP + 3) + 6, rowY, GuiStyle.MUTED_COLOR, false);
			rowY += 13;
		}
		if (!any) g.drawString(this.font, Component.translatable("gui.dndsheets.sheet.no_slots"), left, rowY, GuiStyle.MUTED_COLOR, false);

		if (sheet.has("concentratingOn")) {
			g.drawString(this.font, Component.translatable("hud.dndsheets.concentrating", net.hawthorn.dndsheets.ContentNames.of(sheet.get("concentratingOn").getAsString())),
				left, dialogTop() + dialogH - FOOTER - 36, GuiStyle.TITLE_COLOR, false);
			Icons.draw(g, Icons.stat("concentration"), left + contentW() - 16, dialogTop() + dialogH - FOOTER - 40, 14);
		}
	}

	private static int[] slotArray(JsonObject sheet, String key) {
		int[] values = new int[10];
		if (sheet == null || !sheet.has(key) || !sheet.get(key).isJsonArray()) return values;
		JsonArray array = sheet.getAsJsonArray(key);
		for (int i = 0; i < Math.min(10, array.size()); i++) {
			try {
				values[i] = array.get(i).getAsInt();
			} catch (RuntimeException ignored) {
				values[i] = 0;
			}
		}
		return values;
	}

	// --- saving ---

	/** Writes the typed fields into the client sheet and sends it, only if something was typed. */
	private void saveFields() {
		if (!dirty) return;
		JsonObject sheet = sheet();
		if (sheet == null) return;
		if (nameBox != null) sheet.addProperty("characterName", nameBox.getValue());
		if (speedBox != null) sheet.addProperty("speed", speedBox.getValue());
		for (int i = 0; i < 6; i++) {
			if (scoreBoxes[i] != null && !scoreBoxes[i].getValue().isBlank()) sheet.addProperty(ABILITY_KEYS[i], scoreBoxes[i].getValue());
		}
		sendWholeSheet();
		dirty = false;
	}

	private void sendWholeSheet() {
		JsonObject sheet = sheet();
		if (sheet == null) return;
		DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetServerMessage(sheet.toString().getBytes(StandardCharsets.UTF_8)));
	}

	// --- render ---

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
		this.renderPanel(g);
		JsonObject sheet = sheet();
		if (sheet == null) return;

		//Header: class line under the name.
		String race = textOf(sheet, "characterRace");
		String klass = textOf(sheet, "characterClass");
		String background = textOf(sheet, "background");
		List<String> parts = new ArrayList<>();
		if (!race.isBlank()) parts.add(race);
		if (!klass.isBlank()) parts.add(klass);
		if (!background.isBlank()) parts.add(background);
		Component line = parts.isEmpty() ? Component.translatable("gui.dndsheets.sheet.no_class") : Component.literal(String.join(" · ", parts));
		g.drawString(this.font, this.font.substrByWidth(line, contentW()).getString(), dialogLeft() + PAD, dialogTop() + 28, parts.isEmpty() ? GuiStyle.MUTED_COLOR : GuiStyle.SUBTITLE_COLOR, false);
		GuiStyle.rule(g, dialogLeft() + PAD, dialogLeft() + dialogW - PAD, dialogTop() + 40);

		switch (tab) {
			case OVERVIEW -> renderOverview(g, sheet);
			case SKILLS -> renderSkills(g, sheet);
			case COMBAT -> renderCombat(g, sheet);
			case SPELLS -> renderSpells(g, sheet);
		}
		super.render(g, mouseX, mouseY, partialTicks);
	}
}
