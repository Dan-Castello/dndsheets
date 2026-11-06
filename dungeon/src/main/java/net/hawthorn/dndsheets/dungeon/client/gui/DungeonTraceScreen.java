package net.hawthorn.dndsheets.dungeon.client.gui;
import javax.annotation.Nullable;
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
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
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
	//Layout: tools column | canvas | piece column. The dialog grows with the window, and the canvas takes
	//whatever is left between the columns (it used to be a fixed 200px box in a fixed 300x380 dialog, so a
	//big window still drew a small grid, and a short one pushed Confirm off the bottom).
	private static final int MARGIN = 8;
	private static final int GAP = 8;
	private static final int LEFT_COLUMN = 92;
	private static final int RIGHT_COLUMN = 132;
	//Title band above the columns; the canvas also leaves NORTH_BAND for its "N" marker.
	private static final int TOP_BAND = 22;
	private static final int NORTH_BAND = 12;
	private static final int ROW = 18;
	private static final int BUTTON_HEIGHT = 16;
	private static final int MIN_DIALOG_HEIGHT = 240;
	private static final int MAX_DIALOG_HEIGHT = 380;
	private static final int MIN_CANVAS = 96;
	//Under the canvas: one line saying how the piece will connect (doors, entrances, problems).
	private static final int STATUS_BAND = 12;
	private static final int MAX_LEVELS = 4;
	//24 is the practical ceiling, not the server's (MAX_SIDE=64 in DungeonTraceCaptureMessage): beyond
	//that the cell drops below ~8px and stops being a comfortable mouse target. The real limit is click
	//precision, not an arbitrary protocol number.
	private static final int[] GRID_SIZES = {8, 10, 12, 16, 20, 24};
	private static final int DEFAULT_GRID_INDEX = 2; //12
	private static final int MIN_HEIGHT = 1;
	private static final int MAX_HEIGHT = 10;
	private static final int MAX_UNDO = 20;
	private static final int MAX_ORIGIN_OFFSET = 16;
	private static final int MAX_RECENT_OBJECTS = 5;
	//null = "Auto": for Object, don't touch orientation; for Door/Start, GridToStructure's heuristic.
	//The rest are concrete manual directions.
	//Up/Down: for Door/Start a hatch in the ceiling/floor; for Object, pointing that way. Stairs use the first five.
	private static final Direction[] FACING_CYCLE = {null, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP, Direction.DOWN};

	//MOVE: select a block of cells and drag it (or nudge it with the arrows) somewhere else.
	private enum PaintMode { FREE, RECT, MOVE }

	//ROWS = horizontal stripes: the slots take turns by height (only walls have heights).
	private enum Pattern { SOLID, ALTERNATE, ROWS, RANDOM }

	private enum Tool {
		WALL(GridToStructure.Cell.WALL, "gui.dndsheets.dungeon_trace.tool_wall"),
		FLOOR(GridToStructure.Cell.FLOOR, "gui.dndsheets.dungeon_trace.tool_floor"),
		DOOR(GridToStructure.Cell.DOOR, "gui.dndsheets.dungeon_trace.tool_door"),
		START(GridToStructure.Cell.START, "gui.dndsheets.dungeon_trace.tool_start"),
		OBJECT(GridToStructure.Cell.OBJECT, "gui.dndsheets.dungeon_trace.tool_object"),
		STAIRS(GridToStructure.Cell.STAIRS, "gui.dndsheets.dungeon_trace.tool_stairs"),
		//No cell type of its own: gives existing wall/floor cells another block (see paint()).
		PAINT(null, "gui.dndsheets.dungeon_trace.tool_paint"),
		ERASE(GridToStructure.Cell.EMPTY, "gui.dndsheets.dungeon_trace.tool_erase");

		final GridToStructure.Cell cell;
		final String label;

		Tool(GridToStructure.Cell cell, String label) {
			this.cell = cell;
			this.label = label;
		}
	}

	private final Screen parent;
	//Not final: a room opened just to paste can still be saved as a piece (DungeonTraceSaveScreen).
	private String id, structureId, pool, tags;
	private int weight;

	private int gridIndex = DEFAULT_GRID_INDEX;
	private int gridSize;
	private int cell;
	//The visible square of the plan, in pixels (a big map shows only part of itself at a time).
	private int canvas;
	//Big (imported) maps: zoom in px per cell (0 = fit the whole grid) and the first cell in view.
	private int zoom = 0;
	private int viewX = 0, viewZ = 0;
	//Middle button (or Ctrl+left) dragging the view: where the drag started.
	private boolean panning = false;
	private double panMouseX, panMouseY;
	private int panViewX, panViewZ;
	//Whole-dungeon extras (GridToStructure.Extras, set in DungeonExtrasScreen); imported maps start with them on.
	boolean ceiling = false, carve = false, signs = false;
	ResourceLocation lightBlock = null;
	int lightSpacing = 0;
	List<GridToStructure.Note> notes = List.of();
	//A map from an online generator (DungeonMapImporter): its size is the map's, not one of GRID_SIZES.
	private boolean imported = false;
	//placedBlocks() for the world preview, which asks every frame: rebuilt at most every 250 ms.
	private List<GridToStructure.PlacedBlock> placedCache;
	private long placedAt;

	private GridToStructure.Cell[][] grid;
	private GridToStructure.CellOptions[][] options;
	private Tool currentTool = Tool.WALL;
	private ResourceLocation currentObjectBlock = null;
	//The stairs block of a staircase with a direction (null = DungeonPiecePlacer.DEFAULT_STEP).
	private ResourceLocation currentStairsBlock = null;
	//Shared between Object (rotation) and Door/Start (manual direction) — see the class Javadoc.
	private Direction currentFacing = null;
	private ResourceLocation wallBlock = null;
	private ResourceLocation floorBlock = null;
	//Wall height of the level being edited. Not "height": that would hide Screen.height (the window's).
	private int wallHeight = 3;
	//Stacked levels (storeys) of the piece. grid/options/wallHeight are the current level's; these lists
	//hold every level, kept in step by syncLevel().
	private final List<GridToStructure.Cell[][]> levels = new ArrayList<>();
	private final List<GridToStructure.CellOptions[][]> levelOptions = new ArrayList<>();
	private final List<Integer> levelHeights = new ArrayList<>();
	private int level = 0;

	private PaintMode paintMode = PaintMode.FREE;
	//Rectangle in progress: null when none is being dragged. Only meaningful in RECT mode.
	private int rectStartX = -1, rectStartZ = -1, rectEndX = -1, rectEndZ = -1;
	private boolean rectDragging = false;
	//MOVE mode: the selected rectangle (inclusive; selX1 < 0 = nothing selected), and the drag moving it.
	private int selX1 = -1, selZ1, selX2, selZ2;
	private boolean movingSelection = false;
	private int grabX, grabZ, moveDx, moveDz;

	private int originDx = 0, originDy = 0, originDz = 0;
	//The last thing done was a paste: Undo takes the paste back (server side) instead of a stroke.
	private boolean lastWasPaste = false;
	//Block that closes the doorways drawn from now on (null = open); see GridToStructure, DOORWAY.
	private ResourceLocation currentDoorBlock = null;
	//Fixed block the piece is built from once previewed in the world; null = follows the DM.
	@Nullable
	private BlockPos anchor;

	//One snapshot per stroke (initial click), not per painted cell — undoing a whole drag at once is the
	//expected behavior; undoing cell by cell within the same stroke isn't. Cap of 20: the grid is small
	//(24x24 max), so neither memory nor the copy is a real problem.
	private record Snapshot(int level, GridToStructure.Cell[][] grid, GridToStructure.CellOptions[][] options) {}
	private final Deque<Snapshot> undoStack = new ArrayDeque<>();

	//MRU of chosen object blocks: without this, switching between two different pieces of furniture
	//forces reopening the search every time, even if the DM already searched for it thirty seconds ago.
	private final Deque<ResourceLocation> recentObjectBlocks = new ArrayDeque<>();
	private final List<Button> recentButtons = new ArrayList<>();

	private Button blockButton;
	private Button wallBlockButton;
	private Button floorBlockButton;
	/** A row of buttons where exactly one is the current choice (brass ring, see render). */
	private record Segment(List<Button> buttons, java.util.function.IntSupplier selected) {}
	private final List<Segment> segments = new ArrayList<>();
	//Wall editor (side view of a selected wall run): open/closed, and which way the run goes.
	private boolean elevation = false;
	private boolean elevationAlongX = true;
	//While dragging in the wall editor: 1 = opening holes, 0 = filling them, -1 = not dragging.
	private int elevationBrush = -1;
	private Button editWallButton;
	//Side view: false = clicks open/close blocks (windows), true = clicks paint blocks with the slots.
	private boolean elevationPaint = false;
	//The pattern buttons, to draw each one's little preview over it.
	private final List<Button> patternButtons = new ArrayList<>();
	//Mouse over a wall in the plan: the status line says how to edit it from the side.
	private boolean hoverWall = false;
	private EditBox doorPoolBox;
	private final List<Button> toolButtons = new ArrayList<>();
	//Paint brush: up to three blocks, spread over the painted cells by the pattern (see brushBlock).
	private final ResourceLocation[] palette = new ResourceLocation[3];
	private Pattern pattern = Pattern.SOLID;
	private final java.util.Random random = new java.util.Random();
	private final List<Button> paletteButtons = new ArrayList<>();
	private final java.util.Map<ResourceLocation, Integer> mapColors = new java.util.HashMap<>();
	/** A stepper's value, drawn between its "-" and "+" (absolute coordinates, set by init). */
	private record Readout(int x, int y, int width, java.util.function.Supplier<Component> text, Component tip) {}
	private final List<Readout> readouts = new ArrayList<>();
	//Dialog-relative Y of the recent-object buttons and of the door-pool label (both set by init).
	private int recentTop;
	private int poolLabelY;

	private DungeonTraceScreen(String id, String structureId, String pool, int weight, String tags, Screen parent) {
		super(Component.translatable("gui.dndsheets.dungeon_trace.title"), 0, 0); //Size from dialogWidth()/dialogHeight().
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
	//Every level goes, and the undo history with it (its snapshots have the old size).
	private void resize(int size) {
		gridSize = size;
		applyCellSize();
		levels.clear();
		levelOptions.clear();
		levelHeights.clear();
		undoStack.clear();
		selX1 = -1;
		addLevel();
		level = 0;
		grid = levels.get(0);
		options = levelOptions.get(0);
		wallHeight = levelHeights.get(0);
	}

	private void addLevel() {
		GridToStructure.Cell[][] newGrid = new GridToStructure.Cell[gridSize][gridSize];
		GridToStructure.CellOptions[][] newOptions = new GridToStructure.CellOptions[gridSize][gridSize];
		for (int z = 0; z < gridSize; z++) {
			java.util.Arrays.fill(newGrid[z], GridToStructure.Cell.EMPTY);
			java.util.Arrays.fill(newOptions[z], GridToStructure.CellOptions.EMPTY);
		}
		levels.add(newGrid);
		levelOptions.add(newOptions);
		levelHeights.add(3);
	}

	private void syncLevel() {
		levels.set(level, grid);
		levelOptions.set(level, options);
		levelHeights.set(level, wallHeight);
	}

	//"+" on the top level adds a new one (up to MAX_LEVELS); empty levels on top aren't saved (usedLevels).
	private void changeLevel(int delta) {
		syncLevel();
		int target = level + delta;
		if (target < 0) return;
		if (target >= levels.size()) {
			if (levels.size() >= MAX_LEVELS) return;
			addLevel();
		}
		showLevel(target);
	}

	private void showLevel(int target) {
		selX1 = -1;
		movingSelection = false;
		level = target;
		grid = levels.get(level);
		options = levelOptions.get(level);
		wallHeight = levelHeights.get(level);
	}

	/** Levels up to the highest one with something drawn (at least one). */
	private int usedLevels() {
		syncLevel();
		for (int i = levels.size() - 1; i > 0; i--) {
			for (GridToStructure.Cell[] row : levels.get(i)) for (GridToStructure.Cell c : row) if (c != GridToStructure.Cell.EMPTY) return i + 1;
		}
		return 1;
	}

	/** What the piece places, every level stacked — the world preview draws exactly this. */
	List<GridToStructure.PlacedBlock> placedBlocks() {
		long now = System.currentTimeMillis();
		if (placedCache != null && now - placedAt < 250) return placedCache;
		int count = usedLevels();
		int[] heights = new int[count];
		for (int i = 0; i < count; i++) heights[i] = levelHeights.get(i);
		placedCache = GridToStructure.renderLayers(levels.subList(0, count), heights, levelOptions.subList(0, count), extras());
		placedAt = now;
		return placedCache;
	}

	GridToStructure.Extras extras() {
		return new GridToStructure.Extras(ceiling, carve, lightSpacing > 0 ? lightBlockOrDefault() : null, lightSpacing, signs ? notes : List.of());
	}

	ResourceLocation lightBlockOrDefault() {
		return lightBlock != null ? lightBlock : new ResourceLocation("minecraft", "lantern");
	}

	@Override
	protected int dialogHeight() {
		return this.height <= 0 ? MAX_DIALOG_HEIGHT : Math.max(MIN_DIALOG_HEIGHT, Math.min(MAX_DIALOG_HEIGHT, this.height - 16));
	}

	//Square room for the canvas: the height under the title, capped by the width the columns leave.
	private int canvasArea() {
		int byHeight = dialogHeight() - TOP_BAND - NORTH_BAND - STATUS_BAND - MARGIN;
		if (this.width <= 0) return byHeight;
		return Math.max(MIN_CANVAS, Math.min(byHeight, this.width - 16 - 2 * MARGIN - LEFT_COLUMN - RIGHT_COLUMN - 2 * GAP));
	}

	@Override
	protected int dialogWidth() {
		return 2 * MARGIN + LEFT_COLUMN + RIGHT_COLUMN + 2 * GAP + canvasArea();
	}

	private void applyCellSize() {
		int fit = Math.max(1, canvasArea() / gridSize);
		cell = zoom > 0 ? zoom : imported ? fit : Math.max(4, fit);
		canvas = Math.min(canvasArea(), cell * gridSize);
		int visible = canvas / cell;
		viewX = Math.max(0, Math.min(gridSize - visible, viewX));
		viewZ = Math.max(0, Math.min(gridSize - visible, viewZ));
	}

	//The cells in view, for the drawing loops (a 200x200 map drawn whole every frame is 40 000 fills).
	private int firstX() { return viewX; }
	private int lastX() { return Math.min(gridSize, viewX + canvas / cell + 1); }
	private int firstZ() { return viewZ; }
	private int lastZ() { return Math.min(gridSize, viewZ + canvas / cell + 1); }

	/**
	 * <p>A map made with an online generator, opened here to look at and change before pasting it. A room
	 * still in the world preview is only replaced once the DM agrees.</p>
	 */
	public static void openImported(net.hawthorn.dndsheets.dungeon.DungeonMapImporter.Result map, int height, Screen parent) {
		Runnable open = () -> {
			DungeonTracePreview.stop();
			DungeonTraceScreen screen = new DungeonTraceScreen("", "", "", 1, "", parent);
			screen.loadMap(map, height);
			Minecraft.getInstance().setScreen(screen);
		};
		if (DungeonTracePreview.draft == null) open.run();
		else net.hawthorn.dndsheets.client.gui.ConfirmScreen.ask(Component.translatable("gui.dndsheets.dungeon_map.discard_draft"), open);
	}

	private void loadMap(net.hawthorn.dndsheets.dungeon.DungeonMapImporter.Result map, int height) {
		imported = true;
		int rows = map.grid().length, cols = map.grid()[0].length;
		resize(Math.max(rows, cols));
		for (int z = 0; z < rows; z++) {
			for (int x = 0; x < cols; x++) {
				grid[z][x] = map.grid()[z][x];
				if (map.options()[z][x] != null) options[z][x] = map.options()[z][x];
			}
		}
		wallHeight = height;
		levelHeights.set(0, height);
		ceiling = true;
		carve = true;
		lightSpacing = 8;
		notes = map.notes();
		signs = !notes.isEmpty();
	}

	/** The same five fields the DM already entered in {@link DungeonPieceAddScreen}: drawing is the other half of the same flow, not a new one. */
	public static void open(String id, String structureId, String pool, int weight, String tags, Screen parent) {
		//A room is still standing in the world preview: go back to it rather than start a second one over it
		//(the DM may not know Enter returns to the editor).
		DungeonTraceScreen draft = DungeonTracePreview.draft;
		if (draft != null) {
			Minecraft.getInstance().setScreen(draft);
			if (Minecraft.getInstance().player != null) {
				Minecraft.getInstance().player.displayClientMessage(Component.translatable("gui.dndsheets.dungeon_trace.resumed"), true);
			}
			return;
		}
		Minecraft.getInstance().setScreen(new DungeonTraceScreen(id, structureId, pool, weight, tags, parent));
	}

	@Override
	protected void init() {
		applyCellSize();
		toolButtons.clear();
		paletteButtons.clear();
		recentButtons.clear();
		readouts.clear();
		segments.clear();
		patternButtons.clear();
		editWallButton = null;
		int rightX = dialogWidth() - MARGIN - RIGHT_COLUMN;

		//--- Left column: the tools, then the settings of the ACTIVE tool only ---
		//The door-pool box always exists (paint() reads it), but only shows for Door/Entrance.
		String doorPool = doorPoolBox != null ? doorPoolBox.getValue() : "";
		int y = TOP_BAND;
		if (elevation) {
			//Side view: the whole column is the wall's panel, the tools don't apply to it.
			addModalButton(MARGIN, y, LEFT_COLUMN, BUTTON_HEIGHT, Component.translatable("gui.dndsheets.dungeon_trace.wall_back"), b -> {
				elevation = false;
				this.rebuildWidgets();
			});
			y += ROW;
			segmented(MARGIN, y, new int[]{45, 45}, new Component[]{
					Component.translatable("gui.dndsheets.dungeon_trace.wall_mode_holes"),
					Component.translatable("gui.dndsheets.dungeon_trace.wall_mode_paint")},
				"gui.dndsheets.dungeon_trace.tip_wall_mode", () -> elevationPaint ? 1 : 0, i -> {
					elevationPaint = i == 1;
					this.rebuildWidgets();
				});
			y += ROW + 4;
			if (elevationPaint) y = addPaletteControls(y) + 4;
			addModalButton(MARGIN, y, LEFT_COLUMN, BUTTON_HEIGHT, Component.translatable("gui.dndsheets.dungeon_trace.wall_wear"), b -> wearSelection())
				.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.tip_wall_wear")));
			y += ROW;
			addModalButton(MARGIN, y, LEFT_COLUMN, BUTTON_HEIGHT, Component.translatable("gui.dndsheets.dungeon_trace.wall_restore"), b -> restoreSelection())
				.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.tip_wall_restore")));
		} else {
			//Swatch + outline mark the active tool (see render): disabling it, as before, greyed out the very
			//button the DM is using and read as "not available".
			for (Tool tool : Tool.values()) {
				Button button = addModalButton(MARGIN, y, LEFT_COLUMN, BUTTON_HEIGHT, Component.translatable(tool.label), b -> selectTool(tool));
				String tip = switch (tool) {
					case WALL -> "gui.dndsheets.dungeon_trace.tip_tool_wall";
					case DOOR -> "gui.dndsheets.dungeon_trace.tip_tool_door";
					case START -> "gui.dndsheets.dungeon_trace.tip_tool_start";
					case STAIRS -> "gui.dndsheets.dungeon_trace.tip_tool_stairs";
					case PAINT -> "gui.dndsheets.dungeon_trace.tip_tool_paint";
					default -> null;
				};
				if (tip != null) button.setTooltip(Tooltip.create(Component.translatable(tip)));
				toolButtons.add(button);
				y += ROW;
			}
			y += 4;
			//Zoom buttons over the plan's top-right corner: the wheel and Ctrl+drag work too, but nobody finds them.
			int navX = MARGIN + LEFT_COLUMN + GAP + canvasArea() - 52, navY = TOP_BAND - 2;
			addModalButton(navX, navY, 13, 11, Component.literal("−"), b -> zoomAt(viewLeft() + canvas / 2.0, viewTop() + canvas / 2.0, -1)) //i18n-ok: symbol
				.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.tip_zoom_out")));
			addModalButton(navX + 13, navY, 13, 11, Component.literal("+"), b -> zoomAt(viewLeft() + canvas / 2.0, viewTop() + canvas / 2.0, 1)) //i18n-ok: symbol
				.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.tip_zoom_in")));
			addModalButton(navX + 26, navY, 26, 11, Component.translatable("gui.dndsheets.dungeon_trace.zoom_fit"), b -> {
				zoom = viewX = viewZ = 0;
				applyCellSize();
			}).setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.tip_zoom_fit")));
		}

		doorPoolBox = new EditBox(this.font, dialogLeft() + MARGIN, dialogTop() + y + 2 * ROW + 10, LEFT_COLUMN, BUTTON_HEIGHT,
			Component.translatable("gui.dndsheets.dungeon_trace.door_pool"));
		doorPoolBox.setHint(Component.translatable("gui.dndsheets.dungeon_trace.door_pool_hint"));
		doorPoolBox.setMaxLength(32);
		doorPoolBox.setValue(doorPool);
		doorPoolBox.visible = !elevation && (currentTool == Tool.DOOR || currentTool == Tool.START);
		this.addRenderableWidget(doorPoolBox);
		poolLabelY = y + 2 * ROW;

		if (elevation) {
			//Panel already built above.
		} else if (paintMode == PaintMode.MOVE) {
			//Move mode has its own panel (the selection's), whatever the tool.
			editWallButton = addModalButton(MARGIN, y, LEFT_COLUMN, BUTTON_HEIGHT, Component.translatable("gui.dndsheets.dungeon_trace.wall_edit"), b -> {
				elevation = true;
				elevationAlongX = selZ1 == selZ2;
				this.rebuildWidgets();
			});
			editWallButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.tip_wall_edit")));
			y += ROW;
			addModalButton(MARGIN, y, LEFT_COLUMN, BUTTON_HEIGHT, Component.translatable("gui.dndsheets.dungeon_trace.wall_wear"), b -> wearSelection())
				.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.tip_wall_wear")));
			y += ROW;
			addModalButton(MARGIN, y, LEFT_COLUMN, BUTTON_HEIGHT, Component.translatable("gui.dndsheets.dungeon_trace.wall_restore"), b -> restoreSelection())
				.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.tip_wall_restore")));
		} else switch (currentTool) {
			case OBJECT -> {
				blockButton = addModalButton(MARGIN, y, LEFT_COLUMN, BUTTON_HEIGHT, blockLabel(), b -> BlockPickerScreen.open(this, id -> {
					currentObjectBlock = id;
					rememberRecent(id);
				}));
				blockButton.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.tip_object")));
				y += ROW;
				recentTop = y + ROW * addFacingButton(y, true, "gui.dndsheets.dungeon_trace.tip_facing");
				rebuildRecentButtons();
			}
			case DOOR, START -> {
				int rows = addFacingButton(y, true, "gui.dndsheets.dungeon_trace.tip_facing");
				//The pool box was laid out for one facing row: push it down past the second.
				poolLabelY = y + (rows + 1) * ROW;
				doorPoolBox.setY(dialogTop() + poolLabelY + 10);
				addModalButton(MARGIN, y + rows * ROW, LEFT_COLUMN, BUTTON_HEIGHT, doorBlockLabel(), b -> {
					if (hasShiftDown()) currentDoorBlock = null;
					else BlockPickerScreen.open(this, id -> currentDoorBlock = id);
					b.setMessage(doorBlockLabel());
				}).setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.tip_door_block")));
			}
			case STAIRS -> {
				addFacingButton(y, false, "gui.dndsheets.dungeon_trace.tip_stairs_facing");
				addModalButton(MARGIN, y + ROW, LEFT_COLUMN, BUTTON_HEIGHT, stairsBlockLabel(), b -> {
					if (hasShiftDown()) currentStairsBlock = null;
					else BlockPickerScreen.open(this, id -> currentStairsBlock = id);
					b.setMessage(stairsBlockLabel());
				}).setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.tip_stairs_block")));
			}
			case PAINT -> addPaletteControls(y);
			default -> {}
		}

		//--- Right column: the piece as a whole, then the editing actions ---
		int half = (RIGHT_COLUMN - 4) / 2;
		y = TOP_BAND;
		stepper(rightX, y, RIGHT_COLUMN, this::sizeLabel, this::changeSize, "gui.dndsheets.dungeon_trace.tip_size");
		y += ROW;
		stepper(rightX, y, RIGHT_COLUMN, this::levelLabel, this::changeLevel, "gui.dndsheets.dungeon_trace.tip_level");
		y += ROW;
		stepper(rightX, y, RIGHT_COLUMN, this::heightLabel, this::changeHeight, "gui.dndsheets.dungeon_trace.tip_height");
		y += ROW;
		wallBlockButton = addModalButton(rightX, y, RIGHT_COLUMN, BUTTON_HEIGHT, wallLabel(), b -> BlockPickerScreen.open(this, id -> wallBlock = id));
		y += ROW;
		floorBlockButton = addModalButton(rightX, y, RIGHT_COLUMN, BUTTON_HEIGHT, floorLabel(), b -> BlockPickerScreen.open(this, id -> floorBlock = id));
		y += ROW;
		stepper(rightX, y, half, this::originXLabel, this::changeOriginDx, "gui.dndsheets.dungeon_trace.tip_origin");
		stepper(rightX + half + 4, y, half, this::originZLabel, this::changeOriginDz, "gui.dndsheets.dungeon_trace.tip_origin");
		y += ROW;
		stepper(rightX, y, RIGHT_COLUMN, this::originYLabel, this::changeOriginDy, "gui.dndsheets.dungeon_trace.tip_origin_y");
		y += ROW + 4;

		addModalButton(rightX, y, half, BUTTON_HEIGHT, Component.translatable("gui.dndsheets.dungeon_trace.undo_button"), b -> undo());
		//A whole dungeon has no use for the circle stamp; its slot opens the dungeon's options instead.
		if (imported) {
			addModalButton(rightX + half + 4, y, half, BUTTON_HEIGHT, Component.translatable("gui.dndsheets.dungeon_trace.extras_button"),
				b -> this.minecraft.setScreen(new DungeonExtrasScreen(this)))
				.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.tip_extras")));
		} else {
			addModalButton(rightX + half + 4, y, half, BUTTON_HEIGHT, Component.translatable("gui.dndsheets.dungeon_trace.circle_button"), b -> {
				pushUndo();
				GridToStructure.stampCircle(grid, gridSize / 2, gridSize / 2, gridSize / 2 - 1, 1);
			}).setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.tip_circle")));
		}
		y += ROW;
		//All three modes in view: a cycling button meant clicking round to find the one wanted.
		segmented(rightX, y, new int[]{42, 42, 44}, new Component[]{
				Component.translatable("gui.dndsheets.dungeon_trace.mode_free"),
				Component.translatable("gui.dndsheets.dungeon_trace.mode_rect"),
				Component.translatable("gui.dndsheets.dungeon_trace.mode_move")},
			"gui.dndsheets.dungeon_trace.tip_mode", () -> paintMode.ordinal(), i -> {
				paintMode = PaintMode.values()[i];
				rectDragging = false;
				movingSelection = false;
				selX1 = -1;
				elevation = false;
				this.rebuildWidgets();
			});
		y += ROW;
		//Leaves the drawing standing in the world, fixed to a block (DungeonTracePreview). Anchored the
		//first time at the DM's feet; Shift+click moves the anchor back to where they stand now.
		addModalButton(rightX, y, half, BUTTON_HEIGHT, Component.translatable("gui.dndsheets.dungeon_trace.preview_button"), b -> {
			if (anchor == null || hasShiftDown()) anchor = BlockPos.containing(this.minecraft.player.position());
			DungeonTracePreview.start(this);
		}).setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.tip_preview")));
		//Builds it where the preview stands (or at the DM's feet), without making it a dungeon piece.
		addModalButton(rightX + half + 4, y, half, BUTTON_HEIGHT, Component.translatable("gui.dndsheets.dungeon_trace.paste_button"), b -> send(true))
			.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.tip_paste")));

		int bottom = dialogHeight() - MARGIN - 20;
		//Opened just to paste there's no id or pool yet: Confirm asks for them instead of being disabled.
		Button confirm = addModalButton(rightX, bottom, half, 20, Component.translatable("gui.dndsheets.common.confirm"), b -> {
			if (id.isEmpty() || pool.isEmpty()) this.minecraft.setScreen(new DungeonTraceSaveScreen(this));
			else send(false);
		});
		if (id.isEmpty() || pool.isEmpty()) confirm.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.confirm_needs_piece")));
		addModalButton(rightX + half + 4, bottom, half, 20, Component.translatable("gui.dndsheets.common.cancel"), b -> this.onClose());
	}

	//Block pickers return to this screen, which runs init() again: labels are read from the fields there,
	//so the callbacks only need to set the field.
	private void selectTool(Tool tool) {
		currentTool = tool;
		//Stairs only climb sideways.
		if (tool == Tool.STAIRS && currentFacing != null && currentFacing.getAxis().isVertical()) currentFacing = null;
		rectDragging = false;
		this.rebuildWidgets();
	}

	//Auto · N · E · S · W side by side, and ↑ · ↓ on a row of their own (seven across didn't fit a letter);
	//the current one ringed. Returns how many rows it took.
	private int addFacingButton(int y, boolean vertical, String tip) {
		Component[] labels = new Component[FACING_CYCLE.length], tips = new Component[FACING_CYCLE.length];
		for (int i = 0; i < FACING_CYCLE.length; i++) {
			labels[i] = FACING_CYCLE[i] == null
				? Component.translatable("gui.dndsheets.dungeon_trace.facing_auto")
				: Component.translatable("gui.dndsheets.dungeon_trace.facing_" + FACING_CYCLE[i].getSerializedName());
			tips[i] = Component.translatable(FACING_CYCLE[i] != null && FACING_CYCLE[i].getAxis().isVertical()
				? "gui.dndsheets.dungeon_trace.tip_facing_" + FACING_CYCLE[i].getSerializedName() : tip);
		}
		segmented(MARGIN, y, new int[]{32, 13, 13, 13, 13}, java.util.Arrays.copyOfRange(labels, 0, 5), java.util.Arrays.copyOfRange(tips, 0, 5),
			() -> indexOf(currentFacing) < 5 ? indexOf(currentFacing) : -1, i -> currentFacing = FACING_CYCLE[i]);
		if (!vertical) return 1;
		segmented(MARGIN, y + ROW, new int[]{45, 45}, java.util.Arrays.copyOfRange(labels, 5, 7), java.util.Arrays.copyOfRange(tips, 5, 7),
			() -> indexOf(currentFacing) - 5, i -> currentFacing = FACING_CYCLE[5 + i]);
		return 2;
	}

	private Component stairsBlockLabel() {
		return materialLabel(I18n.get("gui.dndsheets.dungeon_trace.stairs_block_prefix"), currentStairsBlock, LEFT_COLUMN - 8);
	}

	private void segmented(int x, int y, int[] widths, Component[] labels, String tip,
			java.util.function.IntSupplier selected, java.util.function.IntConsumer pick) {
		Component[] tips = new Component[labels.length];
		java.util.Arrays.fill(tips, Component.translatable(tip));
		segmented(x, y, widths, labels, tips, selected, pick);
	}

	private List<Button> segmented(int x, int y, int[] widths, Component[] labels, Component[] tips,
			java.util.function.IntSupplier selected, java.util.function.IntConsumer pick) {
		List<Button> group = new ArrayList<>();
		for (int i = 0; i < labels.length; i++) {
			int choice = i;
			Button button = addModalButton(x, y, widths[i], BUTTON_HEIGHT, labels[i], b -> pick.accept(choice));
			button.setTooltip(Tooltip.create(tips[i]));
			group.add(button);
			x += widths[i] + 2;
		}
		segments.add(new Segment(group, selected));
		return group;
	}

	/**
	 * <p>Right-click on a wall: open the side view of the straight run it belongs to (the longer of its row
	 * and its column), without going through Move mode and a selection first.</p>
	 */
	private void openWallRun(int x, int z) {
		int x0 = x, x1 = x, z0 = z, z1 = z;
		while (x0 > 0 && grid[z][x0 - 1] == GridToStructure.Cell.WALL) x0--;
		while (x1 < gridSize - 1 && grid[z][x1 + 1] == GridToStructure.Cell.WALL) x1++;
		while (z0 > 0 && grid[z0 - 1][x] == GridToStructure.Cell.WALL) z0--;
		while (z1 < gridSize - 1 && grid[z1 + 1][x] == GridToStructure.Cell.WALL) z1++;
		elevationAlongX = x1 - x0 >= z1 - z0;
		selX1 = elevationAlongX ? x0 : x;
		selX2 = elevationAlongX ? x1 : x;
		selZ1 = elevationAlongX ? z : z0;
		selZ2 = elevationAlongX ? z : z1;
		rectDragging = false;
		elevation = true;
		this.rebuildWidgets();
	}

	private Component slotLabel(int slot) {
		String value = palette[slot] != null ? palette[slot].getPath() : I18n.get("gui.dndsheets.dungeon_trace.slot_empty");
		//Room on the left for the block's colour swatch (see render).
		return Component.literal(fit(value, LEFT_COLUMN - 22));
	}

	/**
	 * <p>The block the Paint brush lays on a cell: the filled slots spread by the pattern — Solid takes the
	 * first, Alternate checkers them by position (x+z), Random picks one. No slot filled = null, which
	 * removes the paint (the cell goes back to the piece's material).</p>
	 */
	@Nullable
	private ResourceLocation brushBlock(int x, int z, int y) {
		List<ResourceLocation> filled = new ArrayList<>();
		for (ResourceLocation block : palette) if (block != null) filled.add(block);
		if (filled.isEmpty()) return null;
		return switch (pattern) {
			case SOLID -> filled.get(0);
			//Checkered in all three directions: alternates along the wall AND up it.
			case ALTERNATE -> filled.get((x + z + y) % filled.size());
			case ROWS -> filled.get(Math.max(0, y - 1) % filled.size());
			case RANDOM -> filled.get(random.nextInt(filled.size()));
		};
	}

	/**
	 * <p>Paint a wall column block by block, with the pattern (stripes need the heights); a floor gets one
	 * block. With every slot empty it takes the paint off.</p>
	 */
	private GridToStructure.CellOptions paintCell(GridToStructure.Cell here, GridToStructure.CellOptions old, int x, int z) {
		if (here == GridToStructure.Cell.FLOOR) return old.withMaterial(brushBlock(x, z, 0)).withBlocks(null);
		ResourceLocation first = brushBlock(x, z, 1);
		if (first == null) return old.withMaterial(null).withBlocks(null);
		ResourceLocation[] column = new ResourceLocation[wallHeight];
		for (int y = 1; y <= wallHeight; y++) column[y - 1] = brushBlock(x, z, y);
		return old.withMaterial(first).withBlocks(column);
	}

	/** The block's map colour, for painted cells and palette swatches; cached, it's read every frame. */
	private int mapColor(ResourceLocation id) {
		return mapColors.computeIfAbsent(id, key -> {
			net.minecraft.world.level.block.Block block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(key);
			int col = block != null ? block.defaultMapColor().col : 0;
			return col != 0 ? 0xFF000000 | col : GuiStyle.ACCENT_COLOR;
		});
	}

	//The first filled slot, not brushBlock(): with Random that would change colour every frame.
	private int toolColor(Tool tool) {
		if (tool != Tool.PAINT) return colorFor(tool.cell);
		for (ResourceLocation block : palette) if (block != null) return mapColor(block);
		return GuiStyle.ACCENT_COLOR;
	}

	//The three block slots plus the pattern row; shared by the Paint tool and the side view's Paint mode.
	private int addPaletteControls(int y) {
		for (int i = 0; i < palette.length; i++) {
			int slot = i;
			Button button = addModalButton(MARGIN, y, LEFT_COLUMN, BUTTON_HEIGHT, slotLabel(slot), b -> {
				if (hasShiftDown()) palette[slot] = null;
				else BlockPickerScreen.open(this, id -> palette[slot] = id);
				b.setMessage(slotLabel(slot));
			});
			button.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_trace.tip_paint_slot")));
			paletteButtons.add(button);
			y += ROW;
		}
		//No words on the pattern buttons (they don't fit four across): each draws a tiny sample, see render.
		Component[] labels = new Component[Pattern.values().length];
		Component[] tips = new Component[Pattern.values().length];
		for (Pattern p : Pattern.values()) {
			labels[p.ordinal()] = Component.empty();
			tips[p.ordinal()] = Component.translatable("gui.dndsheets.dungeon_trace.pattern_" + p.name().toLowerCase(java.util.Locale.ROOT));
		}
		patternButtons.addAll(segmented(MARGIN, y, new int[]{21, 21, 21, 23}, labels, tips, () -> pattern.ordinal(), i -> pattern = Pattern.values()[i]));
		return y + ROW;
	}

	/** "-  value  +": the value is drawn text (see render), not a disabled button that looked broken and greyed. */
	private void stepper(int x, int y, int width, java.util.function.Supplier<Component> label, java.util.function.IntConsumer change, String tip) {
		addModalButton(x, y, BUTTON_HEIGHT, BUTTON_HEIGHT, Component.literal("-"), b -> change.accept(-1)); //i18n-ok: symbol
		addModalButton(x + width - BUTTON_HEIGHT, y, BUTTON_HEIGHT, BUTTON_HEIGHT, Component.literal("+"), b -> change.accept(1)); //i18n-ok: symbol
		readouts.add(new Readout(dialogLeft() + x + BUTTON_HEIGHT + 2, dialogTop() + y, width - 2 * BUTTON_HEIGHT - 4, label, Component.translatable(tip)));
	}

	private Component levelLabel() {
		return Component.translatable("gui.dndsheets.dungeon_trace.level_label", level + 1, levels.size());
	}

	private Component wallLabel() {
		return materialLabel(I18n.get("gui.dndsheets.dungeon_trace.tool_wall"), wallBlock, RIGHT_COLUMN - 8);
	}

	private Component floorLabel() {
		return materialLabel(I18n.get("gui.dndsheets.dungeon_trace.tool_floor"), floorBlock, RIGHT_COLUMN - 8);
	}

	@Override
	public void tick() {
		doorPoolBox.tick();
		//The selection went (undo, level change...): the wall editor has nothing left to show.
		if (elevation && !canEditWall()) {
			elevation = false;
			this.rebuildWidgets();
		}
	}

	//Empty = "use this piece's pool" (the same behavior that existed before a different one could be
	//chosen), not a pool literally named "".
	@Nullable
	private String doorPoolOverride() {
		String text = doorPoolBox.getValue().trim();
		return text.isEmpty() ? null : text;
	}

	private Component originXLabel() {
		return Component.translatable("gui.dndsheets.dungeon_trace.origin_x", originDx);
	}

	private Component originYLabel() {
		return Component.translatable("gui.dndsheets.dungeon_trace.origin_y", originDy);
	}

	private void changeOriginDy(int delta) {
		originDy = Math.max(-MAX_ORIGIN_OFFSET, Math.min(MAX_ORIGIN_OFFSET, originDy + delta));
	}

	private Component doorBlockLabel() {
		String value = currentDoorBlock != null ? currentDoorBlock.getPath() : I18n.get("gui.dndsheets.dungeon_trace.door_block_open");
		return Component.literal(fit(I18n.get("gui.dndsheets.dungeon_trace.door_block_prefix") + ": " + value, LEFT_COLUMN - 8));
	}

	/** From DungeonTraceSaveScreen: the room drawn to paste becomes a dungeon piece after all. */
	void saveAs(String newId, String newStructure, String newPool, int newWeight, String newTags) {
		id = newId;
		structureId = newStructure;
		pool = newPool;
		weight = newWeight;
		tags = newTags;
		send(false);
	}

	private Component originZLabel() {
		return Component.translatable("gui.dndsheets.dungeon_trace.origin_z", originDz);
	}

	private void changeOriginDx(int delta) {
		originDx = Math.max(-MAX_ORIGIN_OFFSET, Math.min(MAX_ORIGIN_OFFSET, originDx + delta));
	}

	private void changeOriginDz(int delta) {
		originDz = Math.max(-MAX_ORIGIN_OFFSET, Math.min(MAX_ORIGIN_OFFSET, originDz + delta));
	}

	private Component sizeLabel() {
		return Component.literal(gridSize + "x" + gridSize);
	}

	//Nothing else moves: the columns don't depend on the canvas, which re-centres itself in its room.
	private void changeSize(int delta) {
		//An imported map is the map's size: stepping to 24x24 would throw it away.
		if (imported) return;
		int target = Math.max(0, Math.min(GRID_SIZES.length - 1, gridIndex + delta));
		if (target == gridIndex) return;
		gridIndex = target;
		elevation = false;
		resize(GRID_SIZES[gridIndex]);
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
		//Under the Object block button, as many as fit above the dialog's bottom edge.
		int y = recentTop;
		for (ResourceLocation id : recentObjectBlocks) {
			if (y + BUTTON_HEIGHT > dialogHeight() - MARGIN) break;
			ResourceLocation captured = id;
			recentButtons.add(addModalButton(MARGIN, y, LEFT_COLUMN, BUTTON_HEIGHT, Component.literal(fit(captured.getPath(), LEFT_COLUMN - 8)), b -> {
				currentObjectBlock = captured;
				blockButton.setMessage(blockLabel());
			}));
			y += ROW;
		}
	}

	private void pushUndo() {
		lastWasPaste = false;
		if (undoStack.size() >= MAX_UNDO) undoStack.removeLast();
		undoStack.push(new Snapshot(level, deepCopy(grid), deepCopy(options)));
	}

	private static <T> T[][] deepCopy(T[][] source) {
		T[][] copy = source.clone();
		for (int i = 0; i < copy.length; i++) copy[i] = source[i].clone();
		return copy;
	}

	private void undo() {
		//Right after pasting, Undo means "take that paste out of the world".
		if (lastWasPaste) {
			lastWasPaste = false;
			this.minecraft.player.connection.sendCommand("dnddungeon undopaste");
			return;
		}
		Snapshot snapshot = undoStack.poll();
		if (snapshot == null) return;
		//Back to the level the stroke was on, so the undo is seen happening.
		syncLevel();
		selX1 = -1;
		levels.set(snapshot.level(), snapshot.grid());
		levelOptions.set(snapshot.level(), snapshot.options());
		showLevel(snapshot.level());
	}

	private Component blockLabel() {
		return materialLabel(I18n.get("gui.dndsheets.dungeon_trace.object_prefix"), currentObjectBlock, LEFT_COLUMN - 8);
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

	private static int indexOf(Direction facing) {
		for (int i = 0; i < FACING_CYCLE.length; i++) if (FACING_CYCLE[i] == facing) return i;
		return 0;
	}

	private Component heightLabel() {
		return Component.translatable("gui.dndsheets.dungeon_trace.height_label", wallHeight);
	}

	private void changeHeight(int delta) {
		wallHeight = Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, wallHeight + delta));
	}

	//pasteOnly: build it in the world and stop (no piece saved); otherwise capture it as a dungeon piece.
	private void send(boolean pasteOnly) {
		int count = usedLevels();
		String[] rows = new String[count * gridSize];
		int[] heights = new int[count];
		List<Integer> objX = new ArrayList<>(), objZ = new ArrayList<>();
		List<String> objBlockId = new ArrayList<>(), objFacing = new ArrayList<>();
		List<Integer> doorX = new ArrayList<>(), doorZ = new ArrayList<>();
		List<String> doorPool = new ArrayList<>(), doorFacing = new ArrayList<>(), doorBlock = new ArrayList<>();
		List<Integer> matX = new ArrayList<>(), matZ = new ArrayList<>();
		List<String> matBlockId = new ArrayList<>();
		List<Integer> holeX = new ArrayList<>(), holeZ = new ArrayList<>(), holeMask = new ArrayList<>();
		List<Integer> wallBlockX = new ArrayList<>(), wallBlockZ = new ArrayList<>(), wallBlockY = new ArrayList<>();
		List<String> wallBlockIds = new ArrayList<>();

		//The levels' rows go one after another; z counts across all of them (see DungeonTraceCaptureMessage).
		for (int l = 0; l < count; l++) {
			GridToStructure.Cell[][] levelGrid = levels.get(l);
			GridToStructure.CellOptions[][] levelOpts = levelOptions.get(l);
			heights[l] = levelHeights.get(l);
			for (int z = 0; z < gridSize; z++) {
				int row = l * gridSize + z;
				StringBuilder text = new StringBuilder(gridSize);
				for (int x = 0; x < gridSize; x++) {
					GridToStructure.Cell c = levelGrid[z][x];
					text.append(GridToStructure.toChar(c));
					GridToStructure.CellOptions opt = levelOpts[z][x];
					//Stairs with a direction travel as objects too: the server takes the options whatever the cell.
					if (c == GridToStructure.Cell.OBJECT && opt.objectBlock() != null || c == GridToStructure.Cell.STAIRS && opt.objectFacing() != null) {
						objX.add(x);
						objZ.add(row);
						objBlockId.add(opt.objectBlock() != null ? opt.objectBlock().toString() : "");
						objFacing.add(opt.objectFacing() != null ? opt.objectFacing().getSerializedName() : "");
					} else if ((c == GridToStructure.Cell.DOOR || c == GridToStructure.Cell.START) && (opt.doorPool() != null || opt.doorFacing() != null || opt.objectBlock() != null)) {
						doorX.add(x);
						doorZ.add(row);
						doorPool.add(opt.doorPool() != null ? opt.doorPool() : "");
						doorFacing.add(opt.doorFacing() != null ? opt.doorFacing().getSerializedName() : "");
						doorBlock.add(opt.objectBlock() != null ? opt.objectBlock().toString() : "");
					} else if ((c == GridToStructure.Cell.WALL || c == GridToStructure.Cell.FLOOR) && opt.material() != null) {
						matX.add(x);
						matZ.add(row);
						matBlockId.add(opt.material().toString());
					}
					if (c == GridToStructure.Cell.WALL && opt.blocks() != null) {
						for (int y = 1; y <= opt.blocks().length; y++) {
							if (opt.blocks()[y - 1] == null) continue;
							wallBlockX.add(x);
							wallBlockZ.add(row);
							wallBlockY.add(y);
							wallBlockIds.add(opt.blocks()[y - 1].toString());
						}
					}
					if (c == GridToStructure.Cell.WALL && opt.holes() != 0) {
						holeX.add(x);
						holeZ.add(row);
						holeMask.add(opt.holes());
					}
				}
				rows[row] = text.toString();
			}
		}

		List<GridToStructure.Note> sentNotes = signs ? notes : List.of();
		//In pieces and compressed: an imported map is bigger than one packet (see DungeonTraceChunkMessage).
		net.hawthorn.dndsheets.dungeon.network.DungeonTraceChunkMessage.send(new DungeonTraceCaptureMessage(
			id, structureId, pool, weight, tags, heights[0], rows,
			toArray(objX), toArray(objZ), objBlockId.toArray(new String[0]), objFacing.toArray(new String[0]),
			toArray(doorX), toArray(doorZ), doorPool.toArray(new String[0]), doorFacing.toArray(new String[0]),
			wallBlock != null ? wallBlock.toString() : "", floorBlock != null ? floorBlock.toString() : "", originDx, originDz,
			toArray(matX), toArray(matZ), matBlockId.toArray(new String[0]), anchor, heights, pasteOnly,
			toArray(holeX), toArray(holeZ), toArray(holeMask), originDy, doorBlock.toArray(new String[0]),
			toArray(wallBlockX), toArray(wallBlockZ), toArray(wallBlockY), wallBlockIds.toArray(new String[0]),
			ceiling, carve, lightSpacing > 0 ? lightBlockOrDefault().toString() : "", lightSpacing,
			sentNotes.stream().mapToInt(GridToStructure.Note::x).toArray(), sentNotes.stream().mapToInt(GridToStructure.Note::z).toArray(),
			sentNotes.stream().map(GridToStructure.Note::text).toArray(String[]::new)));
		//Pasting keeps the editor open: the same room can be pasted again somewhere else.
		if (pasteOnly) {
			lastWasPaste = true;
			return;
		}
		DungeonTracePreview.stop();
		Minecraft.getInstance().setScreen(parent);
	}

	private static int[] toArray(List<Integer> values) {
		return values.stream().mapToInt(Integer::intValue).toArray();
	}

	@Override
	public void onClose() {
		DungeonTracePreview.stop();
		Minecraft.getInstance().setScreen(parent);
	}

	//No dimming over the world: the ghost preview behind the dialog is the point of having it open.
	@Override
	public void renderBackground(GuiGraphics guiGraphics) {
		if (this.minecraft == null || this.minecraft.level == null) super.renderBackground(guiGraphics);
	}

	/** Where the piece's corner goes: the anchor if previewed in the world, otherwise the DM's feet; plus the X/Z offset. */
	BlockPos previewOrigin(net.minecraft.world.entity.player.Player player) {
		return (anchor != null ? anchor : BlockPos.containing(player.position())).offset(originDx, originDy, originDz);
	}

	void moveAnchor(int dx, int dy, int dz) {
		if (anchor != null) anchor = anchor.offset(dx, dy, dz);
	}

	String pieceId() { return id; }

	//Read-only, for DungeonTracePreviewRenderer — it draws in the world the same thing that's here, not
	//a separate copy of state.
	ResourceLocation wallBlockId() { return wallBlock; }
	ResourceLocation floorBlockId() { return floorBlock; }
	int originDx() { return originDx; }
	int originDz() { return originDz; }

	//The visible square of the plan on screen.
	private int viewLeft() {
		return dialogLeft() + MARGIN + LEFT_COLUMN + GAP + (canvasArea() - canvas) / 2;
	}

	private int viewTop() {
		int room = dialogHeight() - TOP_BAND - NORTH_BAND - STATUS_BAND - MARGIN;
		return dialogTop() + TOP_BAND + NORTH_BAND + (room - canvas) / 2;
	}

	//Where cell (0, 0) is drawn: the view shifted by the scroll, possibly off to the left/top of it.
	private int canvasLeft() {
		return viewLeft() - viewX * cell;
	}

	private int canvasTop() {
		return viewTop() - viewZ * cell;
	}

	private boolean inView(double mouseX, double mouseY) {
		return mouseX >= viewLeft() && mouseX < viewLeft() + canvas && mouseY >= viewTop() && mouseY < viewTop() + canvas;
	}

	//Grid cell under a screen point, or null if it falls outside the canvas.
	@Nullable
	private int[] cellAt(double mouseX, double mouseY) {
		if (!inView(mouseX, mouseY)) return null;
		int left = canvasLeft(), top = canvasTop();
		int gx = (int) ((mouseX - left) / cell), gz = (int) ((mouseY - top) / cell);
		if (gx < 0 || gx >= gridSize || gz < 0 || gz >= gridSize) return null;
		return new int[]{gx, gz};
	}

	//Wheel over the plan: zoom keeping the cell under the mouse where it is.
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		if (elevation || !inView(mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, delta);
		zoomAt(mouseX, mouseY, delta);
		return true;
	}

	private void zoomAt(double mouseX, double mouseY, double delta) {
		double gx = (mouseX - canvasLeft()) / cell, gz = (mouseY - canvasTop()) / cell;
		int fit = Math.max(1, canvasArea() / gridSize);
		int next = Math.max(Math.min(fit, cell), Math.min(24, cell + (delta > 0 ? Math.max(1, cell / 4) : -Math.max(1, cell / 5))));
		zoom = next <= fit ? 0 : next;
		applyCellSize();
		viewX = (int) Math.round(gx - (mouseX - viewLeft()) / (double) cell);
		viewZ = (int) Math.round(gz - (mouseY - viewTop()) / (double) cell);
		applyCellSize();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!elevation && (button == 2 || button == 0 && hasControlDown()) && inView(mouseX, mouseY)) {
			panning = true;
			panMouseX = mouseX;
			panMouseY = mouseY;
			panViewX = viewX;
			panViewZ = viewZ;
			return true;
		}
		if (button == 0 && elevation) {
			int[] at = elevationAt(mouseX, mouseY);
			if (at != null) {
				int[] xz = elevationCell(at[0]);
				if (grid[xz[1]][xz[0]] != GridToStructure.Cell.WALL) return true;
				pushUndo();
				//Carving: the first block clicked decides the stroke, open holes or fill them back in.
				elevationBrush = elevationPaint ? 2 : options[xz[1]][xz[0]].isHole(at[1]) ? 0 : 1;
				carve(at[0], at[1]);
				return true;
			}
			return super.mouseClicked(mouseX, mouseY, button);
		}
		if (button == 1) {
			int[] at = cellAt(mouseX, mouseY);
			if (at != null && grid[at[1]][at[0]] == GridToStructure.Cell.WALL) {
				openWallRun(at[0], at[1]);
				return true;
			}
		}
		if (button == 0) {
			int[] at = cellAt(mouseX, mouseY);
			if (at != null && paintMode == PaintMode.MOVE) {
				//Inside the selection: grab it. Elsewhere: start selecting a new rectangle.
				if (inSelection(at[0], at[1])) {
					movingSelection = true;
					grabX = at[0];
					grabZ = at[1];
					moveDx = moveDz = 0;
				} else {
					selX1 = -1;
					rectDragging = true;
					rectStartX = rectEndX = at[0];
					rectStartZ = rectEndZ = at[1];
				}
				return true;
			}
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
		if (panning) {
			viewX = panViewX - (int) Math.round((mouseX - panMouseX) / cell);
			viewZ = panViewZ - (int) Math.round((mouseY - panMouseY) / cell);
			applyCellSize();
			return true;
		}
		if (elevation) {
			int[] at = elevationAt(mouseX, mouseY);
			if (at != null && elevationBrush >= 0) carve(at[0], at[1]);
			return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
		}
		if (button == 0) {
			int[] at = cellAt(mouseX, mouseY);
			if (at != null) {
				if (movingSelection) {
					moveDx = at[0] - grabX;
					moveDz = at[1] - grabZ;
				} else if (paintMode != PaintMode.FREE && rectDragging) {
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
		if (panning) {
			panning = false;
			return true;
		}
		if (elevation) {
			elevationBrush = -1;
			return super.mouseReleased(mouseX, mouseY, button);
		}
		if (button == 0 && paintMode == PaintMode.MOVE) {
			if (rectDragging) {
				selX1 = Math.min(rectStartX, rectEndX);
				selX2 = Math.max(rectStartX, rectEndX);
				selZ1 = Math.min(rectStartZ, rectEndZ);
				selZ2 = Math.max(rectStartZ, rectEndZ);
				rectDragging = false;
				return true;
			}
			if (movingSelection) {
				movingSelection = false;
				shiftSelection(moveDx, moveDz, hasShiftDown());
				return true;
			}
		}
		if (button == 0 && paintMode == PaintMode.RECT && rectDragging) {
			int minX = Math.min(rectStartX, rectEndX), maxX = Math.max(rectStartX, rectEndX);
			int minZ = Math.min(rectStartZ, rectEndZ), maxZ = Math.max(rectStartZ, rectEndZ);
			for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) paint(x, z);
			rectDragging = false;
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	//--- Wall editor: the selected run of wall seen from the side, one square per block ---

	/** A one-cell-wide selection with some wall in it: the only shape that has a meaningful side view. */
	private boolean canEditWall() {
		if (selX1 < 0 || (selX1 != selX2 && selZ1 != selZ2)) return false;
		for (int z = selZ1; z <= selZ2; z++) for (int x = selX1; x <= selX2; x++) if (grid[z][x] == GridToStructure.Cell.WALL) return true;
		return false;
	}

	private int elevationLength() {
		return elevationAlongX ? selX2 - selX1 + 1 : selZ2 - selZ1 + 1;
	}

	/** Grid cell of the run's i-th column. */
	private int[] elevationCell(int i) {
		return elevationAlongX ? new int[]{selX1 + i, selZ1} : new int[]{selX1, selZ1 + i};
	}

	//{left, top, block size} of the side view, centred in the canvas's room.
	private int[] elevationBox() {
		int areaLeft = dialogLeft() + MARGIN + LEFT_COLUMN + GAP, areaTop = dialogTop() + TOP_BAND + NORTH_BAND;
		int areaWidth = canvasArea(), areaHeight = dialogHeight() - TOP_BAND - NORTH_BAND - STATUS_BAND - MARGIN;
		int length = elevationLength();
		int size = Math.max(2, Math.min(20, Math.min((areaWidth - 24) / length, areaHeight / wallHeight)));
		return new int[]{areaLeft + (areaWidth - size * length) / 2, areaTop + (areaHeight - size * wallHeight) / 2, size};
	}

	//{column, height} under the mouse (height 1 = bottom row), or null.
	@Nullable
	private int[] elevationAt(double mouseX, double mouseY) {
		int[] box = elevationBox();
		int column = (int) Math.floor((mouseX - box[0]) / box[2]), row = (int) Math.floor((mouseY - box[1]) / box[2]);
		if (column < 0 || column >= elevationLength() || row < 0 || row >= wallHeight) return null;
		return new int[]{column, wallHeight - row};
	}

	private void carve(int column, int y) {
		int[] xz = elevationCell(column);
		if (grid[xz[1]][xz[0]] != GridToStructure.Cell.WALL) return;
		GridToStructure.CellOptions opt = options[xz[1]][xz[0]];
		//Brush 2 = paint this one block with the slots' pattern (no slot = back to the column's material).
		if (elevationBrush == 2) {
			if (pattern == Pattern.RANDOM && opt.blockAt(y) != null && java.util.Arrays.asList(palette).contains(opt.blockAt(y))) return;
			options[xz[1]][xz[0]] = opt.withBlockAt(y, brushBlock(xz[0], xz[1], y));
			return;
		}
		int bit = 1 << (y - 1);
		options[xz[1]][xz[0]] = opt.withHoles(elevationBrush == 1 ? opt.holes() | bit : opt.holes() & ~bit);
	}

	/**
	 * <p>Random gaps in the selected walls, more of them higher up (a worn wall crumbles from the top). Each
	 * press adds more; Restore takes them all back. Undoable like any stroke.</p>
	 */
	private void wearSelection() {
		if (selX1 < 0) return;
		pushUndo();
		for (int z = selZ1; z <= selZ2; z++) {
			for (int x = selX1; x <= selX2; x++) {
				if (grid[z][x] != GridToStructure.Cell.WALL) continue;
				int holes = options[z][x].holes();
				for (int y = 1; y <= wallHeight; y++) if (random.nextDouble() < 0.12 + 0.28 * y / wallHeight) holes |= 1 << (y - 1);
				options[z][x] = options[z][x].withHoles(holes);
			}
		}
	}

	private void restoreSelection() {
		if (selX1 < 0) return;
		pushUndo();
		for (int z = selZ1; z <= selZ2; z++) for (int x = selX1; x <= selX2; x++) options[z][x] = options[z][x].withHoles(0);
	}

	private void renderElevation(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		int[] box = elevationBox();
		int length = elevationLength(), size = box[2];
		GuiStyle.inset(guiGraphics, box[0] - 3, box[1] - 3, box[0] + size * length + 2, box[1] + size * wallHeight + 2);
		int[] hover = elevationAt(mouseX, mouseY);
		for (int i = 0; i < length; i++) {
			int[] xz = elevationCell(i);
			GridToStructure.Cell c = grid[xz[1]][xz[0]];
			GridToStructure.CellOptions opt = options[xz[1]][xz[0]];
			for (int y = 1; y <= wallHeight; y++) {
				int x1 = box[0] + i * size, y1 = box[1] + (wallHeight - y) * size;
				int color = c != GridToStructure.Cell.WALL ? (colorFor(c) & 0xFFFFFF) | 0x30000000
					: opt.isHole(y) ? 0xFF151515 : opt.blockAt(y) != null ? mapColor(opt.blockAt(y)) : colorFor(GridToStructure.Cell.WALL);
				guiGraphics.fill(x1, y1, x1 + size - 1, y1 + size - 1, color);
			}
		}
		if (hover != null) {
			int x1 = box[0] + hover[0] * size, y1 = box[1] + (wallHeight - hover[1]) * size;
			guiGraphics.renderOutline(x1 - 1, y1 - 1, size + 1, size + 1, GuiStyle.ACCENT_COLOR);
		}
		//Which way the run goes: west→east for a row, north→south for a column.
		String start = elevationAlongX ? "west" : "north", end = elevationAlongX ? "east" : "south";
		int midY = box[1] + size * wallHeight / 2 - 4;
		guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.dungeon_trace.facing_" + start), box[0] - 10, midY, GuiStyle.MUTED_COLOR);
		guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.dungeon_trace.facing_" + end), box[0] + size * length + 10, midY, GuiStyle.MUTED_COLOR);
	}

	private boolean inSelection(int x, int z) {
		return selX1 >= 0 && x >= selX1 && x <= selX2 && z >= selZ1 && z <= selZ2;
	}

	/**
	 * <p>Moves the selected cells by (dx, dz), with their blocks, doors and paint. Empty cells in the
	 * selection don't travel, so a selected wall slides over the floor without punching holes in it; what
	 * it leaves behind is empty (copy = leave the originals). Whatever lands off the grid is lost, and the
	 * selection follows the cells, clipped to the grid.</p>
	 */
	private void shiftSelection(int dx, int dz, boolean copy) {
		if (selX1 < 0 || (dx == 0 && dz == 0)) return;
		pushUndo();
		int w = selX2 - selX1 + 1, h = selZ2 - selZ1 + 1;
		GridToStructure.Cell[][] cells = new GridToStructure.Cell[h][w];
		GridToStructure.CellOptions[][] opts = new GridToStructure.CellOptions[h][w];
		for (int z = 0; z < h; z++) {
			for (int x = 0; x < w; x++) {
				cells[z][x] = grid[selZ1 + z][selX1 + x];
				opts[z][x] = options[selZ1 + z][selX1 + x];
				if (!copy) {
					grid[selZ1 + z][selX1 + x] = GridToStructure.Cell.EMPTY;
					options[selZ1 + z][selX1 + x] = GridToStructure.CellOptions.EMPTY;
				}
			}
		}
		for (int z = 0; z < h; z++) {
			for (int x = 0; x < w; x++) {
				int tx = selX1 + x + dx, tz = selZ1 + z + dz;
				if (cells[z][x] == GridToStructure.Cell.EMPTY || tx < 0 || tz < 0 || tx >= gridSize || tz >= gridSize) continue;
				grid[tz][tx] = cells[z][x];
				options[tz][tx] = opts[z][x];
			}
		}
		selX1 = Math.max(0, selX1 + dx);
		selZ1 = Math.max(0, selZ1 + dz);
		selX2 = Math.min(gridSize - 1, selX2 + dx);
		selZ2 = Math.min(gridSize - 1, selZ2 + dz);
		if (selX1 > selX2 || selZ1 > selZ2) selX1 = -1;
	}

	//MOVE mode with a selection: arrows nudge it one cell (Shift copies), Delete/Backspace empties it.
	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_Z && hasControlDown() && !doorPoolBox.isFocused()) {
			undo();
			return true;
		}
		if (paintMode == PaintMode.MOVE && selX1 >= 0 && !elevation && !doorPoolBox.isFocused()) {
			boolean copy = hasShiftDown();
			switch (keyCode) {
				case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT -> { shiftSelection(-1, 0, copy); return true; }
				case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT -> { shiftSelection(1, 0, copy); return true; }
				case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> { shiftSelection(0, -1, copy); return true; }
				case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> { shiftSelection(0, 1, copy); return true; }
				case org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE, org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE -> {
					pushUndo();
					for (int z = selZ1; z <= selZ2; z++) for (int x = selX1; x <= selX2; x++) {
						grid[z][x] = GridToStructure.Cell.EMPTY;
						options[z][x] = GridToStructure.CellOptions.EMPTY;
					}
					return true;
				}
				default -> {}
			}
		}
		//Arrows scroll a map bigger than the view.
		if (!elevation && !doorPoolBox.isFocused() && canvas < cell * gridSize) {
			int step = Math.max(1, canvas / cell / 4);
			switch (keyCode) {
				case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT -> viewX -= step;
				case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT -> viewX += step;
				case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> viewZ -= step;
				case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> viewZ += step;
				default -> { return super.keyPressed(keyCode, scanCode, modifiers); }
			}
			applyCellSize();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private void paint(int x, int z) {
		if (x < 0 || x >= gridSize || z < 0 || z >= gridSize) return;
		GridToStructure.Cell here = grid[z][x];
		GridToStructure.CellOptions old = options[z][x];
		if (currentTool == Tool.PAINT) {
			//Only walls and floors have a material. Random keeps a cell already painted from the palette,
			//or dragging over it would reshuffle it on every mouse move.
			if (here != GridToStructure.Cell.WALL && here != GridToStructure.Cell.FLOOR) return;
			if (pattern == Pattern.RANDOM && old.material() != null && java.util.Arrays.asList(palette).contains(old.material())) return;
			options[z][x] = paintCell(here, old, x, z);
			return;
		}
		grid[z][x] = currentTool.cell;
		options[z][x] = switch (currentTool) {
			case OBJECT -> new GridToStructure.CellOptions(currentObjectBlock, currentFacing, null, null);
			case DOOR, START -> new GridToStructure.CellOptions(currentDoorBlock, null, doorPoolOverride(), currentFacing);
			case STAIRS -> new GridToStructure.CellOptions(currentStairsBlock, currentFacing != null && currentFacing.getAxis().isHorizontal() ? currentFacing : null, null, null);
			//Redrawing a wall as wall (or floor as floor) keeps its paint; changing the type drops it.
			case WALL, FLOOR -> here == currentTool.cell ? old : GridToStructure.CellOptions.EMPTY;
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
			case STAIRS -> 0xFF9A7BC4;
		};
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		renderPanel(guiGraphics);
		guiGraphics.drawCenteredString(this.font, GuiStyle.title(this.title), dialogLeft() + dialogWidth() / 2, dialogTop() + 8, GuiStyle.TITLE_COLOR);

		int left = canvasLeft(), top = canvasTop();
		int viewLeft = viewLeft(), viewTop = viewTop();
		int[] hovered = elevation ? null : cellAt(mouseX, mouseY);
		hoverWall = hovered != null && grid[hovered[1]][hovered[0]] == GridToStructure.Cell.WALL;
		if (elevation) {
			renderElevation(guiGraphics, mouseX, mouseY);
			renderStatus(guiGraphics, viewLeft, viewTop + canvas + 5);
			renderWidgetsAndChrome(guiGraphics, mouseX, mouseY, partialTicks);
			return;
		}
		GuiStyle.inset(guiGraphics, viewLeft - 3, viewTop - 3, viewLeft + canvas + 2, viewTop + canvas + 2);
		//A big map is scrolled: whatever lies outside the view is cut off.
		guiGraphics.enableScissor(viewLeft, viewTop, viewLeft + canvas, viewTop + canvas);
		//Under 4 px a cell has no room for the 1 px gap between cells.
		int gap = cell >= 4 ? 1 : 0;
		for (int z = firstZ(); z < lastZ(); z++) {
			for (int x = firstX(); x < lastX(); x++) {
				int x1 = left + x * cell, y1 = top + z * cell;
				guiGraphics.fill(x1, y1, x1 + cell - gap, y1 + cell - gap, colorFor(grid[z][x]));
				if (cell < 4) continue;
				//Painted: the block's own colour inside a rim of the cell type's, so wall and floor still read.
				ResourceLocation material = grid[z][x] == GridToStructure.Cell.WALL ? options[z][x].blockAt(1) : options[z][x].material();
				//A closed doorway shows its block the same way.
				if (material == null && (grid[z][x] == GridToStructure.Cell.DOOR || grid[z][x] == GridToStructure.Cell.START)) material = options[z][x].objectBlock();
				if (material != null && grid[z][x] != GridToStructure.Cell.OBJECT) {
					int rim = Math.max(1, cell / 5);
					guiGraphics.fill(x1 + rim, y1 + rim, x1 + cell - 1 - rim, y1 + cell - 1 - rim, mapColor(material));
				}
				//Carved wall (windows, gaps): a dark dot in the middle.
				if (grid[z][x] == GridToStructure.Cell.WALL && options[z][x].holes() != 0) {
					int dot = Math.max(1, cell / 3), cx = x1 + (cell - 1 - dot) / 2, cy = y1 + (cell - 1 - dot) / 2;
					guiGraphics.fill(cx, cy, cx + dot, cy + dot, 0xFF151515);
				}
			}
		}
		renderLevelBelow(guiGraphics, left, top);
		if (cell >= 4) renderDoorEdges(guiGraphics, left, top);
		//Notes (a donjon room's contents...): a yellow dot, the text when hovered (see the end of render).
		GridToStructure.Note hoveredNote = null;
		for (GridToStructure.Note note : notes) {
			int dot = Math.max(2, cell / 2), nx = left + note.x() * cell + (cell - dot) / 2, nz = top + note.z() * cell + (cell - dot) / 2;
			guiGraphics.fill(nx - 1, nz - 1, nx + dot + 1, nz + dot + 1, 0xFF000000);
			guiGraphics.fill(nx, nz, nx + dot, nz + dot, 0xFFFFD84A);
			if (inView(mouseX, mouseY) && Math.abs(mouseX - (nx + dot / 2.0)) <= Math.max(3, dot) && Math.abs(mouseY - (nz + dot / 2.0)) <= Math.max(3, dot)) hoveredNote = note;
		}

		if (rectDragging) {
			int minX = Math.min(rectStartX, rectEndX), maxX = Math.max(rectStartX, rectEndX);
			int minZ = Math.min(rectStartZ, rectEndZ), maxZ = Math.max(rectStartZ, rectEndZ);
			int x1 = left + minX * cell, y1 = top + minZ * cell;
			int x2 = left + (maxX + 1) * cell, y2 = top + (maxZ + 1) * cell;
			guiGraphics.renderOutline(x1, y1, x2 - x1, y2 - y1, GuiStyle.ACCENT_COLOR);
		} else if (paintMode == PaintMode.MOVE) {
			renderSelection(guiGraphics, left, top);
		} else {
			//The cell the brush would paint, tinted with the active tool's colour.
			int[] at = cellAt(mouseX, mouseY);
			if (at != null) {
				int x1 = left + at[0] * cell, y1 = top + at[1] * cell;
				guiGraphics.fill(x1, y1, x1 + cell - 1, y1 + cell - 1, (toolColor(currentTool) & 0xFFFFFF) | 0x99000000);
				guiGraphics.renderOutline(x1 - 1, y1 - 1, cell + 1, cell + 1, GuiStyle.ACCENT_COLOR);
			}
		}
		guiGraphics.disableScissor();

		renderStatus(guiGraphics, viewLeft, viewTop + canvas + 5);
		//North marker: the facing buttons say N/E/S/W, and the grid's top edge is north.
		guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.dungeon_trace.facing_north"), viewLeft + canvas / 2, viewTop - 12, GuiStyle.MUTED_COLOR);
		renderWidgetsAndChrome(guiGraphics, mouseX, mouseY, partialTicks);
		if (hoveredNote != null) guiGraphics.renderTooltip(this.font, this.font.split(Component.literal(hoveredNote.text()), 220), mouseX, mouseY);
	}

	//Everything that isn't the canvas: widgets, swatches, the selected ring of each segment, the steppers.
	private void renderWidgetsAndChrome(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		if (editWallButton != null) editWallButton.active = canEditWall();
		if (doorPoolBox.visible) {
			guiGraphics.drawString(this.font, fit(I18n.get("gui.dndsheets.dungeon_trace.door_pool"), LEFT_COLUMN), dialogLeft() + MARGIN, dialogTop() + poolLabelY + 1, GuiStyle.SUBTITLE_COLOR, false);
		}

		super.render(guiGraphics, mouseX, mouseY, partialTicks);
		if (doorPoolBox.visible) net.hawthorn.dndsheets.client.gui.components.TomeField.frameWidget(guiGraphics, doorPoolBox.getX(), doorPoolBox.getY(), doorPoolBox.getWidth(), doorPoolBox.getHeight(), doorPoolBox.isFocused());

		//Tool buttons double as the colour legend; the active one gets the brass ring (DESIGN.md §4).
		for (int i = 0; i < toolButtons.size(); i++) {
			Button button = toolButtons.get(i);
			Tool tool = Tool.values()[i];
			int sx = button.getX() + 4, sy = button.getY() + 4;
			guiGraphics.fill(sx - 1, sy - 1, sx + 9, sy + 9, GuiStyle.ACCENT_COLOR);
			guiGraphics.fill(sx, sy, sx + 8, sy + 8, toolColor(tool));
			if (tool == currentTool) guiGraphics.renderOutline(button.getX() - 1, button.getY() - 1, button.getWidth() + 2, button.getHeight() + 2, GuiStyle.ACCENT_COLOR);
		}
		for (int i = 0; i < patternButtons.size(); i++) renderPatternSample(guiGraphics, patternButtons.get(i), Pattern.values()[i]);
		for (Segment segment : segments) {
			int selected = segment.selected().getAsInt();
			//Out of range = the choice lives in another group (the facing's ↑/↓ row).
			if (selected < 0 || selected >= segment.buttons().size()) continue;
			Button button = segment.buttons().get(selected);
			guiGraphics.renderOutline(button.getX() - 1, button.getY() - 1, button.getWidth() + 2, button.getHeight() + 2, GuiStyle.ACCENT_COLOR);
		}
		for (int i = 0; i < paletteButtons.size(); i++) {
			if (palette[i] == null) continue;
			Button button = paletteButtons.get(i);
			int sx = button.getX() + 4, sy = button.getY() + 4;
			guiGraphics.fill(sx - 1, sy - 1, sx + 9, sy + 9, GuiStyle.ACCENT_COLOR);
			guiGraphics.fill(sx, sy, sx + 8, sy + 8, mapColor(palette[i]));
		}

		for (Readout readout : readouts) {
			GuiStyle.inset(guiGraphics, readout.x(), readout.y(), readout.x() + readout.width(), readout.y() + BUTTON_HEIGHT);
			guiGraphics.drawCenteredString(this.font, readout.text().get(), readout.x() + readout.width() / 2, readout.y() + 4, GuiStyle.SUBTITLE_COLOR);
			if (mouseX >= readout.x() && mouseX < readout.x() + readout.width() && mouseY >= readout.y() && mouseY < readout.y() + BUTTON_HEIGHT) {
				guiGraphics.renderTooltip(this.font, this.font.split(readout.tip(), 200), mouseX, mouseY);
			}
		}
	}

	//A 3x3 sample of the pattern, in the first two slots' colours (or two greys with no slots yet).
	private void renderPatternSample(GuiGraphics guiGraphics, Button button, Pattern sample) {
		int a = palette[0] != null ? mapColor(palette[0]) : 0xFF9A9A9A, b = palette[1] != null ? mapColor(palette[1]) : 0xFF555555;
		int size = 3, left = button.getX() + (button.getWidth() - 3 * size) / 2, top = button.getY() + (button.getHeight() - 3 * size) / 2;
		int[] noise = {0, 1, 1, 0, 0, 1, 1, 1, 0};
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 3; col++) {
				boolean second = switch (sample) {
					case SOLID -> false;
					case ALTERNATE -> (row + col) % 2 == 1;
					case ROWS -> row % 2 == 1;
					case RANDOM -> noise[row * 3 + col] == 1;
				};
				guiGraphics.fill(left + col * size, top + row * size, left + (col + 1) * size, top + (row + 1) * size, second ? b : a);
			}
		}
	}

	//The selection's frame; while it's being dragged, its cells drawn where they would land.
	private void renderSelection(GuiGraphics guiGraphics, int left, int top) {
		if (selX1 < 0) return;
		int dx = movingSelection ? moveDx : 0, dz = movingSelection ? moveDz : 0;
		if (movingSelection) {
			for (int z = selZ1; z <= selZ2; z++) {
				for (int x = selX1; x <= selX2; x++) {
					int tx = x + dx, tz = z + dz;
					if (grid[z][x] == GridToStructure.Cell.EMPTY || tx < 0 || tz < 0 || tx >= gridSize || tz >= gridSize) continue;
					int x1 = left + tx * cell, y1 = top + tz * cell;
					guiGraphics.fill(x1, y1, x1 + cell - 1, y1 + cell - 1, (colorFor(grid[z][x]) & 0xFFFFFF) | 0xB0000000);
				}
			}
		}
		int x1 = left + (selX1 + dx) * cell, y1 = top + (selZ1 + dz) * cell;
		guiGraphics.renderOutline(x1 - 1, y1 - 1, (selX2 - selX1 + 1) * cell + 1, (selZ2 - selZ1 + 1) * cell + 1, GuiStyle.ACCENT_COLOR);
	}

	//Onion skin: the level below, faint, under this level's empty cells — to line walls up and place the
	//stairs' landing.
	private void renderLevelBelow(GuiGraphics guiGraphics, int left, int top) {
		if (level == 0) return;
		GridToStructure.Cell[][] below = levels.get(level - 1);
		for (int z = firstZ(); z < lastZ(); z++) {
			for (int x = firstX(); x < lastX(); x++) {
				if (grid[z][x] != GridToStructure.Cell.EMPTY || below[z][x] == GridToStructure.Cell.EMPTY) continue;
				int x1 = left + x * cell, y1 = top + z * cell;
				guiGraphics.fill(x1, y1, x1 + cell - 1, y1 + cell - 1, (colorFor(below[z][x]) & 0xFFFFFF) | 0x50000000);
			}
		}
	}

	/**
	 * <p>Each door and entrance gets a bar on the edge it opens through: that is where the generator attaches
	 * the next piece (its own door, face to face). Red bar = no empty neighbour to face, so it points into
	 * the room and nothing will ever attach there.</p>
	 */
	private void renderDoorEdges(GuiGraphics guiGraphics, int left, int top) {
		int bar = Math.max(2, cell / 4);
		for (int z = firstZ(); z < lastZ(); z++) {
			for (int x = firstX(); x < lastX(); x++) {
				GridToStructure.Cell c = grid[z][x];
				if (c != GridToStructure.Cell.DOOR && c != GridToStructure.Cell.START) continue;
				Direction manual = options[z][x].doorFacing();
				Direction facing = manual != null ? manual : GridToStructure.outwardFacing(grid, x, z);
				int color = manual == null && !GridToStructure.hasOutside(grid, x, z) ? GuiStyle.WARN_COLOR : 0xFFFFFFFF;
				int x1 = left + x * cell, y1 = top + z * cell, x2 = x1 + cell - 1, y2 = y1 + cell - 1;
				switch (facing) {
					case NORTH -> guiGraphics.fill(x1, y1, x2, y1 + bar, color);
					case SOUTH -> guiGraphics.fill(x1, y2 - bar, x2, y2, color);
					case WEST -> guiGraphics.fill(x1, y1, x1 + bar, y2, color);
					case EAST -> guiGraphics.fill(x2 - bar, y1, x2, y2, color);
					//Hatch: an arrow in the cell, there's no edge to mark.
					default -> guiGraphics.drawCenteredString(this.font, facing == Direction.UP ? "↑" : "↓", x1 + cell / 2, y1 + (cell - 8) / 2, color);
				}
			}
		}
		//Stairs with a direction: an arrow pointing the way up.
		for (int z = firstZ(); z < lastZ(); z++) {
			for (int x = firstX(); x < lastX(); x++) {
				if (grid[z][x] != GridToStructure.Cell.STAIRS || options[z][x].objectFacing() == null) continue;
				String arrow = switch (options[z][x].objectFacing()) {
					case NORTH -> "↑";
					case SOUTH -> "↓";
					case EAST -> "→";
					default -> "←";
				};
				guiGraphics.drawCenteredString(this.font, arrow, left + x * cell + cell / 2, top + z * cell + (cell - 8) / 2, 0xFFFFFFFF);
			}
		}
	}

	//One line under the canvas: how this piece will connect when a dungeon is generated (or that it won't).
	private void renderStatus(GuiGraphics guiGraphics, int left, int y) {
		Component status;
		int color = GuiStyle.MUTED_COLOR;
		if (elevation) {
			status = Component.translatable(elevationPaint ? "gui.dndsheets.dungeon_trace.status_wall_paint" : "gui.dndsheets.dungeon_trace.status_wall_edit");
		} else if (hoverWall && !rectDragging) {
			status = Component.translatable("gui.dndsheets.dungeon_trace.status_hover_wall");
		} else if (paintMode == PaintMode.MOVE) {
			status = Component.translatable(selX1 < 0 ? "gui.dndsheets.dungeon_trace.status_move_select" : "gui.dndsheets.dungeon_trace.status_move_drag");
		} else if (imported) {
			status = Component.translatable("gui.dndsheets.dungeon_trace.status_navigate");
		} else if (id.isEmpty()) {
			status = Component.translatable("gui.dndsheets.dungeon_trace.status_paste_only");
		} else {
			int doors = 0, starts = 0, inward = 0;
			for (int l = 0; l < levels.size(); l++) {
				GridToStructure.Cell[][] g = l == level ? grid : levels.get(l);
				GridToStructure.CellOptions[][] o = l == level ? options : levelOptions.get(l);
				for (int z = 0; z < gridSize; z++) {
					for (int x = 0; x < gridSize; x++) {
						if (g[z][x] == GridToStructure.Cell.DOOR) doors++;
						else if (g[z][x] == GridToStructure.Cell.START) starts++;
						else continue;
						if (o[z][x].doorFacing() == null && !GridToStructure.hasOutside(g, x, z)) inward++;
					}
				}
			}
			if (doors + starts == 0) {
				status = Component.translatable("gui.dndsheets.dungeon_trace.status_no_doors");
				color = GuiStyle.WARN_COLOR;
			} else if (inward > 0) {
				status = Component.translatable("gui.dndsheets.dungeon_trace.status_inward", doors, starts, inward);
				color = GuiStyle.WARN_COLOR;
			} else {
				status = Component.translatable("gui.dndsheets.dungeon_trace.status_doors", doors, starts);
			}
		}
		guiGraphics.drawCenteredString(this.font, GuiStyle.fit(this.font, status, canvasArea()), left + canvas / 2, y, color);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
