package net.hawthorn.dndsheets.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * <p>Maps made with online dungeon generators, turned into the editor's own grid so they paste with
 * {@link GridToStructure}/{@link DungeonPiecePlacer} like a drawn room:</p>
 * <ul>
 *   <li><b>donjon</b> (.json): a grid of cells with bit flags (room, corridor, door kinds, stairs).</li>
 *   <li><b>Universal VTT</b> (.dd2vtt, .uvtt, .df2vtt: Dungeondraft, Dungeon Alchemist, DungeonFog...):
 *   walls as line segments and doors as "portals", in grid squares. No floor: it's whatever the walls enclose.</li>
 *   <li><b>Dungeon Scrawl</b> (.ds, a zip): the dungeon's outline as polygons, inner walls as lines and
 *   doors as small shapes, in pixels.</li>
 * </ul>
 *
 * <p>Everything is drawn onto one block canvas, {@code scale} blocks per map square: a wall between two
 * squares takes a line of blocks, so a 1-square corridor of a vector map is {@code scale - 1} blocks wide.
 * Then any empty block touching a floor becomes wall, which closes whatever the map left open.</p>
 *
 * <p>Pure logic (no world): checked by DungeonSelfTest.</p>
 */
public final class DungeonMapImporter {
	//512 x 512 blocks is already a quarter of a million columns; past that it's a scale typo, not a dungeon.
	public static final int MAX_SIDE = 512;

	private static final ResourceLocation WOODEN_DOOR = new ResourceLocation("minecraft", "oak_door");
	private static final ResourceLocation IRON_DOOR = new ResourceLocation("minecraft", "iron_door");
	private static final ResourceLocation BARS = new ResourceLocation("minecraft", "iron_bars");
	//An archway: the doorway stays 2 tall with wall above, but nothing closes it.
	private static final ResourceLocation ARCH = new ResourceLocation("minecraft", "air");

	/** {@code notes}: in blocks; the first line is the short version (a sign), the rest the details. */
	public record Result(GridToStructure.Cell[][] grid, GridToStructure.CellOptions[][] options, String format, List<GridToStructure.Note> notes) {}

	private DungeonMapImporter() {}

	/** Reads the file by its content (the extensions vary by tool); IllegalArgumentException = not a map this knows. */
	public static Result read(Path file, int scale) throws IOException {
		String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		if (name.endsWith(".ds")) {
			try (ZipFile zip = new ZipFile(file.toFile())) {
				var entry = zip.getEntry("map");
				if (entry == null) throw new IllegalArgumentException("not a Dungeon Scrawl file (no \"map\" inside)");
				return fromDungeonScrawl(JsonParser.parseString(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject(), scale);
			}
		}
		return parse(JsonParser.parseString(Files.readString(file)).getAsJsonObject(), scale);
	}

	public static Result parse(JsonObject json, int scale) {
		if (json.has("cell_bit") && json.has("cells")) return fromDonjon(json, scale);
		if (json.has("resolution") && json.has("line_of_sight")) return fromUniversalVtt(json, scale);
		if (json.has("state") && json.has("data")) return fromDungeonScrawl(json, scale);
		throw new IllegalArgumentException("unknown map format (donjon .json, Universal VTT .dd2vtt/.uvtt or Dungeon Scrawl .ds)");
	}

	// ---------------- donjon ----------------

	static Result fromDonjon(JsonObject json, int scale) {
		JsonObject bit = json.getAsJsonObject("cell_bit");
		long room = bit.get("room").getAsLong(), corridor = bit.get("corridor").getAsLong();
		long arch = bit.get("arch").getAsLong(), door = bit.get("door").getAsLong(), locked = bit.get("locked").getAsLong();
		long trapped = bit.get("trapped").getAsLong(), secret = bit.get("secret").getAsLong(), portcullis = bit.get("portcullis").getAsLong();
		long stairs = bit.get("stair_up").getAsLong() | bit.get("stair_down").getAsLong();
		JsonArray rows = json.getAsJsonArray("cells");
		int height = rows.size(), width = rows.get(0).getAsJsonArray().size();
		long[][] cells = new long[height][width];
		for (int z = 0; z < height; z++) {
			JsonArray row = rows.get(z).getAsJsonArray();
			for (int x = 0; x < width; x++) cells[z][x] = row.get(x).getAsLong();
		}
		Canvas canvas = new Canvas(width, height, scale);
		for (int z = 0; z < height; z++) {
			for (int x = 0; x < width; x++) {
				if ((cells[z][x] & (room | corridor)) != 0) canvas.fillSquare(x, z);
			}
		}
		for (int z = 0; z < height; z++) {
			for (int x = 0; x < width; x++) {
				long c = cells[z][x];
				//A doorway square: a wall across its middle, the door at its centre, the way through running
				//between its two open neighbours.
				ResourceLocation block = (c & secret) != 0 ? null : (c & portcullis) != 0 ? BARS
					: (c & locked) != 0 ? IRON_DOOR : (c & (door | trapped)) != 0 ? WOODEN_DOOR : (c & arch) != 0 ? ARCH : null;
				boolean isDoorway = (c & (arch | door | locked | trapped | secret | portcullis)) != 0;
				if (isDoorway) {
					boolean eastWest = open(cells, x - 1, z, room | corridor) && open(cells, x + 1, z, room | corridor);
					double gx = x + 0.5, gz = z + 0.5;
					if (eastWest) canvas.doorway(gx, z, gx, z + 1 - 1e-6, block, (c & secret) != 0);
					else canvas.doorway(x, gz, x + 1 - 1e-6, gz, block, (c & secret) != 0);
				}
				if ((c & stairs) != 0) canvas.stairs(x + 0.5, z + 0.5);
			}
		}
		//What donjon says about each room and corridor feature, at its spot.
		List<GridToStructure.Note> notes = new ArrayList<>();
		if (json.has("rooms")) {
			for (JsonElement element : json.getAsJsonArray("rooms")) {
				if (!element.isJsonObject()) continue;
				JsonObject entry = element.getAsJsonObject();
				if (!entry.has("west") || !entry.has("north")) continue;
				double cx = (entry.get("west").getAsInt() + entry.get("east").getAsInt() + 1) / 2.0;
				double cz = (entry.get("north").getAsInt() + entry.get("south").getAsInt() + 1) / 2.0;
				StringBuilder text = new StringBuilder("#" + (entry.has("id") ? entry.get("id").getAsString() : "?"));
				JsonObject contents = entry.has("contents") ? entry.getAsJsonObject("contents") : new JsonObject();
				if (contents.has("summary")) text.append(' ').append(contents.get("summary").getAsString());
				if (contents.has("detail") && contents.get("detail").isJsonObject()) {
					for (var detail : contents.getAsJsonObject("detail").entrySet()) {
						if (!detail.getValue().isJsonArray()) continue;
						for (JsonElement line : detail.getValue().getAsJsonArray()) {
							if (!"--".equals(line.getAsString())) text.append('\n').append(line.getAsString());
						}
					}
				}
				notes.add(new GridToStructure.Note(canvas.block(cx, canvas.width), canvas.block(cz, canvas.height), text.toString()));
			}
		}
		if (json.has("corridor_features")) {
			for (var entry : json.getAsJsonObject("corridor_features").entrySet()) {
				JsonObject feature = entry.getValue().getAsJsonObject();
				String text = "(" + entry.getKey() + ") " + (feature.has("summary") ? feature.get("summary").getAsString() : "");
				if (!feature.has("marks")) continue;
				for (JsonElement mark : feature.getAsJsonArray("marks")) {
					JsonObject at = mark.getAsJsonObject();
					notes.add(new GridToStructure.Note(canvas.block(at.get("col").getAsInt() + 0.5, canvas.width),
						canvas.block(at.get("row").getAsInt() + 0.5, canvas.height), text));
				}
			}
		}
		return canvas.build("donjon", notes);
	}

	private static boolean open(long[][] cells, int x, int z, long mask) {
		return z >= 0 && z < cells.length && x >= 0 && x < cells[z].length && (cells[z][x] & mask) != 0;
	}

	// ---------------- Universal VTT ----------------

	static Result fromUniversalVtt(JsonObject json, int scale) {
		JsonObject resolution = json.getAsJsonObject("resolution");
		JsonObject size = resolution.getAsJsonObject("map_size");
		JsonObject origin = resolution.has("map_origin") ? resolution.getAsJsonObject("map_origin") : new JsonObject();
		double ox = origin.has("x") ? origin.get("x").getAsDouble() : 0, oy = origin.has("y") ? origin.get("y").getAsDouble() : 0;
		Canvas canvas = new Canvas(size.get("x").getAsDouble(), size.get("y").getAsDouble(), scale);
		for (String key : new String[]{"line_of_sight", "objects_line_of_sight"}) {
			if (!json.has(key)) continue;
			for (JsonElement line : json.getAsJsonArray(key)) {
				JsonArray points = line.getAsJsonArray();
				for (int i = 0; i + 1 < points.size(); i++) {
					JsonObject a = points.get(i).getAsJsonObject(), b = points.get(i + 1).getAsJsonObject();
					canvas.wall(a.get("x").getAsDouble() - ox, a.get("y").getAsDouble() - oy, b.get("x").getAsDouble() - ox, b.get("y").getAsDouble() - oy);
				}
			}
		}
		if (json.has("portals")) {
			for (JsonElement element : json.getAsJsonArray("portals")) {
				JsonArray bounds = element.getAsJsonObject().getAsJsonArray("bounds");
				JsonObject a = bounds.get(0).getAsJsonObject(), b = bounds.get(1).getAsJsonObject();
				canvas.doorway(a.get("x").getAsDouble() - ox, a.get("y").getAsDouble() - oy, b.get("x").getAsDouble() - ox, b.get("y").getAsDouble() - oy, WOODEN_DOOR, false);
			}
		}
		//No floor in the format: the floor is what the walls (and closed doors) shut off from the map's edge.
		canvas.floorEnclosed();
		return canvas.build("Universal VTT", List.of());
	}

	// ---------------- Dungeon Scrawl ----------------

	static Result fromDungeonScrawl(JsonObject json, int scale) {
		JsonObject nodes = json.getAsJsonObject("state").getAsJsonObject("document").getAsJsonObject("nodes");
		JsonObject geometry = json.getAsJsonObject("data").getAsJsonObject("geometry");
		double cellSize = 36;
		List<String> shapes = new ArrayList<>(), doors = new ArrayList<>();
		for (var entry : nodes.entrySet()) {
			JsonObject node = entry.getValue().getAsJsonObject();
			String type = node.get("type").getAsString();
			if (type.equals("PAGE") && node.has("grid")) cellSize = node.getAsJsonObject("grid").get("cellDiameter").getAsDouble();
			//The dungeon's outline is the geometry each map template points at; doors are geometries under a door asset.
			if (type.equals("TEMPLATE") && node.has("template")) {
				String shape = node.getAsJsonObject("template").get("dungeonShape").getAsString();
				if (nodes.has(shape)) shapes.add(nodes.getAsJsonObject(shape).get("geometryId").getAsString());
			}
			if (type.equals("GEOMETRY") && node.has("parentId") && nodes.has(node.get("parentId").getAsString())) {
				JsonObject parent = nodes.getAsJsonObject(node.get("parentId").getAsString());
				if (parent.has("kind") && "door".equals(parent.get("kind").getAsString())) doors.add(node.get("geometryId").getAsString());
			}
		}
		//Pixels -> squares, shifted so the dungeon starts at 0,0.
		List<double[][]> rings = new ArrayList<>(), lines = new ArrayList<>();
		for (String id : shapes) collect(geometry.getAsJsonObject(id), cellSize, rings, lines);
		if (rings.isEmpty()) throw new IllegalArgumentException("the Dungeon Scrawl map has no dungeon shape");
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		for (double[][] ring : rings) {
			for (double[] p : ring) {
				minX = Math.min(minX, p[0]); minY = Math.min(minY, p[1]);
				maxX = Math.max(maxX, p[0]); maxY = Math.max(maxY, p[1]);
			}
		}
		Canvas canvas = new Canvas(maxX - minX, maxY - minY, scale);
		canvas.floorInside(rings, minX, minY);
		for (double[][] ring : rings) {
			for (int i = 0; i < ring.length; i++) {
				double[] a = ring[i], b = ring[(i + 1) % ring.length];
				canvas.wall(a[0] - minX, a[1] - minY, b[0] - minX, b[1] - minY);
			}
		}
		for (double[][] line : lines) {
			for (int i = 0; i + 1 < line.length; i++) canvas.wall(line[i][0] - minX, line[i][1] - minY, line[i + 1][0] - minX, line[i + 1][1] - minY);
		}
		for (String id : doors) {
			if (!geometry.has(id)) continue;
			List<double[][]> doorRings = new ArrayList<>(), doorLines = new ArrayList<>();
			collect(geometry.getAsJsonObject(id), cellSize, doorRings, doorLines);
			for (double[][] line : doorLines) {
				for (int i = 0; i + 1 < line.length; i++) canvas.wall(line[i][0] - minX, line[i][1] - minY, line[i + 1][0] - minX, line[i + 1][1] - minY);
			}
			//The door's leaf: a thin rectangle; the door goes along its long side, through its middle.
			for (double[][] ring : doorRings) {
				double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
				for (double[] p : ring) {
					x0 = Math.min(x0, p[0]); y0 = Math.min(y0, p[1]);
					x1 = Math.max(x1, p[0]); y1 = Math.max(y1, p[1]);
				}
				double cx = (x0 + x1) / 2 - minX, cy = (y0 + y1) / 2 - minY;
				if (x1 - x0 >= y1 - y0) canvas.doorway(x0 - minX, cy, x1 - minX, cy, WOODEN_DOOR, false);
				else canvas.doorway(cx, y0 - minY, cx, y1 - minY, WOODEN_DOOR, false);
			}
		}
		return canvas.build("Dungeon Scrawl", List.of());
	}

	private static void collect(JsonObject geometry, double cellSize, List<double[][]> rings, List<double[][]> lines) {
		if (geometry.has("polygons")) {
			for (JsonElement polygon : geometry.getAsJsonArray("polygons")) {
				for (JsonElement ring : polygon.getAsJsonArray()) rings.add(points(ring.getAsJsonArray(), cellSize));
			}
		}
		if (geometry.has("polylines")) {
			for (JsonElement line : geometry.getAsJsonArray("polylines")) lines.add(points(line.getAsJsonArray(), cellSize));
		}
	}

	private static double[][] points(JsonArray array, double cellSize) {
		double[][] points = new double[array.size()][];
		for (int i = 0; i < array.size(); i++) {
			JsonArray p = array.get(i).getAsJsonArray();
			points[i] = new double[]{p.get(0).getAsDouble() / cellSize, p.get(1).getAsDouble() / cellSize};
		}
		return points;
	}

	// ---------------- the block canvas ----------------

	private static final class Canvas {
		final int scale, width, height;
		final boolean[][] floor, wall, stairs;
		final Direction[][] doorFacing;
		final ResourceLocation[][] doorBlock;

		Canvas(double squaresWide, double squaresHigh, int scale) {
			this.scale = scale;
			this.width = (int) Math.ceil(squaresWide * scale) + 1;
			this.height = (int) Math.ceil(squaresHigh * scale) + 1;
			if (width > MAX_SIDE || height > MAX_SIDE) {
				throw new IllegalArgumentException("the map would be " + width + " x " + height + " blocks (max " + MAX_SIDE + "): use a smaller scale");
			}
			floor = new boolean[height][width];
			wall = new boolean[height][width];
			stairs = new boolean[height][width];
			doorFacing = new Direction[height][width];
			doorBlock = new ResourceLocation[height][width];
		}

		int block(double squares, int size) {
			return Math.max(0, Math.min(size - 1, (int) Math.floor(squares * scale + 1e-6)));
		}

		void fillSquare(int x, int z) {
			for (int bz = z * scale; bz < (z + 1) * scale && bz < height; bz++) {
				for (int bx = x * scale; bx < (x + 1) * scale && bx < width; bx++) floor[bz][bx] = true;
			}
		}

		//Small steps along the segment: diagonal walls come out as a block staircase with no gaps a fill could leak through.
		void wall(double x1, double z1, double x2, double z2) {
			int steps = (int) Math.ceil(Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1)) * scale * 2) + 1;
			for (int i = 0; i <= steps; i++) {
				double t = (double) i / steps;
				wall[block(z1 + (z2 - z1) * t, height)][block(x1 + (x2 - x1) * t, width)] = true;
			}
		}

		/** A wall line with a door at its middle; {@code hidden} = all wall (a secret door). */
		void doorway(double x1, double z1, double x2, double z2, ResourceLocation block, boolean hidden) {
			wall(x1, z1, x2, z2);
			if (hidden) return;
			int bx = block((x1 + x2) / 2, width), bz = block((z1 + z2) / 2, height);
			//The way through crosses the wall line.
			doorFacing[bz][bx] = Math.abs(x2 - x1) >= Math.abs(z2 - z1) ? Direction.NORTH : Direction.EAST;
			doorBlock[bz][bx] = block;
		}

		void stairs(double x, double z) {
			stairs[block(z, height)][block(x, width)] = true;
		}

		/** Floor = every block the walls shut off from the edge (4-way fill, so diagonal walls hold). */
		void floorEnclosed() {
			boolean[][] outside = new boolean[height][width];
			ArrayDeque<int[]> queue = new ArrayDeque<>();
			for (int x = 0; x < width; x++) { queue.add(new int[]{x, 0}); queue.add(new int[]{x, height - 1}); }
			for (int z = 0; z < height; z++) { queue.add(new int[]{0, z}); queue.add(new int[]{width - 1, z}); }
			while (!queue.isEmpty()) {
				int[] p = queue.poll();
				int x = p[0], z = p[1];
				if (x < 0 || z < 0 || x >= width || z >= height || outside[z][x] || wall[z][x] || doorFacing[z][x] != null) continue;
				outside[z][x] = true;
				queue.add(new int[]{x + 1, z}); queue.add(new int[]{x - 1, z});
				queue.add(new int[]{x, z + 1}); queue.add(new int[]{x, z - 1});
			}
			for (int z = 0; z < height; z++) {
				for (int x = 0; x < width; x++) floor[z][x] = !outside[z][x] && !wall[z][x];
			}
		}

		/** Floor = block centres inside the outline (even-odd over every ring, so holes stay holes). */
		void floorInside(List<double[][]> rings, double minX, double minZ) {
			for (int bz = 0; bz < height; bz++) {
				double pz = (bz + 0.5) / scale + minZ;
				for (int bx = 0; bx < width; bx++) {
					double px = (bx + 0.5) / scale + minX;
					boolean inside = false;
					for (double[][] ring : rings) {
						for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
							if ((ring[i][1] > pz) != (ring[j][1] > pz)
								&& px < (ring[j][0] - ring[i][0]) * (pz - ring[i][1]) / (ring[j][1] - ring[i][1]) + ring[i][0]) inside = !inside;
						}
					}
					floor[bz][bx] = inside;
				}
			}
		}

		Result build(String format, List<GridToStructure.Note> notes) {
			GridToStructure.Cell[][] grid = new GridToStructure.Cell[height][width];
			GridToStructure.CellOptions[][] options = new GridToStructure.CellOptions[height][width];
			for (int z = 0; z < height; z++) {
				for (int x = 0; x < width; x++) {
					GridToStructure.Cell cell;
					if (doorFacing[z][x] != null) {
						cell = GridToStructure.Cell.DOOR;
						options[z][x] = new GridToStructure.CellOptions(doorBlock[z][x], null, null, doorFacing[z][x]);
					} else if (wall[z][x]) cell = touchesFloor(x, z) ? GridToStructure.Cell.WALL : GridToStructure.Cell.EMPTY;
					else if (stairs[z][x]) cell = GridToStructure.Cell.STAIRS;
					else if (floor[z][x]) cell = GridToStructure.Cell.FLOOR;
					//Whatever the map left open around its floor gets closed.
					else cell = touchesFloor(x, z) ? GridToStructure.Cell.WALL : GridToStructure.Cell.EMPTY;
					grid[z][x] = cell;
				}
			}
			return new Result(grid, options, format, notes);
		}

		private boolean touchesFloor(int x, int z) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					int nx = x + dx, nz = z + dz;
					if (nx >= 0 && nz >= 0 && nx < width && nz < height && floor[nz][nx] && !wall[nz][nx]) return true;
				}
			}
			return false;
		}
	}
}
