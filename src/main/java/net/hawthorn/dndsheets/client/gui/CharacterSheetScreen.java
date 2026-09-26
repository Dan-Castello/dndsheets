package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.client.gui.components.TomeButton;
import net.hawthorn.dndsheets.client.gui.components.TomeField;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.RollIndex;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.client.gui.components.AdjustableImageButton;
import net.hawthorn.dndsheets.client.gui.components.RollScrollWidget;
import net.hawthorn.dndsheets.init.DndsheetsModKeyMappings;
import net.hawthorn.dndsheets.network.AdvancedRollEditorOpenMessage;
import net.hawthorn.dndsheets.network.RollEditorOpenMessage;
import net.hawthorn.dndsheets.client.procedures.CharacterSheetSaveProcedure;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.GuiGraphics;

import net.hawthorn.dndsheets.world.inventory.CharacterSheetMenu;
import net.hawthorn.dndsheets.network.SheetRollButtonMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;

import net.hawthorn.dndsheets.client.procedures.CharacterSheetLoadProcedure;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CharacterSheetScreen extends AbstractContainerScreen<CharacterSheetMenu> {
	private final static HashMap<String, Object> guistate = CharacterSheetMenu.guistate;
	//The player looking at the sheet: HP/max HP/temp HP/level/hunger are read live from them (see
	//containerTick). It's the only one of the five fields MCreator copied from the menu that anything
	//actually read.
	private final Player entity;
	//private: verified that no other file in the mod reads/writes these two fields (only used within
	//this class) — there was no reason for them to be public static and left mutable from any external
	//mod on the classpath.
	private static PanelStatus panelActive = PanelStatus.MAIN;
	private static boolean editMode = false;

	EditBox hitPoints;      // Synced live from entity.getHealth() - see containerTick()
	EditBox hitPointsMax;   // Synced live from entity.getMaxHealth()
	EditBox hitPointsTemp;  // Synced live from entity.getAbsorptionAmount() (golden hearts = D&D temp HP)
	EditBox armorClass;
	EditBox speed;
	EditBox characterName;
	EditBox characterRace;
	EditBox characterClass;
	EditBox background;
	EditBox proficiency;    // Auto-calculated from the real level (5e proficiency rule)
	EditBox level;          // Synced live from entity.experienceLevel (Minecraft's real XP)
	EditBox hunger;         // Synced live from entity.getFoodData().getFoodLevel()
	Button grimoireButton;  // Opens the Grimoire (see GrimoireScreen), without touching the sheet
	Button menuButton;      // Opens the player Menu (see PlayerPanelScreen): characters, presets, journal, guide...

	EditBox hitDice;
	EditBox hitDiceTypes;

	EditBox strength;
	EditBox dexterity;
	EditBox constitution;
	EditBox intelligence;
	EditBox wisdom;
	EditBox charisma;

	List<ImageButton> checkButtons = new ArrayList<>();
	List<ImageButton> saveButtons = new ArrayList<>();
	List<ImageButton> skillButtons = new ArrayList<>();
	List<ImageButton> checkEditButtons = new ArrayList<>();
	List<ImageButton> saveEditButtons = new ArrayList<>();
	List<ImageButton> skillEditButtons = new ArrayList<>();

	ImageButton initiativeButton;
	ImageButton initiativeEditButton;

	AdjustableImageButton mainTab;
	AdjustableImageButton skillsTab;
	AdjustableImageButton attacksTab;

	RollScrollWidget attackRolls;
	ImageButton addButton;

	/*
		SIDE PANEL OFFSETS
	 */
	//All six ability scores are grouped together.
	private final int ABILITY_OFFSET_X = 57;
	private final int ABILITY_OFFSET_Y = 55;
	private final int ABILITY_SIZE_X = 20;
	private final int ABILITY_SIZE_Y = 18;
	private final int ABILITY_SEPARATION = 22;

	//Side panel's right edge: the ability icons end at 98 and the background's rule falls at 114.
	private static final int SIDE_PANEL_RIGHT = 104;
	//Edit mode toggle, right below the last ability. Used to be at x = leftPos - 6, i.e. OUTSIDE the
	//panel, over the parchment's margin: it looked like a loose icon with no relation to anything.
	private static final int EDIT_TOGGLE_Y = 192;

	private final int NAME_OFFSET_X = 15;
	private final int NAME_OFFSET_Y = 20;

	/*
		MAIN PANEL GRID

		This used to be twenty loose numbers, each adjusted by hand against the texture: rows fell at
		y = 20, 55, 90, 125, 165, 205 (rhythm 35, 35, 35, 40, 40) and columns at x = 125, 220, 235, 304,
		unrelated to each other. Moving one field meant eyeballing all its neighbors into place.

		Now everything comes from the grid below: four rows of fields spread across three sections with a
		header. Each row is LABEL (8px tall) + FIELD (18), and each section opens with its title and a
		brass rule. What's gained isn't just order: twelve loose labels on a blank parchment have no
		hierarchy, and grouped, they're found at a glance.

		To adjust a row's height, change ROW_STEP, not six constants.
	 */
	//350x240, not 350x200: the height went up once Level/Hunger and the page buttons stopped fitting. The
	//grid below has to fit WITHIN this, and the static block afterward checks it.
	private static final int SHEET_WIDTH = 350;
	private static final int SHEET_HEIGHT = 240;

	private static final int PANEL_X = 122;      //First usable column: the background's rule falls at x=114.
	private static final int PANEL_RIGHT = 340;  //Last usable pixel within the 350 width.
	private static final int FIELD_H = 18;
	private static final int LABEL_GAP = 10;     //Gap for a label above its field.
	private static final int ROW_STEP = 36;      //From one field to the next within a section.
	private static final int SECTION_STEP = 20;  //From a section's last field to the next header.
	private static final int HEADING_STEP = 22;  //From a section's header to its first row's label.

	//--- Section 1: identity ---
	private static final int SEC1_Y = 8;
	private static final int ROW1_Y = SEC1_Y + HEADING_STEP;
	//--- Section 2: combat ---
	private static final int SEC2_Y = ROW1_Y + FIELD_H + SECTION_STEP;
	private static final int ROW2_Y = SEC2_Y + HEADING_STEP;
	private static final int ROW3_Y = ROW2_Y + ROW_STEP;
	//--- Section 3: resources ---
	private static final int SEC3_Y = ROW3_Y + FIELD_H + SECTION_STEP;
	private static final int ROW4_Y = SEC3_Y + HEADING_STEP;

	//Row 1 — Race | Class | Background. Three equal slots filling the panel's width.
	private static final int IDENTITY_W = 70;
	private static final int IDENTITY_STEP = 74;
	private final int RACE_OFFSET_X = PANEL_X;
	private final int RACE_OFFSET_Y = ROW1_Y;
	private final int CLASS_OFFSET_X = PANEL_X + IDENTITY_STEP;
	private final int CLASS_OFFSET_Y = ROW1_Y;
	private final int BACKG_OFFSET_X = PANEL_X + IDENTITY_STEP * 2;
	private final int BACKG_OFFSET_Y = ROW1_Y;

	//Row 2 — AC | HP | Max HP | Temp HP. Speed is LEFT OUT of this group: with five slots, the fifth
	//label's ("Speed", 54px) start at x=305 and end at 359, off the 350-wide panel. And besides, it isn't
	//a combat number of the same family; it fits better next to Initiative.
	private final int ACHP_OFFSET_X = PANEL_X;
	private final int ACHP_OFFSET_Y = ROW2_Y;
	private final int ACHP_SEPARATION = 54;

	//Row 3 — Speed | Proficiency | Initiative.
	private final int SPEED_OFFSET_X = PANEL_X;
	private final int SPEED_OFFSET_Y = ROW3_Y;
	private final int PROF_OFFSET_X = PANEL_X + 72;
	private final int PROF_OFFSET_Y = ROW3_Y;
	private final int INITIATIVE_OFFSET_X = PANEL_X + 152;
	private final int INITIATIVE_OFFSET_Y = ROW3_Y;

	//Row 4 — Level | Hunger | Hit Dice (die + types).
	private final int LEVEL_OFFSET_X = PANEL_X;
	private final int LEVEL_OFFSET_Y = ROW4_Y;
	private final int HUNGER_OFFSET_X = PANEL_X + 46;
	private final int HUNGER_OFFSET_Y = ROW4_Y;
	private final int HITDICE_OFFSET_X = PANEL_X + 98;
	private final int HITDICE_OFFSET_Y = ROW4_Y;
	private static final int HITDICE_TYPES_W = PANEL_RIGHT - (PANEL_X + 98 + 26);

	//Page buttons (Grimoire, Presets, Guide): centered across the full width, not the right panel,
	//because they're actions for the whole sheet, not a single section. 80*3 + 10*2 = 260, (350-260)/2 = 45.
	//72, not 80: the bottom row goes from three buttons to four once "Characters" is added, and four at
	//80 don't fit within the sheet's 350. With 72 and a step of 86, the last one ends at 340, exactly PANEL_RIGHT.
	private static final int BOTTOM_BUTTON_WIDTH = 72;
	private static final int BOTTOM_BUTTON_STEP = 86;
	private static final int BOTTOM_BUTTON_HEIGHT = 16;
	private static final int BOTTOM_ROW_Y = ROW4_Y + FIELD_H + 12;
	private final int GRIMOIRE_OFFSET_X = 10;
	private final int GRIMOIRE_OFFSET_Y = BOTTOM_ROW_Y;
	private final int MENU_OFFSET_X = GRIMOIRE_OFFSET_X + BOTTOM_BUTTON_STEP;
	private final int MENU_OFFSET_Y = BOTTOM_ROW_Y;

	/*
		SKILLS TAB GRID

		The eighteen skills are grouped by ability, which is how they work in 5e and how they're printed
		on a real sheet. That grouping already existed in the code, but only as comments (//STR, //DEX,
		//INT...): on screen they were two columns of nine consecutive rows, with nothing saying which
		ability each one draws from.

		The header labels are NOT new keys: they're the same LABEL_ABILITY_* the side panel's ability
		checks already use, so translating one translates both places.
	 */
	private static final int SKILL_TOP = 10;
	private static final int SKILL_ROW = 20;          //From one skill to the next.
	private static final int SKILL_GROUP_STEP = 14;   //What a group header takes up.
	private static final int SKILL_LABEL_GAP = 18;    //From the roll button to its label.

	//Column 1 at x=116 (the background's rule falls at 114) and column 2 at 224. The second sits further
	//right than would look symmetric, deliberately: its labels are the long ones ("Animal Handling" runs
	//108px), and with both columns the same width that one ran off the panel — it reached x=363 on a
	//350-wide sheet. It's an overflow that only showed up in Spanish.
	private static final int SKILL_COL1_X = 114;
	private static final int SKILL_COL2_X = 230;
	//The parchment runs to x=364, so the headers' rule is cut off at 358 to leave a margin. The columns
	//are this tight because the Spanish labels nearly fill the width: between "Sleight of Hand" (84px) in
	//the first and "Animal Handling" (108) in the second, plus the roll buttons, they eat up 228 of the
	//244 available. That's why column 1 starts flush against the side panel's edge — this tab has no
	//vertical background rule (only character_sheet.png, the main one, carries it).
	private static final int SKILL_RIGHT = 358;

	//How many skills hang off each ability, in order. Column 1: Strength (1), Dexterity (3),
	//Intelligence (5). Column 2: Wisdom (5), Charisma (4).
	private static final int[] SKILL_COL1_GROUPS = {1, 3, 5};
	private static final int[] SKILL_COL2_GROUPS = {5, 4};

	/*
		ATTACKS TAB GRID

		The list used to align to its own numbers (x=125, width 210) instead of the panel, so it sat a
		few pixels out of alignment with the other two tabs. Now it uses the same columns. The height
		leaves room below for the add button.
	 */
	private static final int ATTACK_TOP = ROW1_Y;
	private static final int ATTACK_HEIGHT = 160;

	static {
		//The grid runs off the panel far too easily: it happened while writing it (the page buttons fell
		//at y=246 on a 240-tall panel) and had already happened before (y=228 on a 200-tall background).
		//Nothing breaks, nothing warns, and it only shows up by opening the sheet — so it's checked when
		//the class loads, against the real constants, which is the only thing that can't drift out of
		//sync with them.
		int bottom = BOTTOM_ROW_Y + BOTTOM_BUTTON_HEIGHT;
		if (bottom > SHEET_HEIGHT) {
			throw new IllegalStateException("The sheet grid reaches y=" + bottom
				+ " and the panel is " + SHEET_HEIGHT + " tall. Raise SHEET_HEIGHT or lower ROW_STEP/SECTION_STEP.");
		}
		int rightmost = PANEL_X + IDENTITY_STEP * 2 + IDENTITY_W;
		if (rightmost > PANEL_RIGHT) {
			throw new IllegalStateException("The identity row reaches x=" + rightmost
				+ " and the panel ends at " + PANEL_RIGHT + ".");
		}

		int attackBottom = ATTACK_TOP + ATTACK_HEIGHT + 8 + 16;  //+8 gap, +16 for the add button
		if (attackBottom > SHEET_HEIGHT) {
			throw new IllegalStateException("The attack list and its button reach y=" + attackBottom
				+ " and the panel is " + SHEET_HEIGHT + " tall.");
		}

		//Both skill columns split into NINE slots each. If someone changes the group sizes and they stop
		//adding up to nine, skillRowY blows up asking for a slot that doesn't exist — but only when that
		//tab is opened, and only on the specific missing row. Better that it fails loudly, and here.
		for (int[] groups : new int[][] {SKILL_COL1_GROUPS, SKILL_COL2_GROUPS}) {
			int slots = 0;
			for (int size : groups) slots += size;
			if (slots != 9) {
				throw new IllegalStateException("A skill column lays out " + slots
					+ " slots but it must be 9 (18 skills in two columns).");
			}
			int height = skillGroupY(groups, groups.length - 1)
				+ SKILL_GROUP_STEP + groups[groups.length - 1] * SKILL_ROW;
			if (height > SHEET_HEIGHT) {
				throw new IllegalStateException("A skill column reaches y=" + height
					+ " and the panel is " + SHEET_HEIGHT + " tall.");
			}
		}
	}

	//Text color for the fields that fill themselves in (HP, AC, level, hunger, proficiency): amber, to
	//tell them apart at a glance from the normal blank fields that CAN be typed into by hand.
	//Ink on parchment: very dark brown instead of pure black, which looks harsh on a warm background.
	private static final int INK_COLOR = 0x2A2118;
	/** Label for closed tabs: they sit over leather, so the same dulled parchment color as TomeButton. */
	private static final int TAB_TEXT_CLOSED = 0xCBBA97;
	/** Section headers: watered-down ink, so they title without competing with the field labels. */
	private static final int SECTION_COLOR = 0x6B5636;
	//Section bands: black and white at very low opacity, not colors of their own. This darkens and
	//lightens whatever parchment sits behind them without clashing with it if its tone ever changes.
	private static final int BAND_FILL = 0x12000000;
	private static final int BAND_LIGHT = 0x20FFFFFF;
	private static final int BAND_SHADOW = 0x22000000;
	//Burnt amber for what fills itself in. The lighter amber before (0xFFD37F) was designed for a dark
	//background; over parchment it didn't have enough contrast to read.
	private static final int AUTO_FIELD_COLOR = 0x8A5A12;

	public enum PanelStatus {
		MAIN,
		SKILLS,
		ATTACKS,
		NONE
	}

	public CharacterSheetScreen(CharacterSheetMenu container, Inventory inventory, Component text) {
		super(container, inventory, text);
		this.entity = container.entity;
		this.imageWidth = SHEET_WIDTH;
		this.imageHeight = SHEET_HEIGHT;
	}

	private static final ResourceLocation BG_MAIN = new ResourceLocation("dndsheets:textures/screens/character_sheet.png");
	private static final ResourceLocation BG_SKILLS = new ResourceLocation("dndsheets:textures/screens/character_sheet_2.png");
	private static final ResourceLocation BG_ATTACKS = new ResourceLocation("dndsheets:textures/screens/character_sheet_3.png");
	private static final ResourceLocation ICON_STR = new ResourceLocation("dndsheets:textures/screens/str.png");
	private static final ResourceLocation ICON_DEX = new ResourceLocation("dndsheets:textures/screens/dex.png");
	private static final ResourceLocation ICON_CON = new ResourceLocation("dndsheets:textures/screens/cons.png");
	private static final ResourceLocation ICON_INT = new ResourceLocation("dndsheets:textures/screens/int.png");
	private static final ResourceLocation ICON_WIS = new ResourceLocation("dndsheets:textures/screens/wis.png");
	private static final ResourceLocation ICON_CHA = new ResourceLocation("dndsheets:textures/screens/cha.png");

	//renderLabels runs every frame: these Components (static text, never changes) are cached once
	//instead of being rebuilt on every single one.
	private static final Component LABEL_NAME = Component.translatable("gui.dndsheets.character_sheet.label_name");
	//The icons next to the ability score fields (str.png, dex.png...) are pictograms with no text —
	//without this, a new player has no way of knowing which field is Strength and which is Dexterity
	//other than by order. Used as a tooltip (see initAbilityScoreBoxes) and as the roll buttons' text.
	private static final Component LABEL_ABILITY_STR = Component.translatable("gui.dndsheets.character_sheet.ability_str");
	private static final Component LABEL_ABILITY_DEX = Component.translatable("gui.dndsheets.character_sheet.ability_dex");
	private static final Component LABEL_ABILITY_CON = Component.translatable("gui.dndsheets.character_sheet.ability_con");
	private static final Component LABEL_ABILITY_INT = Component.translatable("gui.dndsheets.character_sheet.ability_int");
	private static final Component LABEL_ABILITY_WIS = Component.translatable("gui.dndsheets.character_sheet.ability_wis");
	private static final Component LABEL_ABILITY_CHA = Component.translatable("gui.dndsheets.character_sheet.ability_cha");
	private static final Component LABEL_ARMOR_CLASS_AC = Component.translatable("gui.dndsheets.character_sheet.label_armor_class_ac");
	private static final Component LABEL_HIT_POINTS = Component.translatable("gui.dndsheets.character_sheet.label_hit_points");
	private static final Component LABEL_HIT_POINTS_MAX = Component.translatable("gui.dndsheets.character_sheet.label_hit_points_max");
	private static final Component LABEL_HIT_POINTS_TEMP = Component.translatable("gui.dndsheets.character_sheet.label_hit_points_temp");
	private static final Component LABEL_SPEED = Component.translatable("gui.dndsheets.character_sheet.label_speed");
	private static final Component LABEL_PROFICIENCY_BONUS = Component.translatable("gui.dndsheets.character_sheet.label_proficiency_bonus");
	private static final Component LABEL_CLASS = Component.translatable("gui.dndsheets.character_sheet.label_class");
	private static final Component LABEL_RACE = Component.translatable("gui.dndsheets.character_sheet.label_race");
	private static final Component LABEL_BACKGROUND = Component.translatable("gui.dndsheets.character_sheet.label_background");
	private static final Component LABEL_HITDICE = Component.translatable("gui.dndsheets.character_sheet.label_hitdice");
	private static final Component LABEL_LEVEL = Component.translatable("gui.dndsheets.character_sheet.label_level");
	private static final Component LABEL_HUNGER = Component.translatable("gui.dndsheets.character_sheet.label_hunger");
	private static final Component LABEL_INITIATIVE = Component.translatable("gui.dndsheets.character_sheet.label_initiative");
	private static final Component LABEL_SKILL_ATHLETICS = Component.translatable("gui.dndsheets.character_sheet.label_skill_athletics");
	private static final Component LABEL_SKILL_ACROBATICS = Component.translatable("gui.dndsheets.character_sheet.label_skill_acrobatics");
	private static final Component LABEL_SKILL_SLEIGHTOFHAND = Component.translatable("gui.dndsheets.character_sheet.label_skill_sleightofhand");
	private static final Component LABEL_SKILL_STEALTH = Component.translatable("gui.dndsheets.character_sheet.label_skill_stealth");
	private static final Component LABEL_SKILL_ARCANA = Component.translatable("gui.dndsheets.character_sheet.label_skill_arcana");
	private static final Component LABEL_SKILL_HISTORY = Component.translatable("gui.dndsheets.character_sheet.label_skill_history");
	private static final Component LABEL_SKILL_INVESTIGATION = Component.translatable("gui.dndsheets.character_sheet.label_skill_investigation");
	private static final Component LABEL_SKILL_NATURE = Component.translatable("gui.dndsheets.character_sheet.label_skill_nature");
	private static final Component LABEL_SKILL_RELIGION = Component.translatable("gui.dndsheets.character_sheet.label_skill_religion");
	private static final Component LABEL_SKILL_ANIMALHANDLING = Component.translatable("gui.dndsheets.character_sheet.label_skill_animalhandling");
	private static final Component LABEL_SKILL_INSIGHT = Component.translatable("gui.dndsheets.character_sheet.label_skill_insight");
	private static final Component LABEL_SKILL_MEDICINE = Component.translatable("gui.dndsheets.character_sheet.label_skill_medicine");
	private static final Component LABEL_SKILL_PERCEPTION = Component.translatable("gui.dndsheets.character_sheet.label_skill_perception");
	private static final Component LABEL_SKILL_SURVIVAL = Component.translatable("gui.dndsheets.character_sheet.label_skill_survival");
	private static final Component LABEL_SKILL_DECEPTION = Component.translatable("gui.dndsheets.character_sheet.label_skill_deception");
	private static final Component LABEL_SKILL_INTIMIDATION = Component.translatable("gui.dndsheets.character_sheet.label_skill_intimidation");
	private static final Component LABEL_SKILL_PERFORMANCE = Component.translatable("gui.dndsheets.character_sheet.label_skill_performance");
	/** Header for the Attacks tab: the same label its tab carries, no new key. */
	private static final Component LABEL_ATTACKS_TAB = Component.translatable("gui.dndsheets.character_sheet.attacks_tab");
	private static final Component LABEL_ATTACKS_EMPTY = Component.translatable("gui.dndsheets.character_sheet.attacks_empty");
	private static final Component LABEL_SECTION_ABILITIES = Component.translatable("gui.dndsheets.character_sheet.section_abilities");
	private static final Component LABEL_SECTION_IDENTITY = Component.translatable("gui.dndsheets.character_sheet.section_identity");
	private static final Component LABEL_SECTION_COMBAT = Component.translatable("gui.dndsheets.character_sheet.section_combat");
	private static final Component LABEL_SECTION_RESOURCES = Component.translatable("gui.dndsheets.character_sheet.section_resources");
	private static final Component LABEL_SKILL_PERSUASION = Component.translatable("gui.dndsheets.character_sheet.label_skill_persuasion");

	//The 18 skills in the same order they're placed, so they can be iterated over (see
	//warnIfLabelsOverflow). Before, they only existed loose, named one by one in renderLabels.
	private static final Component[] SKILL_LABELS_COL1 = {
		LABEL_SKILL_ATHLETICS, LABEL_SKILL_ACROBATICS, LABEL_SKILL_SLEIGHTOFHAND, LABEL_SKILL_STEALTH,
		LABEL_SKILL_ARCANA, LABEL_SKILL_HISTORY, LABEL_SKILL_INVESTIGATION, LABEL_SKILL_NATURE, LABEL_SKILL_RELIGION,
	};
	private static final Component[] SKILL_LABELS_COL2 = {
		LABEL_SKILL_ANIMALHANDLING, LABEL_SKILL_INSIGHT, LABEL_SKILL_MEDICINE, LABEL_SKILL_PERCEPTION,
		LABEL_SKILL_SURVIVAL, LABEL_SKILL_DECEPTION, LABEL_SKILL_INTIMIDATION, LABEL_SKILL_PERFORMANCE,
		LABEL_SKILL_PERSUASION,
	};

	//Every field on the sheet, to frame them all in one pass. Sourced from guistate, which already has
	//all of them: registering them by hand in the thirteen places that create them is exactly how one
	//gets forgotten.
	private final java.util.List<EditBox> sheetFields = new ArrayList<>();
	//The main panel's widgets, to hide them when switching tabs. See initMainPanel().
	private final java.util.List<AbstractWidget> mainPanelWidgets = new ArrayList<>();

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);

		characterName.render(guiGraphics, mouseX, mouseY, partialTicks);
		strength.render(guiGraphics, mouseX, mouseY, partialTicks);
		dexterity.render(guiGraphics, mouseX, mouseY, partialTicks);
		constitution.render(guiGraphics, mouseX, mouseY, partialTicks);
		intelligence.render(guiGraphics, mouseX, mouseY, partialTicks);
		wisdom.render(guiGraphics, mouseX, mouseY, partialTicks);
		charisma.render(guiGraphics, mouseX, mouseY, partialTicks);


		switch (panelActive) {
			case MAIN:
				hitPoints.render(guiGraphics, mouseX, mouseY, partialTicks);
				hitPointsTemp.render(guiGraphics, mouseX, mouseY, partialTicks);
				hitPointsMax.render(guiGraphics, mouseX, mouseY, partialTicks);
				armorClass.render(guiGraphics, mouseX, mouseY, partialTicks);
				characterRace.render(guiGraphics, mouseX, mouseY, partialTicks);
				characterClass.render(guiGraphics, mouseX, mouseY, partialTicks);
				background.render(guiGraphics, mouseX, mouseY, partialTicks);
				speed.render(guiGraphics, mouseX, mouseY, partialTicks);
				proficiency.render(guiGraphics, mouseX, mouseY, partialTicks);
				hitDice.render(guiGraphics, mouseX, mouseY, partialTicks);
				hitDiceTypes.render(guiGraphics, mouseX, mouseY, partialTicks);
				level.render(guiGraphics, mouseX, mouseY, partialTicks);
				hunger.render(guiGraphics, mouseX, mouseY, partialTicks);
				break;
			case SKILLS:
				break;
			case ATTACKS:
				//The header and the empty-list notice are drawn in renderLabels, not here: this method runs
				//in SCREEN coordinates and the grid's constants are in SHEET coordinates, so without the
				//leftPos/topPos translation the text would land in the top-left corner, over the side panel
				//of ability scores.
				break;
		}

		//After the fields have been drawn: the frame covers the gray ring each one paints for itself.
		for (EditBox field : sheetFields) {
			if (field.visible) frameField(guiGraphics, field);
		}

		this.renderTooltip(guiGraphics, mouseX, mouseY);
	}

	@Override
	protected void renderBg(GuiGraphics guiGraphics, float partialTicks, int gx, int gy) {
		RenderSystem.setShaderColor(1, 1, 1, 1);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();

		switch (panelActive) {
			case MAIN:
				guiGraphics.blit(BG_MAIN, this.leftPos - 24, this.topPos - 24, 0, 0, 398, 288, 398, 288);
				break;
			case SKILLS:
				guiGraphics.blit(BG_SKILLS, this.leftPos - 24, this.topPos - 24, 0, 0, 398, 288, 398, 288);
				break;
			case ATTACKS:
				guiGraphics.blit(BG_ATTACKS, this.leftPos - 24, this.topPos - 24, 0, 0, 398, 288, 398, 288);
				break;
		}

		//Section bands, only on the main tab. Goes here and not in renderLabels because renderLabels runs
		//AFTER the widgets: drawn there, they'd cover the very fields they frame.
		if (panelActive == PanelStatus.MAIN) {
			sectionBand(guiGraphics, SEC1_Y, ROW1_Y);
			sectionBand(guiGraphics, SEC2_Y, ROW3_Y);
			sectionBand(guiGraphics, SEC3_Y, ROW4_Y);
		}

		guiGraphics.blit(ICON_STR, this.leftPos + ABILITY_OFFSET_X + 25, this.topPos + ABILITY_OFFSET_Y, 0, 0, 16, 16, 16, 16);
		guiGraphics.blit(ICON_DEX, this.leftPos + ABILITY_OFFSET_X + 25, this.topPos + ABILITY_OFFSET_Y + ABILITY_SEPARATION, 0, 0, 16, 16, 16, 16);
		guiGraphics.blit(ICON_CON, this.leftPos + ABILITY_OFFSET_X + 25, this.topPos + ABILITY_OFFSET_Y + ABILITY_SEPARATION*2, 0, 0, 16, 16, 16, 16);
		guiGraphics.blit(ICON_INT, this.leftPos + ABILITY_OFFSET_X + 25, this.topPos + ABILITY_OFFSET_Y + ABILITY_SEPARATION*3, 0, 0, 16, 16, 16, 16);
		guiGraphics.blit(ICON_WIS, this.leftPos + ABILITY_OFFSET_X + 25, this.topPos + ABILITY_OFFSET_Y + ABILITY_SEPARATION*4, 0, 0, 16, 16, 16, 16);
		guiGraphics.blit(ICON_CHA, this.leftPos + ABILITY_OFFSET_X + 25, this.topPos + ABILITY_OFFSET_Y + ABILITY_SEPARATION*5, 0, 0, 16, 16, 16, 16);

		RenderSystem.disableBlend();
	}

	//The button/field under the cursor keeps the scroll by default (Screen hands the event to whatever's
	//right under the mouse), and a row in the Attacks list does nothing with it — which is why it used to
	//only be possible to scroll by hovering over gaps with no button (see PresetScreen.mouseScrolled,
	//same fix). Only applies on the Attacks tab and only if the cursor is over the list, so as not to
	//steal scroll from anything on the other tabs.
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		if (panelActive == PanelStatus.ATTACKS && attackRolls.isMouseOver(mouseX, mouseY)) {
			return attackRolls.mouseScrolled(mouseX, mouseY, delta);
		}
		return super.mouseScrolled(mouseX, mouseY, delta);
	}

	@Override
	public boolean keyPressed(int key, int b, int c) {
		if (key == 256) {
			this.minecraft.player.closeContainer();
			CharacterSheetSaveProcedure.execute(guistate);
			return true;
		}
		// Any focused text field claims the entire keypress, regardless of whether
		// EditBox.keyPressed() recognizes it or not. Before, a "non-special" key (e.g.
		// a regular letter, which EditBox only processes in charTyped) returned false here
		// and the event fell through to AbstractContainerScreen.keyPressed(), which closes
		// the sheet if the key matches the inventory keybind (E by default) - losing what was typed.
		EditBox[] textFields = {
			hitPoints, hitPointsTemp, hitPointsMax, armorClass,
			characterName, characterRace, characterClass, background,
			speed, proficiency, hitDice, hitDiceTypes, level, hunger,
			strength, dexterity, constitution, intelligence, wisdom, charisma
		};
		for (EditBox box : textFields) {
			if (box.isFocused()) {
				box.keyPressed(key, b, c);
				return true;
			}
		}

		if (attackRolls.forwardKeyToFocusedNameBox(key, b, c)) return true;

		//Closes with the SAME key that opens it (H by default), like vanilla's inventory does with E.
		//Deliberately placed down here and not above, and that's the entire reason it was commented out
		//since 2025-09-19: the default key is a LETTER, so placed before focus dispatch it would eat the
		//"h" in "Sorcerer" while typing the name and close the sheet mid-word. After dispatch, a focused
		//field has already claimed the key and only the "loose" H arrives here.
		//The server already did its half of the toggle (see CharacterSheetOpenMessage.pressAction); what
		//was missing was this one, because with a screen open the key doesn't even reach the KeyMapping.
		if (DndsheetsModKeyMappings.CHARACTER.isActiveAndMatches(InputConstants.getKey(key, b))) {
			this.minecraft.player.closeContainer();
			CharacterSheetSaveProcedure.execute(guistate);
			return true;
		}

		return super.keyPressed(key, b, c);
	}

	@Override
	public void containerTick() {
		super.containerTick();
		syncFromEntity();

		hitPoints.tick();
		hitPointsTemp.tick();
		hitPointsMax.tick();
		armorClass.tick();
		characterRace.tick();
		characterClass.tick();
		background.tick();
		speed.tick();
		proficiency.tick();
		hitDice.tick();
		hitDiceTypes.tick();
		level.tick();
		hunger.tick();

		characterName.tick();
		strength.tick();
		dexterity.tick();
		constitution.tick();
		intelligence.tick();
		wisdom.tick();
		charisma.tick();

		attackRolls.tickNameBoxes();
	}

	/**
	 * <p>Syncs the fields derived from the player's real state (health, hunger, level)
	 * instead of relying on what the player types in by hand. This turns these
	 * fields into a read-only mirror of the Minecraft player, instead of an
	 * independent sheet that has to be updated manually.</p>
	 */
	private void syncFromEntity() {
		if (entity == null) return;

		int currentHp = (int) Math.ceil(entity.getHealth());
		int maxHp = (int) Math.ceil(entity.getMaxHealth());
		int tempHp = (int) Math.ceil(entity.getAbsorptionAmount()); // Golden hearts = temp HP

		if (!hitPoints.getValue().equals(String.valueOf(currentHp)))
			hitPoints.setValue(String.valueOf(currentHp));
		if (!hitPointsMax.getValue().equals(String.valueOf(maxHp)))
			hitPointsMax.setValue(String.valueOf(maxHp));
		if (!hitPointsTemp.getValue().equals(String.valueOf(tempHp)))
			hitPointsTemp.setValue(String.valueOf(tempHp));

		if (entity.getFoodData() != null) {
			int foodLevel = entity.getFoodData().getFoodLevel();
			if (!hunger.getValue().equals(String.valueOf(foodLevel)))
				hunger.setValue(String.valueOf(foodLevel));
		}

		// Real character level: follows Minecraft's XP until the DM sets it by hand with
		// /dndsheet setlevel (saves "characterLevel" on the sheet) — see SheetLoader.characterLevelOf.
		int xpLevel = SheetLoader.characterLevelOf(SheetLoader.getClientSheet(), entity);
		if (!level.getValue().equals(String.valueOf(xpLevel)))
			level.setValue(String.valueOf(xpLevel));

		// D&D 5e proficiency bonus rule, calculated from the real level
		int calculatedProficiency = 2 + ((xpLevel - 1) / 4);
		if (!proficiency.getValue().equals(String.valueOf(calculatedProficiency)))
			proficiency.setValue(String.valueOf(calculatedProficiency));

		// AC = 10 + Dex mod + real equipped armor (entity.getArmorValue()),
		// so the armor the player actually wears does affect the sheet.
		int dexMod = abilityModifier(dexterity.getValue());
		int calculatedAc = 10 + dexMod + (int) entity.getArmorValue();
		if (!armorClass.getValue().equals(String.valueOf(calculatedAc)))
			armorClass.setValue(String.valueOf(calculatedAc));
	}

	private static int abilityModifier(String score) {
		try {
			return Math.floorDiv(Integer.parseInt(score) - 10, 2);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * <p>Section header: title in dulled ink and a brass rule out to the panel's edge. Drawn from
	 * {@code renderLabels}, which already runs with {@code leftPos}/{@code topPos}'s translation applied,
	 * so the coordinates are the same as the grid's.</p>
	 */
	/**
	 * <p>Brass frame over the gray ring {@link EditBox} paints for itself.</p>
	 *
	 * <p>Vanilla draws every field as a one-pixel gray ring (white when focused) around a black fill, and
	 * both colors are hardcoded in {@code EditBox.renderWidget}. Over the parchment that read as widgets
	 * borrowed from another interface: the screen looked like two designs at once.</p>
	 *
	 * <p>Turning off the border with {@code setBordered(false)} doesn't work: it also removes the black
	 * fill AND changes where the text is drawn (it goes from centered with a margin to hugging the
	 * top-left corner), and with no dark fill behind it the text would have to be ink on parchment — which
	 * with Minecraft's fixed shadow reads as doubled, the same problem already fixed on the labels.</p>
	 *
	 * <p>So the ring isn't removed: it's repainted over. It occupies exactly one pixel outside the box, so
	 * covering it touches neither the text nor the interior. The focus cue is kept (lit brass instead of
	 * white) and a dark line is added outside it, which is what makes the field read as sunk into the
	 * sheet instead of stuck on top of it.</p>
	 */
	/**
	 * <p>Warns via the log if any label runs past its slot. Four times in the same redesign a label
	 * wider than its column slipped through — {@code Speed}, {@code Proficiency Bonus},
	 * {@code Animal Handling}, and the "no attacks" message — and all four <b>only in Spanish</b>: slots
	 * get sized while looking at the screen in one language, and the overflow shows up in another.</p>
	 *
	 * <p>Lives here and not in the {@code static} block because it needs {@code this.font}, which doesn't
	 * exist until there's a screen. And it warns instead of crashing: a label clipped by a long
	 * translation is a cosmetic defect, not a reason to leave whoever's playing without a sheet. The one
	 * who needs to see it is whoever's developing, and the log is enough for that.</p>
	 */
	private void warnIfLabelsOverflow() {
		//{label, x where it starts, x it CANNOT reach}. The limits come from the grid, so they follow the
		//constants on their own.
		Object[][] slots = {
			{LABEL_RACE, RACE_OFFSET_X, CLASS_OFFSET_X},
			{LABEL_CLASS, CLASS_OFFSET_X, BACKG_OFFSET_X},
			{LABEL_BACKGROUND, BACKG_OFFSET_X, PANEL_RIGHT},
			{LABEL_ARMOR_CLASS_AC, ACHP_OFFSET_X, ACHP_OFFSET_X + ACHP_SEPARATION},
			{LABEL_HIT_POINTS, ACHP_OFFSET_X + ACHP_SEPARATION, ACHP_OFFSET_X + ACHP_SEPARATION * 2},
			{LABEL_HIT_POINTS_MAX, ACHP_OFFSET_X + ACHP_SEPARATION * 2, ACHP_OFFSET_X + ACHP_SEPARATION * 3},
			{LABEL_HIT_POINTS_TEMP, ACHP_OFFSET_X + ACHP_SEPARATION * 3, PANEL_RIGHT},
			{LABEL_SPEED, SPEED_OFFSET_X, PROF_OFFSET_X},
			{LABEL_PROFICIENCY_BONUS, PROF_OFFSET_X, INITIATIVE_OFFSET_X},
			{LABEL_INITIATIVE, INITIATIVE_OFFSET_X, PANEL_RIGHT},
			{LABEL_LEVEL, LEVEL_OFFSET_X, HUNGER_OFFSET_X},
			{LABEL_HUNGER, HUNGER_OFFSET_X, HITDICE_OFFSET_X},
			{LABEL_HITDICE, HITDICE_OFFSET_X, PANEL_RIGHT},
			{LABEL_ATTACKS_EMPTY, PANEL_X, PANEL_RIGHT},
			//The side panel: its real limit is the background's vertical rule, not the block's edge.
			{LABEL_NAME, NAME_OFFSET_X, PANEL_X - 8},
			{LABEL_SECTION_ABILITIES, NAME_OFFSET_X, PANEL_X - 8},
		};
		for (Object[] slot : slots) {
			checkLabelFits((Component) slot[0], (Integer) slot[1], (Integer) slot[2]);
		}
		//The skills: the label goes after the roll button, and column 1 can't invade column 2.
		for (int i = 0; i < 9; i++) {
			checkLabelFits(SKILL_LABELS_COL1[i], SKILL_COL1_X + SKILL_LABEL_GAP, SKILL_COL2_X - 4);
			checkLabelFits(SKILL_LABELS_COL2[i], SKILL_COL2_X + SKILL_LABEL_GAP, SKILL_RIGHT);
		}
	}

	private void checkLabelFits(Component label, int left, int limit) {
		int end = left + this.font.width(label);
		if (end > limit) {
			DndsheetsMod.LOGGER.error("Character sheet: the label \"{}\" reaches x={} and its slot ends at {}"
				+ " - it will be clipped or overlap its neighbour.", label.getString(), end, limit);
		}
	}

	private void frameField(GuiGraphics guiGraphics, EditBox box) {
		TomeField.frameWidget(guiGraphics, box.getX(), box.getY(), box.getWidth(), box.getHeight(), box.isFocused());
	}

	/**
	 * <p>A section's band: a rectangle just barely darker than the parchment, with light on top and shadow
	 * below. Gives the group body — a lone title and rule leave the section with no surface, and the
	 * whole sheet reads flat.</p>
	 *
	 * <p>Computed from the grid's constants, not by hand against the texture: that's why it can't drift
	 * out of alignment with the rows it wraps when {@code ROW_STEP} changes.</p>
	 *
	 * @param headingY  the section's {@code SEC*_Y}
	 * @param lastRowY  its LAST field row's {@code ROW*_Y}
	 */
	private void sectionBand(GuiGraphics guiGraphics, int headingY, int lastRowY) {
		int left = this.leftPos + PANEL_X - 8;
		int right = this.leftPos + PANEL_RIGHT + 4;
		int top = this.topPos + headingY - 5;
		int bottom = this.topPos + lastRowY + FIELD_H + 7;

		guiGraphics.fill(left, top, right, bottom, BAND_FILL);
		guiGraphics.fill(left, top, right, top + 1, BAND_LIGHT);
		guiGraphics.fill(left, bottom - 1, right, bottom, BAND_SHADOW);
	}

	private void section(GuiGraphics guiGraphics, Component title, int y) {
		section(guiGraphics, title, y, PANEL_X, PANEL_RIGHT);
	}

	private void section(GuiGraphics guiGraphics, Component title, int y, int left, int right) {
		guiGraphics.drawString(this.font, title, left, y, SECTION_COLOR, false);
		//The rule starts where the title ends, not below it: this way the header takes up a single line
		//and the sections fit within the panel's height, which is exactly what didn't happen with the rule
		//on its own row. And only if there's room left: GuiGraphics.fill with the left edge past the right
		//one doesn't stop drawing, it draws the rectangle backwards. On the side panel the title nearly
		//fills the width, and in Spanish it fills it completely.
		int ruleLeft = left + this.font.width(title) + 6;
		if (right - ruleLeft >= 6) GuiStyle.rule(guiGraphics, ruleLeft, right, y + 3);
	}

	/**
	 * <p>Coordinate for slot {@code index} (0..8) of a skill column, counting what the group headers
	 * above it take up.</p>
	 *
	 * <p>Computed instead of hardcoded because the groups aren't all the same size (Strength has one
	 * skill and Intelligence has five): with hand-written positions, adding or moving one forces
	 * repositioning every one below it. Returns the LABEL's y; the roll button centers over it.</p>
	 */
	private static int skillRowY(int[] groups, int index) {
		int y = SKILL_TOP;
		int seen = 0;
		for (int size : groups) {
			y += SKILL_GROUP_STEP;
			if (index < seen + size) return y + (index - seen) * SKILL_ROW;
			y += size * SKILL_ROW;
			seen += size;
		}
		throw new IllegalArgumentException("Slot " + index + " is outside a column of " + seen + " skills");
	}

	/** Coordinate for group {@code group}'s header within a column. */
	private static int skillGroupY(int[] groups, int group) {
		int y = SKILL_TOP;
		for (int i = 0; i < group; i++) y += SKILL_GROUP_STEP + groups[i] * SKILL_ROW;
		return y;
	}

	@Override
	protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		//A SINGLE ink. There used to be two (a white lightColor and a near-black darkColor) handed out with no
		//apparent logic between neighboring labels, and the background was WHITE: most labels were white
		//on white, invisible. What covered for them was the text baked into the PNG, which was also in
		//English and duplicated these. With the new parchment, one dark ink makes them all legible.
		final int lightColor = INK_COLOR;
		final int darkColor = INK_COLOR;
		guiGraphics.drawString(this.font, LABEL_NAME, NAME_OFFSET_X, NAME_OFFSET_Y - 10, lightColor, false);
		//The side panel was the only part of the sheet without a header, and it shows on all three tabs. That's
		//why it goes outside the switch.
		section(guiGraphics, LABEL_SECTION_ABILITIES, ABILITY_OFFSET_Y - 13, NAME_OFFSET_X, SIDE_PANEL_RIGHT);

		switch (panelActive) {
			case ATTACKS:
				section(guiGraphics, LABEL_ATTACKS_TAB, SEC1_Y);
				//With no attacks, the list is a dark hole that doesn't say what to do with it. The add button
				//is right below but it's a 16 px icon with no label.
				if (attackRolls.getListSize() == 0) {
					guiGraphics.drawString(this.font, LABEL_ATTACKS_EMPTY,
						PANEL_X + (PANEL_RIGHT - PANEL_X - this.font.width(LABEL_ATTACKS_EMPTY)) / 2,
						ATTACK_TOP + ATTACK_HEIGHT / 2 - 4, GuiStyle.MUTED_COLOR, false);
				}
				break;
			case MAIN:
				//Section headers: they turn twelve loose labels on a blank parchment into three groups that can
				//be found at a glance. The rule is the same device GuiStyle uses on the list screens, so the
				//sheet and the rest of the mod read the same.
				section(guiGraphics, LABEL_SECTION_IDENTITY, SEC1_Y);
				section(guiGraphics, LABEL_SECTION_COMBAT, SEC2_Y);
				section(guiGraphics, LABEL_SECTION_RESOURCES, SEC3_Y);

				//Row 1 — identity.
				//Amber = fills itself in (see AUTO_FIELD_COLOR); normal color = typed in by hand.
				guiGraphics.drawString(this.font, LABEL_RACE, RACE_OFFSET_X, RACE_OFFSET_Y - LABEL_GAP, lightColor, false);
				guiGraphics.drawString(this.font, LABEL_CLASS, CLASS_OFFSET_X, CLASS_OFFSET_Y - LABEL_GAP, lightColor, false);
				guiGraphics.drawString(this.font, LABEL_BACKGROUND, BACKG_OFFSET_X, BACKG_OFFSET_Y - LABEL_GAP, lightColor, false);

				//Row 2 — combat.
				guiGraphics.drawString(this.font, LABEL_ARMOR_CLASS_AC, ACHP_OFFSET_X, ACHP_OFFSET_Y - LABEL_GAP, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, LABEL_HIT_POINTS, ACHP_OFFSET_X + ACHP_SEPARATION, ACHP_OFFSET_Y - LABEL_GAP, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, LABEL_HIT_POINTS_MAX, ACHP_OFFSET_X + ACHP_SEPARATION * 2, ACHP_OFFSET_Y - LABEL_GAP, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, LABEL_HIT_POINTS_TEMP, ACHP_OFFSET_X + ACHP_SEPARATION * 3, ACHP_OFFSET_Y - LABEL_GAP, AUTO_FIELD_COLOR, false);

				//Row 3 — speed, proficiency, and initiative.
				guiGraphics.drawString(this.font, LABEL_SPEED, SPEED_OFFSET_X, SPEED_OFFSET_Y - LABEL_GAP, lightColor, false);
				guiGraphics.drawString(this.font, LABEL_PROFICIENCY_BONUS, PROF_OFFSET_X, PROF_OFFSET_Y - LABEL_GAP, AUTO_FIELD_COLOR, false);
				//The "+" sits right next to the field, not inside it: the field only stores the number.
				guiGraphics.drawString(this.font, "+", PROF_OFFSET_X - 7, PROF_OFFSET_Y + 5, AUTO_FIELD_COLOR, false);
				//Left-aligned like everything else. Used to be centered over its button, which was the
				//sheet's only exception and on top of that forced drawCenteredString, which forces a shadow
				//(see below).
				guiGraphics.drawString(this.font, LABEL_INITIATIVE, INITIATIVE_OFFSET_X, INITIATIVE_OFFSET_Y - LABEL_GAP, lightColor, false);

				//Row 4 — resources. "Hit Dice" labels the whole cell (amount + types), not just the first
				//field, which is why its label spans above both.
				guiGraphics.drawString(this.font, LABEL_LEVEL, LEVEL_OFFSET_X, LEVEL_OFFSET_Y - LABEL_GAP, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, LABEL_HUNGER, HUNGER_OFFSET_X, HUNGER_OFFSET_Y - LABEL_GAP, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, LABEL_HITDICE, HITDICE_OFFSET_X, HITDICE_OFFSET_Y - LABEL_GAP, lightColor, false);
				break;
			case SKILLS:
				//Group headers: they say which ability each block draws from, which is half the
				//information a skill list needs and used to appear nowhere on screen — the grouping only
				//existed as comments in the code. They reuse the side panel's ability labels, so there are
				//no new translation keys.
				//No bands here like on the main tab: five bands across two columns read as stripes, and
				//the headers with their rule already separate the groups more than enough.
				section(guiGraphics, LABEL_ABILITY_STR, skillGroupY(SKILL_COL1_GROUPS, 0), SKILL_COL1_X, SKILL_COL2_X - 8);
				section(guiGraphics, LABEL_ABILITY_DEX, skillGroupY(SKILL_COL1_GROUPS, 1), SKILL_COL1_X, SKILL_COL2_X - 8);
				section(guiGraphics, LABEL_ABILITY_INT, skillGroupY(SKILL_COL1_GROUPS, 2), SKILL_COL1_X, SKILL_COL2_X - 8);
				section(guiGraphics, LABEL_ABILITY_WIS, skillGroupY(SKILL_COL2_GROUPS, 0), SKILL_COL2_X, SKILL_RIGHT);
				section(guiGraphics, LABEL_ABILITY_CHA, skillGroupY(SKILL_COL2_GROUPS, 1), SKILL_COL2_X, SKILL_RIGHT);

				//STRENGTH
				guiGraphics.drawString(this.font, LABEL_SKILL_ATHLETICS, SKILL_COL1_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL1_GROUPS, 0), lightColor, false);

				//DEXTERITY
				guiGraphics.drawString(this.font, LABEL_SKILL_ACROBATICS, SKILL_COL1_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL1_GROUPS, 1), darkColor, false);
				guiGraphics.drawString(this.font, LABEL_SKILL_SLEIGHTOFHAND, SKILL_COL1_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL1_GROUPS, 2), darkColor, false);
				guiGraphics.drawString(this.font, LABEL_SKILL_STEALTH, SKILL_COL1_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL1_GROUPS, 3), darkColor, false);

				//INTELLIGENCE
				guiGraphics.drawString(this.font, LABEL_SKILL_ARCANA, SKILL_COL1_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL1_GROUPS, 4), lightColor, false);
				guiGraphics.drawString(this.font, LABEL_SKILL_HISTORY, SKILL_COL1_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL1_GROUPS, 5), lightColor, false);
				guiGraphics.drawString(this.font, LABEL_SKILL_INVESTIGATION, SKILL_COL1_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL1_GROUPS, 6), lightColor, false);
				guiGraphics.drawString(this.font, LABEL_SKILL_NATURE, SKILL_COL1_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL1_GROUPS, 7), lightColor, false);
				guiGraphics.drawString(this.font, LABEL_SKILL_RELIGION, SKILL_COL1_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL1_GROUPS, 8), lightColor, false);

				//WISDOM
				guiGraphics.drawString(this.font, LABEL_SKILL_ANIMALHANDLING, SKILL_COL2_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL2_GROUPS, 0), lightColor, false);
				guiGraphics.drawString(this.font, LABEL_SKILL_INSIGHT, SKILL_COL2_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL2_GROUPS, 1), lightColor, false);
				guiGraphics.drawString(this.font, LABEL_SKILL_MEDICINE, SKILL_COL2_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL2_GROUPS, 2), lightColor, false);
				guiGraphics.drawString(this.font, LABEL_SKILL_PERCEPTION, SKILL_COL2_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL2_GROUPS, 3), lightColor, false);
				guiGraphics.drawString(this.font, LABEL_SKILL_SURVIVAL, SKILL_COL2_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL2_GROUPS, 4), lightColor, false);

				//CHARISMA
				guiGraphics.drawString(this.font, LABEL_SKILL_DECEPTION, SKILL_COL2_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL2_GROUPS, 5), lightColor, false);
				guiGraphics.drawString(this.font, LABEL_SKILL_INTIMIDATION, SKILL_COL2_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL2_GROUPS, 6), lightColor, false);
				guiGraphics.drawString(this.font, LABEL_SKILL_PERFORMANCE, SKILL_COL2_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL2_GROUPS, 7), lightColor, false);
				guiGraphics.drawString(this.font, LABEL_SKILL_PERSUASION, SKILL_COL2_X + SKILL_LABEL_GAP, skillRowY(SKILL_COL2_GROUPS, 8), lightColor, false);
				break;
		}

	}

	@Override
	public void onClose() {
		super.onClose();
	}

	/**
	 * <p>Refills the fields from the client sheet, if this screen is the one currently open.</p>
	 *
	 * <p>Called by {@code SheetClientMessage} every time a COMPLETE sheet arrives from the server:
	 * switching characters, resting, applying a preset, spending an Ability Score Improvement. Single-field
	 * patches ({@code SheetFieldUpdateMessage}) deliberately don't go through here — they arrive mid-combat
	 * and would repaint over whatever the player is typing.</p>
	 */
	public static void refreshIfOpen() {
		if (net.minecraft.client.Minecraft.getInstance().screen instanceof CharacterSheetScreen screen) {
			CharacterSheetLoadProcedure.execute(guistate, screen);
		}
	}

	/**
	 * <p>This sends a packet to the server with the roll expression it wants to roll.</p>
	 * @param category
	 * @param index
	 */
	public void sendRoll(int category, int index, int subIndex) {
		CharacterSheetSaveProcedure.execute(guistate);
		Logger logger = LogManager.getLogger(DndsheetsMod.MODID);
		logger.log(org.apache.logging.log4j.Level.getLevel("info"), "cat: " + category + " | index: " + index + " | subindex: " + subIndex);
		//Shift+click on the die = private roll (Stealth, Investigation...): only reaches whoever rolled
		//and connected operators, instead of everyone nearby — see RollAnnouncerProcedure.sendPrivately.
		boolean isPrivate = hasShiftDown();
		DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetRollButtonMessage(category, index, subIndex, isPrivate));
		SheetRollButtonMessage.handle(entity, category, index, subIndex, isPrivate);

	}

	/**
	 * <p>This updates the active and inactive elements of the screen in accordance with the panelStatus.</p>
	 */
	public void updateTabs() {
		boolean isActive;

		//Tab Buttons
		Button activeTab;
		switch (panelActive) {
			case MAIN:
				activeTab = mainTab;
				break;
			case SKILLS:
				activeTab = skillsTab;
				break;
			case ATTACKS:
				activeTab = attacksTab;
				break;
			default:
				return;
		}
		List<AdjustableImageButton> tabButtons = new ArrayList<>();
		Collections.addAll(tabButtons, mainTab, skillsTab, attacksTab);
		tabButtons.forEach((e) -> {
			if (e != activeTab) {
				e.setY(this.topPos - 12);
				e.setHeight(15);
				e.setImage( new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_tabbutton.png"), 0, 0, 15, 50, 45);
				e.txtColor = TAB_TEXT_CLOSED;
				e.txtShadow = true;  //Light over leather: the shadow gives it relief.
				e.active = true;
			}
			else {
				e.setY(this.topPos - 17);
				e.setHeight(20);
				e.setImage( new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_tabbutton_active.png"), 0, 0, 20, 50, 60);
				//Dark ink: the open tab is parchment, and MCreator's white doesn't read over parchment.
				e.txtColor = INK_COLOR;
				//No shadow: in dark ink over the open tab's parchment, the shadow is a second copy of the
				//label one pixel off, and the word reads as written twice.
				e.txtShadow = false;
				e.active = false;
			}
		});

		//Side Panel
		checkButtons.forEach((e) -> setActiveVisible(!editMode, e));
		saveButtons.forEach((e) -> setActiveVisible(!editMode, e));
		checkEditButtons.forEach((e) -> setActiveVisible(editMode, e));
		saveEditButtons.forEach((e) -> setActiveVisible(editMode, e));

		//Main Tab
		//Unlike the Skills/Attacks tabs (below), these fields used to only have "active" touched, never
		//"visible": switching tabs left them disabled but they kept drawing themselves over Skills/Attacks
		//in the same screen position — the overlapping UI that got reported. EditBox.visible starts true and never got turned off.
		isActive = panelActive == PanelStatus.MAIN;
		for (AbstractWidget widget : mainPanelWidgets) setActiveVisible(isActive, widget);

		//The two initiative widgets share a spot and take turns based on edit mode, so they go AFTER the
		//loop above: this fine-tunes them, that one sets them all the same.
		setActiveVisible(isActive && !editMode, initiativeButton);
		setActiveVisible(isActive && editMode, initiativeEditButton);

		//Skill Tab
		boolean skillsActive = panelActive == PanelStatus.SKILLS && !editMode;
		skillButtons.forEach((e) -> setActiveVisible(skillsActive, e));
		boolean skillsEditActive = panelActive == PanelStatus.SKILLS && editMode;
		skillEditButtons.forEach((e) -> setActiveVisible(skillsEditActive, e));

		//Attack Tab
		isActive = panelActive == PanelStatus.ATTACKS;
		attackRolls.setActive(isActive);
		attackRolls.setEditMode(editMode);

		setActiveVisible(isActive, addButton);
	}

	//Audit item F4: replaces ~16 repeated pairs of "x.active = isActive; x.visible = isActive;".
	private static void setActiveVisible(boolean isActive, AbstractWidget... widgets) {
		for (AbstractWidget widget : widgets) {
			widget.active = isActive;
			widget.visible = isActive;
		}
	}

	/**
	 * <p>This makes two ImageButtons, one which rolls something when clicked, and another that gives a prompt to edit that specific something.</p>
	 * @param guistateKey
	 * @param x
	 * @param y
	 * @param category
	 * @param index
	 * @param isSave
	 * @param rollButtonList
	 * @param editButtonList
	 */
	private void makeRollButton(String guistateKey, int x, int y, int category, int index, boolean isSave, List<ImageButton> rollButtonList, List<ImageButton> editButtonList, Component label) {
		ImageButton rollButton = new ImageButton(this.leftPos + x, this.topPos + y, 16, 16, 0, 0, 16, new ResourceLocation(!isSave ? "dndsheets:textures/screens/atlas/imagebutton_d20.png" : "dndsheets:textures/screens/atlas/imagebutton_d20_save.png"), 16, 32, e -> {
			sendRoll(category, index, 0);
		});
		rollButton.setTooltip(Tooltip.create(Component.translatable(isSave ? "gui.dndsheets.character_sheet.save_tooltip" : "gui.dndsheets.character_sheet.roll_of", label)));
		guistate.put(guistateKey, rollButton);
		this.addRenderableWidget(rollButton);

		ImageButton editButton = new ImageButton(this.leftPos + x, this.topPos + y, 16, 16, 0, 0, 16, new ResourceLocation(!isSave ? "dndsheets:textures/screens/atlas/imagebutton_d20_edit.png" : "dndsheets:textures/screens/atlas/imagebutton_d20_save_edit.png"), 16, 32, e -> {
			CharacterSheetSaveProcedure.execute(guistate);
			RollEditorScreen.workingCategory = category;
			RollEditorScreen.workingIndex = index;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new RollEditorOpenMessage());
		});
		editButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.character_sheet.edit_formula_of", label)));
		guistate.put(guistateKey + "_edit", editButton);
		this.addRenderableWidget(editButton);

		rollButtonList.add(rollButton);
		editButtonList.add(editButton);
	}

	private RollScrollWidget makeScrollList(String guistateKey, int x, int y, int width, int height) {
		RollScrollWidget widget = new RollScrollWidget(x, y, width, height, Component.translatable("gui.dndsheets.character_sheet.attacks_tab"));
		guistate.put(guistateKey, widget);
		this.addRenderableWidget(widget);

		return widget;
	}

	/**
	 * <p>The JsonObject needs to have "rolls" as an element and it must be a JsonArray with JsonObjects, each with "expression" and "context" as members.</p>
	 * @param scrollList
	 * @param obj
	 * @param category
	 * @param index
	 */
	/**
	 * <p>Removes from the screen all widgets of {@code scrollList}'s current rows and empties the list.
	 * Called by {@code CharacterSheetLoadProcedure} BEFORE repopulating from a fresh sheet from the
	 * server (changing race, applying a preset, resting, leveling up) — without this, each new sheet
	 * stacked attack rows on top of the old ones instead of replacing them, and an extra row's delete
	 * button ended up with an index that no longer existed in the real array (see
	 * {@link RollScrollWidget#clearAndCollectWidgets}).</p>
	 */
	public void clearScrollList(RollScrollWidget scrollList) {
		scrollList.clearAndCollectWidgets().forEach(this::removeWidget);
	}

	public void addToScrollList(RollScrollWidget scrollList, JsonObject obj, int category, int index, PanelStatus panel) {
		if (!obj.has("rolls")) return;
		JsonArray rolls = obj.getAsJsonArray("rolls");
		String name = obj.getAsJsonPrimitive("name").getAsString();

		EditBox nameBox = new EditBox(this.font, 0, 0, 150, 18, Component.translatable(""));
		nameBox.setMaxLength(25);
		nameBox.setValue(name);
		this.addWidget(nameBox);

		List<Button> rollButtons = new ArrayList<>();
		List<Button> editButtons = new ArrayList<>();

		for (int i = 0; i < rolls.size(); i++) {
			int subIndex = i;
			String imgLocation = "";
			switch (i) {
				case 0:
					imgLocation = switch(panel) {
						case MAIN -> "";
						case SKILLS -> "";
						case ATTACKS -> "dndsheets:textures/screens/atlas/imagebutton_d20_damage.png";
						default -> "";
					};
				break;
				case 1:
					imgLocation = switch(panel) {
						case MAIN -> "";
						case SKILLS -> "";
						case ATTACKS -> "dndsheets:textures/screens/atlas/imagebutton_d20_attack.png";
						default -> "";
					};
				break;
			}


			String rollTooltip = switch (i) {
				case 0 -> "damage";
				case 1 -> "attack";
				default -> "roll";
			};

			ImageButton rollButton = new ImageButton(0, 0, 16, 16, 0, 0, 16, new ResourceLocation(imgLocation), 16, 32, e -> {
				int btnIndex = scrollList.getIndex(e);
				sendRoll(category, btnIndex, subIndex);
			});
			rollButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.character_sheet.roll_tooltip", rollTooltip)));
			this.addWidget(rollButton);
			rollButtons.add(rollButton);

			imgLocation = imgLocation.replaceAll(".png", "_edit.png");

			ImageButton editButton = new ImageButton(0, 0, 16, 16, 0, 0, 16, new ResourceLocation(imgLocation), 16, 32, e -> {
				int btnIndex = scrollList.getIndex(e);
				CharacterSheetSaveProcedure.execute(guistate);
				AdvancedRollEditorScreen.workingCategory = category;
				AdvancedRollEditorScreen.workingIndex = btnIndex;
				AdvancedRollEditorScreen.workingSubIndex = subIndex;
				DndsheetsMod.PACKET_HANDLER.sendToServer(new AdvancedRollEditorOpenMessage());
			});
			editButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.character_sheet.edit_formula", rollTooltip)));
			this.addWidget(editButton);
			editButtons.add(editButton);
		}

		ImageButton deleteButton = new ImageButton(0, 0, 8, 8, 0, 0, 8, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_delete.png"), 8, 16, e -> ConfirmScreen.ask(Component.literal(nameBox.getValue()), () -> {
			int removedIndex = scrollList.removeListItem(e);
			this.removeWidget(nameBox);
			rollButtons.forEach(this::removeWidget);
			editButtons.forEach(this::removeWidget);
			this.removeWidget(e);
			if (removedIndex < 0) return; //This button no longer matched any real row in the list.

			JsonObject sheet = SheetLoader.getClientSheet();
			SheetLoader.validateSheet(sheet);
			JsonArray arr = sheet.getAsJsonArray(RollIndex.Category.fromInt(category).toString());
			if (removedIndex < arr.size()) arr.remove(removedIndex); //Defense in depth: see clearScrollList.
		}));
		deleteButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.character_sheet.delete_row")));
		this.addWidget(deleteButton);

		scrollList.addListItem(nameBox, rollButtons, editButtons, deleteButton);
	}

	//Every text field on the sheet repeated this same "placeholder that reappears when the field is
	//empty" logic as an anonymous EditBox subclass, changing only the translation key. x/y are offsets
	//without leftPos/topPos applied yet, like the class's OFFSET_X/OFFSET_Y constants.
	private EditBox placeholderEditBox(int x, int y, int width, int height, String translationKey, int maxLength) {
		String placeholder = Component.translatable(translationKey).getString();
		EditBox box = new EditBox(this.font, this.leftPos + x, this.topPos + y, width, height, Component.translatable(translationKey)) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				setSuggestion(getValue().isEmpty() ? placeholder : null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				setSuggestion(getValue().isEmpty() ? placeholder : null);
			}
		};
		box.setSuggestion(placeholder);
		box.setMaxLength(maxLength);
		return box;
	}

	private void initSidePanel() {
		initCharacterNameBox();
		initAbilityScoreBoxes();
		initAbilityRollButtons();
	}

	private void initCharacterNameBox() {
		characterName = placeholderEditBox(NAME_OFFSET_X, NAME_OFFSET_Y, 85, 18, "gui.dndsheets.character_sheet.charactername", 50);
		guistate.put("text:charactername", characterName);
		this.addWidget(this.characterName);
	}

	private void initAbilityScoreBoxes() {
		strength = placeholderEditBox(ABILITY_OFFSET_X, ABILITY_OFFSET_Y, ABILITY_SIZE_X, ABILITY_SIZE_Y, "gui.dndsheets.character_sheet.strength", 2);
		strength.setTooltip(Tooltip.create(LABEL_ABILITY_STR));
		guistate.put("text:strength", strength);
		this.addWidget(this.strength);

		dexterity = placeholderEditBox(ABILITY_OFFSET_X, ABILITY_SEPARATION + ABILITY_OFFSET_Y, ABILITY_SIZE_X, ABILITY_SIZE_Y, "gui.dndsheets.character_sheet.dexterity", 2);
		dexterity.setTooltip(Tooltip.create(LABEL_ABILITY_DEX));
		guistate.put("text:dexterity", dexterity);
		this.addWidget(this.dexterity);

		constitution = placeholderEditBox(ABILITY_OFFSET_X, ABILITY_SEPARATION*2 + ABILITY_OFFSET_Y, ABILITY_SIZE_X, ABILITY_SIZE_Y, "gui.dndsheets.character_sheet.constitution", 2);
		constitution.setTooltip(Tooltip.create(LABEL_ABILITY_CON));
		guistate.put("text:constitution", constitution);
		this.addWidget(this.constitution);

		intelligence = placeholderEditBox(ABILITY_OFFSET_X, ABILITY_SEPARATION*3 + ABILITY_OFFSET_Y, ABILITY_SIZE_X, ABILITY_SIZE_Y, "gui.dndsheets.character_sheet.intelligence", 2);
		intelligence.setTooltip(Tooltip.create(LABEL_ABILITY_INT));
		guistate.put("text:intelligence", intelligence);
		this.addWidget(this.intelligence);

		wisdom = placeholderEditBox(ABILITY_OFFSET_X, ABILITY_SEPARATION*4 + ABILITY_OFFSET_Y, ABILITY_SIZE_X, ABILITY_SIZE_Y, "gui.dndsheets.character_sheet.wisdom", 2);
		wisdom.setTooltip(Tooltip.create(LABEL_ABILITY_WIS));
		guistate.put("text:wisdom", wisdom);
		this.addWidget(this.wisdom);

		charisma = placeholderEditBox(ABILITY_OFFSET_X, ABILITY_SEPARATION*5 + ABILITY_OFFSET_Y, ABILITY_SIZE_X, ABILITY_SIZE_Y, "gui.dndsheets.character_sheet.charisma", 2);
		charisma.setTooltip(Tooltip.create(LABEL_ABILITY_CHA));
		guistate.put("text:charisma", charisma);
		this.addWidget(this.charisma);
	}

	private void initAbilityRollButtons() {
		int checkBtnOffset = -42;
		int saveBtnOffset = -24;

		//STR
		makeRollButton("button:roll_str", ABILITY_OFFSET_X+checkBtnOffset, ABILITY_OFFSET_Y, 0, 0, false, checkButtons, checkEditButtons, LABEL_ABILITY_STR);
		makeRollButton("button:roll_str_save", ABILITY_OFFSET_X+saveBtnOffset, ABILITY_OFFSET_Y, 1, 0, true, saveButtons, saveEditButtons, LABEL_ABILITY_STR);
		//DEX
		makeRollButton("button:roll_dex", ABILITY_OFFSET_X+checkBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION, 0, 1, false, checkButtons, checkEditButtons, LABEL_ABILITY_DEX);
		makeRollButton("button:roll_dex_save", ABILITY_OFFSET_X+saveBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION, 1, 1, true, saveButtons, saveEditButtons, LABEL_ABILITY_DEX);
		//CON
		makeRollButton("button:roll_con", ABILITY_OFFSET_X+checkBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION*2, 0, 2, false, checkButtons, checkEditButtons, LABEL_ABILITY_CON);
		makeRollButton("button:roll_con_save", ABILITY_OFFSET_X+saveBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION*2, 1, 2, true, saveButtons, saveEditButtons, LABEL_ABILITY_CON);
		//INT
		makeRollButton("button:roll_int", ABILITY_OFFSET_X+checkBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION*3, 0, 3, false, checkButtons, checkEditButtons, LABEL_ABILITY_INT);
		makeRollButton("button:roll_int_save", ABILITY_OFFSET_X+saveBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION*3, 1, 3, true, saveButtons, saveEditButtons, LABEL_ABILITY_INT);
		//WIS
		makeRollButton("button:roll_wis", ABILITY_OFFSET_X+checkBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION*4, 0, 4, false, checkButtons, checkEditButtons, LABEL_ABILITY_WIS);
		makeRollButton("button:roll_wis_save", ABILITY_OFFSET_X+saveBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION*4, 1, 4, true, saveButtons, saveEditButtons, LABEL_ABILITY_WIS);
		//CHA
		makeRollButton("button:roll_cha", ABILITY_OFFSET_X+checkBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION*5, 0, 5, false, checkButtons, checkEditButtons, LABEL_ABILITY_CHA);
		makeRollButton("button:roll_cha_save", ABILITY_OFFSET_X+saveBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION*5, 1, 5, true, saveButtons, saveEditButtons, LABEL_ABILITY_CHA);
	}

	private void initMainPanel() {
		//Everything created in here belongs to the main panel, and updateTabs() hides it when switching
		//tabs. It's captured as a diff over children() instead of being listed by hand in updateTabs.
		//
		//The hand-written list already failed twice, always the same way: someone adds a field, forgets
		//to register it in the distant place where it gets hidden, and the field stays drawn on top of
		//Skills and Attacks — unlabeled, doing nothing, and with nothing failing. It happened first to
		//the whole batch and then to Level, Hunger and the Guide button. Captured this way, a new field
		//gets included just by existing.
		int before = this.children().size();

		initVitalsBoxes();
		initOptionPickerFields();
		initHitDiceFields();
		initInitiativeButtons();
		initBottomButtons();

		mainPanelWidgets.clear();
		for (GuiEventListener child : this.children().subList(before, this.children().size())) {
			if (child instanceof AbstractWidget widget) mainPanelWidgets.add(widget);
		}
	}

	private void initVitalsBoxes() {
		hitPoints = placeholderEditBox(ACHP_OFFSET_X + ACHP_SEPARATION, ACHP_OFFSET_Y, 32, 18, "gui.dndsheets.character_sheet.hitpoints", 4);
		guistate.put("text:hitpoints", hitPoints);
		this.addWidget(this.hitPoints);

		hitPointsTemp = placeholderEditBox(ACHP_OFFSET_X + ACHP_SEPARATION*3, ACHP_OFFSET_Y, 32, 18, "gui.dndsheets.character_sheet.hitpoints_temp", 4);
		guistate.put("text:hitpoints_temp", hitPointsTemp);
		this.addWidget(this.hitPointsTemp);

		hitPointsMax = placeholderEditBox(ACHP_OFFSET_X + ACHP_SEPARATION*2, ACHP_OFFSET_Y, 32, 18, "gui.dndsheets.character_sheet.hitpoints_max", 4);
		guistate.put("text:hitpoints_max", hitPointsMax);
		this.addWidget(this.hitPointsMax);

		armorClass = placeholderEditBox(ACHP_OFFSET_X, ACHP_OFFSET_Y, 32, 18, "gui.dndsheets.character_sheet.armorclass", 2);
		guistate.put("text:armorclass", armorClass);
		this.addWidget(this.armorClass);

		speed = placeholderEditBox(SPEED_OFFSET_X, SPEED_OFFSET_Y, 32, 18, "gui.dndsheets.character_sheet.speed", 2);
		guistate.put("text:speed", speed);
		this.addWidget(this.speed);

		proficiency = placeholderEditBox(PROF_OFFSET_X, PROF_OFFSET_Y, 14, 18, "gui.dndsheets.character_sheet.proficiency", 1);
		guistate.put("text:proficiency", proficiency);
		this.addWidget(this.proficiency);

		// --- Fields derived from the real player: level and hunger ---
		// NOTE: the (X/Y) positions are a starting point; adjust them against your background
		// texture (character_sheet.png) so they visually fit with the rest of the panel.
		level = new EditBox(this.font, this.leftPos + LEVEL_OFFSET_X, this.topPos + LEVEL_OFFSET_Y, 20, 18, Component.translatable("gui.dndsheets.character_sheet.level"));
		level.setMaxLength(2);
		guistate.put("text:level", level);
		this.addWidget(this.level);

		hunger = new EditBox(this.font, this.leftPos + HUNGER_OFFSET_X, this.topPos + HUNGER_OFFSET_Y, 24, 18, Component.translatable("gui.dndsheets.character_sheet.hunger"));
		hunger.setMaxLength(2);
		guistate.put("text:hunger", hunger);
		this.addWidget(this.hunger);

		// These fields now reflect the player's real state (see syncFromEntity()),
		// so they're locked to keep them from being edited by hand and drifting out of sync.
		// The amber color tells them apart at a glance from the fields that CAN be typed into by hand.
		EditBox[] autoFields = {hitPoints, hitPointsMax, hitPointsTemp, proficiency, level, hunger, armorClass};
		for (EditBox autoField : autoFields) {
			autoField.setEditable(false);
			autoField.setTextColorUneditable(AUTO_FIELD_COLOR);
		}
	}

	private void initOptionPickerFields() {
		characterRace = new EditBox(this.font, this.leftPos + RACE_OFFSET_X, this.topPos + RACE_OFFSET_Y, IDENTITY_W, 18, Component.translatable("gui.dndsheets.character_sheet.characterrace")) {
			@Override
			public boolean mouseClicked(double mx, double my, int button) {
				if (!this.isMouseOver(mx, my)) return false;
				//Race is chosen by Origins, not by a picker of our own (see Modularity Map / dndsheets_species): this
				//opens Origins' real selector and only syncs afterward (see SpeciesCommand.choose).
				//The sheet is closed BEFORE the selector arrives: leaving it open on top stole the
				//click/keyboard from Origins' selector, which became unusable until the sheet was closed by hand.
				//Without the species addon the command doesn't exist (a Brigadier error in chat, no selector):
				//it falls back to our own list selector, which returns to THIS sheet on choosing (parent captured
				//by the handler; the container is never closed on that path — see CharacterOptionListScreen).
				CharacterSheetSaveProcedure.execute(guistate);
				if (CharacterSetupScreen.speciesLoaded()) {
					net.minecraft.client.Minecraft.getInstance().player.connection.sendCommand("dndspecies choose");
					CharacterSheetScreen.this.onClose();
				} else {
					DndsheetsMod.PACKET_HANDLER.sendToServer(new net.hawthorn.dndsheets.network.BrowseActionMessage(
						net.hawthorn.dndsheets.network.BrowseActionMessage.Action.CHARACTER_OPTIONS,
						net.hawthorn.dndsheets.CharacterOptionsRegistry.RACE));
				}
				return true;
			}
		};
		characterRace.setEditable(false);
		characterRace.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.characterrace").getString());
		guistate.put("text:characterrace", characterRace);
		this.addWidget(this.characterRace);

		background = new EditBox(this.font, this.leftPos + BACKG_OFFSET_X, this.topPos + BACKG_OFFSET_Y, IDENTITY_W, 18, Component.translatable("gui.dndsheets.character_sheet.background")) {
			@Override
			public boolean mouseClicked(double mx, double my, int button) {
				if (!this.isMouseOver(mx, my)) return false;
				//Background is chosen by Origins, not by a picker of our own (see Modularity Map / dndsheets_species):
				//this opens Origins' real selector and only syncs afterward. Same pattern as Race,
				//including the no-species fallback.
				CharacterSheetSaveProcedure.execute(guistate);
				if (CharacterSetupScreen.speciesLoaded()) {
					net.minecraft.client.Minecraft.getInstance().player.connection.sendCommand("dndspecies choosebackground");
					CharacterSheetScreen.this.onClose();
				} else {
					DndsheetsMod.PACKET_HANDLER.sendToServer(new net.hawthorn.dndsheets.network.BrowseActionMessage(
						net.hawthorn.dndsheets.network.BrowseActionMessage.Action.CHARACTER_OPTIONS,
						net.hawthorn.dndsheets.CharacterOptionsRegistry.BACKGROUND));
				}
				return true;
			}
		};
		background.setEditable(false);
		background.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.background").getString());
		guistate.put("text:background", background);
		this.addWidget(this.background);

		characterClass = new EditBox(this.font, this.leftPos + CLASS_OFFSET_X, this.topPos + CLASS_OFFSET_Y, IDENTITY_W, 18, Component.translatable("gui.dndsheets.character_sheet.characterclass")) {
			@Override
			public boolean mouseClicked(double mx, double my, int button) {
				if (!this.isMouseOver(mx, my)) return false;
				//Class is also chosen by Origins (origins-classes:class layer, see Modularity Map /
				//dndsheets_species): same pattern as Race/Background, and it still applies the real PRESET. Without
				//species the fallback is NOT the list of names but the preset selector — the core's real
				//mechanism (hit die, ability scores, equipment), same as in CharacterSetupScreen.
				CharacterSheetSaveProcedure.execute(guistate);
				if (CharacterSetupScreen.speciesLoaded()) {
					net.minecraft.client.Minecraft.getInstance().player.connection.sendCommand("dndspecies chooseclass");
					CharacterSheetScreen.this.onClose();
				} else {
					DndsheetsMod.PACKET_HANDLER.sendToServer(new net.hawthorn.dndsheets.network.BrowseActionMessage(
						net.hawthorn.dndsheets.network.BrowseActionMessage.Action.LIST_PRESETS));
				}
				return true;
			}
		};
		characterClass.setEditable(false);
		characterClass.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.characterclass").getString());
		guistate.put("text:characterclass", characterClass);
		this.addWidget(this.characterClass);
	}

	private void initHitDiceFields() {
		hitDiceTypes = placeholderEditBox(HITDICE_OFFSET_X + 26, HITDICE_OFFSET_Y, HITDICE_TYPES_W, 18, "gui.dndsheets.character_sheet.hitdice_types", 50);
		guistate.put("text:hitdice_types", hitDiceTypes);
		this.addWidget(this.hitDiceTypes);

		hitDice = placeholderEditBox(HITDICE_OFFSET_X, HITDICE_OFFSET_Y, 20, 18, "gui.dndsheets.character_sheet.hitdice", 2);
		guistate.put("text:hitdice", hitDice);
		this.addWidget(this.hitDice);
	}

	private void initInitiativeButtons() {
		initiativeButton = new ImageButton(this.leftPos + INITIATIVE_OFFSET_X, this.topPos + INITIATIVE_OFFSET_Y, 16, 16, 0, 0, 16, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_d20.png"), 16, 32, e -> {
			sendRoll(0, 6, 0);
		});
		initiativeButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.character_sheet.roll_of", LABEL_INITIATIVE)));
		guistate.put("button:roll_init", initiativeButton);
		this.addRenderableWidget(initiativeButton);

		initiativeEditButton = new ImageButton(this.leftPos + INITIATIVE_OFFSET_X, this.topPos + INITIATIVE_OFFSET_Y, 16, 16, 0, 0, 16, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_d20_edit.png"), 16, 32, e -> {
			CharacterSheetSaveProcedure.execute(guistate);
			RollEditorScreen.workingCategory = 0;
			RollEditorScreen.workingIndex = 6;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new RollEditorOpenMessage());
		});
		initiativeEditButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.character_sheet.edit_formula_of", LABEL_INITIATIVE)));
		guistate.put("button:roll_init_edit", initiativeEditButton);
		this.addRenderableWidget(initiativeEditButton);
	}

	private void initBottomButtons() {
		//NOTE: no slot drawn in the texture yet. Placed in the bottom margin, below Level/
		//Hunger, so as not to overlap the Initiative circle (which occupies the area x=270-345, y=90-200).
		grimoireButton = TomeButton.of(Component.translatable("gui.dndsheets.character_sheet.grimoire"), b -> GrimoireScreen.open(this), this.leftPos + GRIMOIRE_OFFSET_X, this.topPos + GRIMOIRE_OFFSET_Y, BOTTOM_BUTTON_WIDTH, BOTTOM_BUTTON_HEIGHT);
		guistate.put("button:grimoire", grimoireButton);
		this.addRenderableWidget(grimoireButton);

		//One button where there used to be three (Presets, Characters, Guide). It's not just saving space: those
		//three were EVERYTHING the player could open without knowing a command, and a fourth no longer fit
		//in this row (see BOTTOM_BUTTON_WIDTH). Behind it is a list with sections and search that grows
		//without fighting the sheet's grid — see PlayerPanelScreen. The Grimoire stays on its own because it's
		//the only one pressed in the middle of a turn.
		menuButton = TomeButton.of(Component.translatable("gui.dndsheets.character_sheet.menu"), b -> {
			CharacterSheetSaveProcedure.execute(guistate); //Don't lose what was typed when navigating away.
			PlayerPanelScreen.open(this);
		}, this.leftPos + MENU_OFFSET_X, this.topPos + MENU_OFFSET_Y, BOTTOM_BUTTON_WIDTH, BOTTOM_BUTTON_HEIGHT);
		guistate.put("button:menu", menuButton);
		this.addRenderableWidget(menuButton);
	}

	private void initSkillPanel() {
		/*
			SKILL ROLL BUTTONS
		 */

		//STR
		makeRollButton("button:roll_athletics", SKILL_COL1_X, skillRowY(SKILL_COL1_GROUPS, 0) - 4, 2, 0, false, skillButtons, skillEditButtons, LABEL_SKILL_ATHLETICS);

		//DEX
		makeRollButton("button:roll_acrobatics", SKILL_COL1_X, skillRowY(SKILL_COL1_GROUPS, 1) - 4, 2, 1, false, skillButtons, skillEditButtons, LABEL_SKILL_ACROBATICS);
		makeRollButton("button:roll_sleightofhand", SKILL_COL1_X, skillRowY(SKILL_COL1_GROUPS, 2) - 4, 2, 2, false, skillButtons, skillEditButtons, LABEL_SKILL_SLEIGHTOFHAND);
		makeRollButton("button:roll_stealth", SKILL_COL1_X, skillRowY(SKILL_COL1_GROUPS, 3) - 4, 2, 3, false, skillButtons, skillEditButtons, LABEL_SKILL_STEALTH);

		//INT
		makeRollButton("button:roll_arcana", SKILL_COL1_X, skillRowY(SKILL_COL1_GROUPS, 4) - 4, 2, 4, false, skillButtons, skillEditButtons, LABEL_SKILL_ARCANA);
		makeRollButton("button:roll_history", SKILL_COL1_X, skillRowY(SKILL_COL1_GROUPS, 5) - 4, 2, 5, false, skillButtons, skillEditButtons, LABEL_SKILL_HISTORY);
		makeRollButton("button:roll_investigation", SKILL_COL1_X, skillRowY(SKILL_COL1_GROUPS, 6) - 4, 2, 6, false, skillButtons, skillEditButtons, LABEL_SKILL_INVESTIGATION);
		makeRollButton("button:roll_nature", SKILL_COL1_X, skillRowY(SKILL_COL1_GROUPS, 7) - 4, 2, 7, false, skillButtons, skillEditButtons, LABEL_SKILL_NATURE);
		makeRollButton("button:roll_religion", SKILL_COL1_X, skillRowY(SKILL_COL1_GROUPS, 8) - 4, 2, 8, false, skillButtons, skillEditButtons, LABEL_SKILL_RELIGION);

		//WIS
		makeRollButton("button:roll_animalhandling", SKILL_COL2_X, skillRowY(SKILL_COL2_GROUPS, 0) - 4, 2, 9, false, skillButtons, skillEditButtons, LABEL_SKILL_ANIMALHANDLING);
		makeRollButton("button:roll_insight", SKILL_COL2_X, skillRowY(SKILL_COL2_GROUPS, 1) - 4, 2, 10, false, skillButtons, skillEditButtons, LABEL_SKILL_INSIGHT);
		makeRollButton("button:roll_medicine", SKILL_COL2_X, skillRowY(SKILL_COL2_GROUPS, 2) - 4, 2, 11, false, skillButtons, skillEditButtons, LABEL_SKILL_MEDICINE);
		makeRollButton("button:roll_perception", SKILL_COL2_X, skillRowY(SKILL_COL2_GROUPS, 3) - 4, 2, 12, false, skillButtons, skillEditButtons, LABEL_SKILL_PERCEPTION);
		makeRollButton("button:roll_survival", SKILL_COL2_X, skillRowY(SKILL_COL2_GROUPS, 4) - 4, 2, 13, false, skillButtons, skillEditButtons, LABEL_SKILL_SURVIVAL);

		//CHA
		makeRollButton("button:roll_deception", SKILL_COL2_X, skillRowY(SKILL_COL2_GROUPS, 5) - 4, 2, 14, false, skillButtons, skillEditButtons, LABEL_SKILL_DECEPTION);
		makeRollButton("button:roll_intimidation", SKILL_COL2_X, skillRowY(SKILL_COL2_GROUPS, 6) - 4, 2, 15, false, skillButtons, skillEditButtons, LABEL_SKILL_INTIMIDATION);
		makeRollButton("button:roll_performance", SKILL_COL2_X, skillRowY(SKILL_COL2_GROUPS, 7) - 4, 2, 16, false, skillButtons, skillEditButtons, LABEL_SKILL_PERFORMANCE);
		makeRollButton("button:roll_persuasion", SKILL_COL2_X, skillRowY(SKILL_COL2_GROUPS, 8) - 4, 2, 17, false, skillButtons, skillEditButtons, LABEL_SKILL_PERSUASION);

	}

	private void initAttackPanel() {

		attackRolls = makeScrollList("scrolllist:attack_rolls", this.leftPos + PANEL_X, this.topPos + ATTACK_TOP,
			PANEL_RIGHT - PANEL_X, ATTACK_HEIGHT);
		//now that it exists, CharacterSheetLoadProcedure is responsible for populating the attackRolls list using addToScrollList().

		addButton = new ImageButton(attackRolls.getX(), attackRolls.getY() + attackRolls.getHeight() + 8, 16, 16, 0, 0, 16, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_add.png"), 16, 32, e -> {
			JsonObject sheet = SheetLoader.getClientSheet();
			SheetLoader.validateSheet(sheet);
			JsonArray arr = sheet.getAsJsonArray("attacks");

			JsonObject rollForm = new JsonObject();
			JsonArray rollSet = new JsonArray();
			JsonArray rollGroup1 = new JsonArray();
			JsonArray rollGroup2 = new JsonArray();
			rollForm.addProperty("name", "New Attack");

			JsonObject roll1_1 = new JsonObject();
			roll1_1.addProperty("context", "Damage Roll");
			roll1_1.addProperty("expression", "2d6 + $str");
			JsonObject roll1_2 = new JsonObject();
			roll1_2.addProperty("context", "");
			roll1_2.addProperty("expression", "");

			JsonObject roll2_1 = new JsonObject();
			roll2_1.addProperty("context", "Attack Roll");
			roll2_1.addProperty("expression", "1d20 + $str + $prof");
			JsonObject roll2_2 = new JsonObject();
			roll2_2.addProperty("context", "");
			roll2_2.addProperty("expression", "");

			rollGroup1.add(roll1_1);
			rollGroup1.add(roll1_2);
			rollGroup2.add(roll2_1);
			rollGroup2.add(roll2_2);

			rollSet.add(rollGroup1);
			rollSet.add(rollGroup2);

			rollForm.add("rolls", rollSet);

			arr.add(rollForm);
			addToScrollList(attackRolls, rollForm, 3, attackRolls.getListSize(), PanelStatus.ATTACKS);

		});
		addButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.character_sheet.add_attack")));
		this.addRenderableWidget(addButton);
	}

	@Override
	public void init() {
		super.init();

		mainTab = new AdjustableImageButton(this.leftPos + 15, this.topPos - 12, 50, 15, 0, 0, 15, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_tabbutton.png"), 50, 45, e -> {
			panelActive = PanelStatus.MAIN;
			updateTabs();

		}, Component.translatable("gui.dndsheets.character_sheet.main_tab"));
		this.addRenderableWidget(mainTab);

		skillsTab = new AdjustableImageButton(this.leftPos + 65, this.topPos - 12, 50, 15, 0, 0, 15, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_tabbutton.png"), 50, 45, e -> {
			panelActive = PanelStatus.SKILLS;
			updateTabs();

		}, Component.translatable("gui.dndsheets.character_sheet.skills_tab"));
		this.addRenderableWidget(skillsTab);

		attacksTab = new AdjustableImageButton(this.leftPos + 115, this.topPos - 12, 50, 15, 0, 0, 15, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_tabbutton.png"), 50, 45, e -> {
			panelActive = PanelStatus.ATTACKS;
			updateTabs();

		}, Component.translatable("gui.dndsheets.character_sheet.attacks_tab"));
		this.addRenderableWidget(attacksTab);

		ImageButton editToggle = new ImageButton(this.leftPos + NAME_OFFSET_X, this.topPos + EDIT_TOGGLE_Y, 16, 16, 0, 0, 16, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_editmode.png"), 16, 32, e -> {
			editMode = !editMode;
			updateTabs();
		});
		editToggle.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.character_sheet.edit_toggle")));
		guistate.put("button:edit_toggle", editToggle);
		this.addRenderableWidget(editToggle);

		initSidePanel();
		initAttackPanel();
		initMainPanel();
		initSkillPanel();

		//Cleared before filling: init() runs again when the window is resized, and
		//guistate is static (shared with the menu), so without this the list would grow on every resize.
		sheetFields.clear();
		for (Object widget : guistate.values()) {
			if (widget instanceof EditBox field) sheetFields.add(field);
		}

		updateTabs();
		warnIfLabelsOverflow();
		CharacterSheetLoadProcedure.execute(guistate, this);
	}
}
