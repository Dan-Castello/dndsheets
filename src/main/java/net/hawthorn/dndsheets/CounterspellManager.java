package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * <p>Counterspell: the same "primed, triggers on its own only when it helps" pattern as Shield (see
 * {@link ShieldManager}), but reacts to ANOTHER caster (a player or a DM's monster) starting to cast a
 * spell nearby — {@link SpellCastManager#handleCastRequest} and {@link MonsterActionManager} call
 * {@link #findCounterer} right before resolving the effect.</p>
 *
 * <p><b>Deliberate simplification</b>: in real 5e a level-3 Counterspell automatically negates spells of
 * level 3 or lower, and against higher-level spells an ability check is needed (DC 10 + spell level) or
 * spending a slot of equal or greater level. Here the level-3 slot the rule demands is spent, but
 * <b>there's no check against higher-level spells</b>: a primed Counterspell negates any spell, with no
 * roll involved.</p>
 *
 * <p>This paragraph used to say "the slot pool is flat, with no levels per slot." That stopped being true
 * once {@code SpellSlots} started carrying a per-level table; what's still missing is just the ability
 * check.</p>
 */
public class CounterspellManager {

	//Counterspell is a level-3 spell in 5e.
	private static final int LEVEL = 3;
	private static final double RANGE = 30.0;

	//Triggered from AbilityItemDispatcher instead of subscribing to RightClickItem on its own.
	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		sheet.addProperty("counterspellReady", true);
		SheetLoader.saveAndSync(player, sheet);
		CombatFx.activate(player);
		player.sendSystemMessage(Component.translatable("chat.dndsheets.resource.counterspell_ready").withStyle(ChatFeedback.RESOURCE));
	}

	//Public: called right before resolving a spell's effect, whether it's cast by a player
	//(SpellCastManager) or a DM's monster (MonsterActionManager). Looks for the first nearby player with
	//Counterspell primed, a reaction available, and spell slots; if found, spends their slot and
	//reaction and returns their name (the caller handles announcing the failure and not resolving the effect).
	//Null if nobody could counter it.
	@Nullable
	public static String findCounterer(Level level, Vec3 origin, Entity caster) {
		//A player's Counterspell only protects the party against an ENEMY caster (a DM's monster) —
		//it used to not check where the spell came from, so ANY player's Counterspell would also negate
		//ANOTHER player's spell (e.g. an attack against a goblin), unintentionally "protecting" the
		//enemy ally. Real PvP between players deliberately doesn't go through Counterspell.
		if (caster instanceof ServerPlayer) return null;

		AABB box = new AABB(origin, origin).inflate(RANGE);
		for (Entity candidate : level.getEntities((Entity) null, box, e -> e instanceof ServerPlayer && e != caster)) {
			ServerPlayer player = (ServerPlayer) candidate;
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			if (sheet == null || !sheet.has("counterspellReady") || !sheet.get("counterspellReady").getAsBoolean()) continue;

			//Counterspell is a LEVEL 3 spell, so it requires a slot of level 3 or higher. With the old
			//flat pool it was enough to have "a slot," and a level-1 wizard could counter.
			if (!SpellSlots.hasSlotFor(sheet, LEVEL) || !TurnManager.tryReact(player)) continue;

			SpellSlots.spend(sheet, LEVEL);
			//Consumed on trigger, like any other single-use resource — without this it would stay
			//"primed" forever and negate any enemy spell that came near in any future round without
			//the player having to re-prepare it (it could be passively "spammed").
			sheet.addProperty("counterspellReady", false);
			SheetLoader.saveAndSync(player, sheet);
			return SheetLoader.characterNameOf(sheet, player);
		}
		return null;
	}

	public static ItemStack buildCounterspellStack() {
		return AbilityItem.build(ItemLook.COUNTERSPELL, "counterspellSpell", Component.translatable("chat.dndsheets.counterspell.item_name"),
			Component.translatable("chat.dndsheets.counterspell.item_lore").withStyle(ChatFormatting.GRAY));
	}
}
