package mf.asciitext.keyboard;

import android.content.Context;
import android.content.res.Resources;
import android.inputmethodservice.Keyboard;
import android.util.Log;
import android.util.TypedValue;

public class CustomKeyboard extends Keyboard {

    private int ROWS = 4;

    public CustomKeyboard(Context context, int xmlLayoutResId) {
        super(context, xmlLayoutResId);
    }

    public CustomKeyboard(Context context, int xmlLayoutResId, int modeId, int width, int height) {
        super(context, xmlLayoutResId, modeId, width, height);
    }

    public CustomKeyboard(Context context, int xmlLayoutResId, int modeId) {
        super(context, xmlLayoutResId, modeId);
    }

    public CustomKeyboard(Context context, int layoutTemplateResId, CharSequence characters, int columns, int horizontalPadding) {
        super(context, layoutTemplateResId, characters, columns, horizontalPadding);
    }

    // https://stackoverflow.com/a/20584529/4054683

    @Override
    public int getHeight() {
        return getKeyHeight() * ROWS;
    }

    public void changeKeyHeight(int height) {
        for (Keyboard.Key key : getKeys()) {
            double modifier = height / (double) key.height;
            key.height = height;
            key.y *= modifier;
//            height = key.height;
        }
        setKeyHeight(height);
        Log.d("height", "" + height);
        getNearestKeys(0, 0); //somehow adding this fixed a weird bug where bottom row keys could not be pressed if keyboard height is too tall.. from the Keyboard source code seems like calling this will recalculate some values used in keypress detection calculation
    }
}
