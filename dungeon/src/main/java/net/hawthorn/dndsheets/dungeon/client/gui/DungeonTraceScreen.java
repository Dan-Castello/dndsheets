package net.hawthorn.dndsheets.dungeon.client.gui;
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
 * <p>Dibuja una pieza de mazmorra celda a celda en vez de construirla a mano y escanearla con un bloque
 * de estructura. Al pulsar "Guardar" manda la grilla al servidor ({@link DungeonTraceCaptureMessage}),
 * que la traduce ({@link GridToStructure}) y la planta en el mundo ({@code DungeonPiecePlacer}) — el
 * resultado se registra exactamente igual que una pieza escaneada a mano.</p>
 *
 * <p>Las celdas de objeto no llevan un bloque fijo: "Elegir bloque..." abre {@link BlockPickerScreen},
 * que busca en el registro global de bloques — de cualquier mod instalado, muebles incluidos — así que
 * no hace falta ninguna compatibilidad especial por mod.</p>
 *
 * <p>Puerta y Entrada tienen su propio pool destino ("Pool puerta") y su propia dirección manual — el
 * mismo botón "Girar" que usa Objeto, reutilizado: en Puerta/Entrada, "Auto" es la heurística de
 * {@link GridToStructure} (primer vecino vacío N/E/S/O); cualquier otro valor la reemplaza.</p>
 */
public class DungeonTraceScreen extends ModalDialogScreen {
	//Presupuesto fijo de píxeles para el lienzo: el diálogo no cambia de tamaño (ModalDialogScreen lo fija
	//en el constructor), así que una grilla más grande sale con celdas más chicas, no con una ventana
	//más grande. El tamaño de celda se deriva de esto, no al revés.
	private static final int CANVAS_AREA = 200;
	//24 es el techo práctico, no el del servidor (MAX_SIDE=64 en DungeonTraceCaptureMessage): más allá de
	//eso la celda baja de ~8px y deja de ser un blanco cómodo para el mouse. El límite real es la
	//precisión del clic, no un número arbitrario de protocolo.
	private static final int[] GRID_SIZES = {8, 10, 12, 16, 20, 24};
	private static final int DEFAULT_GRID_INDEX = 2; //12
	private static final int CANVAS_Y = 98;
	private static final int MIN_HEIGHT = 1;
	private static final int MAX_HEIGHT = 10;
	private static final int MAX_UNDO = 20;
	private static final int MAX_ORIGIN_OFFSET = 16;
	private static final int MAX_RECENT_OBJECTS = 5;
	//null = "Auto": para Objeto, no tocar la orientación; para Puerta/Entrada, la heurística de
	//GridToStructure. El resto son direcciones manuales concretas.
	private static final Direction[] FACING_CYCLE = {null, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

	private enum PaintMode { FREE, RECT }

	private enum Tool {
		WALL(GridToStructure.Cell.WALL, "Pared"),
		FLOOR(GridToStructure.Cell.FLOOR, "Piso"),
		DOOR(GridToStructure.Cell.DOOR, "Puerta"),
		START(GridToStructure.Cell.START, "Entrada"),
		OBJECT(GridToStructure.Cell.OBJECT, "Objeto"),
		ERASE(GridToStructure.Cell.EMPTY, "Borrar");

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
	//Compartido entre Objeto (rotación) y Puerta/Entrada (dirección manual) — ver el Javadoc de la clase.
	private Direction currentFacing = null;
	private ResourceLocation wallBlock = null;
	private ResourceLocation floorBlock = null;
	private int height = 3;

	private PaintMode paintMode = PaintMode.FREE;
	//Rectángulo en curso: null cuando no se está arrastrando uno. Solo tiene sentido en modo RECT.
	private int rectStartX = -1, rectStartZ = -1, rectEndX = -1, rectEndZ = -1;
	private boolean rectDragging = false;

	private int originDx = 0, originDz = 0;

	//Una foto por trazo (clic inicial), no por celda pintada — deshacer un arrastre completo de una vez
	//es lo esperable; deshacer celda por celda dentro del mismo trazo no lo es. Tope de 20: la grilla es
	//chica (máximo 24x24), así que ni la memoria ni la copia son un problema real.
	private record Snapshot(GridToStructure.Cell[][] grid, GridToStructure.CellOptions[][] options) {}
	private final Deque<Snapshot> undoStack = new ArrayDeque<>();

	//MRU de bloques de objeto elegidos: sin esto, alternar entre dos muebles distintos obliga a reabrir
	//el buscador cada vez, aunque el DM ya lo haya buscado hace treinta segundos.
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

	//Cambiar de tamaño vacía la grilla — no hay forma de reproyectar celdas ya pintadas a una cuadrícula
	//distinta que tenga sentido, y agrandar/achicar a mitad de dibujo es un caso raro. El DM elige el
	//tamaño primero, dibuja después.
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

	/** Los mismos cinco campos que ya escribió el DM en {@link DungeonPieceAddScreen}: dibujar es la otra mitad del mismo flujo, no uno nuevo. */
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
			//Sin "> " ni ningún otro texto variable: la fila ya está justa a lo ancho (6 botones en 300px),
			//así que la herramienta activa se marca apagando el botón (active=false), no agregándole texto.
			Button button = addModalButton(toolsLeft + i * (toolWidth + gap), 8, toolWidth, toolHeight,
				Component.literal(tool.label), b -> selectTool(tool));
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
		addModalButton(82, 44, 46, 16, Component.literal("Círculo"), b ->
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
		undoButton = addModalButton(20, row5, 60, 16, Component.literal("Deshacer"), b -> undo());
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

	//Vacío = "usa el pool de esta pieza" (el mismo comportamiento que había antes de poder elegir uno
	//distinto), no un pool literalmente llamado "".
	private String doorPoolOverride() {
		String text = doorPoolBox.getValue().trim();
		return text.isEmpty() ? null : text;
	}

	private Component modeLabel() {
		return Component.literal(paintMode == PaintMode.FREE ? "Libre" : "Rectángulo");
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

	//ponytail: Confirmar/Cancelar quedan donde los puso init() con el lienzo del tamaño con el que se
	//abrió la pantalla — CANVAS_AREA se eligió para que el lienzo real mida entre 192 y 200px con
	//cualquier tamaño de GRID_SIZES, así que como mucho queda 8px de aire de más bajo el lienzo, nunca
	//una superposición. Recalcularlos de verdad implica reconstruir todos los widgets, no solo estos dos.
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

	//Se reconstruye entera en vez de llevar un pool de botones reutilizables: la lista mide como mucho
	//MAX_RECENT_OBJECTS, así que el costo de tirar y recrear los botones es irrelevante.
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
		return materialLabel("Obj", currentObjectBlock, 140);
	}

	//Trunca en vez de confiar en que el nombre del bloque entre: un mueble de otro mod puede llamarse
	//"polished_blackstone_bricks", y ningún ancho fijo de botón adivina eso. font.width(text) > maxWidth
	//es la única señal confiable de que se salió, no una cuenta de caracteres a ojo.
	private Component materialLabel(String prefix, ResourceLocation block, int maxWidth) {
		String value = block != null ? block.getPath() : "(por defecto)";
		return Component.literal(fit(prefix + ": " + value, maxWidth));
	}

	private String fit(String text, int maxWidth) {
		if (this.font.width(text) <= maxWidth) return text;
		return this.font.plainSubstrByWidth(text, maxWidth - this.font.width("…")) + "…";
	}

	//Sin prefijo "Girar: " — el botón mide 50px y "Girar: north" no entra ni truncado a algo legible.
	//La posición en la fila ya dice qué es; el valor solo necesita ser corto.
	private Component facingLabel() {
		if (currentFacing == null) return Component.literal("Auto");
		return Component.literal(switch (currentFacing) {
			case NORTH -> "N";
			case EAST -> "E";
			case SOUTH -> "S";
			case WEST -> "O";
			default -> currentFacing.getSerializedName();
		});
	}

	private static int indexOf(Direction facing) {
		for (int i = 0; i < FACING_CYCLE.length; i++) if (FACING_CYCLE[i] == facing) return i;
		return 0;
	}

	private Component heightLabel() {
		return Component.literal("Alto: " + height);
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

	//Solo lectura, para DungeonTracePreviewRenderer — dibuja en el mundo lo mismo que hay acá, no una
	//copia de estado por separado.
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

	//Celda de grilla bajo un punto de pantalla, o null si cae fuera del lienzo.
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

	//Arrastrar el mouse pinta como si fuera clic tras clic en modo Libre — sin esto, una pared de 12
	//celdas son 12 clics sueltos en vez de un trazo. En modo Rectángulo solo mueve la previsualización;
	//el relleno se aplica entero al soltar (mouseReleased), no celda por celda.
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

		//Sin etiqueta aparte a la izquierda del EditBox: a 80px de ancho "Pool puerta:" ya se comía la
		//mitad de la caja. El hint (ver init()) dice lo mismo cuando está vacío, que es cuando hace falta.
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
