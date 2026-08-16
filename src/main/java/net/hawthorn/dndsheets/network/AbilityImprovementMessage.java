package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.LevelUpManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Cliente -&gt; servidor: el jugador eligió su Mejora de Puntuación de Característica. {@code second} vacío
 * significa "+2 a una"; con las dos, "+1 a cada una". El servidor comprueba que de verdad le tocaba una
 * mejora antes de aplicar nada — ver {@link LevelUpManager#applyImprovement}.
 */
public class AbilityImprovementMessage {
	final String first;
	final String second;

	public AbilityImprovementMessage(String first, String second) {
		this.first = first;
		this.second = second;
	}

	public AbilityImprovementMessage(FriendlyByteBuf buffer) {
		this.first = buffer.readUtf();
		this.second = buffer.readUtf();
	}

	public static void buffer(AbilityImprovementMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.first);
		buffer.writeUtf(message.second);
	}

	public static void handler(AbilityImprovementMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer player = context.getSender();
			if (player != null) LevelUpManager.applyImprovement(player, message.first, message.second);
		});
	}
}
