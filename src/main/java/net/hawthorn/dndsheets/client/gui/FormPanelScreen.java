package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.client.gui.components.DirectionalCycleButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>The <b>panel-on-parchment</b> and its arithmetic: the frame, the title, the rule, the clamp that
 * keeps it from running off the top, the rows that stack up, and the {@code EditBox}es with their label
 * above. Nothing else. It doesn't decide what buttons go at the bottom or when anything gets sent.</p>
 *
 * <p>It exists because two screens were writing this same thing separately. {@link SmallFormScreen} is a
 * form: it gets filled in and <i>Confirm</i> is pressed once. {@link SheetAdjustScreen} is a control
 * panel: ten independent actions that each apply on their own, with no <i>Confirm</i> at all. They're
 * two different contracts, which is why the latter could never extend the former — but the frame they
 * draw is the same, and that part really was duplicated: {@code parseIntOr}, {@code cycleLabel}, the
 * {@code onClose} that returns to the parent, the fields' {@code tick}, the {@code Math.max(44, ...)},
 * and the panel's 14px padding were all written twice.</p>
 *
 * <p>The detail that gave it away: {@code SmallFormScreen} carried the comment <i>"Same clamp as
 * SheetAdjustScreen"</i>. The base class had copied the number from the screen that went its own way,
 * not the other way around. With this, the number lives in one place.</p>
 */
public abstract class FormPanelScreen extends Screen {

	protected static final int FIELD_WIDTH = 160;
	protected static final int FIELD_HEIGHT = 20;
	//30, not 26: leaves 10px free above each field for its label (see addField) without overlapping the
	//previous field — before this, no field on this form showed on screen what it was for, only its
	//narration Component (invisible, screen readers only) and the default value already typed in.
	protected static final int ROW_HEIGHT = 30;
	//Height of the header band above the first row: it has to leave room for the title (8px), the rule,
	//and the first field's label, which is drawn at formTop-10.
	protected static final int TITLE_BAND = 34;

	private final int titleRows;
	private final Screen parent;
	private final List<EditBox> editBoxes = new ArrayList<>();
	private final List<String> editBoxLabels = new ArrayList<>();
	protected int centerX;
	protected int formTop;
	private int cursorY;
	protected int formBottom;

	protected FormPanelScreen(Component title, int titleRows, Screen parent) {
		super(title);
		this.titleRows = titleRows;
		this.parent = parent;
	}

	/** Adds the panel's rows, in order, with addField(...)/addFieldRow(...)/addCycleButton(...). */
	protected abstract void buildForm();

	/**
	 * <p>A row's width. Defaults to {@link #FIELD_WIDTH}, which is what the screens that inherit from
	 * {@link SmallFormScreen} have always used: as long as nobody overrides it, the arithmetic is
	 * identical.</p>
	 */
	protected int formWidth() {
		return FIELD_WIDTH;
	}

	/** Vertical spacing between rows. A tight panel can lower it to fit more. */
	protected int rowHeight() {
		return ROW_HEIGHT;
	}

	/** Height of the header band. A panel that paints a second line under the title needs more. */
	protected int titleBand() {
		return TITLE_BAND;
	}

	/** Height per row for the initial centering: how many rows fit above the center. */
	protected final void layoutTop() {
		centerX = this.width / 2;
		//Without this cap, a panel with many rows centered on height/2 pushed the first ones (and its
		//title) off screen on short windows or with a high GUI Scale, instead of just ending up cramped:
		//the top couldn't be seen or clicked.
		formTop = Math.max(44, this.height / 2 - rowHeight() * titleRows);
		cursorY = formTop;
		editBoxes.clear();
		editBoxLabels.clear();
	}

	protected final int nextRowY() {
		int y = cursorY;
		cursorY += rowHeight();
		return y;
	}

	/** A text box (still typeable) with a "..." button that opens {@link ChoiceScreen} for {@code source} and fills it. */
	protected EditBox addPickField(String label, String defaultValue, int maxLength, String source, boolean multi) {
		int y = nextRowY();
		int left = centerX - formWidth() / 2;
		EditBox box = registerBox(label, defaultValue, maxLength, left, y, formWidth() - 24);
		this.addRenderableWidget(net.hawthorn.dndsheets.client.gui.components.TomeButton.of(net.minecraft.network.chat.Component.literal("..."),
			b -> ChoiceScreen.request(source, multi, box.getValue(), box::setValue), left + formWidth() - 20, y, 20, FIELD_HEIGHT));
		return box;
	}

	protected EditBox addField(String label, String defaultValue, int maxLength) {
		int y = nextRowY();
		return registerBox(label, defaultValue, maxLength, centerX - formWidth() / 2, y, formWidth());
	}

	/**
	 * <p>With an explicit position, for a row that mixes a field with buttons that don't belong to this
	 * panel (apply, set...). It still gets registered, so it inherits the cursor's {@code tick} and its
	 * label above just the same — which is exactly what used to get forgotten when placing them by hand
	 * with {@code new EditBox}.</p>
	 */
	protected EditBox addFieldAt(String label, String defaultValue, int maxLength, int y, int x, int width) {
		return registerBox(label, defaultValue, maxLength, x, y, width);
	}

	/**
	 * <p>Several fields in ONE row, spread across {@link #formWidth()} with 4px of spacing.</p>
	 *
	 * <p>The split is computed, not hand-written: {@code SheetAdjustScreen} had two fixed 90px fields
	 * inside 190px, and when it wanted to fit a button into that same row it was left with 190-188 =
	 * <b>2 pixels</b> of width, practically impossible to click. That's one of the project's two worst
	 * layout bugs, and it comes straight from eyeballing the split.</p>
	 */
	protected EditBox[] addFieldRow(String[] labels, String[] defaults, int maxLength) {
		int y = nextRowY();
		int columns = labels.length;
		int gap = 4;
		int width = (formWidth() - gap * (columns - 1)) / columns;
		int left = centerX - formWidth() / 2;

		EditBox[] boxes = new EditBox[columns];
		for (int i = 0; i < columns; i++) {
			boxes[i] = registerBox(labels[i], defaults[i], maxLength, left + i * (width + gap), y, width);
		}
		return boxes;
	}

	private EditBox registerBox(String label, String defaultValue, int maxLength, int x, int y, int width) {
		EditBox box = new EditBox(this.font, x, y, width, FIELD_HEIGHT, Component.literal(label));
		//The cap FIRST and the value after, never the other way around. Both calls truncate: setValue cuts
		//to whatever maxLength is worth AT THAT MOMENT, and a freshly constructed EditBox comes with 32 —
		//vanilla's default — so filling in before raising the cap truncated every prefilled form to 32
		//characters. It showed up two steps removed and wearing a different face: a saved encounter with
		//"dndsheets:adult_bronze_dragon, d" got summoned as "ids that don't exist", with nothing pointing
		//back to this line.
		//The maximum, moreover, never goes below what the screen just prefilled: a cap exists for what the
		//user TYPES, and what was already there isn't "excess".
		box.setMaxLength(Math.max(maxLength, defaultValue.length()));
		box.setValue(defaultValue);
		this.addWidget(box);
		if (editBoxes.isEmpty()) this.setInitialFocus(box);
		editBoxes.add(box);
		editBoxLabels.add(label);
		return box;
	}

	protected CycleField addCycleButton(String prefix, String[] options) {
		return addCycleButton(prefix, options, options, 0);
	}

	//For options whose real value (saved/sent to the server) is an internal code that isn't clear to a
	//DM (e.g. "str"/"dex") — displayLabels is ONLY what's shown on the button, in the same order as
	//options; CycleField.value() still returns options' internal code, not the displayed text.
	protected CycleField addCycleButton(String prefix, String[] options, String[] displayLabels) {
		return addCycleButton(prefix, options, displayLabels, 0);
	}

	//With an initial index: to prefill an EDIT form (see ContentFormScreen) with the value the entry
	//already had, instead of always starting at options[0] as if it were new.
	protected CycleField addCycleButton(String prefix, String[] options, String[] displayLabels, int initialIndex) {
		return addCycleButton(prefix, options, displayLabels, initialIndex, nextRowY(), centerX - formWidth() / 2, formWidth());
	}

	/** With an explicit position: for a panel that shares a row between a cycle field and its apply button. */
	protected CycleField addCycleButton(String prefix, String[] options, String[] displayLabels, int initialIndex,
			int y, int x, int width) {
		CycleField field = new CycleField(options);
		field.index = initialIndex >= 0 && initialIndex < options.length ? initialIndex : 0;
		field.button = this.addRenderableWidget(new DirectionalCycleButton(x, y, width, FIELD_HEIGHT,
			cycleLabel(prefix, displayLabels[field.index]),
			() -> {
				field.index = (field.index + 1) % options.length;
				field.button.setMessage(cycleLabel(prefix, displayLabels[field.index]));
			},
			() -> {
				field.index = (field.index - 1 + options.length) % options.length;
				field.button.setMessage(cycleLabel(prefix, displayLabels[field.index]));
			}));
		return field;
	}

	protected static int parseIntOr(String value, int fallback) {
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	protected static Component cycleLabel(String prefix, String value) {
		return Component.literal(prefix + ": " + value);
	}

	/** Cycle button with its current index — see addCycleButton. */
	protected static final class CycleField {
		private final String[] options;
		private Button button;
		private int index = 0;

		private CycleField(String[] options) {
			this.options = options;
		}

		public String value() {
			return options[index];
		}
	}

	/**
	 * <p>Frame, title, and rule. Called from each subclass's {@code render}, which decides what else to
	 * paint on top — a control panel might want a second read-only line under the title.</p>
	 */
	protected final void renderPanelChrome(GuiGraphics guiGraphics) {
		//The header needs its own band. The title used to be at formTop-16 (occupying up to formTop-8) and
		//the first field's label starts at formTop-10: two rows of pixels overlapped, and with Minecraft's
		//font that doesn't read as "close together", it reads as duplicated, smeared text.
		GuiStyle.panel(guiGraphics, centerX - formWidth() / 2 - 14, formTop - titleBand(), centerX + formWidth() / 2 + 14, formBottom);
		guiGraphics.drawCenteredString(this.font, title, this.width / 2, formTop - titleBand() + 6, GuiStyle.TITLE_COLOR);
		//Separator rule, the same resource ListPickerScreen uses for its header.
		GuiStyle.rule(guiGraphics, centerX - formWidth() / 2 - 6, centerX + formWidth() / 2 + 6, formTop - 18);
	}

	/** The fields with their label above. Goes after super.render so it stays in front of the panel. */
	protected final void renderFields(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		for (int i = 0; i < editBoxes.size(); i++) {
			EditBox box = editBoxes.get(i);
			guiGraphics.drawString(this.font, editBoxLabels.get(i), box.getX(), box.getY() - 10, GuiStyle.TITLE_COLOR, false);
			box.render(guiGraphics, mouseX, mouseY, partialTicks);
		}
	}

	//Returns to the previous screen instead of closing the whole menu — same navigation mechanism as
	//ListPickerScreen, see that class.
	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	public final void tick() {
		for (EditBox box : editBoxes) box.tick();
	}

	@Override
	public final boolean isPauseScreen() {
		return false;
	}
}
