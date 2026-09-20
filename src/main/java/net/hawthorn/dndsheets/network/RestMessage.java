package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.RestManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * <p>The whole lifecycle of a rest vote in one class, with an enum instead of four nearly identical
 * messages (invariant 3) — the same pattern already used by {@code WildShapeMessage} and
 * {@code BrowseActionMessage}.</p>
 *
 * <ul>
 *   <li>{@code PROPOSE} — client → server. Chose short or long in {@code RestChoiceScreen} and
 *       starts the vote.</li>
 *   <li>{@code VOTE_OPEN} — server → client (to each one). "So-and-so proposes a long rest, vote".
 *       The name and the label are resolved by the server: it's the one holding the pending proposal.</li>
 *   <li>{@code VOTE_RESPONSE} — client → server. Accepted or rejected. The rest is only applied if
 *       EVERYONE accepts.</li>
 *   <li>{@code VOTE_CLOSE} — server → client. Closes the window because the vote was already resolved,
 *       expired, or was canceled some other way; without this, whoever hadn't voted yet was left with an
 *       "Accept/Reject" for a vote that no longer existed (see {@code RestManager.clear()}).</li>
 * </ul>
 */
public class RestMessage {

	//At the end, never in the middle: writeEnum travels by ordinal (see invariant 2 in PROJECT_CONTEXT.md).
	public enum Kind { PROPOSE, VOTE_OPEN, VOTE_RESPONSE, VOTE_CLOSE }

	final Kind kind;
	//Each field only carries data for its own Kind; for the rest it travels empty. Kept as separately
	//named fields instead of a single shared boolean: "longRest" and "accept" are different questions, and
	//a field that means one thing or another depending on the kind is exactly what's hard to decipher at
	//3am.
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
			//Doesn't go through handleOnServerAsDm: proposing and voting on a rest is any player's action,
			//not the DM's. Who can apply it is decided by RestManager, which requires unanimity.
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
