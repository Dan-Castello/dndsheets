package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.CharacterOptionListScreen;
import net.hawthorn.dndsheets.client.gui.CharacterSheetScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

//Servidor -> cliente: la lista de opciones cargadas para la categoría pedida (ver
//CharacterOptionsRegistry, solo vive en memoria del servidor), abre el selector con datos reales.
public class CharacterOptionsListMessage {
	String category;
	List<String> options;

	public CharacterOptionsListMessage(String category, List<String> options) {
		this.category = category;
		this.options = options;
	}

	public CharacterOptionsListMessage(FriendlyByteBuf buffer) {
		this.category = buffer.readUtf();
		this.options = buffer.readList(FriendlyByteBuf::readUtf);
	}

	public static void buffer(CharacterOptionsListMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.category);
		buffer.writeCollection(message.options, FriendlyByteBuf::writeUtf);
	}

	public static void handler(CharacterOptionsListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> {
			//Todavía no navegamos a ningún lado: la pantalla activa en este instante es la que pidió la
			//lista, así que es el momento de capturarla para volver a ELLA (no cerrar todo) al elegir o
			//cancelar (ver CharacterOptionListScreen).
			//Se captura CUALQUIER pantalla y no solo la hoja: la lista de pasos (CharacterSetupScreen)
			//también pide opciones, y devolverla a la hoja la echaría de la configuración a media faena.
			//Es además lo que ya hacía PresetScreen, así que ahora los dos selectores vuelven igual.
			CharacterOptionListScreen.open(Minecraft.getInstance().screen, message.category, message.options);
		});
	}
}
