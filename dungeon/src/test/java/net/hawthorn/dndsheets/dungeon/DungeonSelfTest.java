package net.hawthorn.dndsheets.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * <p>Moved from {@code JsonContentSelfTest} (core) when the dungeon toolkit became this addon:
 * {@link DungeonManager#buildPoolJsons} and {@link GridToStructure} touch no Minecraft classes
 * and need no running server, so they are checked standalone right here, just as core did
 * with its own pure logic.</p>
 *
 * <p>Run by hand from the repo root:
 * {@code java -cp <classpath> net.hawthorn.dndsheets.dungeon.DungeonSelfTest}</p>
 */
public class DungeonSelfTest {
	public static void main(String[] args) throws Exception {
		checkDungeonPools();
		checkGridToStructure();
		checkStructureImport();
		checkMapImport();

		System.out.println("DungeonSelfTest: OK.");
	}

	//The only logic with real branches in the dungeon feature (see DungeonManager): group by pool,
	//clamp weight to [1,150] (StructureTemplatePool.DIRECT_CODEC requires it, an out-of-range value breaks
	//parsing of the whole file) and skip pieces with a corrupt structureId. The rest (copying the .nbt,
	///reload, JigsawPlacement.generateJigsaw) are thin calls to vanilla APIs, no need to re-test them.
	private static void checkDungeonPools() {
		List<DungeonPieceRegistry.DungeonPiece> pieces = List.of(
			new DungeonPieceRegistry.DungeonPiece("entrance", "dndsheets_dm:rooms/entrance", "start", 200, ""),
			new DungeonPieceRegistry.DungeonPiece("corridor1", "dndsheets_dm:rooms/corridor1", "corridor", 3, ""),
			new DungeonPieceRegistry.DungeonPiece("corridor2", "dndsheets_dm:rooms/corridor2", "corridor", 0, ""),
			new DungeonPieceRegistry.DungeonPiece("broken", "esto no es un id valido", "corridor", 5, "")
		);

		Map<String, JsonObject> pools = DungeonManager.buildPoolJsons(pieces);
		assertTrue(pools.size() == 2, "buildPoolJsons should produce 2 pools (start, corridor), got " + pools.size());

		JsonObject start = pools.get("start");
		assertTrue(start != null && "minecraft:empty".equals(start.get("fallback").getAsString()), "the start pool should have fallback=minecraft:empty");
		JsonArray startElements = start.getAsJsonArray("elements");
		assertTrue(startElements.size() == 1, "the start pool should have 1 element");
		JsonObject startWrapper = startElements.get(0).getAsJsonObject();
		assertTrue(startWrapper.get("weight").getAsInt() == 150, "weight 200 should be clamped to 150");
		JsonObject startElement = startWrapper.getAsJsonObject("element");
		assertTrue("minecraft:single_pool_element".equals(startElement.get("element_type").getAsString()), "element_type should be minecraft:single_pool_element");
		assertTrue("dndsheets_dm:rooms/entrance".equals(startElement.get("location").getAsString()), "location should be the piece's structureId");
		assertTrue("minecraft:empty".equals(startElement.get("processors").getAsString()), "processors should be minecraft:empty");
		assertTrue("rigid".equals(startElement.get("projection").getAsString()), "projection should be rigid");

		JsonObject corridor = pools.get("corridor");
		JsonArray corridorElements = corridor.getAsJsonArray("elements");
		//"broken" has an invalid structureId and is skipped: only corridor1 and corridor2 remain.
		assertTrue(corridorElements.size() == 2, "the corridor pool should have 2 elements (the piece with an invalid structureId is skipped), got " + corridorElements.size());
		boolean foundClampedZero = false;
		for (JsonElement el : corridorElements) {
			if (el.getAsJsonObject().get("weight").getAsInt() == 1) foundClampedZero = true;
		}
		assertTrue(foundClampedZero, "weight 0 should be clamped to 1");
	}

	private static void checkGridToStructure() {
		//Minimal 3x3 room: walls on the edge, hollow floor in the center, height 2.
		GridToStructure.Cell[][] room = GridToStructure.parse(new String[]{
			"###",
			"#,#",
			"###"
		});
		List<GridToStructure.PlacedBlock> blocks = GridToStructure.render(room, 2);
		long wallColumns = blocks.stream().filter(b -> b.kind() == GridToStructure.Kind.WALL).count();
		assertTrue(wallColumns == 8 * 2, "8 wall cells x 2 tall, got " + wallColumns);
		long floors = blocks.stream().filter(b -> b.kind() == GridToStructure.Kind.FLOOR).count();
		assertTrue(floors == 9, "all 9 cells (walls included) should have a floor, got " + floors);

		//Door on the north wall: no "outside" neighbor except beyond the grid, to the north.
		List<GridToStructure.PlacedBlock> withDoor = GridToStructure.translate(new String[]{
			"#D#",
			"#,#",
			"###"
		}, 2);
		GridToStructure.PlacedBlock door = withDoor.stream()
			.filter(b -> b.kind() == GridToStructure.Kind.CONNECTOR).findFirst()
			.orElseThrow(() -> new AssertionError("the D cell should produce a CONNECTOR"));
		assertTrue(door.facing() == net.minecraft.core.Direction.NORTH, "the door on the north edge should point outward, to the north, but pointed " + door.facing());

		//Entrance (S) same as the door but with Kind.START, so hasStartJigsaw recognizes it.
		List<GridToStructure.PlacedBlock> withStart = GridToStructure.translate(new String[]{"#S#", "#,#", "###"}, 1);
		assertTrue(withStart.stream().anyMatch(b -> b.kind() == GridToStructure.Kind.START), "the S cell should produce a START");

		//Object (o) with no block chosen: kind OBJECT with a null blockId, the layer that places blocks picks the
		//default. With a chosen block (from any mod, it's just a ResourceLocation): it carries it through as is.
		GridToStructure.Cell[][] withObject = GridToStructure.parse(new String[]{"#o#", "#,#", "###"});
		GridToStructure.PlacedBlock plainObject = GridToStructure.render(withObject, 1).stream()
			.filter(b -> b.kind() == GridToStructure.Kind.OBJECT).findFirst()
			.orElseThrow(() -> new AssertionError("the o cell should produce an OBJECT"));
		assertTrue(plainObject.blockId() == null, "without a palette, the object should not carry a fixed blockId");

		net.minecraft.resources.ResourceLocation furniture = new net.minecraft.resources.ResourceLocation("algunmod", "silla");
		GridToStructure.CellOptions[][] objectOptions = new GridToStructure.CellOptions[3][3];
		objectOptions[0][1] = new GridToStructure.CellOptions(furniture, null, null, null);
		GridToStructure.PlacedBlock chosenObject = GridToStructure.render(withObject, 1, objectOptions).stream()
			.filter(b -> b.kind() == GridToStructure.Kind.OBJECT).findFirst()
			.orElseThrow(() -> new AssertionError("the o cell should produce an OBJECT"));
		assertTrue(furniture.equals(chosenObject.blockId()), "the object should carry the block from another mod chosen by the DM, carried " + chosenObject.blockId());

		//Painted cells: a wall column and a floor cell carry the block the DM painted; unpainted ones carry null.
		net.minecraft.resources.ResourceLocation moss = new net.minecraft.resources.ResourceLocation("minecraft", "mossy_stone_bricks");
		GridToStructure.Cell[][] paintGrid = GridToStructure.parse(new String[]{"##", ",,"});
		GridToStructure.CellOptions[][] paint = new GridToStructure.CellOptions[2][2];
		paint[0][0] = GridToStructure.CellOptions.painted(moss);
		paint[1][1] = GridToStructure.CellOptions.painted(moss);
		List<GridToStructure.PlacedBlock> painted = GridToStructure.render(paintGrid, 2, paint);
		assertTrue(painted.stream().filter(b -> b.kind() == GridToStructure.Kind.WALL && b.x() == 0).allMatch(b -> moss.equals(b.blockId())), "the painted wall column should carry its block at every height");
		assertTrue(painted.stream().filter(b -> b.kind() == GridToStructure.Kind.WALL && b.x() == 1).allMatch(b -> b.blockId() == null), "an unpainted wall should use the piece's material");
		assertTrue(painted.stream().anyMatch(b -> b.kind() == GridToStructure.Kind.FLOOR && b.x() == 1 && b.z() == 1 && moss.equals(b.blockId())), "the painted floor cell should carry its block");

		//Carved wall: a hole at height 2 leaves heights 1 and 3 standing.
		GridToStructure.Cell[][] window = GridToStructure.parse(new String[]{"#"});
		GridToStructure.CellOptions[][] windowOptions = {{GridToStructure.CellOptions.EMPTY.withHoles(0b10)}};
		List<Integer> wallYs = GridToStructure.render(window, 3, windowOptions).stream()
			.filter(b -> b.kind() == GridToStructure.Kind.WALL).map(GridToStructure.PlacedBlock::y).toList();
		assertTrue(wallYs.equals(List.of(1, 3)), "the carved wall should keep heights 1 and 3, kept " + wallYs);

		//A block per height: stripes override the column's material only where set.
		net.minecraft.resources.ResourceLocation stripe = new net.minecraft.resources.ResourceLocation("minecraft", "bricks");
		GridToStructure.CellOptions[][] striped = {{GridToStructure.CellOptions.painted(moss).withBlockAt(2, stripe)}};
		List<net.minecraft.resources.ResourceLocation> stripes = GridToStructure.render(window, 3, striped).stream()
			.filter(b -> b.kind() == GridToStructure.Kind.WALL).map(GridToStructure.PlacedBlock::blockId).toList();
		assertTrue(stripes.equals(List.of(moss, stripe, moss)), "height 2 should be bricks between moss, got " + stripes);

		//Two levels: the upper floor sits on top of the lower walls (y = height + 1), and stairs leave a hole in it.
		GridToStructure.Cell[][] ground = GridToStructure.parse(new String[]{"#H", ",,"});
		GridToStructure.Cell[][] upper = GridToStructure.parse(new String[]{",,", ",,"});
		List<GridToStructure.PlacedBlock> stacked = GridToStructure.renderLayers(List.of(ground, upper), new int[]{3, 2}, null);
		assertTrue(stacked.stream().anyMatch(b -> b.kind() == GridToStructure.Kind.FLOOR && b.y() == 4 && b.x() == 0 && b.z() == 0), "the second level's floor should sit at y = 3 + 1");
		assertTrue(stacked.stream().noneMatch(b -> b.kind() == GridToStructure.Kind.FLOOR && b.y() == 4 && b.x() == 1 && b.z() == 0), "the floor above the stairs should be left open");
		assertTrue(stacked.stream().filter(b -> b.kind() == GridToStructure.Kind.STAIRS).mapToInt(GridToStructure.PlacedBlock::y).max().orElse(0) == 4, "the stairs should climb through the upper floor (y 1..4)");

		//Stairs with a direction: real steps, each one higher than the stairs cell behind it.
		GridToStructure.Cell[][] run = GridToStructure.parse(new String[]{"HHH"});
		GridToStructure.CellOptions east = new GridToStructure.CellOptions(null, net.minecraft.core.Direction.EAST, null, null);
		List<Integer> steps = GridToStructure.render(run, 3, new GridToStructure.CellOptions[][]{{east, east, east}}).stream()
			.filter(b -> b.kind() == GridToStructure.Kind.STAIRS).sorted(java.util.Comparator.comparingInt(GridToStructure.PlacedBlock::x))
			.map(GridToStructure.PlacedBlock::y).toList();
		assertTrue(steps.equals(List.of(1, 2, 3)), "the steps should climb east 1, 2, 3, got " + steps);

		//Hatches: Up = a jigsaw in the ceiling with scaffolding under it, Down = a jigsaw in place of the floor.
		GridToStructure.Cell[][] hatch = GridToStructure.parse(new String[]{"D"});
		List<GridToStructure.PlacedBlock> up = GridToStructure.render(hatch, 3, new GridToStructure.CellOptions[][]{{new GridToStructure.CellOptions(null, null, null, net.minecraft.core.Direction.UP)}});
		assertTrue(up.stream().anyMatch(b -> b.kind() == GridToStructure.Kind.CONNECTOR && b.y() == 4 && b.facing() == net.minecraft.core.Direction.UP), "the ceiling hatch should sit at y = 3 + 1, facing up");
		assertTrue(up.stream().filter(b -> b.kind() == GridToStructure.Kind.STAIRS).count() == 3, "the ceiling hatch should have scaffolding under it");
		List<GridToStructure.PlacedBlock> down = GridToStructure.render(hatch, 3, new GridToStructure.CellOptions[][]{{new GridToStructure.CellOptions(null, null, null, net.minecraft.core.Direction.DOWN)}});
		assertTrue(down.stream().anyMatch(b -> b.kind() == GridToStructure.Kind.CONNECTOR && b.y() == 0), "the floor hatch should replace the floor");
		assertTrue(down.stream().noneMatch(b -> b.kind() == GridToStructure.Kind.FLOOR), "the floor hatch should leave no floor block");

		//Door with a target pool and manual direction chosen by the DM: they replace the piece's pool
		//(that is decided by DungeonPiecePlacer, not this class) and the "first empty neighbor" heuristic.
		GridToStructure.Cell[][] doorGrid = GridToStructure.parse(new String[]{"#D#", "#,#", "###"});
		GridToStructure.CellOptions[][] doorOptions = new GridToStructure.CellOptions[3][3];
		doorOptions[0][1] = new GridToStructure.CellOptions(null, null, "otro_pool", net.minecraft.core.Direction.SOUTH);
		GridToStructure.PlacedBlock chosenDoor = GridToStructure.render(doorGrid, 1, doorOptions).stream()
			.filter(b -> b.kind() == GridToStructure.Kind.CONNECTOR).findFirst()
			.orElseThrow(() -> new AssertionError("the D cell should produce a CONNECTOR"));
		assertTrue("otro_pool".equals(chosenDoor.targetPool()), "the door should carry the pool chosen by the DM, carried " + chosenDoor.targetPool());
		assertTrue(chosenDoor.facing() == net.minecraft.core.Direction.SOUTH, "the manual direction should beat the automatic heuristic, got " + chosenDoor.facing());

		//With no settings, the door still has no pool of its own (whoever places it decides) and follows the heuristic.
		GridToStructure.PlacedBlock defaultDoor = GridToStructure.render(doorGrid, 1).stream()
			.filter(b -> b.kind() == GridToStructure.Kind.CONNECTOR).findFirst()
			.orElseThrow(() -> new AssertionError("the D cell should produce a CONNECTOR"));
		assertTrue(defaultDoor.targetPool() == null, "with no settings, the door should not carry a pool of its own");
		assertTrue(defaultDoor.facing() == net.minecraft.core.Direction.NORTH, "with no settings, the automatic heuristic should still apply");

		//Circular stamp: recognizable (has a wall ring and floor inside), not exact (the grid corners,
		//farther than the radius, stay empty).
		GridToStructure.Cell[][] circle = new GridToStructure.Cell[9][9];
		for (GridToStructure.Cell[] row : circle) java.util.Arrays.fill(row, GridToStructure.Cell.EMPTY);
		GridToStructure.stampCircle(circle, 4, 4, 4, 1);
		assertTrue(circle[4][4] == GridToStructure.Cell.FLOOR, "the circle's center should be floor");
		assertTrue(circle[4][0] == GridToStructure.Cell.WALL, "the circle's north edge should be wall");
		assertTrue(circle[0][0] == GridToStructure.Cell.EMPTY, "the corner, outside the radius, should be empty");

		System.out.println("checkGridToStructure: OK, the grid translates floor/wall/door/entrance and the circular stamp comes out recognizable.");
	}

	private static void checkStructureImport() {
		assertTrue(DungeonManager.structureNameFor("Casa Grande (v2)").equals("casa_grande_v2"),
			"a normal file name should come out as a valid path");
		assertTrue(DungeonManager.structureNameFor("Capitán").equals("capitan"),
			"accents are stripped, not turned into a separator");
		assertTrue(DungeonManager.structureNameFor("torre").equals("torre"), "a name that is already valid is left untouched");
		assertTrue(DungeonManager.structureNameFor("___torre___").equals("torre"),
			"a stray separator at the start or end is not part of the name");
		//A name made entirely of non-Latin characters cannot yield an empty path: that would be an invalid id.
		for (String odd : List.of("", "   ", "!!!", "日本")) {
			assertTrue(DungeonManager.structureNameFor(odd).equals("structure"),
				"a name with nothing usable left should fall back to a default");
		}
		assertTrue(DungeonManager.structureNameFor(null).equals("structure"), "and with no name, null is not acceptable either");

		for (String name : List.of("Casa Grande (v2)", "Capitán", "___torre___", "MAYÚSCULAS Y ESPACIOS")) {
			assertTrue(DungeonManager.structureNameFor(name).matches("[a-z0-9_]+"),
				"\"" + name + "\" does not give a path that ResourceLocation accepts");
		}

		System.out.println("checkStructureImport: OK, any file name ends up as a valid path.");
	}

	//Online generators' maps: one tiny map per format, then the real samples in dungeongen/ when present.
	private static void checkMapImport() throws Exception {
		JsonObject donjon = com.google.gson.JsonParser.parseString("{\"cell_bit\":{\"room\":2,\"corridor\":4,\"arch\":65536,\"door\":131072,"
			+ "\"locked\":262144,\"trapped\":524288,\"secret\":1048576,\"portcullis\":2097152,\"stair_up\":8388608,\"stair_down\":4194304},"
			+ "\"cells\":[[0,0,0,0,0],[2,2,131076,4,4],[0,0,0,0,0]]}").getAsJsonObject();
		DungeonMapImporter.Result fromDonjon = DungeonMapImporter.parse(donjon, 2);
		assertTrue(count(fromDonjon, GridToStructure.Cell.DOOR) == 1, "the donjon door cell should give one door");
		assertTrue(doorFacing(fromDonjon) == net.minecraft.core.Direction.EAST, "a door between west and east floors should face east, faced " + doorFacing(fromDonjon));

		JsonObject vtt = com.google.gson.JsonParser.parseString("{\"resolution\":{\"map_origin\":{\"x\":0,\"y\":0},\"map_size\":{\"x\":4,\"y\":4}},"
			+ "\"line_of_sight\":[[{\"x\":1,\"y\":1},{\"x\":3,\"y\":1}],[{\"x\":3,\"y\":1},{\"x\":3,\"y\":3}],[{\"x\":3,\"y\":3},{\"x\":1,\"y\":3}],[{\"x\":1,\"y\":3},{\"x\":1,\"y\":2}]],"
			+ "\"portals\":[{\"bounds\":[{\"x\":1,\"y\":1},{\"x\":1,\"y\":2}]}]}").getAsJsonObject();
		DungeonMapImporter.Result fromVtt = DungeonMapImporter.parse(vtt, 3);
		assertTrue(count(fromVtt, GridToStructure.Cell.FLOOR) == 25, "a 2x2-square room at scale 3 should have 5x5 floor, got " + count(fromVtt, GridToStructure.Cell.FLOOR));
		assertTrue(count(fromVtt, GridToStructure.Cell.DOOR) == 1, "the portal should give one door");

		JsonObject scrawl = com.google.gson.JsonParser.parseString("{\"state\":{\"document\":{\"nodes\":{"
			+ "\"p\":{\"type\":\"PAGE\",\"grid\":{\"cellDiameter\":36}},"
			+ "\"t\":{\"type\":\"TEMPLATE\",\"template\":{\"dungeonShape\":\"g\"}},"
			+ "\"g\":{\"type\":\"GEOMETRY\",\"parentId\":\"t\",\"geometryId\":\"shape\"}}}},"
			+ "\"data\":{\"geometry\":{\"shape\":{\"polygons\":[[[[0,0],[72,0],[72,72],[0,72]]]],\"polylines\":[]}}}}").getAsJsonObject();
		DungeonMapImporter.Result fromScrawl = DungeonMapImporter.parse(scrawl, 3);
		assertTrue(count(fromScrawl, GridToStructure.Cell.FLOOR) == 25, "a 2x2-square outline at scale 3 should have 5x5 floor, got " + count(fromScrawl, GridToStructure.Cell.FLOOR));

		//Extras: ceiling over everything, air inside, a light per spacing, a sign on a note's floor cell.
		GridToStructure.Cell[][] hall = GridToStructure.parse(new String[]{"#####", "#,,,#", "#,,,#", "#,,,#", "#####"});
		net.minecraft.resources.ResourceLocation lantern = new net.minecraft.resources.ResourceLocation("minecraft", "lantern");
		List<GridToStructure.PlacedBlock> dressed = GridToStructure.renderLayers(java.util.Collections.singletonList(hall), new int[]{3}, null,
			new GridToStructure.Extras(true, true, lantern, 2, List.of(new GridToStructure.Note(2, 2, "#1 Goblins\ndetails"))));
		assertTrue(dressed.stream().filter(b -> b.kind() == GridToStructure.Kind.FLOOR && b.y() == 4).count() == 25, "the ceiling should cover all 25 cells at y = 4");
		assertTrue(dressed.stream().anyMatch(b -> b.kind() == GridToStructure.Kind.LIGHT && b.x() == 1 && b.z() == 1 && b.y() == 3 && b.facing() == net.minecraft.core.Direction.DOWN), "a lantern should hang under the ceiling at (1, 1)");
		assertTrue(dressed.stream().anyMatch(b -> b.kind() == GridToStructure.Kind.SIGN && b.x() == 2 && b.z() == 2 && b.y() == 1), "the note should become a sign at (2, 2)");
		long air = dressed.stream().filter(b -> b.kind() == GridToStructure.Kind.AIR).count();
		assertTrue(air == 9 * 3 - 4 - 1, "9 floor cells x 3 high, minus 4 lanterns (every 2: x, z = 1, 3) and the sign, should be carved, got " + air);
		assertTrue(dressed.get(0).kind() == GridToStructure.Kind.AIR, "the carving should come first");
		assertTrue(GridToStructure.signLines("#12 Six poisonous snakes guard the room").size() <= 4, "a sign holds 4 lines at most");

		//A whole imported map through the network: compressed and cut into pieces, then put back together byte for byte.
		String[] bigRows = new String[196];
		java.util.Arrays.fill(bigRows, ",".repeat(98) + "#".repeat(98));
		net.hawthorn.dndsheets.dungeon.network.DungeonTraceCaptureMessage big = new net.hawthorn.dndsheets.dungeon.network.DungeonTraceCaptureMessage(
			"", "", "", 1, "", 4, bigRows, new int[0], new int[0], new String[0], new String[0], new int[0], new int[0], new String[0], new String[0],
			"", "", 0, 0, new int[0], new int[0], new String[0], null, new int[]{4}, true, new int[0], new int[0], new int[0], 0, new String[0],
			new int[0], new int[0], new int[0], new String[0], true, true, "minecraft:lantern", 8, new int[]{5}, new int[]{6}, new String[]{"#1 Empty"});
		List<net.hawthorn.dndsheets.dungeon.network.DungeonTraceChunkMessage> chunks = net.hawthorn.dndsheets.dungeon.network.DungeonTraceChunkMessage.split(big);
		java.util.Map<java.util.UUID, java.io.ByteArrayOutputStream> pending = new java.util.HashMap<>();
		java.util.UUID dm = java.util.UUID.randomUUID();
		net.hawthorn.dndsheets.dungeon.network.DungeonTraceCaptureMessage rebuilt = null;
		for (var chunk : chunks) rebuilt = net.hawthorn.dndsheets.dungeon.network.DungeonTraceChunkMessage.join(pending, dm, chunk);
		assertTrue(rebuilt != null && java.util.Arrays.equals(bytes(big), bytes(rebuilt)), "the map should come back identical from " + chunks.size() + " piece(s)");

		java.nio.file.Path samples = java.nio.file.Path.of("dungeongen");
		if (!java.nio.file.Files.isDirectory(samples)) return;
		try (var files = java.nio.file.Files.list(samples)) {
			for (java.nio.file.Path file : files.toList()) {
				DungeonMapImporter.Result map = DungeonMapImporter.read(file, 3);
				int floors = count(map, GridToStructure.Cell.FLOOR), doors = count(map, GridToStructure.Cell.DOOR);
				System.out.println("  " + file.getFileName() + " (" + map.format() + "): " + map.grid()[0].length + "x" + map.grid().length + " blocks, " + floors + " floor, " + doors + " doors");
				assertTrue(floors > 100 && doors > 0, file + " should import a dungeon with floor and doors");
				if (map.format().equals("donjon")) assertTrue(map.notes().size() >= 60, "donjon should give a note per room (60) plus corridor features, got " + map.notes().size());
				if (System.getenv("DUMP_MAPS") != null) {
					StringBuilder text = new StringBuilder();
					for (GridToStructure.Cell[] row : map.grid()) {
						for (GridToStructure.Cell c : row) text.append(GridToStructure.toChar(c));
						text.append(System.lineSeparator());
					}
					java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("DUMP_MAPS"), file.getFileName() + ".txt"), text);
				}
			}
		}
	}

	private static byte[] bytes(net.hawthorn.dndsheets.dungeon.network.DungeonTraceCaptureMessage message) {
		net.minecraft.network.FriendlyByteBuf buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		net.hawthorn.dndsheets.dungeon.network.DungeonTraceCaptureMessage.buffer(message, buffer);
		byte[] out = new byte[buffer.readableBytes()];
		buffer.readBytes(out);
		return out;
	}

	private static int count(DungeonMapImporter.Result map, GridToStructure.Cell cell) {
		int n = 0;
		for (GridToStructure.Cell[] row : map.grid()) for (GridToStructure.Cell c : row) if (c == cell) n++;
		return n;
	}

	private static net.minecraft.core.Direction doorFacing(DungeonMapImporter.Result map) {
		for (int z = 0; z < map.grid().length; z++) {
			for (int x = 0; x < map.grid()[z].length; x++) if (map.grid()[z][x] == GridToStructure.Cell.DOOR) return map.options()[z][x].doorFacing();
		}
		return null;
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
