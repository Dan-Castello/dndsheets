package net.hawthorn.dndsheets.client.gui;

import javax.annotation.Nullable;
import net.hawthorn.dndsheets.client.gui.components.ButtonListWidget;
import net.hawthorn.dndsheets.client.gui.components.SectionHeader;
import net.hawthorn.dndsheets.client.gui.components.TomeButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <p>Shared base for "vertical list of buttons" screens: centered title + bordered panel behind a
 * {@link ButtonListWidget} that scrolls automatically when not all the buttons fit. Covers both the DM
 * Panel selectors (player, preset, trait, character option, attack to remove, monster action) and the
 * fixed-button menus (DM Panel, Turn mode) — a fixed menu is just a list that never needs to scroll.
 * Previously each one repeated the same layout constants, the {@code mouseScrolled} forwarding to the
 * list, and {@code isPauseScreen() -> false}.</p>
 *
 * <p>{@code init()} and {@code render()} are not {@code final}: a screen with extra content (a
 * subtitle, a fixed button below the list) can override them, call {@code super} first and add its own
 * on top — simpler than trying to anticipate every variant with parameters.</p>
 *
 * <p><b>Navigation:</b> if this screen was opened from another one (DM Panel, player selector...),
 * pass that screen as {@code parent}. Picking a row, pressing "&lt; Back" or Escape returns to
 * {@code parent} instead of closing the whole menu — previously only {@code CharacterOptionListScreen}
 * did this by hand; now it's the base class's default behavior. Without {@code parent} (root screens
 * like {@code DmPanelScreen}/{@code MonsterActionScreen}) it behaves as before: it closes the menu.</p>
 */
public abstract class ListPickerScreen extends Screen {
	protected static final int BUTTON_HEIGHT = 20;
	protected static final int SPACING = 4;
	//A header is a label between rules: at the height of a button row there was extra space left above
	//and below, and five of them (DM Panel) cost five visible list rows. See ButtonListWidget, which now
	//takes each row's height from the button itself.
	private static final int HEADER_HEIGHT = 11;
	private static final int LIST_TOP = 30;
	private static final int PANEL_PADDING = 10;
	private static final int BACK_BUTTON_WIDTH = 50;
	private static final int BACK_BUTTON_HEIGHT = 14;
	//"< Back" and the title share the header row, so their coordinates have to come from the same
	//constants. Previously the button was placed in init() and the title in render() independently, and
	//they collided: the button landed at y=8..22 and the title at y=16..24, plus horizontal overlap as
	//soon as the title got long ("Choose a class preset"). It read like duplicated text.
	private static final int BACK_BUTTON_TOP = 10;
	/** Vertical center of the button, so the title label sits at the same height. */
	private static final int TITLE_Y = BACK_BUTTON_TOP + (BACK_BUTTON_HEIGHT - 8) / 2;
	private static final int SEARCH_HEIGHT = 16;
	private static final int SEARCH_GAP = 6;
	//Row count above which the search box appears BY ITSELF, without the screen asking for it: at this
	//row height, ~14 is where a list stops being visible at a glance in the default window. Previously
	//it was opt-in per screen (searchable()) and the long DM Panel lists just weren't asking for it.
	private static final int AUTO_SEARCH_MIN_ROWS = 14;

	//The key that opened this menu (P, H, G...) sends its character right after the screen appears, and the search
	//box grabs focus at once, so the letter ended up typed in it. Characters in the first instants are dropped.
	private final long openedAt = net.minecraft.Util.getMillis();

	protected final Screen parent;
	private ButtonListWidget list;
	private EditBox searchBox;
	private boolean searchActive;
	//Survives rebuildWidgets(): several screens get rebuilt when the server sheet arrives
	//(refreshIfOpen), and without this every arrival would wipe out whatever was typed in the search box
	//mid-search.
	private String lastQuery = "";
	//Same as lastQuery, and for the same reason: marking a proficiency (SkillProficiencyScreen) sends
	//the sheet to the server, the sheet comes back, refreshIfOpen rebuilds the screen — and the new list
	//started back at the top every time. With eighteen skills that's scrolling back down after EVERY
	//mark.
	private double lastScroll = 0;
	//All the rows created by buildRows(), not just the visible ones — list.replaceRows(...) receives the
	//subset that matches the typed text (see applyFilter()). A null label marks a section header: it
	//never matches a search, so headers disappear when filtering and only results remain.
	private final List<Button> allButtons = new ArrayList<>();
	private final List<String> allLabels = new ArrayList<>();
	//A search is active and nothing matches: say so instead of leaving the panel blank.
	private boolean noMatches;

	/** Root screen, with nothing to go back to (e.g. opened via keybind or right-click). */
	protected ListPickerScreen(Component title) {
		this(title, null);
	}

	/** {@code parent} is the screen "&lt; Back"/Escape should return to; null if this is the root screen of its flow. */
	protected ListPickerScreen(Component title, Screen parent) {
		super(title);
		this.parent = parent;
	}

	/**
	 * Width of the list buttons and the panel: grows with the window (2/5 of it, 200 to 300px), so a big
	 * window or a small GUI scale doesn't leave a narrow column with long names cut. Override for a fixed width.
	 */
	protected int buttonWidth() {
		return Math.max(200, Math.min(300, this.width * 2 / 5));
	}

	/** true forces the search box even if the list is short. With {@value #AUTO_SEARCH_MIN_ROWS}+ rows it appears on its own — see {@code init()}. */
	protected boolean searchable() {
		return false;
	}

	/**
	 * Width of a pane to the RIGHT of the list, inside the same panel (0 = none, the default). A screen
	 * that shows detail for the selected row (Grimoire) draws and places its widgets there, from
	 * {@link #sidePaneLeft()}, instead of stacking fixed rows under the list.
	 */
	protected int sidePaneWidth() {
		return 0;
	}

	private int contentWidth() {
		return sidePaneWidth() > 0 ? buttonWidth() + 3 * SPACING + sidePaneWidth() : buttonWidth();
	}

	/** Left edge of the list (and of the search box above it). */
	protected int listLeft() {
		return (this.width - contentWidth()) / 2;
	}

	protected int sidePaneLeft() {
		return listLeft() + buttonWidth() + 3 * SPACING;
	}

	/** Left edge of the panel. Used by init() (to place "< Back") and render(), which must agree. */
	private int panelLeft() {
		return listLeft() - PANEL_PADDING;
	}

	/** Y where the list starts, below the title (and the search box, if {@link #searchable()}). Override to leave room for a subtitle. */

	/** Y of the first thing under the title rule (search box, or the list). Override to leave a band for a subtitle above both. */
	protected int contentTop() {
		return LIST_TOP;
	}

	protected int listTop() {
		return searchActive ? contentTop() + SEARCH_HEIGHT + SEARCH_GAP : contentTop();
	}

	/** Height available for the list. Override to leave room for a fixed button underneath. */
	protected int listHeight() {
		return this.height - listTop() - 14;
	}

	/** Adds the list rows, in order, with {@link #addRow}. Called from {@code init()}. */
	protected abstract void buildRows();

	/** Text to show centered on screen if the list ends up empty. Null = show nothing. */
	@Nullable
	protected Component emptyMessage() {
		return null;
	}

	/** Same as {@link #addRow(Component, Button.OnPress)} with a glyph at the left of the row; null icon = plain row. */
	protected final Button addRow(Component label, @Nullable net.minecraft.resources.ResourceLocation icon, Button.OnPress onPress) {
		Button button = addRow(label, onPress);
		if (icon != null && button instanceof TomeButton tome) tome.withIcon(icon);
		return button;
	}

	protected final Button addRow(Component label, Button.OnPress onPress) {
		//TomeButton, not Button: vanilla's gray stone button on top of a leather panel reads like a
		//widget borrowed from another interface. Changing it here repaints, from the inside, the forty-
		//plus screens hanging off this base, the same way GuiStyle did with its frames.
		Button button = TomeButton.of(label, onPress, 0, 0, buttonWidth(), BUTTON_HEIGHT);
		this.addWidget(button);
		//Always into the collection, never straight into "list": the visible list is assembled in
		//applyFilter() AFTER buildRows(), which is when it's already known how many rows there are (and
		//with that, whether there's a search box).
		allButtons.add(button);
		allLabels.add(label.getString().toLowerCase(Locale.ROOT));
		return button;
	}

	/** Section header: a label between rules, not clickable and excluded from search. For long menus (see DM Panel). */
	protected final Button addHeader(Component label) {
		Button header = new SectionHeader(label, buttonWidth(), HEADER_HEIGHT);
		this.addWidget(header);
		allButtons.add(header);
		allLabels.add(null);
		return header;
	}

	@Override
	protected void init() {
		allButtons.clear();
		allLabels.clear();

		buildRows();

		searchActive = searchable() || allButtons.size() >= AUTO_SEARCH_MIN_ROWS;
		if (searchActive) {
			searchBox = new EditBox(this.font, listLeft(), contentTop(), buttonWidth(), SEARCH_HEIGHT, Component.translatable("gui.dndsheets.common.search"));
			searchBox.setHint(Component.translatable("gui.dndsheets.common.search_hint"));
			//setValue BEFORE setResponder: the responder calls applyFilter, and "list" doesn't exist yet
			//at this point in init — filtering with the restored text is done by the applyFilter call below.
			searchBox.setValue(lastQuery);
			searchBox.setResponder(text -> {
				lastQuery = text;
				applyFilter();
			});
			this.addRenderableWidget(searchBox);
			this.setInitialFocus(searchBox);
		} else {
			searchBox = null;
		}

		list = new ButtonListWidget(listLeft(), listTop(), buttonWidth(), listHeight(), SPACING);
		applyFilter();
		//After applyFilter(): the scroll ceiling comes from the height of the rows the list currently
		//has, and setScrollAmount clamps on its own if the list ended up shorter.
		list.scrollTo(lastScroll);
		this.addRenderableWidget(list);

		if (parent != null) {
			this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.common.back"), b -> this.onClose(),
				panelLeft() + 4, BACK_BUTTON_TOP, BACK_BUTTON_WIDTH, BACK_BUTTON_HEIGHT));
		}
	}

	//Rebuilds the visible list with whatever matches the typed text (everything, if there's no search
	//box or it's empty) — O(n²) due to replaceRows' contains(), acceptable for content-sized lists
	//(tens of rows, not thousands). Headers (null label) only show up with no filter: during a search
	//only results remain, without labels for emptied-out sections.
	private void applyFilter() {
		String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
		List<Button> visible = new ArrayList<>();
		for (int i = 0; i < allButtons.size(); i++) {
			String label = allLabels.get(i);
			if (label == null ? query.isEmpty() : (query.isEmpty() || label.contains(query))) {
				visible.add(allButtons.get(i));
			}
		}
		list.replaceRows(visible);
		noMatches = !query.isEmpty() && visible.isEmpty();
	}

	@Override
	public void tick() {
		if (searchBox != null) searchBox.tick();
	}

	//Return to the previous screen instead of closing the whole menu — setScreen(null) when there's no
	//parent exactly replicates Screen#onClose()'s default behavior.
	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	public boolean charTyped(char codePoint, int modifiers) {
		if (net.minecraft.Util.getMillis() - openedAt < 200) return true;
		return super.charTyped(codePoint, modifiers);
	}

	//Enter in the search box presses the first matching row: type, Enter, done.
	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if ((keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)
			&& searchBox != null && searchBox.isFocused()) {
			String query = searchBox.getValue().trim().toLowerCase(Locale.ROOT);
			for (int i = 0; i < allButtons.size(); i++) {
				String label = allLabels.get(i);
				Button b = allButtons.get(i);
				if (label != null && b.active && (query.isEmpty() || label.contains(query))) {
					b.onPress();
					return true;
				}
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		boolean handled = list.mouseScrolled(mouseX, mouseY, delta);
		if (handled) lastScroll = list.scroll();
		return handled || super.mouseScrolled(mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);

		int left = panelLeft();
		int right = listLeft() + contentWidth() + PANEL_PADDING;
		//The header sits at a fixed spot (further down), so the panel's top edge does too, instead of
		//depending on listTop() — a screen with a subtitle (Spellbook) moves listTop() further down
		//without leaving the title sticking out above the panel.
		int top = 16 - PANEL_PADDING;
		int bottom = listTop() + listHeight() + PANEL_PADDING;
		GuiStyle.panel(guiGraphics, left, top, right, bottom);

		//With "< Back" the title is centered in the space LEFT to its right, not the whole screen:
		//centered on the screen, a long title would run under the button.
		int titleLeft = parent != null ? left + 4 + BACK_BUTTON_WIDTH : left;
		//Cut to the room it really has (beside "< Back"), whole text on hover: long prompts ("Choose who
		//receives the effect", "Edit trait: ...") used to run under the button or past the panel's edge.
		Component title = GuiStyle.title(this.title);
		int titleRoom = right - titleLeft - 12;
		guiGraphics.drawCenteredString(this.font, GuiStyle.fit(this.font, title, titleRoom), (titleLeft + right) / 2, TITLE_Y, GuiStyle.TITLE_COLOR);
		boolean titleCut = this.font.width(title) > titleRoom;
		//Rule under the title: separates the header from the content without spending a whole row of
		//height, which is what a real separator would cost in a scrolling list. Ornate (center diamond)
		//only here — internal separators use the plain rule (see GuiStyle.ruleOrnate).
		GuiStyle.ruleOrnate(guiGraphics, left + 8, right - 8, 28);

		Component empty = noMatches ? Component.translatable("gui.dndsheets.common.no_results", searchBox.getValue().trim())
			: allButtons.isEmpty() && emptyMessage() == null ? Component.translatable("gui.dndsheets.common.empty")
			: emptyMessage();
		if (empty != null) {
			//Wrapped to the list: a one-line hint like "No presets loaded (ask the DM...)" was wider than the panel.
			List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(empty, buttonWidth());
			int y = this.height / 2 - lines.size() * 5;
			//Below the rows a screen keeps next to its empty message (Dungeon pieces' action rows), not on top of them.
			for (Button b : allButtons) if (b.visible) y = Math.max(y, b.getY() + b.getHeight() + 6);
			for (net.minecraft.util.FormattedCharSequence line : lines) {
				guiGraphics.drawCenteredString(this.font, line, listLeft() + buttonWidth() / 2, y, GuiStyle.MUTED_COLOR);
				y += 10;
			}
		}

		super.render(guiGraphics, mouseX, mouseY, partialTicks);
		//Brass ring over vanilla's white one, same as every form field (see FormPanelScreen.renderFields).
		if (searchBox != null) net.hawthorn.dndsheets.client.gui.components.TomeField.frameWidget(guiGraphics, searchBox.getX(), searchBox.getY(), searchBox.getWidth(), searchBox.getHeight(), searchBox.isFocused());
		if (titleCut && mouseX >= titleLeft && mouseX < right && mouseY >= TITLE_Y - 2 && mouseY < TITLE_Y + 10) {
			guiGraphics.renderTooltip(this.font, this.title, mouseX, mouseY);
		}
	}
}
