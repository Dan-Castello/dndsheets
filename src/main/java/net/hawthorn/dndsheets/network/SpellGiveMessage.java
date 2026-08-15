package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.SpellRegistry;
import net.hawthorn.dndsheets.command.SpellCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: enseña un hechizo o entrega su báculo de lanzado rápido (equivalente en GUI
//a /dndspells learn|staff), desde SpellGiveListScreen.
public class SpellGiveMessage {
	String targetUuid;
	String spellId;
	boolean asStaff;

	public SpellGiveMessage(String targetUuid, String spellId, boolean asStaff) {
		this.targetUuid = targetUuid;
		this.spellId = spellId;
		this.asStaff = asStaff;
	}

	public SpellGiveMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.spellId = buffer.readUtf();
		this.asStaff = buffer.readBoolean();
	}

	public static void buffer(SpellGiveMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeUtf(message.spellId);
		buffer.writeBoolean(message.asStaff);
	}

	public static void handler(SpellGiveMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;

			SpellRegistry.Spell spell = SpellRegistry.get(message.spellId);
			if (spell == null) {
				dm.sendSystemMessage(Component.literal("No conozco el hechizo \"" + message.spellId + "\"."));
				return;
			}

			net.hawthorn.dndsheets.DndsheetsMod.withDmTarget(context, message.targetUuid, target -> {
				if (message.asStaff) {
					target.getInventory().add(SpellCommand.buildStaffStack(message.spellId, spell, "minecraft:blaze_rod"));
					target.sendSystemMessage(Component.literal("Báculo de " + spell.name() + " recibido."));
				} else {
					SpellCommand.learnForPlayer(target, message.spellId, spell);
					target.sendSystemMessage(Component.literal("Aprendiste " + spell.name() + "."));
				}
			});
		});
	}
}
