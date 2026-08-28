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
 * <p>Cliente -&gt; servidor: el jugador eligió, desde el botón "Vincular al báculo" del Grimorio, qué
 * hechizo asignarle al báculo que lleva en la mano principal. Reescribe el {@code quickSpell} de ESE
 * ítem en vez de crear uno nuevo (ver {@code SpellCommand.buildStaffStack}), así que el mismo báculo se
 * puede reasignar tantas veces como haga falta — con cientos de hechizos no es viable uno distinto por
 * cada uno.</p>
 *
 * <p>Solo actúa si el ítem en mano ya trae {@code staffConfigurable:true}: un jugador con un cliente
 * modificado no puede convertir un ítem cualquiera en báculo mandando este mensaje a mano.</p>
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
