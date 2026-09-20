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

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
