package net.hawthorn.dndsheets.client.gui.components;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class RollScrollWidget extends AbstractScrollWidget {

    private static class ListItem {
        public ListItem(EditBox name, List<Button> rollButtons, List<Button> editButtons, Button deleteButton) {
            this.nameBox = name;
            this.rollButtons = rollButtons;
            this.editButtons = editButtons;
            this.deleteButton = deleteButton;
        }
        public EditBox nameBox;
        public List<Button> rollButtons;
        public List<Button> editButtons;
        public Button deleteButton;
        public String getName() {
            return nameBox.getValue();
        }
    }

    private final List<ListItem> list = new ArrayList<>();
    private boolean editMode = false;

    public int separation = 20;
    public int scrollCutoff = 8;

    public RollScrollWidget(int pX, int pY, int pWidth, int pHeight, Component pMessage) {
        super(pX, pY, pWidth, pHeight, pMessage);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {

    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (this.visible) {
            this.renderBackground(guiGraphics);
            guiGraphics.enableScissor(this.getX() + 1, this.getY() + 1, this.getX() + this.width - 1, this.getY() + this.height - 1);
            guiGraphics.pose().pushPose();
            //guiGraphics.pose().translate(0.0D, -this.scrollAmount, 0.0D);
            this.renderContents(guiGraphics, mouseX, mouseY, partialTick);
            guiGraphics.pose().popPose();
            guiGraphics.disableScissor();
            this.renderDecorations(guiGraphics);
        }
    }

    /**
     * <p>Tome background instead of vanilla's. {@code AbstractScrollWidget.renderBorder} fills the whole
     * rectangle in gray and puts a black one pixel inside, with both colors fixed — on the
     * character sheet's parchment that's a gray-and-black brick, the tab's largest widget and the
     * one that clashed the most.</p>
     *
     * <p>The focus signal that vanilla's white used to give is preserved: the frame still lights up.</p>
     */
    @Override
    protected void renderBackground(GuiGraphics guiGraphics) {
        guiGraphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, TomeField.WELL_FILL);
        TomeField.frameWidget(guiGraphics, this.getX(), this.getY(), this.width, this.height, this.isFocused());
    }

    @Override
    protected int getInnerHeight() {
        int innerHeight = this.getHeight();
        if (list.size() >= scrollCutoff) {
            int add = list.size() - scrollCutoff;
            innerHeight += add * separation + 7;
        }
        return innerHeight;
    }

    /**
     * <p>Using this method will put the ImageButtons and EditBox under the control of the scroll widget. This means their positioning, active state and visibility are driven by it.</p>
     * <p>Functionally the things here are grouped together as a singular new "list item" with the items being visually sorted alphabetically (using the contents of the EditBox). The buttons themselves are put next to each other.</p>
     * <p>It's very important each thing here is added to the screen with the screen's addWidget() method first, or it won't work.</p>
     * @param nameBox
     * @param rollButtons
     * @param editButtons
     */
    public void addListItem(EditBox nameBox, List<Button> rollButtons, List<Button> editButtons, Button deleteButton) {
        list.add(new ListItem(nameBox, rollButtons, editButtons, deleteButton));
    }

    public int getListSize() {
        return list.size();
    }

    /**
     * <p>Empties the internal list and returns each row's widgets so the screen can remove them from
     * itself with {@code removeWidget} (this widget doesn't control that registration — see the comment on
     * {@link #addListItem}). Without this, repopulating the list after a new sheet from the server (changing
     * race, applying a preset, resting, leveling up...) would stack old rows on top of the new ones
     * forever: each leftover row ended up with a delete button whose index no longer matched
     * anything real in the sheet's array, and sooner or later it would blow up with
     * {@code IndexOutOfBoundsException} on delete.</p>
     */
    public List<AbstractWidget> clearAndCollectWidgets() {
        List<AbstractWidget> widgets = new ArrayList<>();
        for (ListItem item : list) {
            widgets.add(item.nameBox);
            widgets.addAll(item.rollButtons);
            widgets.addAll(item.editButtons);
            widgets.add(item.deleteButton);
        }
        list.clear();
        return widgets;
    }

    /**
     * <p>This is asking for the delete button so it knows which list item to target.</p>
     * <p>This releases control over the widgets in the list item. If you want them to disappear, make sure you use the screen's removeWidget() method.</p>
     * @param button
     */
    public int removeListItem(Button button) {
        int toRemove = -1; //Previously it stayed at 0 if the button wasn't found, which is a real index: it would silently
        //delete the wrong row instead of signaling that button wasn't part of this list.
        for (int i = 0; i < list.size(); i++) {
            ListItem item = list.get(i);
            if (item.deleteButton == button) {
                toRemove = i;
                break;
            }
        }
        if (toRemove < 0) return -1;
        list.remove(toRemove);
        if (list.size() < scrollCutoff) {
            this.setScrollAmount(0);
        }
        return toRemove;
    }

    public void setActive(boolean newState) {
        active = newState;
        visible = newState;
        list.forEach((item) -> {
            item.rollButtons.forEach((button) -> {
                button.active = newState;
                button.visible = newState;
            });
            item.editButtons.forEach((button) -> {
                button.active = newState;
                button.visible = newState;
            });
            item.nameBox.active = newState;
            item.nameBox.visible = newState;
            item.deleteButton.active = newState;
            item.deleteButton.visible = newState;
        });
    }

    /**
     * <p>This sets the internal boolean for whether edit buttons or roll buttons are visible on this widget.</p>
     */
    public void setEditMode(boolean isEditMode) {
        this.editMode = isEditMode;
    }

    @Override
    public boolean mouseClicked(double pMouseX, double pMouseY, int pButton) {
        return false;
        //This is meant to cancel click events so they go to buttons instead.
    }

    @Override
    protected double scrollRate() {
        if (list.size() >= scrollCutoff) return 8;
        else return 0;

    }

    @Override
    protected boolean scrollbarVisible() {
        return false;
    }

    public String[] getNames() {
        List<String> names = new ArrayList<>();
        String[] arr = new String[0];
        list.forEach((item) -> {
            names.add(item.getName());
        });
        return names.toArray(arr);
    }

    //Audit finding F12: containerTick() called this 20 times per second just to iterate the result
    //once and discard it — a new ArrayList + array were rebuilt every tick even when the weapon list
    //hadn't changed. tickNameBoxes()/forwardKeyToFocusedNameBox() iterate the internal list directly.
    public void tickNameBoxes() {
        list.forEach((item) -> item.nameBox.tick());
    }

    public boolean forwardKeyToFocusedNameBox(int key, int scancode, int modifiers) {
        for (ListItem item : list) {
            if (item.nameBox.isFocused()) {
                item.nameBox.keyPressed(key, scancode, modifiers);
                return true;
            }
        }
        return false;
    }

    /**
     * <p>This tries to get the index of this button somewhere in the list, if it exists.</p>
     * @param button
     * @return
     */
    public int getIndex(Button button) {
        for (int i = 0; i < list.size(); i++) {
            ListItem item = list.get(i);
            for (int j = 0; j < item.rollButtons.size(); j++) {
                Button btnItem = item.rollButtons.get(j);
                if (button == btnItem) {
                    return i;
                }
            }
            for (int j = 0; j < item.editButtons.size(); j++) {
                Button btnItem = item.editButtons.get(j);
                if (button == btnItem) {
                    return i;
                }
            }
        }
        return 0;
    }


    @Override
    protected void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        if (list.isEmpty()) return;
        //Range of actually visible rows computed directly, without iterating all of them to check bounds:
        //before, EVERY frame repositioned and checked bounds for the 3 button stacks + the EditBox of
        //EVERY row, visible or not (several extra EditBox.render() calls are considerably more expensive than a
        //simple button) — with a long Attacks tab (many auto-populated weapons + manually added ones) this was
        //noticeable while scrolling. A one-row margin on each side avoids "pop" at the clip's edge.
        int scroll = (int) this.scrollAmount();
        int first = Math.max(0, scroll / separation - 1);
        int last = Math.min(list.size() - 1, (scroll + this.getHeight()) / separation + 1);

        for (int i = 0; i < list.size(); i++) {
            ListItem item = list.get(i);
            if (i < first || i > last) {
                setInactive(item.deleteButton);
                for (Button button : item.rollButtons) setInactive(button);
                for (Button button : item.editButtons) setInactive(button);
                setInactive(item.nameBox);
                continue;
            }

            boolean isActive;

            //Set delete button position
            item.deleteButton.setX(this.getX() + 4);
            item.deleteButton.setY(this.getY() + separation*(i) + 9 - (int)this.scrollAmount());
            isActive = (item.deleteButton.getY() >= this.getY() - 8) && (item.deleteButton.getY() <= this.getY() + 8 + this.getHeight()) && editMode;
            if (isActive) item.deleteButton.render(guiGraphics, mouseX, mouseY, partialTicks);
            item.deleteButton.active = isActive;
            item.deleteButton.visible = isActive;

            //Set roll button positions
            for (int j = 0; j < item.rollButtons.size(); j++) {
                Button button = item.rollButtons.get(j);
                button.setX(this.getX() + this.getWidth() - 20*(j) - 20);
                button.setY(this.getY() + separation*(i) - (int)this.scrollAmount() + 4);
                isActive = (button.getY() >= this.getY() - 16) && (button.getY() <= this.getY() + 16 + this.getHeight()) && !editMode;
                if (isActive) button.render(guiGraphics, mouseX, mouseY, partialTicks);
                button.active = isActive;
                button.visible = isActive;
            }
            //Repeat for edit buttons
            for (int j = 0; j < item.editButtons.size(); j++) {
                Button button = item.editButtons.get(j);
                button.setX(this.getX() + this.getWidth() - 20*(j) - 20);
                button.setY(this.getY() + separation*(i) - (int)this.scrollAmount() + 4);
                isActive = (button.getY() >= this.getY() - 16) && (button.getY() <= this.getY() + 16 + this.getHeight()) && editMode;
                if (isActive) button.render(guiGraphics, mouseX, mouseY, partialTicks);
                button.active = isActive;
                button.visible = isActive;
            }

            //Set namebox position
            item.nameBox.setX(this.getX() + 16);
            item.nameBox.setY(this.getY() + separation*(i) - (int)this.scrollAmount() + 4);
            isActive = (item.nameBox.getY() >= this.getY() - 16) && (item.nameBox.getY() <= this.getY() + 16 + this.getHeight());
            if (isActive) {
                item.nameBox.render(guiGraphics, mouseX, mouseY, partialTicks);
                //After render(): the frame covers the gray ring the EditBox paints on its own. The fields
                //on the main tab get framed by CharacterSheetScreen iterating over the guistate,
                //but these are created here internally and don't go through that, so they were left without a frame.
                TomeField.frameWidget(guiGraphics, item.nameBox.getX(), item.nameBox.getY(),
                    item.nameBox.getWidth(), item.nameBox.getHeight(), item.nameBox.isFocused());
            }
            item.nameBox.active = isActive;
            item.nameBox.visible = isActive;
        }

    }

    private static void setInactive(AbstractWidget widget) {
        widget.active = false;
        widget.visible = false;
    }

}
