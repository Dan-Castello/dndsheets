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

	private static final int PANEL_EDGE = 0xFF3A2D1C;
	private static final int GOOD = GuiStyle.GOOD_COLOR;
	private static final int BAD = GuiStyle.BAD_COLOR;

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
		//Borderless, with an underline (render): the black vanilla box looked like a form, not a character sheet.
		nameBox = new EditBox(this.font, dialogLeft() + PAD, dialogTop() + 13, Math.min(190, dialogW / 2 - PAD), 10,
			Component.translatable("gui.dndsheets.character_sheet.name"));
		nameBox.setBordered(false);
		nameBox.setTextColor(GuiStyle.TITLE_COLOR);
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
			if (target == tab) label = label.copy().withStyle(st -> st.withColor(GuiStyle.ACCENT_COLOR & 0xFFFFFF).withBold(true));
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
		IconButton button = new IconButton(dialogLeft() + x, dialogTop() + y, size, icon, tooltip.copy()
				.append(Component.literal("\n").append(Component.translatable("gui.dndsheets.sheet.shift_private").withStyle(ChatFormatting.GRAY)))
				.append(Component.literal("\n").append(Component.translatable("gui.dndsheets.sheet.ctrl_edit").withStyle(ChatFormatting.GRAY))),
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

	private static final int VITALS_H = 44;
	private static final int TILE_H = 84;
	private static final int GAP = 4;

	//A label shown in short form with its full name as a tooltip (DESIGN.md §3), and where it was drawn.
	private record Hint(int x, int y, int w, int h, Component text) {}
	private final List<Hint> hints = new ArrayList<>();
	//Screen rects of the save buttons whose saving throw is proficient: they get a brass ring.
	private final List<int[]> saveRings = new ArrayList<>();

	private int hpCardW() {
		return contentW() * 37 / 100;
	}

	private int medallionW() {
		return (contentW() - hpCardW() - 5 * GAP) / 5;
	}

	//Relative to the dialog, like every widget position in init.
	private int medallionX(int index) {
		return PAD + hpCardW() + GAP + index * (medallionW() + GAP);
	}

	private int tileW() {
		return (contentW() - 5 * GAP) / 6;
	}

	private void initOverview(JsonObject sheet) {
		saveRings.clear();
		int top = CONTENT_Y;

		//Speed: the one vital that is typed. Borderless, in the same place every other medallion draws its
		//value; an underline (render) says it can be edited.
		speedBox = new EditBox(this.font, dialogLeft() + medallionX(2) + 6, dialogTop() + top + 22, medallionW() - 12, 10, Component.translatable("gui.dndsheets.character_sheet.label_speed"));
		speedBox.setBordered(false);
		speedBox.setTextColor(GuiStyle.TITLE_COLOR);
		speedBox.setMaxLength(24);
		speedBox.setValue(textOf(sheet, "speed"));
		speedBox.setResponder(text -> dirty = true);
		this.addRenderableWidget(speedBox);

		//Initiative roll, in its medallion's corner.
		rollButton(medallionX(1) + medallionW() - 15, top + 19, 12, Icons.stat("d20"), Component.translatable("gui.dndsheets.character_sheet.roll_of", Component.translatable("gui.dndsheets.character_sheet.label_initiative")), 0, 6, 0);

		int tilesTop = top + VITALS_H + 8;
		for (int i = 0; i < 6; i++) {
			int x = PAD + i * (tileW() + GAP);
			int centerX = x + tileW() / 2;
			final int index = i;
			String score = Integer.toString(intOf(sheet, ABILITY_KEYS[i], 10));
			//Borderless EditBox text is left-aligned: start it where the current value comes out centered.
			EditBox box = new EditBox(this.font, dialogLeft() + centerX - this.font.width(score) / 2, dialogTop() + tilesTop + 45, 14, 10,
				Component.translatable("gui.dndsheets.character_sheet.ability_" + ABBR_KEYS[i]));
			box.setBordered(false);
			box.setTextColor(GuiStyle.SUBTITLE_COLOR);
			box.setMaxLength(2);
			box.setFilter(text -> text.matches("\\d{0,2}"));
			box.setValue(score);
			box.setResponder(text -> dirty = true);
			scoreBoxes[i] = box;
			this.addRenderableWidget(box);

			Component abilityName = Component.translatable("gui.dndsheets.character_sheet.ability_" + ABBR_KEYS[i]);
			int rollY = tilesTop + 65;
			rollButton(centerX - 18, rollY, 16, Icons.stat("d20"), Component.translatable("gui.dndsheets.character_sheet.roll_of", abilityName), 0, index, 0);
			//Save proficiency used to be a bare square under this button that nobody could read: now the
			//button itself carries it, as a brass ring and a line in its tooltip.
			boolean saveProf = RollIndex.isProficient(expressionAt("saves", i));
			Component saveTooltip = Component.translatable("gui.dndsheets.character_sheet.save_tooltip", abilityName).copy()
				.append(Component.literal("\n")).append(Component.translatable(saveProf ? "gui.dndsheets.sheet.save_prof_yes" : "gui.dndsheets.sheet.save_prof_no", abilityName)
					.withStyle(saveProf ? ChatFormatting.GOLD : ChatFormatting.GRAY));
			rollButton(centerX + 2, rollY, 16, Icons.stat("ac"), saveTooltip, 1, index, 0);
			if (saveProf) saveRings.add(new int[] {dialogLeft() + centerX + 1, dialogTop() + rollY - 1, 18});
		}
	}

	private void renderOverview(GuiGraphics g, JsonObject sheet) {
		Player player = Minecraft.getInstance().player;
		int left = contentLeft();
		int top = contentTop();
		hints.clear();

		//Hit points: the hero card, since it's the number a player checks most.
		int hp = player == null ? 0 : (int) Math.ceil(player.getHealth());
		int maxHp = player == null ? 0 : (int) Math.ceil(player.getMaxHealth());
		int temp = player == null ? 0 : (int) Math.ceil(player.getAbsorptionAmount());
		int hpW = hpCardW();
		GuiStyle.inset(g, left, top, left + hpW, top + VITALS_H);
		Icons.draw(g, Icons.stat("hp"), left + 5, top + 5, 14);
		g.drawString(this.font, Component.translatable("gui.dndsheets.sheet.hp"), left + 23, top + 8, GuiStyle.MUTED_COLOR, false);
		String hpText = hp + " / " + maxHp;
		g.pose().pushPose();
		g.pose().translate(left + 6, top + 20, 0);
		g.pose().scale(1.5f, 1.5f, 1);
		g.drawString(this.font, hpText, 0, 0, GuiStyle.TITLE_COLOR, false);
		g.pose().popPose();
		if (temp > 0) g.drawString(this.font, "+" + temp, left + 6 + (int) (this.font.width(hpText) * 1.5f) + 5, top + 23, GuiStyle.INFO_COLOR, false);
		//The bar keeps the existing colour by fraction; temporary HP is a blue segment after it.
		int barX = left + 6;
		int barW = hpW - 12;
		int barY = top + VITALS_H - 9;
		g.fill(barX, barY, barX + barW, barY + 5, 0xFF0B0906);
		double fraction = maxHp <= 0 ? 0 : Math.min(1.0, hp / (double) maxHp);
		int hpFill = (int) ((barW - 2) * fraction);
		g.fill(barX + 1, barY + 1, barX + 1 + hpFill, barY + 4, fraction > 0.5 ? GOOD : fraction > 0.25 ? GuiStyle.WARN_COLOR : BAD);
		if (temp > 0 && maxHp > 0) {
			int tempFill = Math.min(barW - 2 - hpFill, (int) ((barW - 2) * (temp / (double) maxHp)));
			g.fill(barX + 1 + hpFill, barY + 1, barX + 1 + hpFill + tempFill, barY + 4, GuiStyle.INFO_COLOR);
		}

		//The five small vitals: short label on top (full name on hover), value under it.
		int ac = player == null ? 10 : CombatManager.armorClassOf(player, sheet);
		medallion(g, 0, Icons.stat("ac"), "short_ac", Component.translatable("gui.dndsheets.sheet.ac"), Integer.toString(ac));
		medallion(g, 1, Icons.stat("initiative"), "short_init", Component.translatable("gui.dndsheets.character_sheet.label_initiative"), signed(bonusOf(expressionAt("checks", 6))));
		medallion(g, 2, Icons.stat("speed"), "short_speed", Component.translatable("gui.dndsheets.character_sheet.label_speed"), null);
		medallion(g, 3, Icons.stat("spell"), "short_prof", Component.translatable("gui.dndsheets.character_sheet.label_proficiency_bonus"), signed(proficiency()));
		medallion(g, 4, Icons.byPath("action/search"), "short_passive", Component.translatable("gui.dndsheets.sheet.passive"), Integer.toString(PassiveScores.passivePerception(sheet)));

		//Ability tiles, 5e style: the modifier is what you roll with, so it is the big number; the score
		//under it is the editable source.
		int tilesTop = top + VITALS_H + 8;
		for (int i = 0; i < 6; i++) {
			int x = left + i * (tileW() + GAP);
			int centerX = x + tileW() / 2;
			int color = GuiStyle.ABILITY_COLORS[i];
			GuiStyle.inset(g, x, tilesTop, x + tileW(), tilesTop + TILE_H);
			g.fill(x + 1, tilesTop + 1, x + tileW() - 1, tilesTop + 4, color);
			g.drawCenteredString(this.font, Component.translatable("gui.dndsheets.sheet.abbr_" + ABBR_KEYS[i]), centerX, tilesTop + 8, color);
			g.pose().pushPose();
			g.pose().translate(centerX, tilesTop + 20, 0);
			g.pose().scale(2f, 2f, 1);
			g.drawCenteredString(this.font, signed(modOf(intOf(sheet, ABILITY_KEYS[i], 10))), 0, 0, GuiStyle.TITLE_COLOR);
			g.pose().popPose();
			GuiStyle.inset(g, centerX - 13, tilesTop + 42, centerX + 13, tilesTop + 56);
			g.fill(x + 6, tilesTop + 61, x + tileW() - 6, tilesTop + 62, PANEL_EDGE);
		}

		//Conditions at a glance (the Combat tab has the same row), if it fits above the footer.
		int conditionsY = tilesTop + TILE_H + 10;
		if (conditionsY + 16 <= dialogTop() + dialogH - FOOTER) renderConditionsRow(g, sheet, left, conditionsY);
	}

	//value null = the medallion's value is a widget (Speed's box), not text.
	private void medallion(GuiGraphics g, int index, ResourceLocation icon, String shortKey, Component fullName, String value) {
		int x = dialogLeft() + medallionX(index);
		int y = contentTop();
		int w = medallionW();
		GuiStyle.inset(g, x, y, x + w, y + VITALS_H);
		Icons.draw(g, icon, x + 4, y + 5, 10);
		Component label = Component.translatable("gui.dndsheets.sheet." + shortKey);
		g.drawString(this.font, this.font.plainSubstrByWidth(label.getString(), w - 18), x + 16, y + 6, GuiStyle.MUTED_COLOR, false);
		if (value != null) g.drawString(this.font, value, x + 6, y + 22, GuiStyle.TITLE_COLOR, false);
		hints.add(new Hint(x, y, w, 16, fullName));
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

	//Conditions as red chips, glyph + name (no hovering needed to know what you have), or "none".
	//Stops with "+N" once the next chip would overflow the panel.
	private void renderConditionsRow(GuiGraphics g, JsonObject sheet, int left, int y) {
		Component title = Component.translatable("gui.dndsheets.sheet.conditions");
		g.drawString(this.font, title, left, y + 2, GuiStyle.ACCENT_COLOR, false);
		int x = left + this.font.width(title) + 8;
		int right = left + contentW();
		List<String> labels = new ArrayList<>();
		if (sheet.has("conditions")) {
			for (var element : sheet.getAsJsonArray("conditions")) {
				String entry = element.getAsString();
				int at = entry.indexOf('@');
				String label = at < 0 ? entry : entry.substring(0, at);
				if (!label.isEmpty() && !labels.contains(label)) labels.add(label);
			}
		}
		if (labels.isEmpty()) {
			g.drawString(this.font, Component.translatable("gui.dndsheets.sheet.none"), x, y + 2, GuiStyle.MUTED_COLOR, false);
			return;
		}
		int fill = (BAD & 0xFFFFFF) | 0x33000000;
		int edge = (BAD & 0xFFFFFF) | 0x8C000000;
		for (int i = 0; i < labels.size(); i++) {
			String label = labels.get(i);
			ResourceLocation glyph = Icons.condition(label);
			String name = OptionLabels.of(label);
			int chipW = (glyph != null ? 15 : 4) + this.font.width(name) + 4;
			String more = "+" + (labels.size() - i);
			if (x + chipW > right - (i < labels.size() - 1 ? this.font.width(more) + 4 : 0)) {
				g.drawString(this.font, more, x, y + 2, BAD, false);
				break;
			}
			g.fill(x, y - 1, x + chipW, y + 12, fill);
			g.renderOutline(x, y - 1, chipW, 13, edge);
			int textX = x + 4;
			if (glyph != null) {
				Icons.draw(g, glyph, x + 2, y, 11);
				textX = x + 15;
			}
			g.drawString(this.font, name, textX, y + 2, BAD, false);
			x += chipW + 4;
		}
	}

	private void renderCombat(GuiGraphics g, JsonObject sheet) {
		int left = contentLeft();
		int y = dialogTop() + CONTENT_Y;

		renderConditionsRow(g, sheet, left, y);
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
		if (!klass.isBlank()) parts.add(klass + " " + level());
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
		//Active tab: a brass underline besides the colour, so it does not rely on colour alone.
		int tabCount = Tab.values().length;
		int tabW = (contentW() - 4 * (tabCount - 1)) / tabCount;
		int tabX = dialogLeft() + PAD + tab.ordinal() * (tabW + 4);
		g.fill(tabX, dialogTop() + TAB_Y + 16, tabX + tabW, dialogTop() + TAB_Y + 18, GuiStyle.ACCENT_COLOR);

		super.render(g, mouseX, mouseY, partialTicks);

		//The typed fields are borderless; an underline says they can be edited (brass while focused or hovered).
		underline(g, nameBox, Math.min(190, dialogW / 2 - PAD));
		underline(g, speedBox, medallionW() - 12);

		if (tab == Tab.OVERVIEW) {
			for (int[] ring : saveRings) g.renderOutline(ring[0], ring[1], ring[2], ring[2], GuiStyle.ACCENT_COLOR);
			for (Hint hint : hints) {
				if (mouseX >= hint.x() && mouseX < hint.x() + hint.w() && mouseY >= hint.y() && mouseY < hint.y() + hint.h()) {
					g.renderTooltip(this.font, hint.text(), mouseX, mouseY);
				}
			}
		}
	}

	private static void underline(GuiGraphics g, EditBox box, int width) {
		if (box == null) return;
		g.fill(box.getX(), box.getY() + 10, box.getX() + width, box.getY() + 11, box.isFocused() || box.isHovered() ? GuiStyle.ACCENT_COLOR : PANEL_EDGE);
	}
}
