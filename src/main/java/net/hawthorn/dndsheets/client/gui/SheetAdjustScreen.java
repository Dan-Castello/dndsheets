package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DamageTypes;
import net.hawthorn.dndsheets.client.gui.components.TomeButton;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.components.DirectionalCycleButton;
import net.hawthorn.dndsheets.network.PassivePerceptionRequestMessage;
import net.hawthorn.dndsheets.network.SheetAdjustMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * <p>Ajustes de hoja de UN jugador desde el Panel de DM (equivalente en GUI a
 * {@code /dndsheet gold|setslots|advantage|damagetype|passive}), abierto tras elegirlo en
 * {@link PlayerPickerScreen} — el oro y los espacios de conjuro que muestra al abrir son los reales,
 * pedidos al servidor (ver {@code network.SheetSummaryRequestMessage}). Ventaja y tipo de daño/afinidad
 * se eligen con botones cíclicos, igual que en {@link AddMonsterAttackScreen}.</p>
 */
public class SheetAdjustScreen extends FormPanelScreen {
	private static final String[] ADVANTAGE_LABELS = {"normal", "ventaja", "desventaja"};
	private static final String[] AFFINITIES = {"normal", "resistant", "vulnerable", "immune"};
	private static final String[] PACTS = {"cadena", "hoja", "vara"};

	//Los valores de arriba son los identificadores reales guardados en la hoja/comparados en el resto del
	//código (DamageTypes.multiplierFor, CombatManager, SheetCommand...) — no se pueden cambiar sin romper
	//esos otros sitios. Esto solo traduce lo que el botón cíclico MUESTRA.
	private static final Map<String, String> DISPLAY_KEYS = Map.ofEntries(
		Map.entry("normal", "gui.dndsheets.sheet_adjust.normal"),
		Map.entry("ventaja", "gui.dndsheets.sheet_adjust.advantage"),
		Map.entry("desventaja", "gui.dndsheets.sheet_adjust.disadvantage"),
		Map.entry("resistant", "gui.dndsheets.sheet_adjust.resistant"),
		Map.entry("vulnerable", "gui.dndsheets.sheet_adjust.vulnerable"),
		Map.entry("immune", "gui.dndsheets.sheet_adjust.immune"),
		Map.entry("cadena", "gui.dndsheets.sheet_adjust.pact_chain"),
		Map.entry("hoja", "gui.dndsheets.sheet_adjust.pact_blade"),
		Map.entry("vara", "gui.dndsheets.sheet_adjust.pact_tome"),
		Map.entry("fisico", "gui.dndsheets.sheet_adjust.damage_physical"),
		Map.entry("cortante", "gui.dndsheets.sheet_adjust.damage_slashing"),
		Map.entry("perforante", "gui.dndsheets.sheet_adjust.damage_piercing"),
		Map.entry("contundente", "gui.dndsheets.sheet_adjust.damage_bludgeoning"),
		Map.entry("fuego", "gui.dndsheets.sheet_adjust.damage_fire"),
		Map.entry("frio", "gui.dndsheets.sheet_adjust.damage_cold"),
		Map.entry("rayo", "gui.dndsheets.sheet_adjust.damage_lightning"),
		Map.entry("acido", "gui.dndsheets.sheet_adjust.damage_acid"),
		Map.entry("veneno", "gui.dndsheets.sheet_adjust.damage_poison"),
		Map.entry("psiquico", "gui.dndsheets.sheet_adjust.damage_psychic"),
		Map.entry("radiante", "gui.dndsheets.sheet_adjust.damage_radiant"),
		Map.entry("necrotico", "gui.dndsheets.sheet_adjust.damage_necrotic"),
		Map.entry("fuerza", "gui.dndsheets.sheet_adjust.damage_force"),
		Map.entry("trueno", "gui.dndsheets.sheet_adjust.damage_thunder")
	);

	//Ancho de UNA columna dentro de la fila; el ancho de la fila entera es formWidth().
	private static final int COLUMN_WIDTH = 90;

	//Panel ancho y filas apretadas: son diez acciones, y con la separación por defecto no caben.
	@Override protected int formWidth() { return 190; }
	@Override protected int rowHeight() { return 26; }
	//Banda alta: bajo el título va una segunda línea de solo lectura con PG/CA.
	@Override protected int titleBand() { return 44; }

	private final String targetUuid;
	private final String targetName;
	private final int gold;
	private final int slotsMax;
	private final int slotsCurrent;
	private final int hp;
	private final int maxHp;
	private final int ac;
	private final String conditionsCsv;

	private EditBox goldAmountBox;
	private EditBox slotsMaxBox;
	private EditBox slotsCurrentBox;
	private EditBox levelBox;
	private int advantageIndex = 0;
	private int damageTypeIndex = 0;
	private int affinityIndex = 0;
	private int pactIndex = 0;
	private Button advantageButton;
	private Button damageTypeButton;
	private Button affinityButton;
	private Button pactButton;

	private SheetAdjustScreen(String targetUuid, String targetName, int gold, int slotsMax, int slotsCurrent, int hp, int maxHp, int ac, String conditionsCsv, Screen parent) {
		//El título ya dice de quién y con cuánto oro: antes se dibujaba a mano en cada frame.
		super(Component.translatable("gui.dndsheets.sheet_adjust.title", targetName, gold), 6, parent);
		this.targetUuid = targetUuid;
		this.targetName = targetName;
		this.gold = gold;
		this.slotsMax = slotsMax;
		this.slotsCurrent = slotsCurrent;
		this.hp = hp;
		this.maxHp = maxHp;
		this.ac = ac;
		this.conditionsCsv = conditionsCsv;
	}

	public static void open(String targetUuid, String targetName, int gold, int slotsMax, int slotsCurrent, int hp, int maxHp, int ac, String conditionsCsv) {
		Minecraft.getInstance().setScreen(new SheetAdjustScreen(targetUuid, targetName, gold, slotsMax, slotsCurrent, hp, maxHp, ac, conditionsCsv, Minecraft.getInstance().screen));
	}

	@Override
	protected void init() {
		layoutTop();
		buildForm();
	}

	@Override
	protected void buildForm() {
		int y = formTop;

		//--- Oro ---
		goldAmountBox = addFieldAt(Component.translatable("gui.dndsheets.sheet_adjust.gold_amount").getString(), "0", 10, y, centerX - formWidth() / 2, COLUMN_WIDTH);
		goldAmountBox.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.gold_tip")));
		Button addGoldButton = TomeButton.of(Component.translatable("gui.dndsheets.sheet_adjust.gold_add"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.gold(targetUuid, "add", parseIntOr(goldAmountBox.getValue(), 0))), centerX - formWidth() / 2 + COLUMN_WIDTH + 4, y, 40, FIELD_HEIGHT);
		addGoldButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.gold_add_tip")));
		this.addRenderableWidget(addGoldButton);

		Button setGoldButton = TomeButton.of(Component.translatable("gui.dndsheets.sheet_adjust.gold_set"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.gold(targetUuid, "set", parseIntOr(goldAmountBox.getValue(), 0))), centerX - formWidth() / 2 + COLUMN_WIDTH + 48, y, 40, FIELD_HEIGHT);
		setGoldButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.gold_set_tip")));
		this.addRenderableWidget(setGoldButton);
		y += rowHeight();

		//--- Espacios de conjuro ---
		slotsMaxBox = addFieldAt(Component.translatable("gui.dndsheets.sheet_adjust.slots_max").getString(), String.valueOf(slotsMax), 3, y, centerX - formWidth() / 2, COLUMN_WIDTH);
		slotsMaxBox.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.slots_max_tip")));

		slotsCurrentBox = addFieldAt(Component.translatable("gui.dndsheets.sheet_adjust.slots_current").getString(), String.valueOf(slotsCurrent), 3, y, centerX - formWidth() / 2 + COLUMN_WIDTH + 4, COLUMN_WIDTH);
		slotsCurrentBox.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.slots_current_tip")));
		y += rowHeight();

		//Fila propia para "Aplicar": los dos campos (90px cada uno) ya casi llenan formWidth() (190px), así
		//que compartir la fila con ellos dejaba el botón en 190-188=2 PÍXELES de ancho — prácticamente
		//imposible de pulsar, la causa real de "los cambios de espacios de conjuro no se aplican" (no era
		//el recorte vertical que se corrigió antes, este es un bug de ancho aparte).
		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.sheet_adjust.slots_apply"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.slots(targetUuid, parseIntOr(slotsMaxBox.getValue(), 0), parseIntOr(slotsCurrentBox.getValue(), 0))), centerX - formWidth() / 2, y, formWidth(), FIELD_HEIGHT));
		y += rowHeight();

		//--- Ventaja próximo ataque ---
		advantageButton = this.addRenderableWidget(new DirectionalCycleButton(centerX - formWidth() / 2, y, formWidth() - 60, FIELD_HEIGHT,
			translatedLabel("Próximo ataque", ADVANTAGE_LABELS[advantageIndex]),
			() -> { advantageIndex = (advantageIndex + 1) % ADVANTAGE_LABELS.length; advantageButton.setMessage(translatedLabel("Próximo ataque", ADVANTAGE_LABELS[advantageIndex])); },
			() -> { advantageIndex = (advantageIndex - 1 + ADVANTAGE_LABELS.length) % ADVANTAGE_LABELS.length; advantageButton.setMessage(translatedLabel("Próximo ataque", ADVANTAGE_LABELS[advantageIndex])); }));
		advantageButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.advantage_tip")));
		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.common.apply"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.advantage(targetUuid, ADVANTAGE_LABELS[advantageIndex])), centerX - formWidth() / 2 + formWidth() - 56, y, 56, FIELD_HEIGHT));
		y += rowHeight();

		//--- Tipo de daño / afinidad ---
		damageTypeButton = this.addRenderableWidget(new DirectionalCycleButton(centerX - formWidth() / 2, y, COLUMN_WIDTH, FIELD_HEIGHT,
			translatedLabel("Tipo", DamageTypes.CANONICAL[damageTypeIndex]),
			() -> { damageTypeIndex = (damageTypeIndex + 1) % DamageTypes.CANONICAL.length; damageTypeButton.setMessage(translatedLabel("Tipo", DamageTypes.CANONICAL[damageTypeIndex])); },
			() -> { damageTypeIndex = (damageTypeIndex - 1 + DamageTypes.CANONICAL.length) % DamageTypes.CANONICAL.length; damageTypeButton.setMessage(translatedLabel("Tipo", DamageTypes.CANONICAL[damageTypeIndex])); }));
		affinityButton = this.addRenderableWidget(new DirectionalCycleButton(centerX - formWidth() / 2 + COLUMN_WIDTH + 4, y, COLUMN_WIDTH, FIELD_HEIGHT,
			translatedLabel("Afinidad", AFFINITIES[affinityIndex]),
			() -> { affinityIndex = (affinityIndex + 1) % AFFINITIES.length; affinityButton.setMessage(translatedLabel("Afinidad", AFFINITIES[affinityIndex])); },
			() -> { affinityIndex = (affinityIndex - 1 + AFFINITIES.length) % AFFINITIES.length; affinityButton.setMessage(translatedLabel("Afinidad", AFFINITIES[affinityIndex])); }));
		affinityButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.affinity_tip")));
		y += rowHeight();

		//Misma fila propia que arriba, mismo bug de ancho (190-188=2px) si compartía fila con los dos botones cíclicos.
		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.sheet_adjust.affinity_apply"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.damageAffinity(targetUuid, DamageTypes.CANONICAL[damageTypeIndex], AFFINITIES[affinityIndex])), centerX - formWidth() / 2, y, formWidth(), FIELD_HEIGHT));
		y += rowHeight();

		//--- Pacto del brujo (elección permanente, ver AUDIT_UX.md DM #3) ---
		pactButton = this.addRenderableWidget(new DirectionalCycleButton(centerX - formWidth() / 2, y, formWidth() - 60, FIELD_HEIGHT,
			translatedLabel("Pacto", PACTS[pactIndex]),
			() -> { pactIndex = (pactIndex + 1) % PACTS.length; pactButton.setMessage(translatedLabel("Pacto", PACTS[pactIndex])); },
			() -> { pactIndex = (pactIndex - 1 + PACTS.length) % PACTS.length; pactButton.setMessage(translatedLabel("Pacto", PACTS[pactIndex])); }));
		pactButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.pact_tip")));
		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.common.apply"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.pact(targetUuid, PACTS[pactIndex])), centerX - formWidth() / 2 + formWidth() - 56, y, 56, FIELD_HEIGHT));
		y += rowHeight();

		//--- Nivel de personaje (elección permanente, ver AUDIT_UX.md DM #3) ---
		levelBox = addFieldAt(Component.translatable("gui.dndsheets.sheet_adjust.level").getString(), "1", 2, y, centerX - formWidth() / 2, COLUMN_WIDTH);
		levelBox.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.level_tip")));
		Button setLevelButton = TomeButton.of(Component.translatable("gui.dndsheets.sheet_adjust.level_set"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.level(targetUuid, parseIntOr(levelBox.getValue(), 1))), centerX - formWidth() / 2 + COLUMN_WIDTH + 4, y, formWidth() - COLUMN_WIDTH - 4, FIELD_HEIGHT);
		setLevelButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.level_set_tip")));
		this.addRenderableWidget(setLevelButton);
		y += rowHeight();

		//--- Percepción pasiva ---
		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.sheet_adjust.passive"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(new PassivePerceptionRequestMessage(targetUuid)), centerX - formWidth() / 2, y, formWidth(), FIELD_HEIGHT));
		y += rowHeight() + 4;

		//Una fila, no catorce: las condiciones viven en su propia lista (ver ConditionListScreen). Esta
		//pantalla ya llegó a salirse por arriba con 8 filas (ver PROJECT_CONTEXT.md, bug #2), así que no es
		//sitio para meter un control por condición.
		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.sheet_adjust.conditions"), button ->
			ConditionListScreen.open(targetUuid, targetName, conditionsCsv), centerX - formWidth() / 2, y, formWidth(), FIELD_HEIGHT));
		y += rowHeight() + 4;

		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.common.back"), button -> this.onClose(), centerX - formWidth() / 2, y, formWidth(), FIELD_HEIGHT));

		formBottom = y + FIELD_HEIGHT + 10;
	}

	//El cycleLabel de la base NO traduce: devuelve el valor interno tal cual, que es lo correcto para un
	//formulario cuyos valores ya son legibles. Aqui no lo son ("resistant", "cadena"), asi que este panel
	//pasa por DISPLAY_KEYS antes de pintarlos. El valor que viaja al servidor sigue siendo el interno.
	private static Component translatedLabel(String prefix, String internalValue) {
		return Component.literal(prefix + ": " + displayLabel(internalValue));
	}

	private static String displayLabel(String internalValue) {
		String key = DISPLAY_KEYS.get(internalValue);
		return key != null ? Component.translatable(key).getString() : internalValue;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);
		//Marco, título y filete: los mismos que dibujan las 13 pantallas de SmallFormScreen, ahora desde el
		//mismo sitio (ver FormPanelScreen). El título lo pone el constructor.
		renderPanelChrome(guiGraphics);
		//Solo lectura: PG/CA reales del jugador, para no tener que pedirle que abra su propia hoja en
		//pleno combate. Es lo único que este panel pinta de más, y por eso su banda de cabecera es mayor.
		guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.sheet_adjust.vitals", hp, maxHp, ac),
			centerX, formTop - titleBand() + 16, 0xFFAA00);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
		//Los cuatro campos, con su etiqueta encima: antes se renderizaban uno a uno a mano y ninguno
		//mostraba para qué era.
		renderFields(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
