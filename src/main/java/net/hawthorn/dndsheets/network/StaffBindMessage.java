package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.ContentNames;

import net.hawthorn.dndsheets.MonsterRegistry;
import net.hawthorn.dndsheets.MonsterRegistry.MonsterStatBlock;
import net.hawthorn.dndsheets.SpellRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * <p>Client -&gt; server: edits the reconfigurable staff held in the main hand. One staff holds several
 * spells (see {@code SpellRegistry.staffAdd}) and casts the active one, so a distinct staff item per
 * spell isn't needed. ADD/REMOVE come from the Spellbook button, NEXT/PREV from the keybinds and
 * Shift+scroll (spellId is ignored for those).</p>
 *
 * <p>Only acts if the item in hand already has {@code staffConfigurable:true}: a player with a
 * modified client can't turn an arbitrary item into a staff by hand-crafting this message.</p>
 */
public class StaffBindMessage {
	//Append only (invariant 2): it travels by ordinal.
	public enum Action { ADD, REMOVE, NEXT, PREV }

	String spellId;
	Action action;

	public StaffBindMessage(String spellId, Action action) {
		this.spellId = spellId;
		this.action = action;
	}

	public StaffBindMessage(FriendlyByteBuf buffer) {
		this.spellId = buffer.readUtf();
		this.action = buffer.readEnum(Action.class);
	}

	public static void buffer(StaffBindMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.spellId);
		buffer.writeEnum(message.action);
	}

	//Same message, same actions, for the multi-monster summon card (spellId carries the monster id).
	private static void handleCard(StaffBindMessage message, ServerPlayer player, ItemStack stack) {
		String activeId = null;
		switch (message.action) {
			case ADD -> {
				if (MonsterRegistry.get(message.spellId) == null) return;
				if (!MonsterRegistry.cardAdd(stack, message.spellId)) {
					player.sendSystemMessage(Component.translatable("chat.dndsheets.card.full", MonsterRegistry.CARD_MAX_MONSTERS).withStyle(ChatFormatting.RED));
					return;
				}
				activeId = message.spellId;
			}
			case REMOVE -> {
				if (!MonsterRegistry.cardRemove(stack, message.spellId)) return;
				activeId = MonsterRegistry.monsterSpawnIdOf(stack);
				if (activeId == null) {
					stack.setHoverName(Component.translatable("chat.dndsheets.monster.card_blank"));
					return;
				}
			}
			case NEXT -> activeId = MonsterRegistry.cardCycle(stack, 1);
			case PREV -> activeId = MonsterRegistry.cardCycle(stack, -1);
		}
		MonsterStatBlock block = activeId == null ? null : MonsterRegistry.get(activeId);
		if (block == null) return;

		Component name = ContentNames.of(block.name());
		stack.setHoverName(Component.translatable("chat.dndsheets.monster.summon_card_name", name));
		java.util.List<String> all = MonsterRegistry.cardMonsters(stack);
		player.displayClientMessage(Component.translatable("chat.dndsheets.staff.active", name, all.indexOf(activeId) + 1, all.size()).withStyle(ChatFormatting.GREEN), true);
	}

	public static void handler(StaffBindMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer player = context.getSender();
			if (player == null) return;

			ItemStack stack = player.getMainHandItem();
			if (MonsterRegistry.isConfigurableCard(stack)) {
				handleCard(message, player, stack);
				return;
			}
			if (!SpellRegistry.isConfigurableStaff(stack)) return;

			String activeId = null;
			switch (message.action) {
				case ADD -> {
					if (SpellRegistry.get(message.spellId) == null) return;
					if (!SpellRegistry.staffAdd(stack, message.spellId)) {
						player.sendSystemMessage(Component.translatable("chat.dndsheets.staff.full", SpellRegistry.STAFF_MAX_SPELLS).withStyle(ChatFormatting.RED));
						return;
					}
					activeId = message.spellId;
				}
				case REMOVE -> {
					if (!SpellRegistry.staffRemove(stack, message.spellId)) return;
					java.util.List<String> left = SpellRegistry.staffSpells(stack);
					activeId = left.isEmpty() ? null : SpellRegistry.quickSpellIdOf(stack);
					if (activeId == null) {
						stack.resetHoverName();
						return;
					}
				}
				case NEXT -> activeId = SpellRegistry.staffCycle(stack, 1);
				case PREV -> activeId = SpellRegistry.staffCycle(stack, -1);
			}
			SpellRegistry.Spell spell = activeId == null ? null : SpellRegistry.get(activeId);
			if (spell == null) return;

			Component name = ContentNames.of(spell.name());
			stack.setHoverName(Component.translatable("chat.dndsheets.staff.item_name", name));
			//Action bar: the "HUD" for the active spell, no overlay needed.
			player.displayClientMessage(Component.translatable("chat.dndsheets.staff.active", name, SpellRegistry.staffSpells(stack).indexOf(activeId) + 1, SpellRegistry.staffSpells(stack).size()).withStyle(ChatFormatting.GREEN), true);
		});
	}
}
