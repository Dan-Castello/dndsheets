package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.ContentNames;

import net.hawthorn.dndsheets.SpellRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * <p>Client -&gt; server: the player chose, from the "Bind to staff" button in the Spellbook, which
 * spell to assign to the staff held in their main hand. Rewrites the {@code quickSpell} of THAT
 * item instead of creating a new one (see {@code SpellCommand.buildStaffStack}), so the same staff
 * can be reassigned as many times as needed — with hundreds of spells, a distinct staff item per
 * spell isn't viable.</p>
 *
 * <p>Only acts if the item in hand already has {@code staffConfigurable:true}: a player with a
 * modified client can't turn an arbitrary item into a staff by hand-crafting this message.</p>
 */
public class StaffBindMessage {
	String spellId;

	public StaffBindMessage(String spellId) {
		this.spellId = spellId;
	}

	public StaffBindMessage(FriendlyByteBuf buffer) {
		this.spellId = buffer.readUtf();
	}

	public static void buffer(StaffBindMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.spellId);
	}

	public static void handler(StaffBindMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer player = context.getSender();
			if (player == null) return;

			SpellRegistry.Spell spell = SpellRegistry.get(message.spellId);
			if (spell == null) return;

			ItemStack stack = player.getMainHandItem();
			if (!SpellRegistry.bindStaff(stack, message.spellId)) return;

			stack.setHoverName(Component.translatable("chat.dndsheets.staff.item_name", ContentNames.of(spell.name())));
			player.sendSystemMessage(Component.translatable("chat.dndsheets.staff.bound", ContentNames.of(spell.name())).withStyle(ChatFormatting.GREEN));
		});
	}
}
