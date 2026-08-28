package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DeathSaveManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * <p>Los dos botones de la ventana de salvaciones de muerte, en un mensaje con un enum en vez de dos
 * clases sin ni un campo cuyos handlers solo se diferenciaban en a qué método llamaban (invariante 3).</p>
 *
 * <ul>
 *   <li>{@code ROLL} — "Tirar salvación de muerte".</li>
 *   <li>{@code GIVE_UP} — "Dejarse morir": mata al instante, mismo camino que 3 fallos.</li>
 * </ul>
 *
 * <p>Los dos van de cliente a servidor y ninguno lleva a quién afecta: es siempre quien manda el
 * paquete. Un cliente modificado solo puede tirar —o rendir— su propio personaje, y {@code
 * DeathSaveManager} comprueba además que esté de verdad caído.</p>
 */
public class DeathSaveMessage {

	//Al final, nunca en medio: writeEnum viaja por ordinal (ver la invariante 2 de PROJECT_CONTEXT.md).
	public enum Kind { ROLL, GIVE_UP }

	final Kind kind;

	public DeathSaveMessage(Kind kind) {
		this.kind = kind;
	}

	public DeathSaveMessage(FriendlyByteBuf buffer) {
		this.kind = buffer.readEnum(Kind.class);
	}

	public static void buffer(DeathSaveMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.kind);
	}

	public static void handler(DeathSaveMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer player = context.getSender();
			if (player == null) return;
			switch (message.kind) {
				case ROLL -> DeathSaveManager.handleRollRequest(player);
				case GIVE_UP -> DeathSaveManager.handleGiveUpRequest(player);
			}
		});
	}
}
