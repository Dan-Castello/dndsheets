package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <p>Roleplay Wand: pure narration, it never touches rules, turns or sheets. Right-click in the air opens a list
 * of verbs (look, listen...); right-click one creature (the actor), then another (the target), and the table
 * reads "Goblin looks at Aria". Same two-click selection as the Move Wand.</p>
 */
@Mod.EventBusSubscriber
public class RoleplayManager {
	//Order is stored in the stack as an index: append-only, like ItemLook.
	public enum Verb { LOOK, LISTEN, SPEAK, GREET, IGNORE, FOLLOW }

	//ponytail: entries of players who log out mid-selection stay (a few ints); clear on logout if it ever matters.
	private static final Map<UUID, Integer> pendingActor = new HashMap<>();

	public static ItemStack buildRoleplayStack() {
		ItemStack stack = ItemLook.ROLEPLAY.applyTo(new ItemStack(net.hawthorn.dndsheets.init.DndsheetsModItems.TOKEN.get()));
		CompoundTag tag = new CompoundTag();
		tag.putBoolean("roleplay", true);
		tag.putInt("verb", 0);
		stack.getOrCreateTag().put("dndsheets", tag);
		stack.setHoverName(Component.translatable("chat.dndsheets.rp.item_name"));
		net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.translatable("chat.dndsheets.rp.item_lore").withStyle(ChatFormatting.GRAY))));
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.translatable("chat.dndsheets.rp.item_lore2").withStyle(ChatFormatting.GRAY))));
		stack.getOrCreateTagElement("display").put("Lore", lore);
		return stack;
	}

	public static boolean isRoleplayWand(ItemStack stack) {
		return tagOf(stack) != null;
	}

	private static CompoundTag tagOf(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		return tag != null && tag.contains("dndsheets") && tag.getCompound("dndsheets").getBoolean("roleplay")
			? tag.getCompound("dndsheets") : null;
	}

	private static Verb verbOf(CompoundTag tag) {
		Verb[] all = Verb.values();
		return all[Math.floorMod(tag.getInt("verb"), all.length)];
	}

	/** {@code /dndrp <verb>}: sets the verb on the wand in hand (the client list, see client.RoleplayClient, sends it). */
	public static void setVerb(Player player, Verb verb) {
		for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
			CompoundTag tag = tagOf(player.getItemInHand(hand)); //A command, not a click event: no double pass.
			if (tag == null) continue;
			tag.putInt("verb", verb.ordinal());
			player.displayClientMessage(Component.translatable("chat.dndsheets.rp.verb",
				Component.translatable("chat.dndsheets.rp.verb_name." + verb.name().toLowerCase())), true);
			return;
		}
	}

	@SubscribeEvent
	public static void onPickCreature(PlayerInteractEvent.EntityInteract event) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag tag = tagOf(event.getItemStack());
		if (tag == null || !(event.getEntity() instanceof ServerPlayer player)) return;
		Entity target = event.getTarget();
		if (!(target instanceof LivingEntity)) return;
		InteractionEvents.consume(event);

		Integer actorId = pendingActor.get(player.getUUID());
		Entity actor = actorId == null ? null : player.level().getEntity(actorId);
		if (actor == null || !actor.isAlive() || actor == target) {
			pendingActor.put(player.getUUID(), target.getId());
			player.displayClientMessage(Component.translatable("chat.dndsheets.rp.actor",
				ContentNames.of(TurnManager.nameOf(target)), Component.translatable("chat.dndsheets.rp.verb_name." + verbOf(tag).name().toLowerCase())), true);
			return;
		}
		pendingActor.remove(player.getUUID());
		player.getServer().getPlayerList().broadcastSystemMessage(
			Component.translatable("chat.dndsheets.rp.say." + verbOf(tag).name().toLowerCase(),
				ContentNames.of(TurnManager.nameOf(actor)), ContentNames.of(TurnManager.nameOf(target))).withStyle(ChatFormatting.ITALIC, ChatFormatting.YELLOW), false);
	}
}
