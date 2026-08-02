package net.hawthorn.dndsheets.client.gui;

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
import net.hawthorn.dndsheets.network.PresetListRequestMessage;
import net.hawthorn.dndsheets.network.RollEditorOpenMessage;
import net.hawthorn.dndsheets.procedures.CharacterSheetSaveProcedure;
import net.minecraft.client.gui.components.*;
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

import net.hawthorn.dndsheets.procedures.CharacterSheetLoadProcedure;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CharacterSheetScreen extends AbstractContainerScreen<CharacterSheetMenu> {
	private final static HashMap<String, Object> guistate = CharacterSheetMenu.guistate;
	private final Level world;
	private final int x, y, z;
	private final Player entity;
	public static PanelStatus panelActive = PanelStatus.MAIN;
	public static boolean editMode = false;

	EditBox hitPoints;      // Sincronizado en vivo desde entity.getHealth() - ver containerTick()
	EditBox hitPointsMax;   // Sincronizado en vivo desde entity.getMaxHealth()
	EditBox hitPointsTemp;  // Sincronizado en vivo desde entity.getAbsorptionAmount() (corazones dorados = PG temporales de D&D)
	EditBox armorClass;
	EditBox speed;
	EditBox initiative;
	EditBox characterName;
	EditBox characterRace;
	EditBox characterClass;
	EditBox background;
	EditBox proficiency;    // Auto-calculado desde el nivel real (regla de competencia de 5e)
	EditBox level;          // Sincronizado en vivo desde entity.experienceLevel (XP real de Minecraft)
	EditBox hunger;         // Sincronizado en vivo desde entity.getFoodData().getFoodLevel()
	Button grimoireButton;  // Abre el Grimorio (ver GrimoireScreen), sin tocar la hoja
	Button presetsButton;   // Pide la lista de presets de clase al servidor (ver PresetScreen)

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
	ImageButton editToggle;

	Checkbox dsaves_success_1;
	Checkbox dsaves_success_2;
	Checkbox dsaves_success_3;
	Checkbox dsaves_fail_1;
	Checkbox dsaves_fail_2;
	Checkbox dsaves_fail_3;

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

	private final int NAME_OFFSET_X = 15;
	private final int NAME_OFFSET_Y = 20;

	/*
		MAIN PANEL OFFSETS
	 */
	private final int RACE_OFFSET_X = 125;
	private final int RACE_OFFSET_Y = 20;

	private final int BACKG_OFFSET_X = 235;
	private final int BACKG_OFFSET_Y = 20;

	private final int CLASS_OFFSET_X = 125;
	private final int CLASS_OFFSET_Y = 55;

	private final int PROF_OFFSET_X = 125;
	private final int PROF_OFFSET_Y = 165;

	private final int HITDICE_OFFSET_X = 125;
	private final int HITDICE_OFFSET_Y = 125;

	private final int INITIATIVE_OFFSET_X = 304;
	private final int INITIATIVE_OFFSET_Y = 165;

	private final int DEATHSAVES_OFFSET_X = 125;
	private final int DEATHSAVES_OFFSET_Y = 125;

	//AC, Hit Points, Temp Hit Points, Max Hit Points, and Speed are grouped together.
	private final int ACHP_OFFSET_X = 125;
	private final int ACHP_OFFSET_Y = 90;
	private final int ACHP_SEPARATION = 45;

	//NOTA: no hay hueco dibujado para estos dos en character_sheet.png todavía (ver LEEME.md).
	//Se colocan en el margen inferior del panel para que no se solapen con nada mientras tanto.
	private final int LEVEL_OFFSET_X = 125;
	private final int LEVEL_OFFSET_Y = 190;

	private final int HUNGER_OFFSET_X = 220;
	private final int HUNGER_OFFSET_Y = 190;

	/*
		SKILLS PANEL OFFSETS
	 */
	private final int SKILL_SEPARATION = 20;

	private final int SKILL_LIST1_OFFSET_X = 135;
	private final int SKILL_LIST1_OFFSET_Y = 15;

	private final int SKILL_LIST2_OFFSET_X = 255;
	private final int SKILL_LIST2_OFFSET_Y = 15;

	//Color del texto de los campos que se rellenan solos (PG, CA, nivel, hambre, competencia): ámbar, para
	//distinguirlos de un vistazo de los campos en blanco normal que sí se pueden escribir a mano.
	private static final int AUTO_FIELD_COLOR = 0xFFD37F;

	public enum PanelStatus {
		MAIN,
		SKILLS,
		ATTACKS,
		NONE
	}

	public CharacterSheetScreen(CharacterSheetMenu container, Inventory inventory, Component text) {
		super(container, inventory, text);
		this.world = container.world;
		this.x = container.x;
		this.y = container.y;
		this.z = container.z;
		this.entity = container.entity;
		this.imageWidth = 350;
		this.imageHeight = 200;
	}

	private static final ResourceLocation BG_MAIN = new ResourceLocation("dndsheets:textures/screens/character_sheet.png");
	private static final ResourceLocation BG_SKILLS = new ResourceLocation("dndsheets:textures/screens/character_sheet_2.png");
	private static final ResourceLocation BG_ATTACKS = new ResourceLocation("dndsheets:textures/screens/character_sheet_3.png");

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
				break;
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
				guiGraphics.blit(BG_MAIN, this.leftPos - 24, this.topPos - 24, 0, 0, 398, 248, 398, 248);
				break;
			case SKILLS:
				guiGraphics.blit(BG_SKILLS, this.leftPos - 24, this.topPos - 24, 0, 0, 398, 248, 398, 248);
				break;
			case ATTACKS:
				guiGraphics.blit(BG_ATTACKS, this.leftPos - 24, this.topPos - 24, 0, 0, 398, 248, 398, 248);
				break;
		}

		guiGraphics.blit(new ResourceLocation("dndsheets:textures/screens/str.png"), this.leftPos + ABILITY_OFFSET_X + 25, this.topPos + ABILITY_OFFSET_Y, 0, 0, 16, 16, 16, 16);
		guiGraphics.blit(new ResourceLocation("dndsheets:textures/screens/dex.png"), this.leftPos + ABILITY_OFFSET_X + 25, this.topPos + ABILITY_OFFSET_Y + ABILITY_SEPARATION, 0, 0, 16, 16, 16, 16);
		guiGraphics.blit(new ResourceLocation("dndsheets:textures/screens/cons.png"), this.leftPos + ABILITY_OFFSET_X + 25, this.topPos + ABILITY_OFFSET_Y + ABILITY_SEPARATION*2, 0, 0, 16, 16, 16, 16);
		guiGraphics.blit(new ResourceLocation("dndsheets:textures/screens/int.png"), this.leftPos + ABILITY_OFFSET_X + 25, this.topPos + ABILITY_OFFSET_Y + ABILITY_SEPARATION*3, 0, 0, 16, 16, 16, 16);
		guiGraphics.blit(new ResourceLocation("dndsheets:textures/screens/wis.png"), this.leftPos + ABILITY_OFFSET_X + 25, this.topPos + ABILITY_OFFSET_Y + ABILITY_SEPARATION*4, 0, 0, 16, 16, 16, 16);
		guiGraphics.blit(new ResourceLocation("dndsheets:textures/screens/cha.png"), this.leftPos + ABILITY_OFFSET_X + 25, this.topPos + ABILITY_OFFSET_Y + ABILITY_SEPARATION*5, 0, 0, 16, 16, 16, 16);

		//guiGraphics.blit(new ResourceLocation("dndsheets:textures/screens/dsaves_success.png"), this.leftPos + DEATHSAVES_OFFSET_X, this.topPos + DEATHSAVES_OFFSET_Y, 0, 0, 16, 16, 16, 16);
		//guiGraphics.blit(new ResourceLocation("dndsheets:textures/screens/dsaves_fail.png"), this.leftPos + DEATHSAVES_OFFSET_X, this.topPos + DEATHSAVES_OFFSET_Y + 20, 0, 0, 16, 16, 16, 16);



		RenderSystem.disableBlend();
	}

	@Override
	public boolean keyPressed(int key, int b, int c) {
		//if (key == 256 || DndsheetsModKeyMappings.CHARACTER.isActiveAndMatches(InputConstants.getKey(key, b))) {
		if (key == 256) {
			//DndsheetsModKeyMappings.CHARACTER.consumeClick();
			this.minecraft.player.closeContainer();
			CharacterSheetSaveProcedure.execute(guistate);
			return true;
		}
		// Cualquier campo de texto enfocado se queda con la tecla entera, sin importar
		// si EditBox.keyPressed() la reconoce o no. Antes, una tecla "no especial" (p.ej.
		// una letra normal, que EditBox solo procesa en charTyped) devolvía false aquí y
		// el evento caía en AbstractContainerScreen.keyPressed(), que cierra la hoja si la
		// tecla coincide con el keybind de inventario (por defecto, E) - perdiendo lo escrito.
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

		EditBox[] scrollBoxes = attackRolls.getEditBoxes();
        for (EditBox box : scrollBoxes) {
            if (box.isFocused()) {
                box.keyPressed(key, b, c);
                return true;
            }
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

		EditBox[] scrollBoxes = attackRolls.getEditBoxes();
		for (EditBox box : scrollBoxes) {
			box.tick();

		}
	}

	/**
	 * <p>Sincroniza los campos derivados del estado real del jugador (vida, hambre, nivel)
	 * en lugar de depender de lo que el jugador escriba manualmente. Esto convierte a estos
	 * campos en un espejo de solo lectura del jugador de Minecraft, en vez de una hoja
	 * independiente que hay que actualizar a mano.</p>
	 */
	private void syncFromEntity() {
		if (entity == null) return;

		int currentHp = (int) Math.ceil(entity.getHealth());
		int maxHp = (int) Math.ceil(entity.getMaxHealth());
		int tempHp = (int) Math.ceil(entity.getAbsorptionAmount()); // Corazones dorados = PG temporales

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

		// Nivel real de personaje: sigue el XP de Minecraft hasta que el DM lo fije a mano con
		// /dndsheet setlevel (guarda "characterLevel" en la hoja) — ver SheetLoader.characterLevelOf.
		int xpLevel = SheetLoader.characterLevelOf(SheetLoader.getClientSheet(), entity);
		if (!level.getValue().equals(String.valueOf(xpLevel)))
			level.setValue(String.valueOf(xpLevel));

		// Regla de bono de competencia de D&D 5e, calculada a partir del nivel real
		int calculatedProficiency = 2 + ((xpLevel - 1) / 4);
		if (!proficiency.getValue().equals(String.valueOf(calculatedProficiency)))
			proficiency.setValue(String.valueOf(calculatedProficiency));

		// CA = 10 + mod. Destreza + armadura real equipada (entity.getArmorValue()),
		// para que la armadura que lleve puesta el jugador sí afecte a la hoja.
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

	@Override
	protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		//guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_character_sheet"), 15, 10, -12829636, false);
		final int lightColor = 0xFFFFFF;
		final int darkColor = 0x1F1F1F;
		guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_name"), NAME_OFFSET_X, NAME_OFFSET_Y - 10, lightColor, false);

		switch (panelActive) {
			case MAIN:
				//Ámbar = se rellena solo (ver AUTO_FIELD_COLOR); color normal = se escribe a mano.
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_armor_class_ac"), ACHP_OFFSET_X, ACHP_OFFSET_Y - 10, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_hit_points"), ACHP_OFFSET_X + ACHP_SEPARATION, ACHP_OFFSET_Y - 10, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_hit_points_max"), ACHP_OFFSET_X + ACHP_SEPARATION * 2, ACHP_OFFSET_Y - 10, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_hit_points_temp"), ACHP_OFFSET_X + ACHP_SEPARATION * 3, ACHP_OFFSET_Y - 10, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_speed"), ACHP_OFFSET_X + ACHP_SEPARATION * 4, ACHP_OFFSET_Y - 10, lightColor, false);
				guiGraphics.drawString(this.font, "+", PROF_OFFSET_X - 8, PROF_OFFSET_Y + 5, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_proficiency_bonus"), PROF_OFFSET_X + 20, PROF_OFFSET_Y + 5, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_class"), CLASS_OFFSET_X, CLASS_OFFSET_Y - 10, lightColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_race"), RACE_OFFSET_X, RACE_OFFSET_Y - 10, lightColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_background"), BACKG_OFFSET_X, BACKG_OFFSET_Y - 10, lightColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_hitdice"), HITDICE_OFFSET_X, HITDICE_OFFSET_Y - 10, lightColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_level"), LEVEL_OFFSET_X, LEVEL_OFFSET_Y - 10, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_hunger"), HUNGER_OFFSET_X, HUNGER_OFFSET_Y - 10, AUTO_FIELD_COLOR, false);

				guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_initiative"), INITIATIVE_OFFSET_X + 8, INITIATIVE_OFFSET_Y - 15, lightColor);
				break;
			case SKILLS:
				//STRENGTH
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_athletics"), SKILL_LIST1_OFFSET_X, SKILL_LIST1_OFFSET_Y, lightColor, false);

				//DEXTERITY
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_acrobatics"), SKILL_LIST1_OFFSET_X, SKILL_LIST1_OFFSET_Y + SKILL_SEPARATION, darkColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_sleightofhand"), SKILL_LIST1_OFFSET_X, SKILL_LIST1_OFFSET_Y + SKILL_SEPARATION*2, darkColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_stealth"), SKILL_LIST1_OFFSET_X, SKILL_LIST1_OFFSET_Y + SKILL_SEPARATION*3, darkColor, false);

				//INTELLIGENCE
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_arcana"), SKILL_LIST1_OFFSET_X, SKILL_LIST1_OFFSET_Y + SKILL_SEPARATION*4, lightColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_history"), SKILL_LIST1_OFFSET_X, SKILL_LIST1_OFFSET_Y + SKILL_SEPARATION*5, lightColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_investigation"), SKILL_LIST1_OFFSET_X, SKILL_LIST1_OFFSET_Y + SKILL_SEPARATION*6, lightColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_nature"), SKILL_LIST1_OFFSET_X, SKILL_LIST1_OFFSET_Y + SKILL_SEPARATION*7, lightColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_religion"), SKILL_LIST1_OFFSET_X, SKILL_LIST1_OFFSET_Y + SKILL_SEPARATION*8, lightColor, false);

				//WISDOM
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_animalhandling"), SKILL_LIST2_OFFSET_X, SKILL_LIST2_OFFSET_Y, lightColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_insight"), SKILL_LIST2_OFFSET_X, SKILL_LIST2_OFFSET_Y + SKILL_SEPARATION, lightColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_medicine"), SKILL_LIST2_OFFSET_X, SKILL_LIST2_OFFSET_Y + SKILL_SEPARATION*2, lightColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_perception"), SKILL_LIST2_OFFSET_X, SKILL_LIST2_OFFSET_Y + SKILL_SEPARATION*3, lightColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_survival"), SKILL_LIST2_OFFSET_X, SKILL_LIST2_OFFSET_Y + SKILL_SEPARATION*4, lightColor, false);

				//CHARISMA
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_deception"), SKILL_LIST2_OFFSET_X, SKILL_LIST2_OFFSET_Y + SKILL_SEPARATION*5, lightColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_intimidation"), SKILL_LIST2_OFFSET_X, SKILL_LIST2_OFFSET_Y + SKILL_SEPARATION*6, lightColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_performance"), SKILL_LIST2_OFFSET_X, SKILL_LIST2_OFFSET_Y + SKILL_SEPARATION*7, lightColor, false);
				guiGraphics.drawString(this.font, Component.translatable("gui.dndsheets.character_sheet.label_skill_persuasion"), SKILL_LIST2_OFFSET_X, SKILL_LIST2_OFFSET_Y + SKILL_SEPARATION*8, lightColor, false);
				break;
		}

	}

	@Override
	public void onClose() {
		super.onClose();
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
		DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetRollButtonMessage(category, index, subIndex, x, y, z));
		SheetRollButtonMessage.handle(entity, category, index, subIndex, x, y, z);
		
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
				e.setImage( new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_tabbutton.png"), 0, 0, 15, 50, 30);
				e.active = true;
			}
			else {
				e.setY(this.topPos - 17);
				e.setHeight(20);
				e.setImage( new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_tabbutton_active.png"), 0, 0, 20, 50, 40);
				e.active = false;
			}
		});

		//Side Panel
		checkButtons.forEach((e) -> {
			e.active = !editMode;
			e.visible = !editMode;
		});
		saveButtons.forEach((e) -> {
			e.active = !editMode;
			e.visible = !editMode;
		});
		checkEditButtons.forEach((e) -> {
			e.active = editMode;
			e.visible = editMode;
		});
		saveEditButtons.forEach((e) -> {
			e.active = editMode;
			e.visible = editMode;
		});

		//Main Tab
		//A diferencia de las pestañas Skills/Attacks (más abajo), a estos campos antes solo se les tocaba
		//"active" y nunca "visible": cambiar de pestaña los dejaba deshabilitados pero seguían dibujándose
		//encima de Skills/Attacks en su misma posición de pantalla — la UI superpuesta reportada en
		//AUDIT_UX.md. EditBox.visible arranca en true y nunca se apagaba.
		isActive = panelActive == PanelStatus.MAIN;
		hitPoints.active = isActive;
		hitPoints.visible = isActive;
		hitPointsTemp.active = isActive;
		hitPointsTemp.visible = isActive;
		hitPointsMax.active = isActive;
		hitPointsMax.visible = isActive;
		armorClass.active = isActive;
		armorClass.visible = isActive;
		characterRace.active = isActive;
		characterRace.visible = isActive;
		characterClass.active = isActive;
		characterClass.visible = isActive;
		background.active = isActive;
		background.visible = isActive;
		speed.active = isActive;
		speed.visible = isActive;
		proficiency.active = isActive;
		proficiency.visible = isActive;
		hitDice.active = isActive;
		hitDice.visible = isActive;
		hitDiceTypes.active = isActive;
		hitDiceTypes.visible = isActive;
		grimoireButton.active = isActive;
		grimoireButton.visible = isActive;
		presetsButton.active = isActive;
		presetsButton.visible = isActive;

		isActive = panelActive == PanelStatus.MAIN && !editMode;
		initiativeButton.active = isActive;
		initiativeButton.visible = isActive;
		isActive = panelActive == PanelStatus.MAIN && editMode;
		initiativeEditButton.active = isActive;
		initiativeEditButton.visible = isActive;

		//Skill Tab
		skillButtons.forEach((e) -> {
			boolean isActiveL = panelActive == PanelStatus.SKILLS && !editMode;
            e.active = isActiveL;
			e.visible = isActiveL;
		});
		skillEditButtons.forEach((e) -> {
			boolean isActiveL = panelActive == PanelStatus.SKILLS && editMode;
			e.active = isActiveL;
			e.visible = isActiveL;
		});

		//Attack Tab
		isActive = panelActive == PanelStatus.ATTACKS;
		attackRolls.setActive(isActive);
		attackRolls.setEditMode(editMode);

		addButton.active = isActive;
		addButton.visible = isActive;
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
	private void makeRollButton(String guistateKey, int x, int y, int category, int index, boolean isSave, List<ImageButton> rollButtonList, List<ImageButton> editButtonList) {
		ImageButton rollButton = new ImageButton(this.leftPos + x, this.topPos + y, 16, 16, 0, 0, 16, new ResourceLocation(!isSave ? "dndsheets:textures/screens/atlas/imagebutton_d20.png" : "dndsheets:textures/screens/atlas/imagebutton_d20_save.png"), 16, 32, e -> {
			sendRoll(category, index, 0);
		});
		guistate.put(guistateKey, rollButton);
		this.addRenderableWidget(rollButton);

		ImageButton editButton = new ImageButton(this.leftPos + x, this.topPos + y, 16, 16, 0, 0, 16, new ResourceLocation(!isSave ? "dndsheets:textures/screens/atlas/imagebutton_d20_edit.png" : "dndsheets:textures/screens/atlas/imagebutton_d20_save_edit.png"), 16, 32, e -> {
			CharacterSheetSaveProcedure.execute(guistate);
			RollEditorScreen.workingCategory = category;
			RollEditorScreen.workingIndex = index;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new RollEditorOpenMessage());
		});
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


			ImageButton rollButton = new ImageButton(0, 0, 16, 16, 0, 0, 16, new ResourceLocation(imgLocation), 16, 32, e -> {
				int btnIndex = scrollList.getIndex(e);
				sendRoll(category, btnIndex, subIndex);
			});
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
			this.addWidget(editButton);
			editButtons.add(editButton);
		}

		ImageButton deleteButton = new ImageButton(0, 0, 8, 8, 0, 0, 8, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_delete.png"), 8, 16, e -> {
			int removedIndex = scrollList.removeListItem(e);
			this.removeWidget(nameBox);
			rollButtons.forEach(this::removeWidget);
			editButtons.forEach(this::removeWidget);
			this.removeWidget(e);

			JsonObject sheet = SheetLoader.getClientSheet();
			SheetLoader.validateSheet(sheet);
			JsonArray arr = sheet.getAsJsonArray(RollIndex.Category.fromInt(category).toString());
			arr.remove(removedIndex);
		});
		this.addWidget(deleteButton);

		scrollList.addListItem(nameBox, rollButtons, editButtons, deleteButton);
	}

	private void initSidePanel() {

		characterName = new EditBox(this.font, this.leftPos + NAME_OFFSET_X, this.topPos + NAME_OFFSET_Y, 85, 18, Component.translatable("gui.dndsheets.character_sheet.charactername")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.charactername").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.charactername").getString());
				else
					setSuggestion(null);
			}
		};
		characterName.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.charactername").getString());
		characterName.setMaxLength(50);
		guistate.put("text:charactername", characterName);
		this.addWidget(this.characterName);

		/*
			ABILITY SCORES
		 */
		strength = new EditBox(this.font, this.leftPos + ABILITY_OFFSET_X, this.topPos + ABILITY_OFFSET_Y, ABILITY_SIZE_X, ABILITY_SIZE_Y, Component.translatable("gui.dndsheets.character_sheet.strength")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.strength").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.strength").getString());
				else
					setSuggestion(null);
			}
		};
		strength.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.strength").getString());
		strength.setMaxLength(2);
		guistate.put("text:strength", strength);
		this.addWidget(this.strength);

		dexterity = new EditBox(this.font, this.leftPos + ABILITY_OFFSET_X, this.topPos + ABILITY_SEPARATION + ABILITY_OFFSET_Y, ABILITY_SIZE_X, ABILITY_SIZE_Y, Component.translatable("gui.dndsheets.character_sheet.dexterity")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.dexterity").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.dexterity").getString());
				else
					setSuggestion(null);
			}
		};
		dexterity.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.dexterity").getString());
		dexterity.setMaxLength(2);
		guistate.put("text:dexterity", dexterity);
		this.addWidget(this.dexterity);

		constitution = new EditBox(this.font, this.leftPos + ABILITY_OFFSET_X, this.topPos + ABILITY_SEPARATION*2 + ABILITY_OFFSET_Y, ABILITY_SIZE_X, ABILITY_SIZE_Y, Component.translatable("gui.dndsheets.character_sheet.constitution")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.constitution").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.constitution").getString());
				else
					setSuggestion(null);
			}
		};
		constitution.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.constitution").getString());
		constitution.setMaxLength(2);
		guistate.put("text:constitution", constitution);
		this.addWidget(this.constitution);

		intelligence = new EditBox(this.font, this.leftPos + ABILITY_OFFSET_X, this.topPos + ABILITY_SEPARATION*3 + ABILITY_OFFSET_Y, ABILITY_SIZE_X, ABILITY_SIZE_Y, Component.translatable("gui.dndsheets.character_sheet.intelligence")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.intelligence").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.intelligence").getString());
				else
					setSuggestion(null);
			}
		};
		intelligence.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.intelligence").getString());
		intelligence.setMaxLength(2);
		guistate.put("text:intelligence", intelligence);
		this.addWidget(this.intelligence);

		wisdom = new EditBox(this.font, this.leftPos + ABILITY_OFFSET_X, this.topPos + ABILITY_SEPARATION*4 + ABILITY_OFFSET_Y, ABILITY_SIZE_X, ABILITY_SIZE_Y, Component.translatable("gui.dndsheets.character_sheet.wisdom")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.wisdom").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.wisdom").getString());
				else
					setSuggestion(null);
			}
		};
		wisdom.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.wisdom").getString());
		wisdom.setMaxLength(2);
		guistate.put("text:wisdom", wisdom);
		this.addWidget(this.wisdom);

		charisma = new EditBox(this.font, this.leftPos + ABILITY_OFFSET_X, this.topPos + ABILITY_SEPARATION*5 + ABILITY_OFFSET_Y, ABILITY_SIZE_X, ABILITY_SIZE_Y, Component.translatable("gui.dndsheets.character_sheet.charisma")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.charisma").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.charisma").getString());
				else
					setSuggestion(null);
			}
		};
		charisma.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.charisma").getString());
		charisma.setMaxLength(2);
		guistate.put("text:charisma", charisma);
		this.addWidget(this.charisma);

		/*
			MAIN ROLL BUTTONS
		 */

		int checkBtnOffset = -42;
		int saveBtnOffset = -24;

		//STR
		makeRollButton("button:roll_str", ABILITY_OFFSET_X+checkBtnOffset, ABILITY_OFFSET_Y, 0, 0, false, checkButtons, checkEditButtons);
		makeRollButton("button:roll_str_save", ABILITY_OFFSET_X+saveBtnOffset, ABILITY_OFFSET_Y, 1, 0, true, saveButtons, saveEditButtons);
		//DEX
		makeRollButton("button:roll_dex", ABILITY_OFFSET_X+checkBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION, 0, 1, false, checkButtons, checkEditButtons);
		makeRollButton("button:roll_dex_save", ABILITY_OFFSET_X+saveBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION, 1, 1, true, saveButtons, saveEditButtons);
		//CON
		makeRollButton("button:roll_con", ABILITY_OFFSET_X+checkBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION*2, 0, 2, false, checkButtons, checkEditButtons);
		makeRollButton("button:roll_con_save", ABILITY_OFFSET_X+saveBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION*2, 1, 2, true, saveButtons, saveEditButtons);
		//INT
		makeRollButton("button:roll_int", ABILITY_OFFSET_X+checkBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION*3, 0, 3, false, checkButtons, checkEditButtons);
		makeRollButton("button:roll_int_save", ABILITY_OFFSET_X+saveBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION*3, 1, 3, true, saveButtons, saveEditButtons);
		//WIS
		makeRollButton("button:roll_wis", ABILITY_OFFSET_X+checkBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION*4, 0, 4, false, checkButtons, checkEditButtons);
		makeRollButton("button:roll_wis_save", ABILITY_OFFSET_X+saveBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION*4, 1, 4, true, saveButtons, saveEditButtons);;
		//CHA
		makeRollButton("button:roll_cha", ABILITY_OFFSET_X+checkBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION*5, 0, 5, false, checkButtons, checkEditButtons);
		makeRollButton("button:roll_cha_save", ABILITY_OFFSET_X+saveBtnOffset, ABILITY_OFFSET_Y+ABILITY_SEPARATION*5, 1, 5, true, saveButtons, saveEditButtons);

	}

	private void initMainPanel() {
		/*
			TEXT FIELDS
		 */

		hitPoints = new EditBox(this.font, this.leftPos + ACHP_OFFSET_X + ACHP_SEPARATION, this.topPos + ACHP_OFFSET_Y, 32, 18, Component.translatable("gui.dndsheets.character_sheet.hitpoints")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.hitpoints").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.hitpoints").getString());
				else
					setSuggestion(null);
			}
		};
		hitPoints.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.hitpoints").getString());
		hitPoints.setMaxLength(4);
		guistate.put("text:hitpoints", hitPoints);
		this.addWidget(this.hitPoints);

		hitPointsTemp = new EditBox(this.font, this.leftPos + ACHP_OFFSET_X + ACHP_SEPARATION*3, this.topPos + ACHP_OFFSET_Y, 32, 18, Component.translatable("gui.dndsheets.character_sheet.hitpoints_temp")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.hitpoints_temp").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.hitpoints_temp").getString());
				else
					setSuggestion(null);
			}
		};
		hitPointsTemp.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.hitpoints_temp").getString());
		hitPointsTemp.setMaxLength(4);
		guistate.put("text:hitpoints_temp", hitPointsTemp);
		this.addWidget(this.hitPointsTemp);

		hitPointsMax = new EditBox(this.font, this.leftPos + ACHP_OFFSET_X + ACHP_SEPARATION*2, this.topPos + ACHP_OFFSET_Y, 32, 18, Component.translatable("gui.dndsheets.character_sheet.hitpoints_max")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.hitpoints_max").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.hitpoints_max").getString());
				else
					setSuggestion(null);
			}
		};
		hitPointsMax.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.hitpoints_max").getString());
		hitPointsMax.setMaxLength(4);
		guistate.put("text:hitpoints_max", hitPointsMax);
		this.addWidget(this.hitPointsMax);

		armorClass = new EditBox(this.font, this.leftPos + ACHP_OFFSET_X, this.topPos + ACHP_OFFSET_Y, 32, 18, Component.translatable("gui.dndsheets.character_sheet.armorclass")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.armorclass").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.armorclass").getString());
				else
					setSuggestion(null);
			}
		};
		armorClass.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.armorclass").getString());
		armorClass.setMaxLength(2);
		guistate.put("text:armorclass", armorClass);
		this.addWidget(this.armorClass);

		speed = new EditBox(this.font, this.leftPos + ACHP_OFFSET_X + ACHP_SEPARATION*4, this.topPos + ACHP_OFFSET_Y, 32, 18, Component.translatable("gui.dndsheets.character_sheet.speed")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.speed").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.speed").getString());
				else
					setSuggestion(null);
			}
		};
		speed.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.speed").getString());
		speed.setMaxLength(2);
		guistate.put("text:speed", speed);
		this.addWidget(this.speed);

		proficiency = new EditBox(this.font, this.leftPos + PROF_OFFSET_X, this.topPos + PROF_OFFSET_Y, 14, 18, Component.translatable("gui.dndsheets.character_sheet.proficiency")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.proficiency").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.proficiency").getString());
				else
					setSuggestion(null);
			}
		};
		proficiency.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.proficiency").getString());
		proficiency.setMaxLength(1);
		guistate.put("text:proficiency", proficiency);
		this.addWidget(this.proficiency);

		// --- Campos derivados del jugador real: nivel y hambre ---
		// NOTA: las posiciones (X/Y) son un punto de partida; ajústalas contra tu textura
		// de fondo (character_sheet.png) para que encajen visualmente con el resto del panel.
		level = new EditBox(this.font, this.leftPos + LEVEL_OFFSET_X, this.topPos + LEVEL_OFFSET_Y, 20, 18, Component.translatable("gui.dndsheets.character_sheet.level"));
		level.setMaxLength(2);
		guistate.put("text:level", level);
		this.addWidget(this.level);

		hunger = new EditBox(this.font, this.leftPos + HUNGER_OFFSET_X, this.topPos + HUNGER_OFFSET_Y, 24, 18, Component.translatable("gui.dndsheets.character_sheet.hunger"));
		hunger.setMaxLength(2);
		guistate.put("text:hunger", hunger);
		this.addWidget(this.hunger);

		// Estos campos ahora reflejan el estado real del jugador (ver syncFromEntity()),
		// así que se bloquean para que no se puedan editar a mano y queden desincronizados.
		// El color ámbar los distingue de un vistazo de los campos que sí se pueden escribir a mano.
		EditBox[] autoFields = {hitPoints, hitPointsMax, hitPointsTemp, proficiency, level, hunger, armorClass};
		for (EditBox autoField : autoFields) {
			autoField.setEditable(false);
			autoField.setTextColorUneditable(AUTO_FIELD_COLOR);
		}

		characterRace = new EditBox(this.font, this.leftPos + RACE_OFFSET_X, this.topPos + RACE_OFFSET_Y, 100, 18, Component.translatable("gui.dndsheets.character_sheet.characterrace")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.characterrace").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.characterrace").getString());
				else
					setSuggestion(null);
			}
		};
		characterRace.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.characterrace").getString());
		characterRace.setMaxLength(50);
		guistate.put("text:characterrace", characterRace);
		this.addWidget(this.characterRace);

		background = new EditBox(this.font, this.leftPos + BACKG_OFFSET_X, this.topPos + BACKG_OFFSET_Y, 100, 18, Component.translatable("gui.dndsheets.character_sheet.background")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.background").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.background").getString());
				else
					setSuggestion(null);
			}
		};
		background.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.background").getString());
		background.setMaxLength(50);
		guistate.put("text:background", background);
		this.addWidget(this.background);

		characterClass = new EditBox(this.font, this.leftPos + CLASS_OFFSET_X, this.topPos + CLASS_OFFSET_Y, 210, 18, Component.translatable("gui.dndsheets.character_sheet.characterclass")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.characterclass").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.characterclass").getString());
				else
					setSuggestion(null);
			}
		};
		characterClass.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.characterclass").getString());
		characterClass.setMaxLength(100);
		guistate.put("text:characterclass", characterClass);
		this.addWidget(this.characterClass);

		hitDiceTypes = new EditBox(this.font, this.leftPos + HITDICE_OFFSET_X + 30, this.topPos + HITDICE_OFFSET_Y, 100, 18, Component.translatable("gui.dndsheets.character_sheet.hitdice_types")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.hitdice_types").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.hitdice_types").getString());
				else
					setSuggestion(null);
			}
		};
		hitDiceTypes.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.hitdice_types").getString());
		hitDiceTypes.setMaxLength(50);
		guistate.put("text:hitdice_types", hitDiceTypes);
		this.addWidget(this.hitDiceTypes);

		hitDice = new EditBox(this.font, this.leftPos + HITDICE_OFFSET_X, this.topPos + HITDICE_OFFSET_Y, 20, 18, Component.translatable("gui.dndsheets.character_sheet.hitdice")) {
			@Override
			public void insertText(String text) {
				super.insertText(text);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.hitdice").getString());
				else
					setSuggestion(null);
			}

			@Override
			public void moveCursorTo(int pos) {
				super.moveCursorTo(pos);
				if (getValue().isEmpty())
					setSuggestion(Component.translatable("gui.dndsheets.character_sheet.hitdice").getString());
				else
					setSuggestion(null);
			}
		};
		hitDice.setSuggestion(Component.translatable("gui.dndsheets.character_sheet.hitdice").getString());
		hitDice.setMaxLength(2);
		guistate.put("text:hitdice", hitDice);
		this.addWidget(this.hitDice);

		initiativeButton = new ImageButton(this.leftPos + INITIATIVE_OFFSET_X, this.topPos + INITIATIVE_OFFSET_Y, 16, 16, 0, 0, 16, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_d20.png"), 16, 32, e -> {
			sendRoll(0, 6, 0);
		});
		guistate.put("button:roll_init", initiativeButton);
		this.addRenderableWidget(initiativeButton);

		initiativeEditButton = new ImageButton(this.leftPos + INITIATIVE_OFFSET_X, this.topPos + INITIATIVE_OFFSET_Y, 16, 16, 0, 0, 16, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_d20_edit.png"), 16, 32, e -> {
			CharacterSheetSaveProcedure.execute(guistate);
			RollEditorScreen.workingCategory = 0;
			RollEditorScreen.workingIndex = 6;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new RollEditorOpenMessage());
		});
		guistate.put("button:roll_init_edit", initiativeEditButton);
		this.addRenderableWidget(initiativeEditButton);

		//NOTA: sin hueco dibujado en la textura todavía. Puestos en el margen inferior (y=214), debajo de
		//todo lo demás, para no pisar el círculo de Iniciativa (que ocupa la zona x=270-345, y=90-200).
		grimoireButton = Button.builder(Component.translatable("gui.dndsheets.character_sheet.grimoire"), b -> this.minecraft.setScreen(new GrimoireScreen()))
			.bounds(this.leftPos + 125, this.topPos + 214, 100, 20).build();
		guistate.put("button:grimoire", grimoireButton);
		this.addRenderableWidget(grimoireButton);

		presetsButton = Button.builder(Component.translatable("gui.dndsheets.character_sheet.presets"), b -> {
			CharacterSheetSaveProcedure.execute(guistate);
			DndsheetsMod.PACKET_HANDLER.sendToServer(new PresetListRequestMessage());
		}).bounds(this.leftPos + 230, this.topPos + 214, 100, 20).build();
		guistate.put("button:presets", presetsButton);
		this.addRenderableWidget(presetsButton);
	}

	private void initSkillPanel() {
		/*
			SKILL ROLL BUTTONS
		 */

		int skillBtnOffsetX = -20;
		int skillBtnOffsetY = -5;

		//STR
		makeRollButton("button:roll_athletics", SKILL_LIST1_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST1_OFFSET_Y, 2, 0, false, skillButtons, skillEditButtons);

		//DEX
		makeRollButton("button:roll_acrobatics", SKILL_LIST1_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST1_OFFSET_Y+SKILL_SEPARATION, 2, 1, false, skillButtons, skillEditButtons);
		makeRollButton("button:roll_sleightofhand", SKILL_LIST1_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST1_OFFSET_Y+SKILL_SEPARATION*2, 2, 2, false, skillButtons, skillEditButtons);
		makeRollButton("button:roll_stealth", SKILL_LIST1_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST1_OFFSET_Y+SKILL_SEPARATION*3, 2, 3, false, skillButtons, skillEditButtons);

		//INT
		makeRollButton("button:roll_arcana", SKILL_LIST1_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST1_OFFSET_Y+SKILL_SEPARATION*4, 2, 4, false, skillButtons, skillEditButtons);
		makeRollButton("button:roll_history", SKILL_LIST1_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST1_OFFSET_Y+SKILL_SEPARATION*5, 2, 5, false, skillButtons, skillEditButtons);
		makeRollButton("button:roll_investigation", SKILL_LIST1_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST1_OFFSET_Y+SKILL_SEPARATION*6, 2, 6, false, skillButtons, skillEditButtons);
		makeRollButton("button:roll_nature", SKILL_LIST1_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST1_OFFSET_Y+SKILL_SEPARATION*7, 2, 7, false, skillButtons, skillEditButtons);
		makeRollButton("button:roll_religion", SKILL_LIST1_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST1_OFFSET_Y+SKILL_SEPARATION*8, 2, 8, false, skillButtons, skillEditButtons);

		//WIS
		makeRollButton("button:roll_animalhandling", SKILL_LIST2_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST2_OFFSET_Y, 2, 9, false, skillButtons, skillEditButtons);
		makeRollButton("button:roll_insight", SKILL_LIST2_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST2_OFFSET_Y+SKILL_SEPARATION, 2, 10, false, skillButtons, skillEditButtons);
		makeRollButton("button:roll_medicine", SKILL_LIST2_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST2_OFFSET_Y+SKILL_SEPARATION*2, 2, 11, false, skillButtons, skillEditButtons);
		makeRollButton("button:roll_perception", SKILL_LIST2_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST2_OFFSET_Y+SKILL_SEPARATION*3, 2, 12, false, skillButtons, skillEditButtons);
		makeRollButton("button:roll_survival", SKILL_LIST2_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST2_OFFSET_Y+SKILL_SEPARATION*4, 2, 13, false, skillButtons, skillEditButtons);

		//CHA
		makeRollButton("button:roll_deception", SKILL_LIST2_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST2_OFFSET_Y+SKILL_SEPARATION*5, 2, 14, false, skillButtons, skillEditButtons);
		makeRollButton("button:roll_intimidation", SKILL_LIST2_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST2_OFFSET_Y+SKILL_SEPARATION*6, 2, 15, false, skillButtons, skillEditButtons);
		makeRollButton("button:roll_performance", SKILL_LIST2_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST2_OFFSET_Y+SKILL_SEPARATION*7, 2, 16, false, skillButtons, skillEditButtons);
		makeRollButton("button:roll_persuasion", SKILL_LIST2_OFFSET_X+skillBtnOffsetX, skillBtnOffsetY+SKILL_LIST2_OFFSET_Y+SKILL_SEPARATION*8, 2, 17, false, skillButtons, skillEditButtons);

	}

	private void initAttackPanel() {

		attackRolls = makeScrollList("scrolllist:attack_rolls", this.leftPos + 125, this.topPos + 12, 210, 151);
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
		this.addRenderableWidget(addButton);
	}

	@Override
	public void init() {
		super.init();

		mainTab = new AdjustableImageButton(this.leftPos + 15, this.topPos - 12, 50, 15, 0, 0, 15, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_tabbutton.png"), 50, 30, e -> {
			panelActive = PanelStatus.MAIN;
			updateTabs();

		}, Component.translatable("gui.dndsheets.character_sheet.main_tab"));
		this.addRenderableWidget(mainTab);

		skillsTab = new AdjustableImageButton(this.leftPos + 65, this.topPos - 12, 50, 15, 0, 0, 15, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_tabbutton.png"), 50, 30, e -> {
			panelActive = PanelStatus.SKILLS;
			updateTabs();

		}, Component.translatable("gui.dndsheets.character_sheet.skills_tab"));
		this.addRenderableWidget(skillsTab);

		attacksTab = new AdjustableImageButton(this.leftPos + 115, this.topPos - 12, 50, 15, 0, 0, 15, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_tabbutton.png"), 50, 30, e -> {
			panelActive = PanelStatus.ATTACKS;
			updateTabs();

		}, Component.translatable("gui.dndsheets.character_sheet.attacks_tab"));
		this.addRenderableWidget(attacksTab);

		ImageButton editToggle = new ImageButton(this.leftPos - 6, this.topPos + 192, 16, 16, 0, 0, 16, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_editmode.png"), 16, 32, e -> {
			editMode = !editMode;
			updateTabs();
		});
		guistate.put("button:edit_toggle", editToggle);
		this.addRenderableWidget(editToggle);

		initSidePanel();
		initAttackPanel();
		initMainPanel();
		initSkillPanel();

		updateTabs();
		CharacterSheetLoadProcedure.execute(guistate, this);
	}
}
