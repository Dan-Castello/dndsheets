package net.hawthorn.dndsheets.dungeon.client.gui;
import net.minecraft.client.resources.language.I18n;
import net.hawthorn.dndsheets.client.gui.ModalDialogScreen;
import net.hawthorn.dndsheets.client.gui.GuiStyle;

import net.hawthorn.dndsheets.dungeon.DndsheetsDungeonMod;
import net.hawthorn.dndsheets.dungeon.GridToStructure;
import net.hawthorn.dndsheets.dungeon.network.DungeonTraceCaptureMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * <p>Draws a dungeon piece cell by cell instead of building it by hand and scanning it with a structure
 * block. Pressing "Save" sends the grid to the server ({@link DungeonTraceCaptureMessage}), which
 * translates it ({@link GridToStructure}) and plants it in the world ({@code DungeonPiecePlacer}) — the
 * result is registered exactly the same as a piece scanned by hand.</p>
 *
 * <p>Object cells don't carry a fixed block: "Choose block..." opens {@link BlockPickerScreen}, which
 * searches the global block registry — from any installed mod, furniture included — so no special
 * per-mod compatibility is needed.</p>
 *
 * <p>Door and Start have their own destination pool ("Door pool") and their own manual direction — the
 * same "Rotate" button Object uses, reused: for Door/Start, "Auto" is {@link GridToStructure}'s heuristic
 * (first empty N/E/S/W neighbor); any other value overrides it.</p>
 */
public class DungeonTraceScreen extends ModalDialogScreen {
	//Fixed pixel budget for the canvas: the dialog doesn't change size (ModalDialogScreen fixes it in the
	//constructor), so a bigger grid comes out with smaller cells, not a bigger window. Cell size is
	//derived from this, not the other way around.
	private static final int CANVAS_AREA = 200;
	//24 is the practical ceiling, not the server's (MAX_SIDE=64 in DungeonTraceCaptureMessage): beyond
	//that the cell drops below ~8px and stops being a comfortable mouse target. The real limit is click
	//precision, not an arbitrary protocol number.
	private static final int[] GRID_SIZES = {8, 10, 12, 16, 20, 24};
	private static final int DEFAULT_GRID_INDEX = 2; //12
	private static final int CANVAS_Y = 98;
	private static final int MIN_HEIGHT = 1;
	private static final int MAX_HEIGHT = 10;
	private static final int MAX_UNDO = 20;
	private static final int MAX_ORIGIN_OFFSET = 16;
	private static final int MAX_RECENT_OBJECTS = 5;
	//null = "Auto": for Object, don't touch orientation; for Door/Start, GridToStructure's heuristic.
	//The rest are concrete manual directions.
	private static final Direction[] FACING_CYCLE = {null, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

	private enum PaintMode { FREE, RECT }

	private enum Tool {
		WALL(GridToStructure.Cell.WALL, "gui.dndsheets.dungeon_trace.tool_wall"),
		FLOOR(GridToStructure.Cell.FLOOR, "gui.dndsheets.dungeon_trace.tool_floor"),
		DOOR(GridToStructure.Cell.DOOR, "gui.dndsheets.dungeon_trace.tool_door"),
		START(GridToStructure.Cell.START, "gui.dndsheets.dungeon_trace.tool_start"),
		OBJECT(GridToStructure.Cell.OBJECT, "gui.dndsheets.dungeon_trace.tool_object"),
		ERASE(GridToStructure.Cell.EMPTY, "gui.dndsheets.dungeon_trace.tool_erase");

		final GridToStructure.Cell cell;
		final String label;

		Tool(GridToStructure.Cell cell, String label) {
			this.cell = cell;
			this.label = label;
		}
	}

	private final Screen parent;
	private final String id, structureId, pool, tags;
	private final int weight;

	private int gridIndex = DEFAULT_GRID_INDEX;
	private int gridSize;
	private int cell;
	private int canvas;

	private GridToStructure.Cell[][] grid;
	private GridToStructure.CellOptions[][] options;
	private Tool currentTool = Tool.WALL;
	private ResourceLocation currentObjectBlock = null;
	//Shared between Object (rotation) and Door/Start (manual direction) — see the class Javadoc.
	private Direction currentFacing = null;
	private ResourceLocation wallBlock = null;
	private ResourceLocation floorBlock = null;
	private int height = 3;

	private PaintMode paintMode = PaintMode.FREE;
	//Rectangle in progress: null when none is being dragged. Only meaningful in RECT mode.
	private int rectStartX = -1, rectStartZ = -1, rectEndX = -1, rectEndZ = -1;
	private boolean rectDragging = false;

	private int originDx = 0, originDz = 0;

	//One snapshot per stroke (initial click), not per painted cell — undoing a whole drag at once is the
	//expected behavior; undoing cell by cell within the same stroke isn't. Cap of 20: the grid is small
	//(24x24 max), so neither memory nor the copy is a real problem.
	private record Snapshot(GridToStructure.Cell[][] grid, GridToStructure.CellOptions[][] options) {}
	private final Deque<Snapshot> undoStack = new ArrayDeque<>();

	//MRU of chosen object blocks: without this, switching between two different pieces of furniture
	//forces reopening the search every time, even if the DM already searched for it thirty seconds ago.
	private final Deque<ResourceLocation> recentObjectBlocks = new ArrayDeque<>();
	private final List<Button> recentButtons = new ArrayList<>();

	private Button blockButton;
	private Button facingButton;
	private Button heightButton;
	private Button sizeButton;
	private Button wallBlockButton;
	private Button floorBlockButton;
	private Button modeButton;
	private Button undoButton;
	private Button originXButton;
	private Button originZButton;
	private EditBox doorPoolBox;
	private final List<Button> toolButtons = new ArrayList<>();

	private DungeonTraceScreen(String id, String structureId, String pool, int weight, String tags, Screen parent) {
		super(Component.translatable("gui.dndsheets.dungeon_trace.title"), 300, 380);
		this.id = id;
		this.structureId = structureId;
		this.pool = pool;
		this.weight = weight;
		this.tags = tags;
		this.parent = parent;
		resize(GRID_SIZES[gridIndex]);
	}

	//Changing size clears the grid — there's no sensible way to reproject already-painted cells onto a
	//different grid, and resizing mid-drawing is a rare case. The DM picks the size first, draws after.
	private void resize(int size) {
		gridSize = size;
		cell = Math.max(4, CANVAS_AREA / size);
		canvas = cell * size;
		grid = new GridToStructure.Cell[size][size];
		options = new GridToStructure.CellOptions[size][size];
		for (int z = 0; z < size; z++) {
			java.util.Arrays.fill(grid[z], GridToStructure.Cell.EMPTY);
			java.util.Arrays.fill(options[z], GridToStructure.CellOptions.EMPTY);
		}
	}

	/** The same five fields the DM already entered in {@link DungeonPieceAddScreen}: drawing is the other half of the same flow, not a new one. */
	public static void open(String id, String structureId, String pool, int weight, String tags, Screen parent) {
		Minecraft.getInstance().setScreen(new DungeonTraceScreen(id, structureId, pool, weight, tags, parent));
	}

	@Override
	protected void init() {
		toolButtons.clear();
		int toolWidth = 44, toolHeight = 16, gap = 2;
		int toolsLeft = (300 - (Tool.values().length * toolWidth + (Tool.values().length - 1) * gap)) / 2;
		for (int i = 0; i < Tool.values().length; i++) {
			Tool tool = Tool.values()[i];
			//No "> " or any other variable text: the row is already tight on width (6 buttons in 300px),
			//so the active tool is marked by disabling the button (active=false), not by adding text to it.
			Button button = addModalButton(toolsLeft + i * (toolWidth + gap), 8, toolWidth, toolHeight,
				Component.translatable(tool.label), b -> selectTool(tool));
			button.active = tool != currentTool;
			toolButtons.add(button);
		}

		blockButton = addModalButton(20, 26, 140, 16, blockLabel(), b -> BlockPickerScreen.open(this, id -> {
			currentObjectBlock = id;
			blockButton.setMessage(blockLabel());
			rememberRecent(id);
		}));
		addModalButton(166, 26, 16, 16, Component.literal("-"), b -> changeHeight(-1));
		heightButton = addModalButton(184, 26, 40, 16, heightLabel(), b -> {});
		heightButton.active = false;
		addModalButton(226, 26, 16, 16, Component.literal("+"), b -> changeHeight(1));
		facingButton = addModalButton(244, 26, 50, 16, facingLabel(), b -> {
			currentFacing = FACING_CYCLE[(indexOf(currentFacing) + 1) % FACING_CYCLE.length];
			facingButton.setMessage(facingLabel());
		});

		sizeButton = addModalButton(20, 44, 60, 16, sizeLabel(), b -> cycleSize());
		addModalButton(82, 44, 46, 16, Component.translatable("gui.dndsheets.dungeon_trace.circle_button"), b ->
			GridToStructure.stampCircle(grid, gridSize / 2, gridSize / 2, gridSize / 2 - 1, 1));
		wallBlockButton = addModalButton(130, 44, 80, 16, materialLabel("Pared", wallBlock, 80), b -> BlockPickerScreen.open(this, id -> {
			wallBlock = id;
			wallBlockButton.setMessage(materialLabel("Pared", wallBlock, 80));
		}));
		floorBlockButton = addModalButton(212, 44, 80, 16, materialLabel("Piso", floorBlock, 80), b -> BlockPickerScreen.open(this, id -> {
			floorBlock = id;
			floorBlockButton.setMessage(materialLabel("Piso", floorBlock, 80));
		}));

		doorPoolBox = new EditBox(this.font, dialogLeft() + 90, dialogTop() + 62, 190, 16,
			Component.translatable("gui.dndsheets.dungeon_trace.door_pool"));
		doorPoolBox.setHint(Component.translatable("gui.dndsheets.dungeon_trace.door_pool_hint"));
		doorPoolBox.setMaxLength(32);
		this.addRenderableWidget(doorPoolBox);

		rebuildRecentButtons();

		int row5 = CANVAS_Y + canvas + 8;
		undoButton = addModalButton(20, row5, 60, 16, Component.translatable("gui.dndsheets.dungeon_trace.undo_button"), b -> undo());
		modeButton = addModalButton(84, row5, 100, 16, modeLabel(), b -> {
			paintMode = paintMode == PaintMode.FREE ? PaintMode.RECT : PaintMode.FREE;
			rectDragging = false;
			modeButton.setMessage(modeLabel());
		});

		int row6 = row5 + 18;
		addModalButton(20, row6, 16, 16, Component.literal("-"), b -> changeOriginDx(-1));
		originXButton = addModalButton(38, row6, 50, 16, originXLabel(), b -> {});
		originXButton.active = false;
		addModalButton(90, row6, 16, 16, Component.literal("+"), b -> changeOriginDx(1));
		addModalButton(112, row6, 16, 16, Component.literal("-"), b -> changeOriginDz(-1));
		originZButton = addModalButton(130, row6, 50, 16, originZLabel(), b -> {});
		originZButton.active = false;
		addModalButton(182, row6, 16, 16, Component.literal("+"), b -> changeOriginDz(1));

		int confirmRow = row6 + 20;
		addModalButton(20, confirmRow, 120, 20, Component.translatable("gui.dndsheets.common.confirm"), b -> save());
		addModalButton(160, confirmRow, 120, 20, Component.translatable("gui.dndsheets.common.cancel"), b -> this.onClose());
	}

	@Override
	public void tick() {
		doorPoolBox.tick();
	}

	//Empty = "use this piece's pool" (the same behavior that existed before a different one could be
	//chosen), not a pool literally named "".
	private String doorPoolOverride() {
		String text = doorPoolBox.getValue().trim();
		return text.isEmpty() ? null : text;
	}

	private Component modeLabel() {
		return Component.translatable(paintMode == PaintMode.FREE ? "gui.dndsheets.dungeon_trace.mode_free" : "gui.dndsheets.dungeon_trace.mode_rect");
	}

	private Component originXLabel() {
		return Component.translatable("gui.dndsheets.dungeon_trace.origin_x", originDx);
	}

	private Component originZLabel() {
		return Component.translatable("gui.dndsheets.dungeon_trace.origin_z", originDz);
	}

	private void changeOriginDx(int delta) {
		originDx = Math.max(-MAX_ORIGIN_OFFSET, Math.min(MAX_ORIGIN_OFFSET, originDx + delta));
		originXButton.setMessage(originXLabel());
	}

	private void changeOriginDz(int delta) {
		originDz = Math.max(-MAX_ORIGIN_OFFSET, Math.min(MAX_ORIGIN_OFFSET, originDz + delta));
		originZButton.setMessage(originZLabel());
	}

	private Component sizeLabel() {
		return Component.literal(gridSize + "x" + gridSize);
	}

	//ponytail: Confirm/Cancel stay where init() put them with the canvas size the screen was opened
	//with — CANVAS_AREA was chosen so the real canvas measures between 192 and 200px at any GRID_SIZES
	//value, so at most there's 8px of extra slack under the canvas, never an overlap. Truly recalculating
	//them means rebuilding all the widgets, not just these two.
	private void cycleSize() {
		gridIndex = (gridIndex + 1) % GRID_SIZES.length;
		resize(GRID_SIZES[gridIndex]);
		sizeButton.setMessage(sizeLabel());
	}

	private void rememberRecent(ResourceLocation id) {
		recentObjectBlocks.remove(id);
		recentObjectBlocks.addFirst(id);
		while (recentObjectBlocks.size() > MAX_RECENT_OBJECTS) recentObjectBlocks.removeLast();
		rebuildRecentButtons();
	}

	//Rebuilt entirely instead of keeping a pool of reusable buttons: the list is at most
	//MAX_RECENT_OBJECTS long, so the cost of discarding and recreating the buttons is irrelevant.
	private void rebuildRecentButtons() {
		for (Button button : recentButtons) this.removeWidget(button);
		recentButtons.clear();
		int x = 20;
		for (ResourceLocation id : recentObjectBlocks) {
			ResourceLocation captured = id;
			recentButtons.add(addModalButton(x, 80, 52, 16, Component.literal(fit(captured.getPath(), 52 - 6)), b -> {
				currentObjectBlock = captured;
				blockButton.setMessage(blockLabel());
			}));
			x += 54;
		}
	}

	private void pushUndo() {
		if (undoStack.size() >= MAX_UNDO) undoStack.removeLast();
		undoStack.push(new Snapshot(deepCopy(grid), deepCopy(options)));
	}

	private static <T> T[][] deepCopy(T[][] source) {
		T[][] copy = source.clone();
		for (int i = 0; i < copy.length; i++) copy[i] = source[i].clone();
		return copy;
	}

	private void undo() {
		Snapshot snapshot = undoStack.poll();
		if (snapshot == null) return;
		grid = snapshot.grid();
		options = snapshot.options();
	}

	private void selectTool(Tool tool) {
		currentTool = tool;
		for (int i = 0; i < Tool.values().length; i++) toolButtons.get(i).active = Tool.values()[i] != tool;
	}

	private Component blockLabel() {
		return materialLabel(I18n.get("gui.dndsheets.dungeon_trace.object_prefix"), currentObjectBlock, 140);
	}

	//Truncates instead of trusting the block name to fit: a piece of furniture from another mod can be
	//named "polished_blackstone_bricks", and no fixed button width guesses that right. font.width(text) >
	//maxWidth is the only reliable signal it overflowed, not an eyeballed character count.
	private Component materialLabel(String prefix, ResourceLocation block, int maxWidth) {
		String value = block != null ? block.getPath() : I18n.get("gui.dndsheets.dungeon_trace.default_material");
		return Component.literal(fit(prefix + ": " + value, maxWidth));
	}

	private String fit(String text, int maxWidth) {
		if (this.font.width(text) <= maxWidth) return text;
		return this.font.plainSubstrByWidth(text, maxWidth - this.font.width("…")) + "…";
	}

	//No "Rotate: " prefix — the button is 50px wide and "Rotate: north" doesn't fit even truncated to
	//something legible. Its position in the row already says what it is; the value just needs to be short.
	private Component facingLabel() {
		if (currentFacing == null) return Component.translatable("gui.dndsheets.dungeon_trace.facing_auto");
		return Component.literal(switch (currentFacing) {
			case NORTH -> "N";
			case EAST -> "E";
			case SOUTH -> "S";
			case WEST -> I18n.get("gui.dndsheets.dungeon_trace.facing_west");
			default -> currentFacing.getSerializedName();
		});
	}

	private static int indexOf(Direction facing) {
		for (int i = 0; i < FACING_CYCLE.length; i++) if (FACING_CYCLE[i] == facing) return i;
		return 0;
	}

	private Component heightLabel() {
		return Component.translatable("gui.dndsheets.dungeon_trace.height_label", height);
	}

	private void changeHeight(int delta) {
		height = Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, height + delta));
		heightButton.setMessage(heightLabel());
	}

	private void save() {
		String[] rows = new String[gridSize];
		List<Integer> objX = new ArrayList<>(), objZ = new ArrayList<>();
		List<String> objBlockId = new ArrayList<>(), objFacing = new ArrayList<>();
		List<Integer> doorX = new ArrayList<>(), doorZ = new ArrayList<>();
		List<String> doorPool = new ArrayList<>(), doorFacing = new ArrayList<>();

		for (int z = 0; z < gridSize; z++) {
			StringBuilder row = new StringBuilder(gridSize);
			for (int x = 0; x < gridSize; x++) {
				row.append(GridToStructure.toChar(grid[z][x]));
				GridToStructure.CellOptions opt = options[z][x];
				if (grid[z][x] == GridToStructure.Cell.OBJECT && opt.objectBlock() != null) {
					objX.add(x);
					objZ.add(z);
					objBlockId.add(opt.objectBlock().toString());
					objFacing.add(opt.objectFacing() != null ? opt.objectFacing().getSerializedName() : "");
				} else if ((grid[z][x] == GridToStructure.Cell.DOOR || grid[z][x] == GridToStructure.Cell.START)
						&& (opt.doorPool() != null || opt.doorFacing() != null)) {
					doorX.add(x);
					doorZ.add(z);
					doorPool.add(opt.doorPool() != null ? opt.doorPool() : "");
					doorFacing.add(opt.doorFacing() != null ? opt.doorFacing().getSerializedName() : "");
				}
			}
			rows[z] = row.toString();
		}

		DndsheetsDungeonMod.PACKET_HANDLER.sendToServer(new DungeonTraceCaptureMessage(
			id, structureId, pool, weight, tags, height, rows,
			toArray(objX), toArray(objZ), objBlockId.toArray(new String[0]), objFacing.toArray(new String[0]),
			toArray(doorX), toArray(doorZ), doorPool.toArray(new String[0]), doorFacing.toArray(new String[0]),
			wallBlock != null ? wallBlock.toString() : "", floorBlock != null ? floorBlock.toString() : "", originDx, originDz));
		Minecraft.getInstance().setScreen(parent);
	}

	private static int[] toArray(List<Integer> values) {
		return values.stream().mapToInt(Integer::intValue).toArray();
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	//Read-only, for DungeonTracePreviewRenderer — it draws in the world the same thing that's here, not
	//a separate copy of state.
	GridToStructure.Cell[][] gridSnapshot() { return grid; }
	GridToStructure.CellOptions[][] optionsSnapshot() { return options; }
	int pieceHeight() { return height; }
	ResourceLocation wallBlockId() { return wallBlock; }
	ResourceLocation floorBlockId() { return floorBlock; }
	int originDx() { return originDx; }
	int originDz() { return originDz; }

	private int canvasLeft() {
		return dialogLeft() + (300 - canvas) / 2;
	}

	//Grid cell under a screen point, or null if it falls outside the canvas.
	private int[] cellAt(double mouseX, double mouseY) {
		int left = canvasLeft(), top = dialogTop() + CANVAS_Y;
		int gx = (int) ((mouseX - left) / cell), gz = (int) ((mouseY - top) / cell);
		if (mouseX < left || mouseY < top || gx < 0 || gx >= gridSize || gz < 0 || gz >= gridSize) return null;
		return new int[]{gx, gz};
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
			int[] at = cellAt(mouseX, mouseY);
			if (at != null) {
				pushUndo();
				if (paintMode == PaintMode.RECT) {
					rectDragging = true;
					rectStartX = rectEndX = at[0];
					rectStartZ = rectEndZ = at[1];
				} else {
					paint(at[0], at[1]);
				}
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	//Dragging the mouse paints as if it were click after click in Free mode — without this, a 12-cell
	//wall is 12 separate clicks instead of one stroke. In Rectangle mode it only moves the preview; the
	//fill is applied all at once on release (mouseReleased), not cell by cell.
	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (button == 0) {
			int[] at = cellAt(mouseX, mouseY);
			if (at != null) {
				if (paintMode == PaintMode.RECT && rectDragging) {
					rectEndX = at[0];
					rectEndZ = at[1];
				} else if (paintMode == PaintMode.FREE) {
					paint(at[0], at[1]);
				}
				return true;
			}
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (button == 0 && paintMode == PaintMode.RECT && rectDragging) {
			int minX = Math.min(rectStartX, rectEndX), maxX = Math.max(rectStartX, rectEndX);
			int minZ = Math.min(rectStartZ, rectEndZ), maxZ = Math.max(rectStartZ, rectEndZ);
			for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) paint(x, z);
			rectDragging = false;
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	private void paint(int x, int z) {
		if (x < 0 || x >= gridSize || z < 0 || z >= gridSize) return;
		grid[z][x] = currentTool.cell;
		options[z][x] = switch (currentTool) {
			case OBJECT -> new GridToStructure.CellOptions(currentObjectBlock, currentFacing, null, null);
			case DOOR, START -> new GridToStructure.CellOptions(null, null, doorPoolOverride(), currentFacing);
			default -> GridToStructure.CellOptions.EMPTY;
		};
	}

	private static int colorFor(GridToStructure.Cell cell) {
		return switch (cell) {
			case EMPTY -> 0xFF2B2B2B;
			case FLOOR -> 0xFFB0A080;
			case WALL -> 0xFF6B6B6B;
			case DOOR -> 0xFF4A90D9;
			case START -> 0xFF2ECC71;
			case OBJECT -> 0xFFCC8844;
		};
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		renderPanel(guiGraphics);

		//No separate label to the left of the EditBox: at 80px wide "Door pool:" already ate up half the
		//box. The hint (see init()) says the same thing when it's empty, which is when it's needed.
		guiGraphics.drawString(this.font, fit("Pool:", 65), dialogLeft() + 20, dialogTop() + 66, GuiStyle.MUTED_COLOR, false);

		int left = canvasLeft(), top = dialogTop() + CANVAS_Y;
		for (int z = 0; z < gridSize; z++) {
			for (int x = 0; x < gridSize; x++) {
				int x1 = left + x * cell, y1 = top + z * cell;
				guiGraphics.fill(x1, y1, x1 + cell - 1, y1 + cell - 1, colorFor(grid[z][x]));
			}
		}

		if (rectDragging) {
			int minX = Math.min(rectStartX, rectEndX), maxX = Math.max(rectStartX, rectEndX);
			int minZ = Math.min(rectStartZ, rectEndZ), maxZ = Math.max(rectStartZ, rectEndZ);
			int x1 = left + minX * cell, y1 = top + minZ * cell;
			int x2 = left + (maxX + 1) * cell, y2 = top + (maxZ + 1) * cell;
			guiGraphics.renderOutline(x1, y1, x2 - x1, y2 - y1, 0xFFFFFFFF);
		}

		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
