package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * <p>Metamagic: Twinned Spell. Right-clicking the item marks the sorcerer's NEXT spell cast (a one-use
 * flag on the sheet, same pattern as {@code nextAttackAdvantage}) so it also reaches a second valid target
 * nearby — {@link SpellCastManager#handleCastRequest} consumes it right after resolving the spell against
 * the normal target.</p>
 *
 * <p><b>Deliberate simplifications</b>: in real 5e this actually costs sorcery points (there's no sorcery
 * point pool modeled here, only the flat spell slot pool), and it only applies to spells that already
 * target just one creature (not explicitly checked here, but an area spell already deals damage to
 * everyone in the radius, so twinning it wouldn't make sense — the flag is left unset for those cases in
 * {@code handleCastRequest}, see the comment there). No uses-per-rest limit, same as Rage/Second Wind.</p>
 */
public class SorcererMetamagicManager {

	//Triggered from AbilityItemDispatcher instead of subscribing to RightClickItem on its own.
	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		sheet.addProperty("twinnedSpellPending", true);
		SheetLoader.saveAndSync(player, sheet);
		CombatFx.activate(player);
		player.sendSystemMessage(Component.translatable("chat.dndsheets.resource.twinned_armed").withStyle(ChatFeedback.RESOURCE));
	}

	//Public: SpellCastManager consumes this when casting the next spell, with or without an actual second
	//target nearby — it's spent either way, just as in 5e you spend the sorcery point even with no one else around.
	public static boolean consumePending(JsonObject sheet) {
		if (sheet == null || !sheet.has("twinnedSpellPending") || !sheet.get("twinnedSpellPending").getAsBoolean()) return false;
		sheet.remove("twinnedSpellPending");
		return true;
	}

	public static ItemStack buildTwinnedSpellStack() {
		return AbilityItem.build(ItemLook.TWINNED, "twinnedSpell", Component.translatable("chat.dndsheets.metamagic.item_name"),
			Component.translatable("chat.dndsheets.metamagic.item_lore").withStyle(ChatFormatting.GRAY));
	}
}
