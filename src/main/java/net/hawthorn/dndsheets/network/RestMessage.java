package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.RestManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * <p>Todo el ciclo de vida de la votación de descanso en una clase, con un enum en vez de cuatro
 * mensajes casi iguales (invariante 3) — el mismo patrón que ya usan {@code WildShapeMessage} y
 * {@code BrowseActionMessage}.</p>
 *
 * <ul>
 *   <li>{@code PROPOSE} — cliente → servidor. Eligió corto o largo en {@code RestChoiceScreen} y
 *       arranca la votación.</li>
 *   <li>{@code VOTE_OPEN} — servidor → cliente (a cada uno). "Fulano propone un descanso largo, vota".
 *       El nombre y la etiqueta las resuelve el servidor: es quien tiene la propuesta pendiente.</li>
 *   <li>{@code VOTE_RESPONSE} — cliente → servidor. Aceptó o rechazó. El descanso solo se aplica si
 *       TODOS aceptan.</li>
 *   <li>{@code VOTE_CLOSE} — servidor → cliente. Cierra la ventana porque la votación ya se resolvió,
 *       expiró o se canceló por otra vía; sin esto, quien no había votado se quedaba con un
 *       "Aceptar/Rechazar" de una votación que ya no existía (ver {@code RestManager.clear()}).</li>
 * </ul>
 */
public class RestMessage {

	//Al final, nunca en medio: writeEnum viaja por ordinal (ver la invariante 2 de PROJECT_CONTEXT.md).
	public enum Kind { PROPOSE, VOTE_OPEN, VOTE_RESPONSE, VOTE_CLOSE }

	final Kind kind;
	//Cada campo solo lleva dato en su Kind; en los demás viaja vacío. Se mantienen con nombre propio en
	//vez de un solo booleano compartido: "longRest" y "accept" son preguntas distintas, y un campo que
	//significa una cosa u otra según el kind es justo lo que cuesta descifrar a las 3 de la mañana.
	final boolean longRest;    //PROPOSE
	final boolean accept;      //VOTE_RESPONSE
	final String proposerName; //VOTE_OPEN
	final String typeLabel;    //VOTE_OPEN

	private RestMessage(Kind kind, boolean longRest, boolean accept, String proposerName, String typeLabel) {
		this.kind = kind;
		this.longRest = longRest;
		this.accept = accept;
		this.proposerName = proposerName;
		this.typeLabel = typeLabel;
	}

	public static RestMessage propose(boolean longRest) {
		return new RestMessage(Kind.PROPOSE, longRest, false, "", "");
	}

	public static RestMessage voteOpen(String proposerName, String typeLabel) {
		return new RestMessage(Kind.VOTE_OPEN, false, false, proposerName, typeLabel);
	}

	public static RestMessage voteResponse(boolean accept) {
		return new RestMessage(Kind.VOTE_RESPONSE, false, accept, "", "");
	}

	public static RestMessage voteClose() {
		return new RestMessage(Kind.VOTE_CLOSE, false, false, "", "");
	}

	public RestMessage(FriendlyByteBuf buffer) {
		this.kind = buffer.readEnum(Kind.class);
		this.longRest = buffer.readBoolean();
		this.accept = buffer.readBoolean();
		this.proposerName = buffer.readUtf();
		this.typeLabel = buffer.readUtf();
	}

	public static void buffer(RestMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.kind);
		buffer.writeBoolean(message.longRest);
		buffer.writeBoolean(message.accept);
		buffer.writeUtf(message.proposerName);
		buffer.writeUtf(message.typeLabel);
	}

	public static void handler(RestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();

		switch (message.kind) {
			//No pasa por handleOnServerAsDm: proponer y votar un descanso es de cualquier jugador, no del
			//DM. Quién puede aplicarlo lo decide RestManager, que exige unanimidad.
			case PROPOSE -> NetworkUtil.handleOnServer(context, () -> {
				ServerPlayer proposer = context.getSender();
				if (proposer != null) {
					RestManager.propose(proposer, message.longRest ? RestManager.RestType.LONG : RestManager.RestType.SHORT);
				}
			});
			case VOTE_RESPONSE -> NetworkUtil.handleOnServer(context, () -> {
				ServerPlayer voter = context.getSender();
				if (voter != null) RestManager.registerVote(voter, message.accept);
			});
			case VOTE_OPEN -> NetworkUtil.handleOnClient(context, () ->
				net.hawthorn.dndsheets.client.gui.RestVoteScreen.open(message.proposerName, message.typeLabel));
			case VOTE_CLOSE -> NetworkUtil.handleOnClient(context,
				net.hawthorn.dndsheets.client.gui.RestVoteScreen::close);
		}
	}
}
