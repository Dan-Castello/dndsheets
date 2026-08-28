package net.hawthorn.dndsheets.world.inventory;

import net.hawthorn.dndsheets.init.DndsheetsModMenus;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;

/**
 * <p>Menú sin ranuras. Existe por dos motivos y ninguno tiene que ver con inventarios: que la ficha
 * pueda ser un {@code AbstractContainerScreen} (y así sobrevivir a la tecla E, al ratón capturado y al
 * ciclo de vida que Minecraft ya sabe manejar), y colgar el {@code guistate} que {@code
 * CharacterSheetScreen} comparte con sus procedures de carga y guardado.</p>
 *
 * <p>Lo que traía de MCreator y ya no está: {@code bound}/{@code boundItemMatcher}/{@code boundEntity}/
 * {@code boundBlockEntity} (nunca se asignaban, así que {@code stillValid} salía siempre por la misma
 * rama y devolvía {@code true}), un {@code ItemStackHandler(0)} que nadie leía, un mapa de ranuras
 * vacío expuesto por un {@code Supplier} que nadie llamaba, y la posición del bloque que abrió el menú
 * — que viajaba desde el mensaje, por un {@code FriendlyByteBuf}, hasta unos campos {@code x/y/z} de la
 * pantalla que ningún método leía jamás.</p>
 */
public class CharacterSheetMenu extends AbstractContainerMenu {
	public final static HashMap<String, Object> guistate = new HashMap<>();
	public final Player entity;

	//El FriendlyByteBuf lo exige la factoría de IForgeMenuType (ver DndsheetsModMenus), no este menú:
	//llega vacío y NO se lee. Si algún día hace falta mandarle datos a la pantalla al abrirla, este es el
	//sitio — pero entonces hay que escribirlos también en quien llama a NetworkHooks.openScreen.
	public CharacterSheetMenu(int id, Inventory inv, FriendlyByteBuf extraData) {
		super(DndsheetsModMenus.CHARACTER_SHEET.get(), id);
		this.entity = inv.player;
	}

	//No hay bloque ni entidad que vigilar: la ficha se cierra con Esc o con la propia tecla, no por
	//alejarse de nada.
	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	//Sin ranuras, shift-click no tiene a dónde llevar nada.
	@Override
	public ItemStack quickMoveStack(Player playerIn, int index) {
		return ItemStack.EMPTY;
	}
}
