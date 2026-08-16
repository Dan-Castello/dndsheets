package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.client.gui.components.TomeButton;
import net.hawthorn.dndsheets.client.gui.components.TomeField;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.hawthorn.dndsheets.CharacterOptionsRegistry;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.RollIndex;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.client.gui.components.AdjustableImageButton;
import net.hawthorn.dndsheets.client.gui.components.RollScrollWidget;
import net.hawthorn.dndsheets.init.DndsheetsModKeyMappings;
import net.hawthorn.dndsheets.network.AdvancedRollEditorOpenMessage;
import net.hawthorn.dndsheets.network.CharacterOptionsRequestMessage;
import net.hawthorn.dndsheets.network.PresetListRequestMessage;
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
	private final Level world;
	private final int x, y, z;
	private final Player entity;
	//private: verificado que ningún otro archivo del mod lee/escribe estos dos campos (solo se usan dentro
	//de esta clase) — no había motivo para que fueran public static y quedaran mutables desde cualquier
	//mod externo en el classpath.
	private static PanelStatus panelActive = PanelStatus.MAIN;
	private static boolean editMode = false;

	EditBox hitPoints;      // Sincronizado en vivo desde entity.getHealth() - ver containerTick()
	EditBox hitPointsMax;   // Sincronizado en vivo desde entity.getMaxHealth()
	EditBox hitPointsTemp;  // Sincronizado en vivo desde entity.getAbsorptionAmount() (corazones dorados = PG temporales de D&D)
	EditBox armorClass;
	EditBox speed;
	EditBox characterName;
	EditBox characterRace;
	EditBox characterClass;
	EditBox background;
	EditBox proficiency;    // Auto-calculado desde el nivel real (regla de competencia de 5e)
	EditBox level;          // Sincronizado en vivo desde entity.experienceLevel (XP real de Minecraft)
	EditBox hunger;         // Sincronizado en vivo desde entity.getFoodData().getFoodLevel()
	Button grimoireButton;  // Abre el Grimorio (ver GrimoireScreen), sin tocar la hoja
	Button presetsButton;   // Pide la lista de presets de clase al servidor (ver PresetScreen)
	Button guideButton;     // Abre la guía del mod (ver GuideBook), páginas de DM incluidas si el cliente es op

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

	private final int NAME_OFFSET_X = 15;
	private final int NAME_OFFSET_Y = 20;

	/*
		RETÍCULA DEL PANEL PRINCIPAL

		Antes esto eran veinte números sueltos, cada uno ajustado a mano contra la textura: las filas caían
		en y = 20, 55, 90, 125, 165, 205 (ritmo 35, 35, 35, 40, 40) y las columnas en x = 125, 220, 235,
		304 sin relación entre ellas. Mover un campo obligaba a recolocar sus vecinos a ojo.

		Ahora todo sale de la retícula de aquí abajo: cuatro filas de campos repartidas en tres secciones
		con cabecera. Cada fila es RÓTULO (8 px de alto) + CAMPO (18), y cada sección abre con su título y
		un filete de latón. Lo que se gana no es solo orden: doce rótulos sueltos sobre un pergamino en
		blanco no tienen jerarquía, y agrupados sí se encuentran de un vistazo.

		Para tocar el alto de una fila se cambia ROW_STEP, no seis constantes.
	 */
	//350x240, no 350x200: el alto subió cuando Nivel/Hambre y los botones de página dejaron de caber. La
	//retícula de más abajo tiene que caber DENTRO de esto, y el bloque static de después lo comprueba.
	private static final int SHEET_WIDTH = 350;
	private static final int SHEET_HEIGHT = 240;

	private static final int PANEL_X = 122;      //Primera columna útil: el filete del fondo cae en x=114.
	private static final int PANEL_RIGHT = 340;  //Último píxel útil dentro del ancho de 350.
	private static final int FIELD_H = 18;
	private static final int LABEL_GAP = 10;     //Hueco del rótulo encima de su campo.
	private static final int ROW_STEP = 36;      //De un campo al siguiente dentro de una sección.
	private static final int SECTION_STEP = 20;  //Del último campo de una sección a la cabecera siguiente.
	private static final int HEADING_STEP = 22;  //De la cabecera de sección al rótulo de su primera fila.

	//--- Sección 1: identidad ---
	private static final int SEC1_Y = 8;
	private static final int ROW1_Y = SEC1_Y + HEADING_STEP;
	//--- Sección 2: combate ---
	private static final int SEC2_Y = ROW1_Y + FIELD_H + SECTION_STEP;
	private static final int ROW2_Y = SEC2_Y + HEADING_STEP;
	private static final int ROW3_Y = ROW2_Y + ROW_STEP;
	//--- Sección 3: recursos ---
	private static final int SEC3_Y = ROW3_Y + FIELD_H + SECTION_STEP;
	private static final int ROW4_Y = SEC3_Y + HEADING_STEP;

	//Fila 1 — Raza | Clase | Trasfondo. Tres huecos iguales que llenan el ancho del panel.
	private static final int IDENTITY_W = 70;
	private static final int IDENTITY_STEP = 74;
	private final int RACE_OFFSET_X = PANEL_X;
	private final int RACE_OFFSET_Y = ROW1_Y;
	private final int CLASS_OFFSET_X = PANEL_X + IDENTITY_STEP;
	private final int CLASS_OFFSET_Y = ROW1_Y;
	private final int BACKG_OFFSET_X = PANEL_X + IDENTITY_STEP * 2;
	private final int BACKG_OFFSET_Y = ROW1_Y;

	//Fila 2 — CA | PG | PG Máx | PG Temp. Velocidad SALE de este grupo: con cinco huecos, el rótulo del
	//quinto ("Velocidad", 54 px) empezaba en x=305 y terminaba en 359, fuera del panel de 350. Y además no
	//es un número de combate de la misma familia; encaja mejor junto a Iniciativa.
	private final int ACHP_OFFSET_X = PANEL_X;
	private final int ACHP_OFFSET_Y = ROW2_Y;
	private final int ACHP_SEPARATION = 54;

	//Fila 3 — Velocidad | Competencia | Iniciativa.
	private final int SPEED_OFFSET_X = PANEL_X;
	private final int SPEED_OFFSET_Y = ROW3_Y;
	private final int PROF_OFFSET_X = PANEL_X + 72;
	private final int PROF_OFFSET_Y = ROW3_Y;
	private final int INITIATIVE_OFFSET_X = PANEL_X + 152;
	private final int INITIATIVE_OFFSET_Y = ROW3_Y;

	//Fila 4 — Nivel | Hambre | Dados de Golpe (dado + tipos).
	private final int LEVEL_OFFSET_X = PANEL_X;
	private final int LEVEL_OFFSET_Y = ROW4_Y;
	private final int HUNGER_OFFSET_X = PANEL_X + 46;
	private final int HUNGER_OFFSET_Y = ROW4_Y;
	private final int HITDICE_OFFSET_X = PANEL_X + 98;
	private final int HITDICE_OFFSET_Y = ROW4_Y;
	private static final int HITDICE_TYPES_W = PANEL_RIGHT - (PANEL_X + 98 + 26);

	//Botones de página (Grimorio, Presets, Guía): centrados en el ancho completo, no en el panel derecho,
	//porque son acciones de la hoja entera y no de una sección. 80*3 + 10*2 = 260, (350-260)/2 = 45.
	private static final int BOTTOM_BUTTON_WIDTH = 80;
	private static final int BOTTOM_BUTTON_HEIGHT = 16;
	private static final int BOTTOM_ROW_Y = ROW4_Y + FIELD_H + 12;
	private final int GRIMOIRE_OFFSET_X = 45;
	private final int GRIMOIRE_OFFSET_Y = BOTTOM_ROW_Y;
	private final int PRESETS_OFFSET_X = 135;
	private final int PRESETS_OFFSET_Y = BOTTOM_ROW_Y;
	private final int GUIDE_OFFSET_X = 225;
	private final int GUIDE_OFFSET_Y = BOTTOM_ROW_Y;

	/*
		RETÍCULA DE LA PESTAÑA DE HABILIDADES

		Las dieciocho habilidades se agrupan por característica, que es como funcionan en 5e y como se
		imprimen en una hoja de verdad. Esa agrupación ya estaba en el código, pero solo como comentarios
		(//STR, //DEX, //INT...): en pantalla eran dos columnas de nueve filas seguidas, sin decir de qué
		característica tira cada una.

		Los rótulos de cabecera NO son claves nuevas: son los mismos LABEL_ABILITY_* que ya usan las
		tiradas de característica del panel lateral, así que traducir uno traduce los dos sitios.
	 */
	private static final int SKILL_TOP = 10;
	private static final int SKILL_ROW = 20;          //De una habilidad a la siguiente.
	private static final int SKILL_GROUP_STEP = 14;   //Lo que ocupa una cabecera de grupo.
	private static final int SKILL_LABEL_GAP = 18;    //Del botón de tirada a su rótulo.

	//Columna 1 en x=116 (el filete del fondo cae en 114) y columna 2 en 224. La segunda va más a la derecha
	//de lo que parecería simétrico a propósito: sus rótulos son los largos ("Trato con Animales" mide 108
	//px), y con las columnas a la misma anchura ese se salía del panel — llegaba a x=363 sobre un ancho de
	//350. Es un desbordamiento que solo aparecía en español.
	private static final int SKILL_COL1_X = 114;
	private static final int SKILL_COL2_X = 230;
	//El pergamino llega hasta x=364, así que el filete de las cabeceras se corta en 358 para dejar margen.
	//Las columnas van tan justas porque los rótulos en español casi llenan el ancho: entre "Juego de Manos"
	//(84 px) en la primera y "Trato con Animales" (108) en la segunda, más los botones de tirada, se comen
	//228 de los 244 disponibles. Por eso la columna 1 arranca pegada al borde del panel lateral — en esta
	//pestaña no hay filete vertical de fondo (solo lo lleva character_sheet.png, la principal).
	private static final int SKILL_RIGHT = 358;

	//Cuántas habilidades cuelgan de cada característica, en orden. Columna 1: Fuerza (1), Destreza (3),
	//Inteligencia (5). Columna 2: Sabiduría (5), Carisma (4).
	private static final int[] SKILL_COL1_GROUPS = {1, 3, 5};
	private static final int[] SKILL_COL2_GROUPS = {5, 4};

	/*
		RETÍCULA DE LA PESTAÑA DE ATAQUES

		La lista se alineaba con sus propios números (x=125, ancho 210) en vez de con el panel, así que
		quedaba unos píxeles descuadrada respecto a las otras dos pestañas. Ahora usa las mismas columnas.
		El alto deja sitio debajo para el botón de añadir.
	 */
	private static final int ATTACK_TOP = ROW1_Y;
	private static final int ATTACK_HEIGHT = 160;

	static {
		//La retícula se sale del panel con demasiada facilidad: pasó al escribirla (los botones de página
		//caían en y=246 sobre un panel de 240) y ya había pasado antes (y=228 sobre un fondo de 200). No
		//rompe nada, no avisa, y solo se ve abriendo la hoja — así que se comprueba al cargar la clase, con
		//las constantes de verdad, que es lo único que no puede quedarse desincronizado de ellas.
		int bottom = BOTTOM_ROW_Y + BOTTOM_BUTTON_HEIGHT;
		if (bottom > SHEET_HEIGHT) {
			throw new IllegalStateException("La retícula de la hoja llega a y=" + bottom
				+ " y el panel mide " + SHEET_HEIGHT + ". Sube SHEET_HEIGHT o baja ROW_STEP/SECTION_STEP.");
		}
		int rightmost = PANEL_X + IDENTITY_STEP * 2 + IDENTITY_W;
		if (rightmost > PANEL_RIGHT) {
			throw new IllegalStateException("La fila de identidad llega a x=" + rightmost
				+ " y el panel acaba en " + PANEL_RIGHT + ".");
		}

		int attackBottom = ATTACK_TOP + ATTACK_HEIGHT + 8 + 16;  //+8 de hueco, +16 del botón de añadir
		if (attackBottom > SHEET_HEIGHT) {
			throw new IllegalStateException("La lista de ataques y su botón llegan a y=" + attackBottom
				+ " y el panel mide " + SHEET_HEIGHT + ".");
		}

		//Las dos columnas de habilidades reparten NUEVE huecos cada una. Si alguien cambia los tamaños de
		//grupo y dejan de sumar nueve, skillRowY revienta al pedir un hueco que no existe — pero solo al
		//abrir esa pestaña y solo en la fila concreta que falte. Mejor que salte entero y aquí.
		for (int[] groups : new int[][] {SKILL_COL1_GROUPS, SKILL_COL2_GROUPS}) {
			int slots = 0;
			for (int size : groups) slots += size;
			if (slots != 9) {
				throw new IllegalStateException("Una columna de habilidades reparte " + slots
					+ " huecos y tienen que ser 9 (18 habilidades en dos columnas).");
			}
			int height = skillGroupY(groups, groups.length - 1)
				+ SKILL_GROUP_STEP + groups[groups.length - 1] * SKILL_ROW;
			if (height > SHEET_HEIGHT) {
				throw new IllegalStateException("Una columna de habilidades llega a y=" + height
					+ " y el panel mide " + SHEET_HEIGHT + ".");
			}
		}
	}

	//Color del texto de los campos que se rellenan solos (PG, CA, nivel, hambre, competencia): ámbar, para
	//distinguirlos de un vistazo de los campos en blanco normal que sí se pueden escribir a mano.
	//Tinta sobre pergamino: marrón muy oscuro en vez de negro puro, que sobre un fondo cálido se ve duro.
	private static final int INK_COLOR = 0x2A2118;
	/** Rótulo de las pestañas cerradas: van sobre cuero, así que el mismo pergamino apagado de TomeButton. */
	private static final int TAB_TEXT_CLOSED = 0xCBBA97;
	/** Cabeceras de sección: tinta aguada, para que titulen sin competir con los rótulos de los campos. */
	private static final int SECTION_COLOR = 0x6B5636;
	//Bandas de sección: negro y blanco a muy poca opacidad, no colores propios. Así se oscurecen y aclaran
	//el pergamino que tengan detrás sin pelearse con él si algún día cambia de tono.
	private static final int BAND_FILL = 0x12000000;
	private static final int BAND_LIGHT = 0x20FFFFFF;
	private static final int BAND_SHADOW = 0x22000000;
	//Ámbar quemado para lo que se rellena solo. El ámbar claro de antes (0xFFD37F) estaba pensado para un
	//fondo oscuro; sobre pergamino no tenía contraste suficiente para leerse.
	private static final int AUTO_FIELD_COLOR = 0x8A5A12;

	//Raza/Trasfondo/Clase eran texto libre: un jugador nuevo no tiene forma de adivinar qué escribir, y en
	//el caso de Clase encima importa de verdad (Config.hitDieFor, WarlockPactMagicManager,
	//WizardArcaneRecoveryManager y WeaponDefault.allowsClass comparan por subcadena contra characterClass;
	//un typo o "Warlock" en vez de "Brujo" hacía fallar esa detección en silencio). Ahora se eligen con un
	//GUI de lista pedido al servidor (ver CharacterOptionsRegistry/CharacterOptionListScreen), en vez de
	//escribirse a mano o recorrerse a clicks uno por uno.
	private void requestOptionPicker(String category) {
		CharacterSheetSaveProcedure.execute(guistate); //Como al abrir Presets: no perder ediciones sin guardar de otros campos al navegar fuera.
		DndsheetsMod.PACKET_HANDLER.sendToServer(new CharacterOptionsRequestMessage(category));
	}

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

	//renderLabels corre cada frame: estos Component (texto estático, nunca cambia) se cachean una sola
	//vez en vez de construirse de nuevo en cada uno.
	private static final Component LABEL_NAME = Component.translatable("gui.dndsheets.character_sheet.label_name");
	//Los íconos junto a los campos de característica (str.png, dex.png...) son pictogramas sin texto —
	//sin esto, un jugador nuevo no tiene forma de saber cuál campo es Fuerza y cuál es Destreza salvo por
	//el orden. Usados como tooltip (ver initAbilityScoreBoxes) y como texto de los botones de tirada.
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
	/** Cabecera de la pestaña de Ataques: el mismo rótulo que lleva su pestaña, sin clave nueva. */
	private static final Component LABEL_ATTACKS_TAB = Component.translatable("gui.dndsheets.character_sheet.attacks_tab");
	private static final Component LABEL_ATTACKS_EMPTY = Component.translatable("gui.dndsheets.character_sheet.attacks_empty");
	private static final Component LABEL_SECTION_IDENTITY = Component.translatable("gui.dndsheets.character_sheet.section_identity");
	private static final Component LABEL_SECTION_COMBAT = Component.translatable("gui.dndsheets.character_sheet.section_combat");
	private static final Component LABEL_SECTION_RESOURCES = Component.translatable("gui.dndsheets.character_sheet.section_resources");
	private static final Component LABEL_SKILL_PERSUASION = Component.translatable("gui.dndsheets.character_sheet.label_skill_persuasion");

	//Las 18 habilidades en el mismo orden en que se colocan, para poder recorrerlas (ver
	//warnIfLabelsOverflow). Antes solo existían sueltas, nombradas una a una en renderLabels.
	private static final Component[] SKILL_LABELS_COL1 = {
		LABEL_SKILL_ATHLETICS, LABEL_SKILL_ACROBATICS, LABEL_SKILL_SLEIGHTOFHAND, LABEL_SKILL_STEALTH,
		LABEL_SKILL_ARCANA, LABEL_SKILL_HISTORY, LABEL_SKILL_INVESTIGATION, LABEL_SKILL_NATURE, LABEL_SKILL_RELIGION,
	};
	private static final Component[] SKILL_LABELS_COL2 = {
		LABEL_SKILL_ANIMALHANDLING, LABEL_SKILL_INSIGHT, LABEL_SKILL_MEDICINE, LABEL_SKILL_PERCEPTION,
		LABEL_SKILL_SURVIVAL, LABEL_SKILL_DECEPTION, LABEL_SKILL_INTIMIDATION, LABEL_SKILL_PERFORMANCE,
		LABEL_SKILL_PERSUASION,
	};

	//Todos los campos de la hoja, para enmarcarlos de una pasada. Salen del guistate, que ya los tiene
	//todos: registrarlos a mano en los trece sitios que los crean es justo como se olvida uno.
	private final java.util.List<EditBox> sheetFields = new ArrayList<>();
	//Los widgets del panel principal, para ocultarlos al cambiar de pestaña. Ver initMainPanel().
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
				section(guiGraphics, LABEL_ATTACKS_TAB, SEC1_Y);
				//Sin ataques, la lista es un hueco oscuro que no dice qué hacer con él. El botón de añadir
				//está justo debajo pero es un icono de 16 px sin rótulo.
				if (attackRolls.getListSize() == 0) {
					guiGraphics.drawString(this.font, LABEL_ATTACKS_EMPTY,
						PANEL_X + (PANEL_RIGHT - PANEL_X - this.font.width(LABEL_ATTACKS_EMPTY)) / 2,
						ATTACK_TOP + ATTACK_HEIGHT / 2 - 4, GuiStyle.MUTED_COLOR, false);
				}
				break;
		}

		//Después de que los campos se hayan dibujado: el marco tapa el anillo gris que cada uno se pinta solo.
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

		//Bandas de sección, solo en la pestaña principal. Va aquí y no en renderLabels porque renderLabels
		//corre DESPUÉS de los widgets: dibujadas allí taparían los propios campos que enmarcan.
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

	//El botón/campo bajo el cursor se queda con el scroll por defecto (Screen le entrega el evento a lo
	//que esté justo debajo del mouse), y una fila de la lista de Ataques no hace nada con él — de ahí que
	//antes solo se pudiera desplazar pasando el mouse por huecos sin botón (ver PresetScreen.mouseScrolled,
	//mismo arreglo). Solo aplica en la pestaña de Ataques y solo si el cursor está sobre la lista, para no
	//robarle el scroll a nada de las otras pestañas.
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		if (panelActive == PanelStatus.ATTACKS && attackRolls.isMouseOver(mouseX, mouseY)) {
			return attackRolls.mouseScrolled(mouseX, mouseY, delta);
		}
		return super.mouseScrolled(mouseX, mouseY, delta);
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

		if (attackRolls.forwardKeyToFocusedNameBox(key, b, c)) return true;

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

	/**
	 * <p>Cabecera de sección: título en tinta apagada y filete de latón hasta el borde del panel. Se dibuja
	 * desde {@code renderLabels}, que ya corre con la traslación de {@code leftPos}/{@code topPos} aplicada,
	 * así que las coordenadas son las mismas de la retícula.</p>
	 */
	/**
	 * <p>Marco de latón sobre el anillo gris que {@link EditBox} se dibuja solo.</p>
	 *
	 * <p>Vanilla pinta cada campo como un anillo gris de un píxel (blanco al tener el foco) alrededor de un
	 * relleno negro, y los dos colores están fijos en {@code EditBox.renderWidget}. Sobre el pergamino eso
	 * se leía como widgets prestados de otra interfaz: la pantalla parecía dos diseños a la vez.</p>
	 *
	 * <p>Apagar el borde con {@code setBordered(false)} no vale: quita también el relleno negro Y cambia
	 * dónde se dibuja el texto (pasa de estar centrado con margen a pegarse a la esquina superior
	 * izquierda), y sin relleno oscuro detrás el texto tendría que ser tinta sobre pergamino — que con la
	 * sombra fija de Minecraft se ve duplicado, el problema que ya arreglamos en los rótulos.</p>
	 *
	 * <p>Así que el anillo no se quita: se repinta encima. Ocupa exactamente un píxel por fuera de la caja,
	 * o sea que taparlo no toca ni el texto ni el interior. Se conserva la señal de foco (latón encendido
	 * en vez de blanco) y se añade una línea oscura por fuera, que es lo que hace que el campo se lea
	 * hundido en la hoja en vez de pegado encima.</p>
	 */
	/**
	 * <p>Avisa por el log si algún rótulo se sale de su hueco. Cuatro veces en un mismo rediseño se coló un
	 * rótulo más ancho que su columna —{@code Velocidad}, {@code Bono de Competencia},
	 * {@code Trato con Animales} y el mensaje de "sin ataques"— y las cuatro <b>solo en español</b>: los
	 * huecos se ajustan mirando la pantalla en un idioma y el desbordamiento aparece en otro.</p>
	 *
	 * <p>Va aquí y no en el bloque {@code static} porque necesita {@code this.font}, que no existe hasta
	 * que hay pantalla. Y avisa en vez de reventar: un rótulo recortado por una traducción larga es un
	 * defecto cosmético, no motivo para dejar sin hoja a quien juega. Quien lo tiene que ver es quien
	 * desarrolla, y para eso basta el log.</p>
	 */
	private void warnIfLabelsOverflow() {
		//{rótulo, x donde empieza, x donde NO puede llegar}. Los límites salen de la retícula, así que
		//siguen a las constantes solos.
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
		};
		for (Object[] slot : slots) {
			checkLabelFits((Component) slot[0], (Integer) slot[1], (Integer) slot[2]);
		}
		//Las habilidades: el rótulo va tras el botón de tirada, y la columna 1 no puede invadir la 2.
		for (int i = 0; i < 9; i++) {
			checkLabelFits(SKILL_LABELS_COL1[i], SKILL_COL1_X + SKILL_LABEL_GAP, SKILL_COL2_X - 4);
			checkLabelFits(SKILL_LABELS_COL2[i], SKILL_COL2_X + SKILL_LABEL_GAP, SKILL_RIGHT);
		}
	}

	private void checkLabelFits(Component label, int left, int limit) {
		int end = left + this.font.width(label);
		if (end > limit) {
			DndsheetsMod.LOGGER.error("Hoja de personaje: el rótulo \"{}\" llega a x={} y su hueco acaba en {}"
				+ " — se verá recortado o encima de lo de al lado.", label.getString(), end, limit);
		}
	}

	private void frameField(GuiGraphics guiGraphics, EditBox box) {
		TomeField.frameWidget(guiGraphics, box.getX(), box.getY(), box.getWidth(), box.getHeight(), box.isFocused());
	}

	/**
	 * <p>Banda de una sección: un rectángulo apenas más oscuro que el pergamino, con luz arriba y sombra
	 * abajo. Da cuerpo al grupo — un título y un filete solos dejan la sección sin superficie, y la hoja
	 * entera se lee plana.</p>
	 *
	 * <p>Se calcula desde las constantes de la retícula, no a mano contra la textura: por eso no puede
	 * desalinearse de las filas que envuelve cuando se cambie {@code ROW_STEP}.</p>
	 *
	 * @param headingY  la {@code SEC*_Y} de la sección
	 * @param lastRowY  la {@code ROW*_Y} de su ÚLTIMA fila de campos
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
		//El filete arranca donde acaba el título, no debajo: así la cabecera ocupa una sola línea y las
		//secciones caben en el alto del panel, que es justo lo que no pasaba con el filete en su propia fila.
		GuiStyle.rule(guiGraphics, left + this.font.width(title) + 6, right, y + 3);
	}

	/**
	 * <p>Coordenada del hueco {@code index} (0..8) de una columna de habilidades, contando lo que ocupan
	 * las cabeceras de grupo que quedan por encima.</p>
	 *
	 * <p>Se calcula en vez de escribirse porque los grupos no son del mismo tamaño (Fuerza tiene una
	 * habilidad e Inteligencia cinco): con posiciones a mano, añadir o mover una obliga a recolocar todas
	 * las de debajo. Devuelve la y del RÓTULO; el botón de tirada se centra sobre ella.</p>
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
		throw new IllegalArgumentException("Hueco " + index + " fuera de una columna de " + seen + " habilidades");
	}

	/** Coordenada de la cabecera del grupo {@code group} de una columna. */
	private static int skillGroupY(int[] groups, int group) {
		int y = SKILL_TOP;
		for (int i = 0; i < group; i++) y += SKILL_GROUP_STEP + groups[i] * SKILL_ROW;
		return y;
	}

	@Override
	protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		//UNA sola tinta. Antes eran dos —lightColor blanco y darkColor casi negro— repartidos sin criterio
		//aparente entre etiquetas vecinas, y el fondo era BLANCO: la mayoría de las etiquetas eran blanco
		//sobre blanco, invisibles. Lo que las tapaba era el texto horneado en el PNG, que además estaba en
		//inglés y duplicaba a estas. Con el pergamino nuevo, una tinta oscura las hace legibles todas.
		final int lightColor = INK_COLOR;
		final int darkColor = INK_COLOR;
		guiGraphics.drawString(this.font, LABEL_NAME, NAME_OFFSET_X, NAME_OFFSET_Y - 10, lightColor, false);

		switch (panelActive) {
			case MAIN:
				//Cabeceras de sección: son lo que convierte doce rótulos sueltos sobre un pergamino en blanco
				//en tres grupos que se encuentran de un vistazo. El filete es el mismo recurso que usa
				//GuiStyle en las pantallas de lista, así que la hoja y el resto del mod se leen igual.
				section(guiGraphics, LABEL_SECTION_IDENTITY, SEC1_Y);
				section(guiGraphics, LABEL_SECTION_COMBAT, SEC2_Y);
				section(guiGraphics, LABEL_SECTION_RESOURCES, SEC3_Y);

				//Fila 1 — identidad.
				//Ámbar = se rellena solo (ver AUTO_FIELD_COLOR); color normal = se escribe a mano.
				guiGraphics.drawString(this.font, LABEL_RACE, RACE_OFFSET_X, RACE_OFFSET_Y - LABEL_GAP, lightColor, false);
				guiGraphics.drawString(this.font, LABEL_CLASS, CLASS_OFFSET_X, CLASS_OFFSET_Y - LABEL_GAP, lightColor, false);
				guiGraphics.drawString(this.font, LABEL_BACKGROUND, BACKG_OFFSET_X, BACKG_OFFSET_Y - LABEL_GAP, lightColor, false);

				//Fila 2 — combate.
				guiGraphics.drawString(this.font, LABEL_ARMOR_CLASS_AC, ACHP_OFFSET_X, ACHP_OFFSET_Y - LABEL_GAP, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, LABEL_HIT_POINTS, ACHP_OFFSET_X + ACHP_SEPARATION, ACHP_OFFSET_Y - LABEL_GAP, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, LABEL_HIT_POINTS_MAX, ACHP_OFFSET_X + ACHP_SEPARATION * 2, ACHP_OFFSET_Y - LABEL_GAP, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, LABEL_HIT_POINTS_TEMP, ACHP_OFFSET_X + ACHP_SEPARATION * 3, ACHP_OFFSET_Y - LABEL_GAP, AUTO_FIELD_COLOR, false);

				//Fila 3 — velocidad, competencia e iniciativa.
				guiGraphics.drawString(this.font, LABEL_SPEED, SPEED_OFFSET_X, SPEED_OFFSET_Y - LABEL_GAP, lightColor, false);
				guiGraphics.drawString(this.font, LABEL_PROFICIENCY_BONUS, PROF_OFFSET_X, PROF_OFFSET_Y - LABEL_GAP, AUTO_FIELD_COLOR, false);
				//El "+" va pegado al campo, no dentro: el campo guarda solo el número.
				guiGraphics.drawString(this.font, "+", PROF_OFFSET_X - 7, PROF_OFFSET_Y + 5, AUTO_FIELD_COLOR, false);
				//Alineado a la izquierda como todos los demás. Estaba centrado sobre su botón, que era la única
				//excepción de la hoja y además obligaba a drawCenteredString, que fuerza sombra (ver más abajo).
				guiGraphics.drawString(this.font, LABEL_INITIATIVE, INITIATIVE_OFFSET_X, INITIATIVE_OFFSET_Y - LABEL_GAP, lightColor, false);

				//Fila 4 — recursos. "Dados de Golpe" rotula la celda entera (cantidad + tipos), no solo el
				//primer campo, por eso su rótulo se extiende por encima de los dos.
				guiGraphics.drawString(this.font, LABEL_LEVEL, LEVEL_OFFSET_X, LEVEL_OFFSET_Y - LABEL_GAP, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, LABEL_HUNGER, HUNGER_OFFSET_X, HUNGER_OFFSET_Y - LABEL_GAP, AUTO_FIELD_COLOR, false);
				guiGraphics.drawString(this.font, LABEL_HITDICE, HITDICE_OFFSET_X, HITDICE_OFFSET_Y - LABEL_GAP, lightColor, false);
				break;
			case SKILLS:
				//Cabeceras de grupo: dicen de qué característica tira cada bloque, que es la mitad de la
				//información de una lista de habilidades y en pantalla no aparecía por ningún lado — la
				//agrupación existía solo como comentarios en el código. Reutilizan los rótulos de
				//característica del panel lateral, así que no hay claves de traducción nuevas.
				//Aquí no hay bandas como en la pestaña principal: cinco bandas en dos columnas se leen como
				//rayas, y las cabeceras con filete ya separan los grupos de sobra.
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
	 * <p>This sends a packet to the server with the roll expression it wants to roll.</p>
	 * @param category
	 * @param index
	 */
	public void sendRoll(int category, int index, int subIndex) {
		CharacterSheetSaveProcedure.execute(guistate);
		Logger logger = LogManager.getLogger(DndsheetsMod.MODID);
		logger.log(org.apache.logging.log4j.Level.getLevel("info"), "cat: " + category + " | index: " + index + " | subindex: " + subIndex);
		//Shift+clic en el dado = tirada privada (Sigilo, Investigación...): solo le llega a quien tira y a
		//los operadores conectados, en vez de a todo el mundo cerca — ver RollAnnouncerProcedure.sendPrivately.
		boolean isPrivate = hasShiftDown();
		DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetRollButtonMessage(category, index, subIndex, x, y, z, isPrivate));
		SheetRollButtonMessage.handle(entity, category, index, subIndex, x, y, z, isPrivate);

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
				e.txtShadow = true;  //Claro sobre cuero: la sombra da relieve.
				e.active = true;
			}
			else {
				e.setY(this.topPos - 17);
				e.setHeight(20);
				e.setImage( new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_tabbutton_active.png"), 0, 0, 20, 50, 60);
				//Tinta oscura: la pestaña abierta es pergamino, y el blanco de MCreator sobre pergamino no se lee.
				e.txtColor = INK_COLOR;
				//Sin sombra: en tinta oscura sobre el pergamino de la pestaña abierta, la sombra es una
				//segunda copia del rótulo a un píxel y la palabra se lee escrita dos veces.
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
		//A diferencia de las pestañas Skills/Attacks (más abajo), a estos campos antes solo se les tocaba
		//"active" y nunca "visible": cambiar de pestaña los dejaba deshabilitados pero seguían dibujándose
		//encima de Skills/Attacks en su misma posición de pantalla — la UI superpuesta reportada en
		//AUDIT_UX.md. EditBox.visible arranca en true y nunca se apagaba.
		isActive = panelActive == PanelStatus.MAIN;
		for (AbstractWidget widget : mainPanelWidgets) setActiveVisible(isActive, widget);

		//Los dos de iniciativa comparten sitio y se turnan según el modo edición, así que van DESPUÉS del
		//bucle de arriba: este los afina, aquel los pone a todos por igual.
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

	//F4 del audit: reemplaza ~16 pares repetidos de "x.active = isActive; x.visible = isActive;".
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
		rollButton.setTooltip(Tooltip.create(Component.literal((isSave ? "Tirada de salvación: " : "Tirar: ") + label.getString())));
		guistate.put(guistateKey, rollButton);
		this.addRenderableWidget(rollButton);

		ImageButton editButton = new ImageButton(this.leftPos + x, this.topPos + y, 16, 16, 0, 0, 16, new ResourceLocation(!isSave ? "dndsheets:textures/screens/atlas/imagebutton_d20_edit.png" : "dndsheets:textures/screens/atlas/imagebutton_d20_save_edit.png"), 16, 32, e -> {
			CharacterSheetSaveProcedure.execute(guistate);
			RollEditorScreen.workingCategory = category;
			RollEditorScreen.workingIndex = index;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new RollEditorOpenMessage());
		});
		editButton.setTooltip(Tooltip.create(Component.literal("Editar la fórmula de: " + label.getString())));
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


			String rollTooltip = switch (i) {
				case 0 -> "daño";
				case 1 -> "ataque";
				default -> "tirada";
			};

			ImageButton rollButton = new ImageButton(0, 0, 16, 16, 0, 0, 16, new ResourceLocation(imgLocation), 16, 32, e -> {
				int btnIndex = scrollList.getIndex(e);
				sendRoll(category, btnIndex, subIndex);
			});
			rollButton.setTooltip(Tooltip.create(Component.literal("Tirar " + rollTooltip + ".")));
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
			editButton.setTooltip(Tooltip.create(Component.literal("Editar la fórmula de " + rollTooltip + ".")));
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
		deleteButton.setTooltip(Tooltip.create(Component.literal("Eliminar esta fila.")));
		this.addWidget(deleteButton);

		scrollList.addListItem(nameBox, rollButtons, editButtons, deleteButton);
	}

	//Cada campo de texto de la hoja repetía esta misma lógica de "placeholder que reaparece cuando el
	//campo queda vacío" como subclase anónima de EditBox, cambiando solo la clave de traducción — ver
	//AUDIT_TECHNICAL.md A-DUP-4. x/y son offsets sin aplicar leftPos/topPos todavía, igual que las
	//constantes OFFSET_X/OFFSET_Y de la clase.
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
		//Todo lo que se cree aquí dentro pertenece al panel principal, y updateTabs() lo oculta al cambiar
		//de pestaña. Se captura por diferencia sobre children() en vez de listarlo a mano en updateTabs.
		//
		//La lista a mano ya falló dos veces, y siempre igual: alguien añade un campo, no se acuerda de
		//apuntarlo en el sitio lejano donde se oculta, y el campo se queda dibujado encima de Habilidades y
		//Ataques — sin rótulo, sin hacer nada y sin que falle nada. Le pasó primero a la tanda entera
		//(AUDIT_UX.md) y después a Nivel, Hambre y el botón de Guía. Capturado así, un campo nuevo entra
		//solo por existir.
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
	}

	private void initOptionPickerFields() {
		characterRace = new EditBox(this.font, this.leftPos + RACE_OFFSET_X, this.topPos + RACE_OFFSET_Y, IDENTITY_W, 18, Component.translatable("gui.dndsheets.character_sheet.characterrace")) {
			@Override
			public boolean mouseClicked(double mx, double my, int button) {
				if (!this.isMouseOver(mx, my)) return false;
				requestOptionPicker(CharacterOptionsRegistry.RACE);
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
				requestOptionPicker(CharacterOptionsRegistry.BACKGROUND);
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
				requestOptionPicker(CharacterOptionsRegistry.CLASS);
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
		initiativeButton.setTooltip(Tooltip.create(Component.literal("Tirar: " + LABEL_INITIATIVE.getString())));
		guistate.put("button:roll_init", initiativeButton);
		this.addRenderableWidget(initiativeButton);

		initiativeEditButton = new ImageButton(this.leftPos + INITIATIVE_OFFSET_X, this.topPos + INITIATIVE_OFFSET_Y, 16, 16, 0, 0, 16, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_d20_edit.png"), 16, 32, e -> {
			CharacterSheetSaveProcedure.execute(guistate);
			RollEditorScreen.workingCategory = 0;
			RollEditorScreen.workingIndex = 6;
			DndsheetsMod.PACKET_HANDLER.sendToServer(new RollEditorOpenMessage());
		});
		initiativeEditButton.setTooltip(Tooltip.create(Component.literal("Editar la fórmula de: " + LABEL_INITIATIVE.getString())));
		guistate.put("button:roll_init_edit", initiativeEditButton);
		this.addRenderableWidget(initiativeEditButton);
	}

	private void initBottomButtons() {
		//NOTA: sin hueco dibujado en la textura todavía. Puestos en el margen inferior, debajo de Nivel/
		//Hambre, para no pisar el círculo de Iniciativa (que ocupa la zona x=270-345, y=90-200).
		grimoireButton = TomeButton.of(Component.translatable("gui.dndsheets.character_sheet.grimoire"), b -> this.minecraft.setScreen(new GrimoireScreen(this)), this.leftPos + GRIMOIRE_OFFSET_X, this.topPos + GRIMOIRE_OFFSET_Y, BOTTOM_BUTTON_WIDTH, BOTTOM_BUTTON_HEIGHT);
		guistate.put("button:grimoire", grimoireButton);
		this.addRenderableWidget(grimoireButton);

		presetsButton = TomeButton.of(Component.translatable("gui.dndsheets.character_sheet.presets"), b -> {
			CharacterSheetSaveProcedure.execute(guistate);
			DndsheetsMod.PACKET_HANDLER.sendToServer(new PresetListRequestMessage());
		}, this.leftPos + PRESETS_OFFSET_X, this.topPos + PRESETS_OFFSET_Y, BOTTOM_BUTTON_WIDTH, BOTTOM_BUTTON_HEIGHT);
		guistate.put("button:presets", presetsButton);
		this.addRenderableWidget(presetsButton);

		boolean isDm = this.minecraft.player != null && this.minecraft.player.hasPermissions(2);
		guideButton = TomeButton.of(Component.translatable("gui.dndsheets.guide.button"), b -> GuideBook.open(isDm), this.leftPos + GUIDE_OFFSET_X, this.topPos + GUIDE_OFFSET_Y, BOTTOM_BUTTON_WIDTH, BOTTOM_BUTTON_HEIGHT);
		guistate.put("button:guide", guideButton);
		this.addRenderableWidget(guideButton);
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
		addButton.setTooltip(Tooltip.create(Component.literal("Añadir un ataque nuevo.")));
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

		ImageButton editToggle = new ImageButton(this.leftPos - 6, this.topPos + 192, 16, 16, 0, 0, 16, new ResourceLocation("dndsheets:textures/screens/atlas/imagebutton_editmode.png"), 16, 32, e -> {
			editMode = !editMode;
			updateTabs();
		});
		editToggle.setTooltip(Tooltip.create(Component.literal("Alternar modo edición: cambia los dados de tirada por editar fórmula/eliminar en la pestaña de Ataques.")));
		guistate.put("button:edit_toggle", editToggle);
		this.addRenderableWidget(editToggle);

		initSidePanel();
		initAttackPanel();
		initMainPanel();
		initSkillPanel();

		//Se vacía antes de rellenar: init() se vuelve a ejecutar al cambiar el tamaño de la ventana, y
		//guistate es estático (lo comparte el menú), así que sin esto la lista crecería en cada reajuste.
		sheetFields.clear();
		for (Object widget : guistate.values()) {
			if (widget instanceof EditBox field) sheetFields.add(field);
		}

		updateTabs();
		warnIfLabelsOverflow();
		CharacterSheetLoadProcedure.execute(guistate, this);
	}
}
