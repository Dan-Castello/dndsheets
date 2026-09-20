package net.hawthorn.dndsheets;

import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

//Single dispatcher for the single-NBT-flag "button" items activated with right-click (Rest Kit,
//Rage Totem, Counterspell, Shield, Hunter's Mark, Second Wind, Divine Smite, Twinned Spell, Wild
//Shape, Bardic Inspiration, quick-spell wands, turn items): previously each manager subscribed
//separately to the same 3 interaction events and re-read the NBT independently (up to 18+ handlers
//per right-click). Here it's read
//once and delegated to the appropriate manager. Each event branch only checks the flags of the
//managers that originally listened to THAT event (e.g. Hunter's Mark only acted on EntityInteract,
//because it needs the target of the click) so as not to change anyone's behavior. "quickSpell" is
//the exception: it's not a boolean flag but a spell id (String), so it's detected with
//dndTag.contains(...) instead of dndTag.getBoolean(...).
@Mod.EventBusSubscriber
public class AbilityItemDispatcher {

	@SubscribeEvent
	public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
		dispatch(event);
	}

	@SubscribeEvent
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		dispatch(event);
	}

	@SubscribeEvent
	public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag dndTag = dndTagOf(event.getItemStack());
		if (dndTag == null) return;

		//The items that NEED a creature in front, and that's why they only exist for this event: Hunter's
		//Mark marks whoever you point at, Inspiration is given to another player, and Shove needs to
		//know who. They come before the common dispatch because this is the only event where their click means anything.
		if (dndTag.getBoolean("hunterMark")) RangerHunterMarkManager.tryUse(event);
		else if (dndTag.getBoolean("bardicInspiration")) BardInspirationManager.tryUse(event);
		else if (dndTag.getBoolean("helpAction")) HelpActionManager.tryUse(event);
		else if (dndTag.getBoolean("shove")) ShoveManager.tryUse(event);
		else dispatch(event, dndTag);
	}

	private static void dispatch(PlayerInteractEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag dndTag = dndTagOf(event.getItemStack());
		if (dndTag != null) dispatch(event, dndTag);
	}

	/**
	 * <p>Common dispatch for all THREE interaction events. These items are used on oneself, so it doesn't
	 * matter what's in front when they're pressed.</p>
	 *
	 * <p>Previously this chain was copied three times, once per event, and the copies had drifted apart:
	 * Divine Smite, Twinned Spell, Counterspell and Shield were only in the "click at air" one. The
	 * result was that those four <b>did nothing if you were looking at a monster or a block</b> —
	 * i.e. exactly during combat, which is when they're used. With a single chain, a new item enters all
	 * three events by construction rather than by remembering to add it.</p>
	 */
	private static void dispatch(PlayerInteractEvent event, CompoundTag dndTag) {
		if (dndTag.getBoolean("restKit")) RestManager.tryOpenRestChoice(event);
		else if (dndTag.getBoolean("rage")) BarbarianRageManager.tryUse(event);
		else if (dndTag.getBoolean("counterspellSpell")) CounterspellManager.tryUse(event);
		else if (dndTag.getBoolean("shieldSpell")) ShieldManager.tryUse(event);
		else if (dndTag.getBoolean("turnNext")) TurnItemManager.tryUse(event, true);
		else if (dndTag.getBoolean("turnUndo")) TurnItemManager.tryUse(event, false);
		else if (dndTag.getBoolean("secondWind")) FighterSecondWindManager.tryUse(event);
		else if (dndTag.getBoolean("turnUndead")) ClericTurnUndeadManager.tryUse(event);
		else if (dndTag.getBoolean("turnActions")) TurnActionManager.tryUse(event);
		else if (dndTag.getBoolean("divineSmite")) PaladinSmiteManager.tryUse(event);
		else if (dndTag.getBoolean("twinnedSpell")) SorcererMetamagicManager.tryUse(event);
		else if (dndTag.getBoolean("wildShape")) DruidWildShapeManager.tryUse(event);
		//Before quickSpell: a wand that's ALSO consumable doesn't exist today, but if it did, consuming it
		//should win — casting without consuming it would make it infinite.
		else if (isConsumable(dndTag)) ConsumableManager.tryUse(event, dndTag.getString("magicItem"));
		else if (dndTag.contains("quickSpell")) QuickSpellManager.tryUse(event, dndTag.getString("quickSpell"));
	}

	//A magic item only takes this path if it's actually consumable: passives (rings, cloaks) shouldn't
	//do anything when clicked, and canceling their event would prevent equipping them into a Curios slot.
	private static boolean isConsumable(CompoundTag dndTag) {
		if (!dndTag.contains("magicItem")) return false;
		MagicItemRegistry.MagicItem item = MagicItemRegistry.get(dndTag.getString("magicItem"));
		return item != null && item.isConsumable();
	}

	@Nullable
	private static CompoundTag dndTagOf(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains("dndsheets")) return null;
		return tag.getCompound("dndsheets");
	}
}
