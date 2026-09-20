package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.LevelUpManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -&gt; server: the player chose their Ability Score Improvement. An empty {@code second} means
 * "+2 to one"; with both filled, "+1 to each." The server verifies they actually had an improvement
 * pending before applying anything — see {@link LevelUpManager#applyImprovement}.
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
