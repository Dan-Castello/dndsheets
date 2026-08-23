package net.hawthorn.dndsheets.dungeon;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Traduce una grilla 2D dibujada por el DM (editor de trazado) a una lista plana de bloques
 * relativos al origen de la pieza. Puro: no toca {@code Level} ni {@code ServerPlayer}, así que se
 * prueba sin arrancar Forge, igual que {@link CharacterRules}. Quien la use en el mundo real (colocar
 * los bloques o volcarlos a un {@code StructureTemplate}) es una capa aparte que sí necesita el juego
 * corriendo.</p>
 *
 * <p>Una pared no tiene por qué salir un círculo geométrico: el DM la dibuja celda a celda, así que
 * sale tan reconocible como un círculo hecho a mano con bloques en el juego — bordes en escalera, no
 * un arco perfecto. Esta clase no intenta suavizarlo, solo traduce lo que hay en la grilla.</p>
 *
 * <p>Las celdas {@code OBJECT} no llevan un bloque fijo: cargan el {@link ResourceLocation} que el DM
 * eligió en el editor (ver {@code BlockPickerScreen}), que puede ser de cualquier mod instalado —
 * muebles incluidos. {@link Kind#WALL}/{@link Kind#FLOOR} siguen usando un bloque por defecto; la capa
 * que planta los bloques decide qué hacer si el {@code ResourceLocation} de un objeto no resuelve a
 * nada (mod desinstalado).</p>
 */
public final class GridToStructure {

	private GridToStructure() {}

	public enum Cell {
		EMPTY, FLOOR, WALL, DOOR, START, OBJECT;

		static Cell fromChar(char c) {
			return switch (c) {
				case '#' -> WALL;
				case ',' -> FLOOR;
				case 'D' -> DOOR;
				case 'S' -> START;
				case 'o' -> OBJECT;
				default -> EMPTY;
			};
		}
	}

	public enum Kind { FLOOR, WALL, CONNECTOR, START, OBJECT }

	/**
	 * <p>Posición relativa al origen de la pieza (x/z = columna/fila de la grilla, y = altura).
	 * {@code blockId} solo se usa con {@link Kind#OBJECT}. {@code targetPool} solo con
	 * {@link Kind#CONNECTOR}/{@link Kind#START}: a qué pool tira este jigsaw al conectar — null significa
	 * "el pool de esta misma pieza" (comportamiento por defecto, quien planta el bloque decide con qué se
	 * rellena).</p>
	 */
	public record PlacedBlock(int x, int y, int z, Kind kind, Direction facing, ResourceLocation blockId, String targetPool) {}

	/**
	 * <p>Todo lo que el DM puede ajustar en una celda más allá de su tipo — qué bloque usa un
	 * {@code OBJECT} y hacia dónde mira, o a qué pool tira y hacia dónde apunta una
	 * {@code DOOR}/{@code START}. Los cuatro campos son independientes entre sí (un objeto nunca usa
	 * {@code doorPool}, una puerta nunca usa {@code objectBlock}) porque una celda es una cosa o la otra,
	 * nunca las dos. Cualquier campo en null es "usar el comportamiento por defecto" — el mismo que tenía
	 * el editor antes de poder tocar esto.</p>
	 */
	public record CellOptions(ResourceLocation objectBlock, Direction objectFacing, String doorPool, Direction doorFacing) {
		public static final CellOptions EMPTY = new CellOptions(null, null, null, null);
	}

	/** Una fila de texto por fila de grilla, todas del mismo largo. Ver {@link Cell#fromChar}. */
	public static Cell[][] parse(String[] rows) {
		if (rows.length == 0) return new Cell[0][0];
		int width = rows[0].length();
		Cell[][] grid = new Cell[rows.length][width];
		for (int z = 0; z < rows.length; z++) {
			if (rows[z].length() != width) {
				throw new IllegalArgumentException("todas las filas deben medir " + width + " celdas, la fila " + z + " mide " + rows[z].length());
			}
			for (int x = 0; x < width; x++) grid[z][x] = Cell.fromChar(rows[z].charAt(x));
		}
		return grid;
	}

	/** Sin ajustes por celda — ver {@link #render(Cell[][], int, CellOptions[][])}. */
	public static List<PlacedBlock> render(Cell[][] grid, int height) {
		return render(grid, height, null);
	}

	/**
	 * @param height  alto de las columnas de pared, en bloques, por encima del piso.
	 * @param options ajustes por celda (ver {@link CellOptions}), mismas dimensiones que {@code grid};
	 *                null (la grilla entera, o una celda suelta) = sin ajustar, se usa el comportamiento
	 *                por defecto de cada tipo de celda.
	 */
	public static List<PlacedBlock> render(Cell[][] grid, int height, CellOptions[][] options) {
		List<PlacedBlock> blocks = new ArrayList<>();
		for (int z = 0; z < grid.length; z++) {
			for (int x = 0; x < grid[z].length; x++) {
				switch (grid[z][x]) {
					case WALL -> {
						blocks.add(new PlacedBlock(x, 0, z, Kind.FLOOR, null, null, null));
						for (int y = 1; y <= height; y++) blocks.add(new PlacedBlock(x, y, z, Kind.WALL, null, null, null));
					}
					case FLOOR -> blocks.add(new PlacedBlock(x, 0, z, Kind.FLOOR, null, null, null));
					case OBJECT -> {
						blocks.add(new PlacedBlock(x, 0, z, Kind.FLOOR, null, null, null));
						CellOptions opt = optionsAt(options, x, z);
						blocks.add(new PlacedBlock(x, 1, z, Kind.OBJECT, opt.objectFacing(), opt.objectBlock(), null));
					}
					case DOOR, START -> {
						blocks.add(new PlacedBlock(x, 0, z, Kind.FLOOR, null, null, null));
						Kind kind = grid[z][x] == Cell.START ? Kind.START : Kind.CONNECTOR;
						CellOptions opt = optionsAt(options, x, z);
						Direction facing = opt.doorFacing() != null ? opt.doorFacing() : outwardFacing(grid, x, z);
						blocks.add(new PlacedBlock(x, 1, z, kind, facing, null, opt.doorPool()));
					}
					case EMPTY -> {}
				}
			}
		}
		return blocks;
	}

	private static CellOptions optionsAt(CellOptions[][] options, int x, int z) {
		if (options == null) return CellOptions.EMPTY;
		CellOptions opt = options[z][x];
		return opt != null ? opt : CellOptions.EMPTY;
	}

	/** Inversa de {@link Cell#fromChar}, para que el editor pueda mandar la grilla como texto por la red. */
	public static char toChar(Cell cell) {
		return switch (cell) {
			case WALL -> '#';
			case FLOOR -> ',';
			case DOOR -> 'D';
			case START -> 'S';
			case OBJECT -> 'o';
			case EMPTY -> '.';
		};
	}

	public static List<PlacedBlock> translate(String[] rows, int height) {
		return render(parse(rows), height);
	}

	//ponytail: heurística simple — la puerta apunta al primer vecino vacío que encuentra, en orden
	//N/E/S/O. Una puerta interior sin ningún vecino vacío no tiene un "afuera" natural y queda
	//apuntando al norte por defecto. Si hace falta enrutar puertas interiores, el editor tendrá que
	//dejar que el DM marque la dirección a mano.
	private static Direction outwardFacing(Cell[][] grid, int x, int z) {
		if (isOutside(grid, x, z - 1)) return Direction.NORTH;
		if (isOutside(grid, x + 1, z)) return Direction.EAST;
		if (isOutside(grid, x, z + 1)) return Direction.SOUTH;
		if (isOutside(grid, x - 1, z)) return Direction.WEST;
		return Direction.NORTH;
	}

	private static boolean isOutside(Cell[][] grid, int x, int z) {
		if (z < 0 || z >= grid.length || x < 0 || x >= grid[z].length) return true;
		return grid[z][x] == Cell.EMPTY;
	}

	/**
	 * <p>Sello circular sobre una grilla ya parseada: útil para que el editor ofrezca un pincel de
	 * "sala circular" en vez de obligar al DM a marcar celda por celda. Compara distancia al centro, así
	 * que el resultado sale en escalera — reconocible como círculo, no geométricamente exacto.</p>
	 */
	public static void stampCircle(Cell[][] grid, int centerX, int centerZ, int radius, int wallThickness) {
		for (int z = 0; z < grid.length; z++) {
			for (int x = 0; x < grid[z].length; x++) {
				double dist = Math.hypot(x - centerX, z - centerZ);
				if (dist <= radius - wallThickness) grid[z][x] = Cell.FLOOR;
				else if (dist <= radius) grid[z][x] = Cell.WALL;
			}
		}
	}
}
