package net.hawthorn.dndsheets.client;

//Espejo en el cliente del estado de TurnManager (servidor), actualizado por network.TurnStateMessage.
//Solo para pintar el HUD (ver TurnHudOverlay) — el servidor sigue siendo la única fuente de verdad para
//las reglas; esto nunca decide nada, solo muestra.
public class TurnHudState {
	private static boolean active = false;
	private static int round = 0;
	private static String currentName = "";
	private static int currentEntityId = -1;
	private static boolean actionUsed = false;
	private static double originX, originY, originZ;

	public static void update(boolean active, int round, String currentName, int currentEntityId, boolean actionUsed, double originX, double originY, double originZ) {
		TurnHudState.active = active;
		TurnHudState.round = round;
		TurnHudState.currentName = currentName;
		TurnHudState.currentEntityId = currentEntityId;
		TurnHudState.actionUsed = actionUsed;
		TurnHudState.originX = originX;
		TurnHudState.originY = originY;
		TurnHudState.originZ = originZ;
	}

	public static boolean active() { return active; }
	public static int round() { return round; }
	public static String currentName() { return currentName; }
	public static int currentEntityId() { return currentEntityId; }
	public static boolean actionUsed() { return actionUsed; }
	public static double originX() { return originX; }
	public static double originY() { return originY; }
	public static double originZ() { return originZ; }
}
