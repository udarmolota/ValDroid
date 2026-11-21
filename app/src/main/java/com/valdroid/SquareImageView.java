package com.valdroid;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

/** An image as tall as it is wide (the Thunderstore catalog's mod icons, two to a row). */
public class SquareImageView extends AppCompatImageView {
    public SquareImageView(Context c) { super(c); }

    public SquareImageView(Context c, AttributeSet a) { super(c, a); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, widthSpec);
    }
}
