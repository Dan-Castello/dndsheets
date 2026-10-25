package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.RollIndex;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.client.SheetEdits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>Edits a roll formula of the sheet, in the same parchment dialog as the rest of the interface. One screen for both
 * kinds: a basic formula (an ability check, a saving throw, a skill) is a single expression; an attack's damage or
 * attack group holds two (a context label and an expression each). Chips insert the usual terms ($str ... $prof) at the end
 * of the box you last touched, and the line under each box shows the flat bonus it adds, computed from the sheet, so a
 * typo in "$dxe" shows up as +0 before the roll is made.</p>
 *
 * <p>It replaces the two container-menu editors that came with the original sheet: no server round trip to open it, and
 * it returns to whoever opened it.</p>
 */
public class RollFormulaScreen extends ModalDialogScreen {
	private static final String[] TERMS = {"$str", "$dex", "$con", "$int", "$wis", "$cha", "$prof", "$hprof"};
	private static final String[] TERM_KEYS = {"str", "dex", "con", "int", "wis", "cha", "prof", "hprof"};

	private final Screen parent;
	private final int category;
	private final int index;
	private final int subIndex;
	private final boolean advanced;
	private final int dialogW;
	private final int dialogH;

	private final EditBox[] expressions;
	private final EditBox[] contexts;
	private EditBox lastFocused;

	private RollFormulaScreen(Screen parent, int category, int index, int subIndex, int w, int h) {
		super(Component.translatable("gui.dndsheets.roll_editor.title"), w, h);
		this.parent = parent;
		this.category = category;
		this.index = index;
		this.subIndex = subIndex;
		this.advanced = RollIndex.Category.fromInt(category).isAdvanced();
		this.dialogW = w;
		this.dialogH = h;
		this.expressions = new EditBox[advanced ? 2 : 1];
		this.contexts = new EditBox[advanced ? 2 : 0];
	}

	public static void open(Screen parent, int category, int index, int subIndex) {
		boolean advanced = RollIndex.Category.fromInt(category).isAdvanced();
		Minecraft.getInstance().setScreen(new RollFormulaScreen(parent, category, index, subIndex, 340, advanced ? 256 : 150));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	protected void init() {
		JsonObject sheet = SheetLoader.getClientSheet();
		if (sheet == null) {
			this.onClose();
			return;
		}
		SheetLoader.validateSheet(sheet);

		String[] stored = readStored(sheet);
		int boxW = dialogW - 40;
		int y = 36;
		for (int i = 0; i < expressions.length; i++) {
			if (advanced) {
				contexts[i] = box(20, y + 10, boxW, Component.translatable("gui.dndsheets.roll_editor.roll_context_suggestion" + (i == 0 ? "" : "2")), stored[i * 2 + 1]);
				y += 30;
			}
			expressions[i] = box(20, y + 10, boxW, Component.translatable("gui.dndsheets.roll_editor.roll_expression_suggestion" + (i == 0 ? "" : "2")), stored[i * 2]);
			y += 36;
		}
		lastFocused = expressions[0];

		//Term chips: two rows of four.
		int chipW = (boxW - 3 * 4) / 4;
		for (int i = 0; i < TERMS.length; i++) {
			final String term = TERMS[i];
			var chip = addModalButton(20 + (i % 4) * (chipW + 4), y + 12 + (i / 4) * 20, chipW, 16,
				Component.translatable("gui.dndsheets.roll_editor.button_" + TERM_KEYS[i]), b -> insert(term));
			chip.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.roll_editor.add_term", term)));
		}
		int buttonsY = dialogH - 26;
		addModalButton(20, buttonsY, (boxW - 4) / 2, 18, Component.translatable("gui.dndsheets.roll_editor.save"), b -> save());
		addModalButton(20 + (boxW - 4) / 2 + 4, buttonsY, (boxW - 4) / 2, 18, Component.translatable("gui.dndsheets.roll_editor.cancel"), b -> this.onClose());
	}

	private EditBox box(int x, int y, int w, Component hint, String value) {
		EditBox box = new EditBox(this.font, dialogLeft() + x, dialogTop() + y, w, 16, Component.translatable("gui.dndsheets.roll_editor.rollexpression"));
		box.setMaxLength(50);
		box.setHint(hint);
		box.setValue(value == null ? "" : value);
		this.addRenderableWidget(box);
		return box;
	}

	/** {expression1, context1, expression2, context2}, or the single basic expression at [0]. */
	private String[] readStored(JsonObject sheet) {
		String[] values = new String[]{"", "", "", ""};
		try {
			JsonArray array = sheet.getAsJsonArray(RollIndex.Category.fromInt(category).toString());
			if (!advanced) {
				if (index < array.size()) values[0] = array.get(index).getAsString();
			} else {
				JsonArray group = array.get(index).getAsJsonObject().getAsJsonArray("rolls").get(subIndex).getAsJsonArray();
				for (int i = 0; i < 2 && i < group.size(); i++) {
					values[i * 2] = group.get(i).getAsJsonObject().get("expression").getAsString();
					values[i * 2 + 1] = group.get(i).getAsJsonObject().get("context").getAsString();
				}
			}
		} catch (RuntimeException e) {
			//A damaged entry: the boxes start empty and saving rewrites it.
		}
		return values;
	}

	private void insert(String term) {
		EditBox target = lastFocused != null ? lastFocused : expressions[0];
		target.setValue(target.getValue() + " + " + term);
	}

	private void save() {
		if (!advanced) {
			SheetEdits.saveFormula(expressions[0].getValue(), category, index);
		} else {
			List<AbstractMap.SimpleEntry<String, String>> data = new ArrayList<>();
			for (int i = 0; i < 2; i++) data.add(new AbstractMap.SimpleEntry<>(contexts[i].getValue(), expressions[i].getValue()));
			SheetEdits.saveAdvanced(data, category, index, subIndex);
		}
		this.onClose();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		for (EditBox box : expressions) if (box.isMouseOver(mouseX, mouseY)) lastFocused = box;
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
		this.renderPanel(g);
		g.drawCenteredString(this.font, GuiStyle.title(this.title), this.width / 2, dialogTop() + 10, GuiStyle.TITLE_COLOR);
		GuiStyle.ruleOrnate(g, dialogLeft() + 14, dialogLeft() + dialogW - 14, dialogTop() + 24);

		JsonObject sheet = SheetLoader.getClientSheet();
		Player player = Minecraft.getInstance().player;
		int level = player == null || sheet == null ? 1 : SheetLoader.characterLevelOf(sheet, player);
		int y = 36;
		for (int i = 0; i < expressions.length; i++) {
			if (advanced) {
				g.drawString(this.font, Component.translatable("gui.dndsheets.roll_editor.label_roll_context"), dialogLeft() + 20, dialogTop() + y, GuiStyle.ACCENT_COLOR, false);
				y += 30;
			}
			Component label = advanced
				? Component.translatable(i == 0 ? "gui.dndsheets.roll_editor.label_roll_editor_1" : "gui.dndsheets.roll_editor.label_roll_editor_2")
				: Component.translatable("gui.dndsheets.roll_editor.label_roll_expression");
			g.drawString(this.font, label, dialogLeft() + 20, dialogTop() + y, GuiStyle.ACCENT_COLOR, false);
			int bonus = SheetMath.bonusOf(sheet, level, expressions[i].getValue());
			Component preview = Component.translatable("gui.dndsheets.roll_editor.preview", SheetMath.signed(bonus));
			g.drawString(this.font, preview, dialogLeft() + dialogW - 20 - this.font.width(preview), dialogTop() + y, GuiStyle.MUTED_COLOR, false);
			y += 36;
		}
		g.drawString(this.font, Component.translatable("gui.dndsheets.roll_editor.label_insert_modifiers"), dialogLeft() + 20, dialogTop() + y, GuiStyle.ACCENT_COLOR, false);
		super.render(g, mouseX, mouseY, partialTicks);
	}
}
