package net.hawthorn.dndsheets.client;

import net.hawthorn.dndsheets.network.TurnStateMessage;

import java.util.List;

//Client-side mirror of TurnManager's (server) state, updated by network.TurnStateMessage.
//Only for painting the HUD (see TurnHudOverlay) — the server remains the sole source of truth for the
//rules; this never decides anything, it only displays.
public class TurnHudState {
	private static boolean active = false;
	private static int round = 0;
	private static int currentEntityId = -1;
	private static boolean actionUsed = false;
	private static double originX, originY, originZ;
	private static List<TurnStateMessage.RosterRow> roster = List.of();

	public static void update(boolean active, int round, int currentEntityId, boolean actionUsed,
							   double originX, double originY, double originZ, List<TurnStateMessage.RosterRow> roster) {
		TurnHudState.active = active;
		TurnHudState.round = round;
		TurnHudState.currentEntityId = currentEntityId;
		TurnHudState.actionUsed = actionUsed;
		TurnHudState.originX = originX;
		TurnHudState.originY = originY;
		TurnHudState.originZ = originZ;
		TurnHudState.roster = roster;
	}

	public static boolean active() { return active; }
	public static int round() { return round; }
	public static int currentEntityId() { return currentEntityId; }
	public static boolean actionUsed() { return actionUsed; }
	public static double originX() { return originX; }
	public static double originY() { return originY; }
	public static double originZ() { return originZ; }
	public static List<TurnStateMessage.RosterRow> roster() { return roster; }

	/** The local player's row, or {@code null} if they have no slot in the order (watching from outside). */
	public static TurnStateMessage.RosterRow myRow(int myEntityId) {
		for (TurnStateMessage.RosterRow row : roster) {
			if (row.entityId() == myEntityId) return row;
		}
		return null;
	}
}
