package net.hawthorn.dndsheets.client.gui;

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

	private final Screen parent;
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

	/** Root screen, with nothing to go back to (e.g. opened via keybind or right-click). */
	protected ListPickerScreen(Component title) {
		this(title, null);
	}

	/** {@code parent} is the screen "&lt; Back"/Escape should return to; null if this is the root screen of its flow. */
	protected ListPickerScreen(Component title, Screen parent) {
		super(title);
		this.parent = parent;
	}

	/** Width of the list buttons and the panel. Override for a wider list (e.g. the Spellbook). */
	protected int buttonWidth() {
		return 200;
	}

	/** true forces the search box even if the list is short. With {@value #AUTO_SEARCH_MIN_ROWS}+ rows it appears on its own — see {@code init()}. */
	protected boolean searchable() {
		return false;
	}

	/** Y where the list starts, below the title (and the search box, if {@link #searchable()}). Override to leave room for a subtitle. */
	/** Left edge of the panel. Used by init() (to place "< Back") and render(), which must agree. */
	private int panelLeft() {
		return (this.width - buttonWidth()) / 2 - PANEL_PADDING;
	}

	protected int listTop() {
		return searchActive ? LIST_TOP + SEARCH_HEIGHT + SEARCH_GAP : LIST_TOP;
	}

	/** Height available for the list. Override to leave room for a fixed button underneath. */
	protected int listHeight() {
		return this.height - listTop() - 14;
	}

	/** Adds the list rows, in order, with {@link #addRow}. Called from {@code init()}. */
	protected abstract void buildRows();

	/** Text to show centered on screen if the list ends up empty. Null = show nothing. */
	protected Component emptyMessage() {
		return null;
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
	protected final void addHeader(Component label) {
		Button header = new SectionHeader(label, buttonWidth(), HEADER_HEIGHT);
		this.addWidget(header);
		allButtons.add(header);
		allLabels.add(null);
	}

	@Override
	protected void init() {
		allButtons.clear();
		allLabels.clear();

		buildRows();

		searchActive = searchable() || allButtons.size() >= AUTO_SEARCH_MIN_ROWS;
		if (searchActive) {
			searchBox = new EditBox(this.font, (this.width - buttonWidth()) / 2, LIST_TOP, buttonWidth(), SEARCH_HEIGHT, Component.translatable("gui.dndsheets.common.search"));
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

		list = new ButtonListWidget((this.width - buttonWidth()) / 2, listTop(), buttonWidth(), listHeight(), SPACING);
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
		int right = this.width - left;
		//The header sits at a fixed spot (further down), so the panel's top edge does too, instead of
		//depending on listTop() — a screen with a subtitle (Spellbook) moves listTop() further down
		//without leaving the title sticking out above the panel.
		int top = 16 - PANEL_PADDING;
		int bottom = listTop() + listHeight() + PANEL_PADDING;
		GuiStyle.panel(guiGraphics, left, top, right, bottom);

		//With "< Back" the title is centered in the space LEFT to its right, not the whole screen:
		//centered on the screen, a long title would run under the button.
		int titleLeft = parent != null ? left + 4 + BACK_BUTTON_WIDTH : left;
		guiGraphics.drawCenteredString(this.font, this.title, (titleLeft + right) / 2, TITLE_Y, GuiStyle.TITLE_COLOR);
		//Rule under the title: separates the header from the content without spending a whole row of
		//height, which is what a real separator would cost in a scrolling list. Ornate (center diamond)
		//only here — internal separators use the plain rule (see GuiStyle.ruleOrnate).
		GuiStyle.ruleOrnate(guiGraphics, left + 8, right - 8, 28);

		Component empty = emptyMessage();
		if (empty != null) {
			guiGraphics.drawCenteredString(this.font, empty, this.width / 2, this.height / 2, GuiStyle.MUTED_COLOR);
		}

		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
