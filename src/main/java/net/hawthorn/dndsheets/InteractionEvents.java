package net.hawthorn.dndsheets;

import net.minecraft.world.InteractionResult;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * <p>A right-click isn't one event: it's two. When the used hand "doesn't consume" anything, the client
 * retries on its own with the other hand (vanilla main-hand → off-hand behavior) and sends a second
 * packet, so the server processes the action TWICE.</p>
 *
 * <p>And {@code setCanceled(true)} alone doesn't prevent it: canceling on the server leaves the result
 * as {@code PASS}, which is exactly what the client reads as "didn't consume, try the other hand." You
 * also have to cancel with a result that DOES consume.</p>
 *
 * <p>It shows up as duplicated chat messages, but that's just the visible symptom: what actually runs
 * twice is the whole handler. An idempotent handler hides it —{@code onSelectMoveDestination} cleared
 * its state on the first pass and returned early on the second, so "moved" printed once while
 * "selected" printed twice— which is why you should ALWAYS call this, not just where it's noticeable.</p>
 */
public final class InteractionEvents {

	private InteractionEvents() {
	}

	/** "This right-click is already handled": cancels and cuts off the client's retry with the other hand.
	 *  Public: also used by the dungeon toolkit addon (separate module, see DungeonToolManager). */
	public static void consume(PlayerInteractEvent event) {
		event.setCanceled(true);
		event.setCancellationResult(InteractionResult.SUCCESS);
	}
}
