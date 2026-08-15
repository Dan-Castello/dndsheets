package net.hawthorn.dndsheets.client.gui;

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
public class SheetAdjustScreen extends Screen {
	private static final String[] ADVANTAGE_LABELS = {"normal", "ventaja", "desventaja"};
	private static final String[] DAMAGE_TYPES = {
		"fisico", "cortante", "perforante", "contundente", "fuego", "frio", "rayo",
		"acido", "veneno", "psiquico", "radiante", "necrotico", "fuerza", "trueno"
	};
	private static final String[] AFFINITIES = {"normal", "resistant", "vulnerable", "immune"};
	private static final String[] PACTS = {"cadena", "hoja", "vara"};

	//Los valores de arriba son los identificadores reales guardados en la hoja/comparados en el resto del
	//código (DamageTypes.multiplierFor, CombatManager, SheetCommand...) — no se pueden cambiar sin romper
	//esos otros sitios. Esto solo traduce lo que el botón cíclico MUESTRA, ver AUDIT_TECHNICAL.md m9.
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

	private static final int FIELD_WIDTH = 90;
	private static final int WIDE_WIDTH = 190;
	private static final int FIELD_HEIGHT = 20;
	private static final int ROW_HEIGHT = 26;

	private final String targetUuid;
	private final String targetName;
	private final Screen parent;
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
	private int y0;
	private int formBottom;
	private int advantageIndex = 0;
	private int damageTypeIndex = 0;
	private int affinityIndex = 0;
	private int pactIndex = 0;
	private Button advantageButton;
	private Button damageTypeButton;
	private Button affinityButton;
	private Button pactButton;

	private SheetAdjustScreen(String targetUuid, String targetName, int gold, int slotsMax, int slotsCurrent, int hp, int maxHp, int ac, String conditionsCsv, Screen parent) {
		super(Component.literal("Ajustes de hoja"));
		this.targetUuid = targetUuid;
		this.targetName = targetName;
		this.gold = gold;
		this.slotsMax = slotsMax;
		this.slotsCurrent = slotsCurrent;
		this.hp = hp;
		this.maxHp = maxHp;
		this.ac = ac;
		this.conditionsCsv = conditionsCsv;
		this.parent = parent;
	}

	public static void open(String targetUuid, String targetName, int gold, int slotsMax, int slotsCurrent, int hp, int maxHp, int ac, String conditionsCsv) {
		Minecraft.getInstance().setScreen(new SheetAdjustScreen(targetUuid, targetName, gold, slotsMax, slotsCurrent, hp, maxHp, ac, conditionsCsv, Minecraft.getInstance().screen));
	}

	//Vuelve a la pantalla anterior (normalmente PlayerPickerScreen) en vez de cerrar todo el menú —
	//mismo mecanismo que ListPickerScreen/SmallFormScreen, ver esas clases.
	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		//Centrado normalmente, pero sin dejar que el panel (título en y0-26, borde en y0-40) se salga por
		//arriba de la pantalla en ventanas bajas/GUI Scale alto — con 8 filas, centrar en height/2 sin tope
		//empujaba "Espacios de conjuro" y el resto fuera de la vista (ni se veía ni se podía pulsar
		//"Aplicar") en vez de solo verse recortado.
		y0 = Math.max(44, this.height / 2 - ROW_HEIGHT * 6);
		int y = y0;

		//--- Oro ---
		goldAmountBox = new EditBox(this.font, centerX - WIDE_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Cantidad"));
		goldAmountBox.setValue("0");
		goldAmountBox.setMaxLength(10);
		goldAmountBox.setTooltip(Tooltip.create(Component.literal("Cantidad de oro a sumar o fijar con los botones de la derecha.")));
		this.addWidget(goldAmountBox);
		this.setInitialFocus(goldAmountBox);
		Button addGoldButton = Button.builder(Component.literal("Añadir"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.gold(targetUuid, "add", parseIntOr(goldAmountBox.getValue(), 0)))
		).bounds(centerX - WIDE_WIDTH / 2 + FIELD_WIDTH + 4, y, 40, FIELD_HEIGHT).build();
		addGoldButton.setTooltip(Tooltip.create(Component.literal("Suma esta cantidad al oro actual del jugador.")));
		this.addRenderableWidget(addGoldButton);

		Button setGoldButton = Button.builder(Component.literal("Fijar"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.gold(targetUuid, "set", parseIntOr(goldAmountBox.getValue(), 0)))
		).bounds(centerX - WIDE_WIDTH / 2 + FIELD_WIDTH + 48, y, 40, FIELD_HEIGHT).build();
		setGoldButton.setTooltip(Tooltip.create(Component.literal("Reemplaza el oro actual del jugador por esta cantidad.")));
		this.addRenderableWidget(setGoldButton);
		y += ROW_HEIGHT;

		//--- Espacios de conjuro ---
		slotsMaxBox = new EditBox(this.font, centerX - WIDE_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Máximo"));
		slotsMaxBox.setValue(String.valueOf(slotsMax));
		slotsMaxBox.setMaxLength(3);
		slotsMaxBox.setTooltip(Tooltip.create(Component.literal("Espacios de conjuro MÁXIMOS del jugador.")));
		this.addWidget(slotsMaxBox);

		slotsCurrentBox = new EditBox(this.font, centerX - WIDE_WIDTH / 2 + FIELD_WIDTH + 4, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Actual"));
		slotsCurrentBox.setValue(String.valueOf(slotsCurrent));
		slotsCurrentBox.setMaxLength(3);
		slotsCurrentBox.setTooltip(Tooltip.create(Component.literal("Espacios de conjuro que le quedan AHORA MISMO al jugador.")));
		this.addWidget(slotsCurrentBox);
		y += ROW_HEIGHT;

		//Fila propia para "Aplicar": los dos campos (90px cada uno) ya casi llenan WIDE_WIDTH (190px), así
		//que compartir la fila con ellos dejaba el botón en 190-188=2 PÍXELES de ancho — prácticamente
		//imposible de pulsar, la causa real de "los cambios de espacios de conjuro no se aplican" (no era
		//el recorte vertical que se corrigió antes, este es un bug de ancho aparte).
		this.addRenderableWidget(Button.builder(Component.literal("Aplicar espacios de conjuro"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.slots(targetUuid, parseIntOr(slotsMaxBox.getValue(), 0), parseIntOr(slotsCurrentBox.getValue(), 0)))
		).bounds(centerX - WIDE_WIDTH / 2, y, WIDE_WIDTH, FIELD_HEIGHT).build());
		y += ROW_HEIGHT;

		//--- Ventaja próximo ataque ---
		advantageButton = this.addRenderableWidget(new DirectionalCycleButton(centerX - WIDE_WIDTH / 2, y, WIDE_WIDTH - 60, FIELD_HEIGHT,
			cycleLabel("Próximo ataque", ADVANTAGE_LABELS[advantageIndex]),
			() -> { advantageIndex = (advantageIndex + 1) % ADVANTAGE_LABELS.length; advantageButton.setMessage(cycleLabel("Próximo ataque", ADVANTAGE_LABELS[advantageIndex])); },
			() -> { advantageIndex = (advantageIndex - 1 + ADVANTAGE_LABELS.length) % ADVANTAGE_LABELS.length; advantageButton.setMessage(cycleLabel("Próximo ataque", ADVANTAGE_LABELS[advantageIndex])); }));
		advantageButton.setTooltip(Tooltip.create(Component.literal("Se aplica solo a la próxima tirada de ataque del jugador, luego vuelve a Normal. Clic derecho para retroceder.")));
		this.addRenderableWidget(Button.builder(Component.literal("Aplicar"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.advantage(targetUuid, ADVANTAGE_LABELS[advantageIndex]))
		).bounds(centerX - WIDE_WIDTH / 2 + WIDE_WIDTH - 56, y, 56, FIELD_HEIGHT).build());
		y += ROW_HEIGHT;

		//--- Tipo de daño / afinidad ---
		damageTypeButton = this.addRenderableWidget(new DirectionalCycleButton(centerX - WIDE_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT,
			cycleLabel("Tipo", DAMAGE_TYPES[damageTypeIndex]),
			() -> { damageTypeIndex = (damageTypeIndex + 1) % DAMAGE_TYPES.length; damageTypeButton.setMessage(cycleLabel("Tipo", DAMAGE_TYPES[damageTypeIndex])); },
			() -> { damageTypeIndex = (damageTypeIndex - 1 + DAMAGE_TYPES.length) % DAMAGE_TYPES.length; damageTypeButton.setMessage(cycleLabel("Tipo", DAMAGE_TYPES[damageTypeIndex])); }));
		affinityButton = this.addRenderableWidget(new DirectionalCycleButton(centerX - WIDE_WIDTH / 2 + FIELD_WIDTH + 4, y, FIELD_WIDTH, FIELD_HEIGHT,
			cycleLabel("Afinidad", AFFINITIES[affinityIndex]),
			() -> { affinityIndex = (affinityIndex + 1) % AFFINITIES.length; affinityButton.setMessage(cycleLabel("Afinidad", AFFINITIES[affinityIndex])); },
			() -> { affinityIndex = (affinityIndex - 1 + AFFINITIES.length) % AFFINITIES.length; affinityButton.setMessage(cycleLabel("Afinidad", AFFINITIES[affinityIndex])); }));
		affinityButton.setTooltip(Tooltip.create(Component.literal("Normal = daño normal, Resistente = mitad, Vulnerable = doble, Inmune = ninguno. Clic derecho para retroceder.")));
		y += ROW_HEIGHT;

		//Misma fila propia que arriba, mismo bug de ancho (190-188=2px) si compartía fila con los dos botones cíclicos.
		this.addRenderableWidget(Button.builder(Component.literal("Aplicar tipo de daño"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.damageAffinity(targetUuid, DAMAGE_TYPES[damageTypeIndex], AFFINITIES[affinityIndex]))
		).bounds(centerX - WIDE_WIDTH / 2, y, WIDE_WIDTH, FIELD_HEIGHT).build());
		y += ROW_HEIGHT;

		//--- Pacto del brujo (elección permanente, ver AUDIT_UX.md DM #3) ---
		pactButton = this.addRenderableWidget(new DirectionalCycleButton(centerX - WIDE_WIDTH / 2, y, WIDE_WIDTH - 60, FIELD_HEIGHT,
			cycleLabel("Pacto", PACTS[pactIndex]),
			() -> { pactIndex = (pactIndex + 1) % PACTS.length; pactButton.setMessage(cycleLabel("Pacto", PACTS[pactIndex])); },
			() -> { pactIndex = (pactIndex - 1 + PACTS.length) % PACTS.length; pactButton.setMessage(cycleLabel("Pacto", PACTS[pactIndex])); }));
		pactButton.setTooltip(Tooltip.create(Component.literal("Elección permanente: Pacto de la Hoja usa Carisma para atacar/dañar con armas. Clic derecho para retroceder.")));
		this.addRenderableWidget(Button.builder(Component.literal("Aplicar"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.pact(targetUuid, PACTS[pactIndex]))
		).bounds(centerX - WIDE_WIDTH / 2 + WIDE_WIDTH - 56, y, 56, FIELD_HEIGHT).build());
		y += ROW_HEIGHT;

		//--- Nivel de personaje (elección permanente, ver AUDIT_UX.md DM #3) ---
		levelBox = new EditBox(this.font, centerX - WIDE_WIDTH / 2, y, FIELD_WIDTH, FIELD_HEIGHT, Component.literal("Nivel"));
		levelBox.setValue("1");
		levelBox.setMaxLength(2);
		levelBox.setTooltip(Tooltip.create(Component.literal("Desacopla el nivel de personaje del XP real de Minecraft.")));
		this.addWidget(levelBox);
		Button setLevelButton = Button.builder(Component.literal("Fijar nivel"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.level(targetUuid, parseIntOr(levelBox.getValue(), 1)))
		).bounds(centerX - WIDE_WIDTH / 2 + FIELD_WIDTH + 4, y, WIDE_WIDTH - FIELD_WIDTH - 4, FIELD_HEIGHT).build();
		setLevelButton.setTooltip(Tooltip.create(Component.literal("Elección permanente: fija el nivel de personaje, no solo lo estima del XP.")));
		this.addRenderableWidget(setLevelButton);
		y += ROW_HEIGHT;

		//--- Percepción pasiva ---
		this.addRenderableWidget(Button.builder(Component.literal("Ver percepción pasiva (solo tú la ves)"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(new PassivePerceptionRequestMessage(targetUuid))
		).bounds(centerX - WIDE_WIDTH / 2, y, WIDE_WIDTH, FIELD_HEIGHT).build());
		y += ROW_HEIGHT + 4;

		//Una fila, no catorce: las condiciones viven en su propia lista (ver ConditionListScreen). Esta
		//pantalla ya llegó a salirse por arriba con 8 filas (ver PROJECT_CONTEXT.md, bug #2), así que no es
		//sitio para meter un control por condición.
		this.addRenderableWidget(Button.builder(Component.literal("Condiciones..."), button ->
			ConditionListScreen.open(targetUuid, targetName, conditionsCsv)
		).bounds(centerX - WIDE_WIDTH / 2, y, WIDE_WIDTH, FIELD_HEIGHT).build());
		y += ROW_HEIGHT + 4;

		this.addRenderableWidget(Button.builder(Component.literal("< Atrás"), button -> this.onClose())
			.bounds(centerX - WIDE_WIDTH / 2, y, WIDE_WIDTH, FIELD_HEIGHT).build());

		formBottom = y + FIELD_HEIGHT + 10;
	}

	private static Component cycleLabel(String prefix, String value) {
		return Component.literal(prefix + ": " + displayLabel(value));
	}

	private static String displayLabel(String internalValue) {
		String key = DISPLAY_KEYS.get(internalValue);
		return key != null ? Component.translatable(key).getString() : internalValue;
	}

	private static int parseIntOr(String value, int fallback) {
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);
		int centerX = this.width / 2;
		GuiStyle.panel(guiGraphics, centerX - WIDE_WIDTH / 2 - 14, y0 - 40, centerX + WIDE_WIDTH / 2 + 14, formBottom);
		guiGraphics.drawCenteredString(this.font, Component.literal("Ajustes de " + targetName + " (oro actual: " + gold + ")"),
			centerX, y0 - 26, GuiStyle.TITLE_COLOR);
		//Solo lectura: PG/CA reales del jugador, para no tener que pedirle que abra su propia hoja en
		//pleno combate — ver AUDIT_UX.md, DM #1.
		guiGraphics.drawCenteredString(this.font, Component.literal("PG " + hp + "/" + maxHp + " · CA " + ac),
			centerX, y0 - 16, 0xFFAA00);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
		goldAmountBox.render(guiGraphics, mouseX, mouseY, partialTicks);
		slotsMaxBox.render(guiGraphics, mouseX, mouseY, partialTicks);
		slotsCurrentBox.render(guiGraphics, mouseX, mouseY, partialTicks);
		levelBox.render(guiGraphics, mouseX, mouseY, partialTicks);
	}

	@Override
	public void tick() {
		goldAmountBox.tick();
		slotsMaxBox.tick();
		slotsCurrentBox.tick();
		levelBox.tick();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
