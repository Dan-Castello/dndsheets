package net.hawthorn.dndsheets.dungeon.network;

import io.netty.buffer.Unpooled;
import net.hawthorn.dndsheets.dungeon.DndsheetsDungeonMod;
import net.hawthorn.dndsheets.network.NetworkUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * <p>Client (the DM) -> server: a {@link DungeonTraceCaptureMessage}, compressed and cut into pieces. A packet
 * from the client may carry 32 KB at most, and an imported dungeon's rows alone are more than that; compressed
 * they're a few KB (long runs of floor and wall), and the pieces cover whatever is left. Pieces of one
 * capture arrive in order (one connection); part 0 starts a new one.</p>
 */
public class DungeonTraceChunkMessage {
	private static final int CHUNK = 30_000;
	private static final int MAX_PARTS = 64;
	//Inflated, a capture can't honestly be bigger than this: a hand-made "zip bomb" stops here.
	private static final int MAX_INFLATED = 8 * 1024 * 1024;

	private static final Map<UUID, ByteArrayOutputStream> PENDING = new HashMap<>();

	final int part, parts;
	final byte[] data;

	public DungeonTraceChunkMessage(int part, int parts, byte[] data) {
		this.part = part;
		this.parts = parts;
		this.data = data;
	}

	public DungeonTraceChunkMessage(FriendlyByteBuf buffer) {
		this.part = buffer.readVarInt();
		this.parts = buffer.readVarInt();
		this.data = buffer.readByteArray(CHUNK);
	}

	public static void buffer(DungeonTraceChunkMessage message, FriendlyByteBuf buffer) {
		buffer.writeVarInt(message.part);
		buffer.writeVarInt(message.parts);
		buffer.writeByteArray(message.data);
	}

	/** Client side: what the editor calls instead of sending the capture whole. */
	public static void send(DungeonTraceCaptureMessage capture) {
		for (DungeonTraceChunkMessage chunk : split(capture)) DndsheetsDungeonMod.PACKET_HANDLER.sendToServer(chunk);
	}

	public static List<DungeonTraceChunkMessage> split(DungeonTraceCaptureMessage capture) {
		FriendlyByteBuf raw = new FriendlyByteBuf(Unpooled.buffer());
		DungeonTraceCaptureMessage.buffer(capture, raw);
		byte[] bytes = new byte[raw.readableBytes()];
		raw.readBytes(bytes);
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
		deflater.setInput(bytes);
		deflater.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] block = new byte[8192];
		while (!deflater.finished()) out.write(block, 0, deflater.deflate(block));
		deflater.end();
		byte[] packed = out.toByteArray();
		int parts = Math.max(1, (packed.length + CHUNK - 1) / CHUNK);
		List<DungeonTraceChunkMessage> chunks = new ArrayList<>();
		for (int i = 0; i < parts; i++) {
			chunks.add(new DungeonTraceChunkMessage(i, parts, java.util.Arrays.copyOfRange(packed, i * CHUNK, Math.min(packed.length, (i + 1) * CHUNK))));
		}
		return chunks;
	}

	/** Puts the pieces back together; null while pieces are missing. Package-private for the self-test. */
	public static DungeonTraceCaptureMessage join(Map<UUID, ByteArrayOutputStream> pending, UUID who, DungeonTraceChunkMessage chunk) throws DataFormatException {
		if (chunk.parts < 1 || chunk.parts > MAX_PARTS || chunk.part < 0 || chunk.part >= chunk.parts) {
			pending.remove(who);
			throw new DataFormatException("bad piece " + chunk.part + "/" + chunk.parts);
		}
		if (chunk.part == 0) pending.put(who, new ByteArrayOutputStream());
		ByteArrayOutputStream gathered = pending.get(who);
		if (gathered == null) throw new DataFormatException("piece " + chunk.part + " without the first one");
		gathered.writeBytes(chunk.data);
		if (chunk.part < chunk.parts - 1) return null;
		pending.remove(who);

		Inflater inflater = new Inflater();
		inflater.setInput(gathered.toByteArray());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] block = new byte[8192];
		while (!inflater.finished()) {
			int n = inflater.inflate(block);
			if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
			out.write(block, 0, n);
			if (out.size() > MAX_INFLATED) {
				inflater.end();
				throw new DataFormatException("capture too big");
			}
		}
		inflater.end();
		return new DungeonTraceCaptureMessage(new FriendlyByteBuf(Unpooled.wrappedBuffer(out.toByteArray())));
	}

	public static void handler(DungeonTraceChunkMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServerAsDm(context, dm -> {
			DungeonTraceCaptureMessage capture;
			try {
				capture = join(PENDING, dm.getUUID(), message);
			} catch (DataFormatException | RuntimeException e) {
				dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.trace_grid_invalid", String.valueOf(e.getMessage())));
				return;
			}
			if (capture != null) DungeonTraceCaptureMessage.handle(dm, capture);
		});
	}
}
