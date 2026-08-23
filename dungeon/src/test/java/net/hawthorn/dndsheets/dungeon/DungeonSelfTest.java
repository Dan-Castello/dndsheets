package net.hawthorn.dndsheets.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * <p>Se mudó desde {@code JsonContentSelfTest} (core) cuando el toolkit de mazmorras pasó a ser este
 * addon: {@link DungeonManager#buildPoolJsons} y {@link GridToStructure} no tocan clases de Minecraft
 * ni necesitan un servidor arrancado, así que se comprueban de pie aquí mismo, igual que hacía el core
 * con su propia lógica pura.</p>
 *
 * <p>Se ejecuta a mano desde la raíz del repo:
 * {@code java -cp <classpath> net.hawthorn.dndsheets.dungeon.DungeonSelfTest}</p>
 */
public class DungeonSelfTest {
	public static void main(String[] args) throws Exception {
		checkDungeonPools();
		checkGridToStructure();
		checkStructureImport();

		System.out.println("DungeonSelfTest: OK.");
	}

	//Única lógica con ramas reales de la feature de mazmorras (ver DungeonManager): agrupar por pool,
	//acotar weight a [1,150] (StructureTemplatePool.DIRECT_CODEC lo exige, un valor fuera de rango tumba
	//el parseo del archivo entero) y saltar piezas con structureId corrupto. El resto (copiar el .nbt,
	///reload, JigsawPlacement.generateJigsaw) son llamadas finas a APIs vanilla, no hace falta re-testearlas.
	private static void checkDungeonPools() {
		List<DungeonPieceRegistry.DungeonPiece> pieces = List.of(
			new DungeonPieceRegistry.DungeonPiece("entrance", "dndsheets_dm:rooms/entrance", "start", 200, ""),
			new DungeonPieceRegistry.DungeonPiece("corridor1", "dndsheets_dm:rooms/corridor1", "corridor", 3, ""),
			new DungeonPieceRegistry.DungeonPiece("corridor2", "dndsheets_dm:rooms/corridor2", "corridor", 0, ""),
			new DungeonPieceRegistry.DungeonPiece("broken", "esto no es un id valido", "corridor", 5, "")
		);

		Map<String, JsonObject> pools = DungeonManager.buildPoolJsons(pieces);
		assertTrue(pools.size() == 2, "buildPoolJsons debería producir 2 pools (start, corridor), dio " + pools.size());

		JsonObject start = pools.get("start");
		assertTrue(start != null && "minecraft:empty".equals(start.get("fallback").getAsString()), "el pool start debería tener fallback=minecraft:empty");
		JsonArray startElements = start.getAsJsonArray("elements");
		assertTrue(startElements.size() == 1, "el pool start debería tener 1 elemento");
		JsonObject startWrapper = startElements.get(0).getAsJsonObject();
		assertTrue(startWrapper.get("weight").getAsInt() == 150, "el peso 200 debería acotarse a 150");
		JsonObject startElement = startWrapper.getAsJsonObject("element");
		assertTrue("minecraft:single_pool_element".equals(startElement.get("element_type").getAsString()), "element_type debería ser minecraft:single_pool_element");
		assertTrue("dndsheets_dm:rooms/entrance".equals(startElement.get("location").getAsString()), "location debería ser el structureId de la pieza");
		assertTrue("minecraft:empty".equals(startElement.get("processors").getAsString()), "processors debería ser minecraft:empty");
		assertTrue("rigid".equals(startElement.get("projection").getAsString()), "projection debería ser rigid");

		JsonObject corridor = pools.get("corridor");
		JsonArray corridorElements = corridor.getAsJsonArray("elements");
		//"broken" tiene un structureId inválido y se salta: solo corridor1 y corridor2 quedan.
		assertTrue(corridorElements.size() == 2, "el pool corridor debería tener 2 elementos (la pieza con structureId inválido se salta), dio " + corridorElements.size());
		boolean foundClampedZero = false;
		for (JsonElement el : corridorElements) {
			if (el.getAsJsonObject().get("weight").getAsInt() == 1) foundClampedZero = true;
		}
		assertTrue(foundClampedZero, "el peso 0 debería acotarse a 1");
	}

	private static void checkGridToStructure() {
		//Sala mínima 3x3: paredes en el borde, piso hueco en el centro, alto 2.
		GridToStructure.Cell[][] room = GridToStructure.parse(new String[]{
			"###",
			"#,#",
			"###"
		});
		List<GridToStructure.PlacedBlock> blocks = GridToStructure.render(room, 2);
		long wallColumns = blocks.stream().filter(b -> b.kind() == GridToStructure.Kind.WALL).count();
		assertTrue(wallColumns == 8 * 2, "8 celdas de pared x 2 de alto, salieron " + wallColumns);
		long floors = blocks.stream().filter(b -> b.kind() == GridToStructure.Kind.FLOOR).count();
		assertTrue(floors == 9, "las 9 celdas (pared incluida) deberían tener piso, salieron " + floors);

		//Puerta en la pared norte: sin vecino "afuera" salvo fuera de la grilla, hacia el norte.
		List<GridToStructure.PlacedBlock> withDoor = GridToStructure.translate(new String[]{
			"#D#",
			"#,#",
			"###"
		}, 2);
		GridToStructure.PlacedBlock door = withDoor.stream()
			.filter(b -> b.kind() == GridToStructure.Kind.CONNECTOR).findFirst()
			.orElseThrow(() -> new AssertionError("la celda D debería producir un CONNECTOR"));
		assertTrue(door.facing() == net.minecraft.core.Direction.NORTH, "la puerta en el borde norte debería apuntar afuera, hacia el norte, apuntó " + door.facing());

		//Entrada (S) igual que la puerta pero con Kind.START, para que hasStartJigsaw la reconozca.
		List<GridToStructure.PlacedBlock> withStart = GridToStructure.translate(new String[]{"#S#", "#,#", "###"}, 1);
		assertTrue(withStart.stream().anyMatch(b -> b.kind() == GridToStructure.Kind.START), "la celda S debería producir un START");

		//Objeto (o) sin bloque elegido: kind OBJECT con blockId null, la capa que planta bloques decide el
		//por defecto. Con bloque elegido (de cualquier mod, es solo un ResourceLocation): lo carga tal cual.
		GridToStructure.Cell[][] withObject = GridToStructure.parse(new String[]{"#o#", "#,#", "###"});
		GridToStructure.PlacedBlock plainObject = GridToStructure.render(withObject, 1).stream()
			.filter(b -> b.kind() == GridToStructure.Kind.OBJECT).findFirst()
			.orElseThrow(() -> new AssertionError("la celda o debería producir un OBJECT"));
		assertTrue(plainObject.blockId() == null, "sin palette, el objeto no debería traer un blockId fijo");

		net.minecraft.resources.ResourceLocation furniture = new net.minecraft.resources.ResourceLocation("algunmod", "silla");
		GridToStructure.CellOptions[][] objectOptions = new GridToStructure.CellOptions[3][3];
		objectOptions[0][1] = new GridToStructure.CellOptions(furniture, null, null, null);
		GridToStructure.PlacedBlock chosenObject = GridToStructure.render(withObject, 1, objectOptions).stream()
			.filter(b -> b.kind() == GridToStructure.Kind.OBJECT).findFirst()
			.orElseThrow(() -> new AssertionError("la celda o debería producir un OBJECT"));
		assertTrue(furniture.equals(chosenObject.blockId()), "el objeto debería cargar el bloque de otro mod que eligió el DM, cargó " + chosenObject.blockId());

		//Puerta con pool destino y dirección manual elegidos por el DM: reemplazan el pool de la pieza
		//(eso lo decide DungeonPiecePlacer, no esta clase) y la heurística de "primer vecino vacío".
		GridToStructure.Cell[][] doorGrid = GridToStructure.parse(new String[]{"#D#", "#,#", "###"});
		GridToStructure.CellOptions[][] doorOptions = new GridToStructure.CellOptions[3][3];
		doorOptions[0][1] = new GridToStructure.CellOptions(null, null, "otro_pool", net.minecraft.core.Direction.SOUTH);
		GridToStructure.PlacedBlock chosenDoor = GridToStructure.render(doorGrid, 1, doorOptions).stream()
			.filter(b -> b.kind() == GridToStructure.Kind.CONNECTOR).findFirst()
			.orElseThrow(() -> new AssertionError("la celda D debería producir un CONNECTOR"));
		assertTrue("otro_pool".equals(chosenDoor.targetPool()), "la puerta debería llevar el pool que eligió el DM, llevó " + chosenDoor.targetPool());
		assertTrue(chosenDoor.facing() == net.minecraft.core.Direction.SOUTH, "la dirección manual debería ganarle a la heurística automática, salió " + chosenDoor.facing());

		//Sin ajustes, la puerta sigue sin pool propio (lo decide quien la planta) y respeta la heurística.
		GridToStructure.PlacedBlock defaultDoor = GridToStructure.render(doorGrid, 1).stream()
			.filter(b -> b.kind() == GridToStructure.Kind.CONNECTOR).findFirst()
			.orElseThrow(() -> new AssertionError("la celda D debería producir un CONNECTOR"));
		assertTrue(defaultDoor.targetPool() == null, "sin ajustes, la puerta no debería traer un pool propio");
		assertTrue(defaultDoor.facing() == net.minecraft.core.Direction.NORTH, "sin ajustes, debería seguir valiendo la heurística automática");

		//Sello circular: reconocible (tiene anillo de pared y piso adentro), no exacto (las esquinas
		//de la grilla, más lejos que el radio, quedan vacías).
		GridToStructure.Cell[][] circle = new GridToStructure.Cell[9][9];
		for (GridToStructure.Cell[] row : circle) java.util.Arrays.fill(row, GridToStructure.Cell.EMPTY);
		GridToStructure.stampCircle(circle, 4, 4, 4, 1);
		assertTrue(circle[4][4] == GridToStructure.Cell.FLOOR, "el centro del círculo debería quedar como piso");
		assertTrue(circle[4][0] == GridToStructure.Cell.WALL, "el borde norte del círculo debería quedar como pared");
		assertTrue(circle[0][0] == GridToStructure.Cell.EMPTY, "la esquina, fuera del radio, debería quedar vacía");

		System.out.println("checkGridToStructure: OK, la grilla traduce piso/pared/puerta/entrada y el sello circular sale reconocible.");
	}

	private static void checkStructureImport() {
		assertTrue(DungeonManager.structureNameFor("Casa Grande (v2)").equals("casa_grande_v2"),
			"un nombre de archivo normal tiene que salir como ruta válida");
		assertTrue(DungeonManager.structureNameFor("Capitán").equals("capitan"),
			"los acentos se quitan, no se convierten en separador");
		assertTrue(DungeonManager.structureNameFor("torre").equals("torre"), "lo que ya vale no se toca");
		assertTrue(DungeonManager.structureNameFor("___torre___").equals("torre"),
			"un separador suelto al principio o al final no es parte del nombre");
		//Un nombre entero en caracteres no latinos no puede dar una ruta vacía: eso sería un id inválido.
		for (String odd : List.of("", "   ", "!!!", "日本")) {
			assertTrue(DungeonManager.structureNameFor(odd).equals("estructura"),
				"un nombre del que no queda nada usable tiene que caer en uno por defecto");
		}
		assertTrue(DungeonManager.structureNameFor(null).equals("estructura"), "y sin nombre, tampoco vale null");

		for (String name : List.of("Casa Grande (v2)", "Capitán", "___torre___", "MAYÚSCULAS Y ESPACIOS")) {
			assertTrue(DungeonManager.structureNameFor(name).matches("[a-z0-9_]+"),
				"\"" + name + "\" no da una ruta que ResourceLocation acepte");
		}

		System.out.println("checkStructureImport: OK, cualquier nombre de archivo acaba siendo una ruta válida.");
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
