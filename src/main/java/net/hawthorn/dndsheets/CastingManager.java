package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <p><b>Spells that take time to go off.</b> Until now every spell resolved in the same tick it was
 * requested: chat announced the result before the first particle reached anyone. With a
 * {@code castTicks} above zero (see {@code SpellRegistry.Spell#castTicksAt} and {@link Config}), the
 * spell stays here in progress for that stretch, visibly charging up in the caster's hands, and
 * resolves when it finishes.</p>
 *
 * <p><b>The cost is already paid by the time this starts.</b> {@code SpellCastManager.prepare} charged
 * the turn action and the spell slot before reaching here, and that's deliberate: in 5e the action is
 * spent when you START casting, and a spell interrupted mid-cast is still lost along with its slot. It's
 * the same rule Counterspell already applied, where the slot is also spent even if the spell doesn't end
 * up taking effect.</p>
 *
 * <p>Three things interrupt it, and all three are the 5e rule for a spell with a casting time of 1 round
 * or more: taking damage and failing the Constitution save (same DC as concentration,
 * {@code max(10, damage/2)} — they deliberately share the formula, it's the same rule written once),
 * becoming incapacitated or dying, and moving from the spot.</p>
 *
 * <p>The state lives in RAM and not on the sheet, unlike concentration: a spell half-cast shouldn't
 * survive a server restart. Only a per-field patch is sent to the client —the same path concentration and
 * pending advantage already use, no new networking— so the first-person animation knows when to start and
 * when to stop (see {@code client.SpellCastAnimator}).</p>
 */
@Mod.EventBusSubscriber
public class CastingManager {
	/** Deliberately mutable: {@code ticksLeft} decreases every tick, and a record would force re-inserting into the map. */
	private static final class Casting {
		private final SpellCastManager.CastRequest request;
		private final int totalTicks;
		private final Vec3 origin;
		private int ticksLeft;

		private Casting(SpellCastManager.CastRequest request, int totalTicks, Vec3 origin) {
			this.request = request;
			this.totalTicks = totalTicks;
			this.origin = origin;
			this.ticksLeft = totalTicks;
		}

		private float progress() {
			return 1.0f - (float) ticksLeft / totalTicks;
		}
	}

	private static final Map<UUID, Casting> casting = new HashMap<>();

	//One block of margin: the idea is "you stayed put while casting", not "you didn't breathe". Without
	//margin, knockback from a hit or the walking step itself would cancel the spell on its own.
	private static final double MAX_MOVE = 1.0;

	/**
	 * <p>Starts a delayed cast. The {@code CastRequest} already carries the targeted target and the
	 * upcast spell: it isn't re-aimed when it resolves, because that would let a spell correct its aim
	 * just by turning the camera while casting.</p>
	 */
	static void begin(ServerPlayer caster, SpellCastManager.CastRequest request, int castTicks) {
		//If one was already in progress (two requests in a row), the new one wins: the old one already
		//charged its cost, so abandoning it is the same as interrupting it, with nothing refunded.
		casting.remove(caster.getUUID());

		casting.put(caster.getUUID(), new Casting(request, castTicks, caster.position()));
		//The turn CANNOT pass while casting: TurnManager.tryAct already queued its auto-advance for the
		//next tick, and without this the spell would resolve with someone else's turn already started.
		TurnManager.holdAutoAdvance();
		notifyClient(caster, request.spell().name(), castTicks);
	}

	//A server tick: decrements the counter of every live cast, paints the charge-up, and resolves the
	//ones that reach zero. Outside of an active cast nothing runs (the map is empty almost always).
	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase != TickEvent.Phase.END || casting.isEmpty()) return;

		//Collected first and acted on afterward: resolving a spell can touch this same map (kill the
		//caster, start another cast), and that would blow up an open iterator.
		List<UUID> finished = new ArrayList<>();
		List<UUID> broken = new ArrayList<>();
		for (Map.Entry<UUID, Casting> entry : casting.entrySet()) {
			Casting current = entry.getValue();
			ServerPlayer caster = casterOf(entry.getKey());
			if (caster == null) continue;

			//Incapacitated, dead, or moved from the spot: lost without ever rolling a die.
			Combatant self = Combatant.of(caster);
			if (!caster.isAlive() || (self != null && self.cannotAct())
					|| caster.position().distanceToSqr(current.origin) > MAX_MOVE * MAX_MOVE) {
				broken.add(entry.getKey());
				continue;
			}

			CombatFx.spellCharge(caster, current.request.spell().school(), current.progress());
			CombatFx.actionBar(caster, Component.translatable("chat.dndsheets.spell.casting",
				current.request.spell().name(), progressBar(current.progress())).withStyle(ChatFormatting.AQUA));

			if (--current.ticksLeft <= 0) finished.add(entry.getKey());
		}

		for (UUID id : broken) fail(id, "chat.dndsheets.spell.cast_interrupted_moved", null, 0);
		for (UUID id : finished) {
			Casting done = casting.remove(id);
			ServerPlayer caster = casterOf(id);
			if (done == null || caster == null) continue;
			notifyClient(caster, null, 0);
			//Auto-advance is returned to the turn BEFORE resolving: the spell's effect can start a
			//combat, kill the caster, or change whose turn it is, and all of that has to happen against
			//the already-restored order, not against a held one.
			releaseTurn(caster);
			SpellCastManager.resolve(caster, done.request);
		}
	}

	/**
	 * <p>Damage taken while casting: a Constitution save against {@code max(10, damage/2)} or the spell is
	 * lost along with its slot. Called from {@link ConcentrationManager#onDamageTaken}, which is the point
	 * ALL of the mod's damage paths already go through — hooking in there is one call instead of four, and
	 * none that can be forgotten when a new damage path is added.</p>
	 */
	public static void onDamageTaken(ServerPlayer player, int damage) {
		Casting current = casting.get(player.getUUID());
		if (current == null || damage <= 0) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		int dc = Math.max(10, damage / 2);
		DiceManager.RollOutcome saveRoll = sheet != null ? DiceManager.roll(sheet, "1d20 + $con") : DiceManager.roll(new JsonObject(), "1d20");
		if (saveRoll.result() != null && saveRoll.result().getValue() >= dc) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.spell.cast_kept",
				SheetLoader.characterNameOf(sheet, player), current.request.spell().name(), dc, saveRoll.formatted())
				.withStyle(ChatFormatting.GRAY));
			return;
		}
		fail(player.getUUID(), "chat.dndsheets.spell.cast_interrupted", saveRoll.formatted(), dc);
	}

	/**
	 * <p>Character switch or disconnect: whatever was in progress is lost, with no message. Called from
	 * {@code SheetLoader}, where cleanup of all live per-player states (concentration, rage, wild shape,
	 * hunter's mark) is already centralized instead of scattering an identical subscriber for each one.</p>
	 *
	 * <p>On disconnect, only the memory needs to be released: notifying the client of someone who already
	 * left goes nowhere, and neither does returning the turn to a player who's no longer there.</p>
	 */
	public static void clearFor(ServerPlayer player) {
		if (casting.remove(player.getUUID()) == null || player.hasDisconnected()) return;
		notifyClient(player, null, 0);
		releaseTurn(player);
	}

	private static void fail(UUID id, String messageKey, String rollText, int dc) {
		Casting lost = casting.remove(id);
		ServerPlayer caster = casterOf(id);
		if (lost == null || caster == null) return;

		notifyClient(caster, null, 0);
		releaseTurn(caster);
		String name = SheetLoader.characterNameOf(SheetLoader.getServerSheet(caster.getStringUUID()), caster);
		Component message = rollText == null
			? Component.translatable(messageKey, name, lost.request.spell().name())
			: Component.translatable(messageKey, name, lost.request.spell().name(), dc, rollText);
		ChatFeedback.broadcast(caster, message.copy().withStyle(ChatFormatting.RED));
	}

	//The turn was being held since begin(): once the cast ends —successfully or not— it goes back to
	//behaving like any other spent action and advances on its own.
	private static void releaseTurn(ServerPlayer caster) {
		if (caster.level() instanceof ServerLevel level) TurnManager.resumeAutoAdvance(level, caster);
	}

	@Nullable
	private static ServerPlayer casterOf(UUID id) {
		net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
		return server != null ? server.getPlayerList().getPlayer(id) : null;
	}

	//Same path concentration, armed smite, and pending advantage already travel through (see
	//ConcentrationManager.notifyClient): one patch per field, zero new network messages. The END tick
	//travels along with the duration, so the client can compute progress on its own instead of receiving
	//a network patch on every cast tick.
	//
	//Unlike concentration, this does NOT write into the server sheet's JsonObject, it only sends the
	//patch: a spell half-cast shouldn't survive a restart, so persisting it would be exactly the bug (and
	//invariant 4's self-test catches it, rightly so — a sheet that's touched gets saved).
	//The price is that a FULL sheet send mid-cast clears the field on the client and cuts the animation
	//short; it heals itself on the next cast and doesn't affect any rule.
	private static void notifyClient(ServerPlayer caster, String spellName, int castTicks) {
		JsonObject patch = new JsonObject();
		if (spellName == null) {
			patch.add("castingSpell", com.google.gson.JsonNull.INSTANCE); //Null in a patch = delete the key.
			patch.add("castingTicks", com.google.gson.JsonNull.INSTANCE);
			patch.add("castingUntil", com.google.gson.JsonNull.INSTANCE);
		} else {
			patch.addProperty("castingSpell", spellName);
			patch.addProperty("castingTicks", castTicks);
			patch.addProperty("castingUntil", caster.level().getGameTime() + castTicks);
		}
		DndsheetsMod.sendSheetFieldUpdate(caster, patch);
	}

	//Ten cells in plain ASCII: the action bar is drawn by the game's font, and a unicode block character
	//comes out as an empty little square as soon as the font doesn't include it. The brackets are added
	//by the language file, not here.
	private static String progressBar(float progress) {
		int filled = Math.max(0, Math.min(10, Math.round(progress * 10)));
		return "=".repeat(filled) + "-".repeat(10 - filled);
	}
}
