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
 * <p>Sheet settings for ONE player from the DM Panel (GUI equivalent of
 * {@code /dndsheet gold|setslots|advantage|damagetype|passive}), opened after picking them in
 * {@link PlayerPickerScreen} — the gold and spell slots shown on open are the real ones,
 * requested from the server (see {@code network.SheetSummaryRequestMessage}). Advantage and damage
 * type/affinity are chosen with cycle buttons, same as in {@link AddMonsterAttackScreen}.</p>
 */
public class SheetAdjustScreen extends FormPanelScreen {
	private static final String[] ADVANTAGE_LABELS = {"normal", "advantage", "disadvantage"};
	private static final String[] AFFINITIES = {"normal", "resistant", "vulnerable", "immune"};
	private static final String[] PACTS = {"chain", "blade", "tome"};

	//The values above are the real identifiers stored on the sheet/compared against elsewhere in the
	//code (DamageTypes.multiplierFor, CombatManager, SheetCommand...) — they can't be changed without
	//breaking those other spots. This only translates what the cycle button DISPLAYS.
	private static final Map<String, String> DISPLAY_KEYS = Map.ofEntries(
		Map.entry("normal", "gui.dndsheets.sheet_adjust.normal"),
		Map.entry("advantage", "gui.dndsheets.sheet_adjust.advantage"),
		Map.entry("disadvantage", "gui.dndsheets.sheet_adjust.disadvantage"),
		Map.entry("resistant", "gui.dndsheets.sheet_adjust.resistant"),
		Map.entry("vulnerable", "gui.dndsheets.sheet_adjust.vulnerable"),
		Map.entry("immune", "gui.dndsheets.sheet_adjust.immune"),
		Map.entry("chain", "gui.dndsheets.sheet_adjust.pact_chain"),
		Map.entry("blade", "gui.dndsheets.sheet_adjust.pact_blade"),
		Map.entry("tome", "gui.dndsheets.sheet_adjust.pact_tome"),
		Map.entry("physical", "gui.dndsheets.sheet_adjust.damage_physical"),
		Map.entry("slashing", "gui.dndsheets.sheet_adjust.damage_slashing"),
		Map.entry("piercing", "gui.dndsheets.sheet_adjust.damage_piercing"),
		Map.entry("bludgeoning", "gui.dndsheets.sheet_adjust.damage_bludgeoning"),
		Map.entry("fire", "gui.dndsheets.sheet_adjust.damage_fire"),
		Map.entry("cold", "gui.dndsheets.sheet_adjust.damage_cold"),
		Map.entry("lightning", "gui.dndsheets.sheet_adjust.damage_lightning"),
		Map.entry("acid", "gui.dndsheets.sheet_adjust.damage_acid"),
		Map.entry("poison", "gui.dndsheets.sheet_adjust.damage_poison"),
		Map.entry("psychic", "gui.dndsheets.sheet_adjust.damage_psychic"),
		Map.entry("radiant", "gui.dndsheets.sheet_adjust.damage_radiant"),
		Map.entry("necrotic", "gui.dndsheets.sheet_adjust.damage_necrotic"),
		Map.entry("force", "gui.dndsheets.sheet_adjust.damage_force"),
		Map.entry("thunder", "gui.dndsheets.sheet_adjust.damage_thunder")
	);

	//Width of ONE column within the row; the width of the whole row is formWidth().
	private static final int COLUMN_WIDTH = 90;

	//Wide panel and tight rows: there are ten actions, and they don't fit with the default spacing.
	@Override protected int formWidth() { return 190; }
	@Override protected int rowHeight() { return 26; }
	//Tall band: below the title there's a second, read-only line with HP/AC.
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
		//The title already says whose and with how much gold: it used to be drawn by hand every frame.
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

		//--- Gold ---
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

		//--- Spell slots ---
		slotsMaxBox = addFieldAt(Component.translatable("gui.dndsheets.sheet_adjust.slots_max").getString(), String.valueOf(slotsMax), 3, y, centerX - formWidth() / 2, COLUMN_WIDTH);
		slotsMaxBox.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.slots_max_tip")));

		slotsCurrentBox = addFieldAt(Component.translatable("gui.dndsheets.sheet_adjust.slots_current").getString(), String.valueOf(slotsCurrent), 3, y, centerX - formWidth() / 2 + COLUMN_WIDTH + 4, COLUMN_WIDTH);
		slotsCurrentBox.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.slots_current_tip")));
		y += rowHeight();

		//Its own row for "Apply": the two fields (90px each) already nearly fill formWidth() (190px), so
		//sharing the row with them left the button at 190-188=2 PIXELS wide — practically
		//impossible to click, the real cause of "spell slot changes don't apply" (it wasn't
		//the vertical clipping fixed earlier, this is a separate width bug).
		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.sheet_adjust.slots_apply"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.slots(targetUuid, parseIntOr(slotsMaxBox.getValue(), 0), parseIntOr(slotsCurrentBox.getValue(), 0))), centerX - formWidth() / 2, y, formWidth(), FIELD_HEIGHT));
		y += rowHeight();

		//--- Next attack advantage ---
		advantageButton = this.addRenderableWidget(new DirectionalCycleButton(centerX - formWidth() / 2, y, formWidth() - 60, FIELD_HEIGHT,
			translatedLabel("gui.dndsheets.sheet_adjust.next_attack", ADVANTAGE_LABELS[advantageIndex]),
			() -> { advantageIndex = (advantageIndex + 1) % ADVANTAGE_LABELS.length; advantageButton.setMessage(translatedLabel("gui.dndsheets.sheet_adjust.next_attack", ADVANTAGE_LABELS[advantageIndex])); },
			() -> { advantageIndex = (advantageIndex - 1 + ADVANTAGE_LABELS.length) % ADVANTAGE_LABELS.length; advantageButton.setMessage(translatedLabel("gui.dndsheets.sheet_adjust.next_attack", ADVANTAGE_LABELS[advantageIndex])); }));
		advantageButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.advantage_tip")));
		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.common.apply"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.advantage(targetUuid, ADVANTAGE_LABELS[advantageIndex])), centerX - formWidth() / 2 + formWidth() - 56, y, 56, FIELD_HEIGHT));
		y += rowHeight();

		//--- Damage type / affinity ---
		damageTypeButton = this.addRenderableWidget(new DirectionalCycleButton(centerX - formWidth() / 2, y, COLUMN_WIDTH, FIELD_HEIGHT,
			translatedLabel("gui.dndsheets.sheet_adjust.type", DamageTypes.CANONICAL[damageTypeIndex]),
			() -> { damageTypeIndex = (damageTypeIndex + 1) % DamageTypes.CANONICAL.length; damageTypeButton.setMessage(translatedLabel("gui.dndsheets.sheet_adjust.type", DamageTypes.CANONICAL[damageTypeIndex])); },
			() -> { damageTypeIndex = (damageTypeIndex - 1 + DamageTypes.CANONICAL.length) % DamageTypes.CANONICAL.length; damageTypeButton.setMessage(translatedLabel("gui.dndsheets.sheet_adjust.type", DamageTypes.CANONICAL[damageTypeIndex])); }));
		affinityButton = this.addRenderableWidget(new DirectionalCycleButton(centerX - formWidth() / 2 + COLUMN_WIDTH + 4, y, COLUMN_WIDTH, FIELD_HEIGHT,
			translatedLabel("gui.dndsheets.sheet_adjust.affinity", AFFINITIES[affinityIndex]),
			() -> { affinityIndex = (affinityIndex + 1) % AFFINITIES.length; affinityButton.setMessage(translatedLabel("gui.dndsheets.sheet_adjust.affinity", AFFINITIES[affinityIndex])); },
			() -> { affinityIndex = (affinityIndex - 1 + AFFINITIES.length) % AFFINITIES.length; affinityButton.setMessage(translatedLabel("gui.dndsheets.sheet_adjust.affinity", AFFINITIES[affinityIndex])); }));
		affinityButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.affinity_tip")));
		y += rowHeight();

		//Same dedicated row as above, same width bug (190-188=2px) if it shared a row with the two cycle buttons.
		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.sheet_adjust.affinity_apply"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.damageAffinity(targetUuid, DamageTypes.CANONICAL[damageTypeIndex], AFFINITIES[affinityIndex])), centerX - formWidth() / 2, y, formWidth(), FIELD_HEIGHT));
		y += rowHeight();

		//--- Warlock pact (permanent choice) ---
		pactButton = this.addRenderableWidget(new DirectionalCycleButton(centerX - formWidth() / 2, y, formWidth() - 60, FIELD_HEIGHT,
			translatedLabel("gui.dndsheets.sheet_adjust.pact", PACTS[pactIndex]),
			() -> { pactIndex = (pactIndex + 1) % PACTS.length; pactButton.setMessage(translatedLabel("gui.dndsheets.sheet_adjust.pact", PACTS[pactIndex])); },
			() -> { pactIndex = (pactIndex - 1 + PACTS.length) % PACTS.length; pactButton.setMessage(translatedLabel("gui.dndsheets.sheet_adjust.pact", PACTS[pactIndex])); }));
		pactButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.pact_tip")));
		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.common.apply"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.pact(targetUuid, PACTS[pactIndex])), centerX - formWidth() / 2 + formWidth() - 56, y, 56, FIELD_HEIGHT));
		y += rowHeight();

		//--- Character level (permanent choice) ---
		levelBox = addFieldAt(Component.translatable("gui.dndsheets.sheet_adjust.level").getString(), "1", 2, y, centerX - formWidth() / 2, COLUMN_WIDTH);
		levelBox.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.level_tip")));
		Button setLevelButton = TomeButton.of(Component.translatable("gui.dndsheets.sheet_adjust.level_set"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.level(targetUuid, parseIntOr(levelBox.getValue(), 1))), centerX - formWidth() / 2 + COLUMN_WIDTH + 4, y, formWidth() - COLUMN_WIDTH - 4, FIELD_HEIGHT);
		setLevelButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.sheet_adjust.level_set_tip")));
		this.addRenderableWidget(setLevelButton);
		y += rowHeight();

		//--- Passive perception ---
		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.sheet_adjust.passive"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(new PassivePerceptionRequestMessage(targetUuid)), centerX - formWidth() / 2, y, formWidth(), FIELD_HEIGHT));
		y += rowHeight() + 4;

		//One row, not fourteen: conditions live in their own list (see ConditionListScreen). This
		//screen already overflowed the top with 8 rows (see PROJECT_CONTEXT.md, bug #2), so it's not
		//the place to cram in a control per condition.
		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.sheet_adjust.conditions"), button ->
			ConditionListScreen.open(targetUuid, targetName, conditionsCsv), centerX - formWidth() / 2, y, formWidth(), FIELD_HEIGHT));
		y += rowHeight() + 4;

		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.common.back"), button -> this.onClose(), centerX - formWidth() / 2, y, formWidth(), FIELD_HEIGHT));

		formBottom = y + FIELD_HEIGHT + 10;
	}

	//The base cycleLabel does NOT translate: it returns the internal value as-is, which is correct for
	//a form whose values are already readable. Here they aren't ("resistant", "chain"), so this panel
	//runs them through DISPLAY_KEYS before painting them. The value sent to the server is still the internal one.
	private static Component translatedLabel(String prefixKey, String internalValue) {
		return Component.translatable(prefixKey).append(": " + displayLabel(internalValue));
	}

	private static String displayLabel(String internalValue) {
		String key = DISPLAY_KEYS.get(internalValue);
		return key != null ? Component.translatable(key).getString() : internalValue;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);
		//Frame, title, and hairline: the same ones the 13 SmallFormScreen screens draw, now from the
		//same place (see FormPanelScreen). The constructor sets the title.
		renderPanelChrome(guiGraphics);
		//Read-only: the player's real HP/AC, so they don't have to be asked to open their own sheet in
		//the middle of combat. It's the only extra thing this panel draws, which is why its header band is taller.
		guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.sheet_adjust.vitals", hp, maxHp, ac),
			centerX, formTop - titleBand() + 16, 0xFFAA00);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
		//The four fields, each with its label above: they used to be rendered one by one by hand and none
		//showed what it was for.
		renderFields(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
