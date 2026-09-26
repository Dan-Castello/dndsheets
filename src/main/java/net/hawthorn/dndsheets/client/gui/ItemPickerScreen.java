package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.client.gui.components.TomeButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * <p>Icon grid to pick a registered item (JEI-style: search, scroll, hover for the tooltip, click to choose)
 * instead of typing its id. Deliberately generic: it knows nothing about who asked — the caller passes what
 * to do with the pick ({@code onPick} gets the registry id, e.g. {@code minecraft:iron_sword}) and an
 * optional filter, so any screen that needs an item can reuse it.</p>
 *
 * <p>Single mode answers on the first click. Multi mode toggles items (highlighted) and answers with the
 * comma-separated list when "Done" is pressed; whatever the field already held that isn't a plain item id
 * ("minecraft:arrow x20") is kept as written while it stays selected.</p>
 */
public class ItemPickerScreen extends Screen {
	private static final int CELL = 20;
	private static final int TOP = 56;

	private final Screen parent;
	private final Consumer<String> onPick;
	private final boolean multi;
	private final List<ItemStack> all = new ArrayList<>();
	//id -> the text the field had for it (or the bare id), in the order they were chosen.
	private final Map<String, String> selected = new LinkedHashMap<>();
	private List<ItemStack> shown = new ArrayList<>();
	private EditBox search;
	private String lastQuery = "";
	private int scrollRows = 0;
	private int columns, visibleRows;

	private ItemPickerScreen(Screen parent, Predicate<Item> filter, String currentCsv, boolean multi, Consumer<String> onPick) {
		super(Component.translatable("gui.dndsheets.item_picker.title"));
		this.parent = parent;
		this.onPick = onPick;
		this.multi = multi;
		for (Item item : ForgeRegistries.ITEMS) {
			if (item != Items.AIR && filter.test(item)) all.add(new ItemStack(item));
		}
		if (multi) {
			for (String raw : currentCsv.split(",")) {
				String token = raw.trim();
				if (!token.isEmpty()) selected.put(token.split("\\s+")[0], token);
			}
		}
	}

	/** Single pick over the current screen; "Back"/Escape returns to it without picking. */
	public static void open(Consumer<String> onPick) {
		open(item -> true, "", false, onPick);
	}

	public static void open(Predicate<Item> filter, Consumer<String> onPick) {
		open(filter, "", false, onPick);
	}

	/** {@code currentCsv} is what the field holds now; with {@code multi} the answer is the new comma-separated list. */
	public static void open(Predicate<Item> filter, String currentCsv, boolean multi, Consumer<String> onPick) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new ItemPickerScreen(minecraft.screen, filter, currentCsv, multi, onPick));
	}

	@Override
	protected void init() {
		int gridWidth = Math.min(this.width - 40, CELL * 14);
		columns = Math.max(1, gridWidth / CELL);
		visibleRows = Math.max(1, (this.height - TOP - 24) / CELL);

		search = new EditBox(this.font, (this.width - gridWidth) / 2, 32, gridWidth, 18, Component.translatable("gui.dndsheets.common.search"));
		search.setHint(Component.translatable("gui.dndsheets.common.search_hint"));
		search.setValue(lastQuery);
		search.setResponder(text -> {
			lastQuery = text;
			scrollRows = 0;
			applyFilter();
		});
		this.addRenderableWidget(search);
		this.setInitialFocus(search);
		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.common.back"), b -> this.onClose(), 8, 8, 60, 18));
		if (multi) {
			this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.choice.done"), b -> {
				Minecraft.getInstance().setScreen(parent);
				onPick.accept(String.join(", ", selected.values()));
			}, this.width - 68, 8, 60, 18));
		}
		applyFilter();
	}

	private void applyFilter() {
		String query = lastQuery.trim().toLowerCase(Locale.ROOT);
		shown = new ArrayList<>();
		for (ItemStack stack : all) {
			if (query.isEmpty()
				|| idOf(stack).contains(query)
				|| stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)) {
				shown.add(stack);
			}
		}
	}

	private static String idOf(ItemStack stack) {
		return ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
	}

	private int gridLeft() {
		return (this.width - columns * CELL) / 2;
	}

	private int maxScroll() {
		return Math.max(0, (shown.size() + columns - 1) / columns - visibleRows);
	}

	/** Index into {@code shown} under the mouse, or -1. */
	private int cellAt(double mouseX, double mouseY) {
		int col = (int) Math.floor((mouseX - gridLeft()) / CELL);
		int row = (int) Math.floor((mouseY - TOP) / CELL);
		if (mouseX < gridLeft() || col >= columns || mouseY < TOP || row >= visibleRows) return -1;
		int index = (scrollRows + row) * columns + col;
		return index < shown.size() ? index : -1;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		int index = cellAt(mouseX, mouseY);
		if (index >= 0 && button == 0) {
			String id = idOf(shown.get(index));
			if (multi) {
				if (selected.remove(id) == null) selected.put(id, id);
			} else {
				//Back to the caller first, then the pick: the callback usually edits a field of that screen.
				Minecraft.getInstance().setScreen(parent);
				onPick.accept(id);
			}
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		scrollRows = Math.max(0, Math.min(maxScroll(), scrollRows - (int) Math.signum(delta)));
		return true;
	}

	@Override
	public void tick() {
		search.tick();
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);
		int left = gridLeft() - 6;
		GuiStyle.panel(guiGraphics, left, 4, this.width - left, TOP + visibleRows * CELL + 6);
		guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 12, GuiStyle.TITLE_COLOR);

		int hovered = cellAt(mouseX, mouseY);
		for (int i = 0; i < visibleRows * columns; i++) {
			int index = scrollRows * columns + i;
			if (index >= shown.size()) break;
			int x = gridLeft() + (i % columns) * CELL;
			int y = TOP + (i / columns) * CELL;
			boolean picked = multi && selected.containsKey(idOf(shown.get(index)));
			guiGraphics.fill(x, y, x + CELL - 1, y + CELL - 1, picked ? 0xAA4F9B46 : index == hovered ? 0x66FFD9A0 : 0x44000000);
			guiGraphics.renderItem(shown.get(index), x + 2, y + 2);
		}
		if (shown.isEmpty()) {
			guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.item_picker.empty"), this.width / 2, TOP + 20, GuiStyle.MUTED_COLOR);
		}

		super.render(guiGraphics, mouseX, mouseY, partialTicks);
		if (hovered >= 0) guiGraphics.renderTooltip(this.font, shown.get(hovered), mouseX, mouseY);
	}
}
