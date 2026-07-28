package com.cappielloantonio.tempo.interfaces;


import android.os.Bundle;

import androidx.annotation.Keep;

@Keep
public interface ClickCallback {
    default void onServerClick(Bundle bundle) {}
    default void onServerLongClick(Bundle bundle) {}
}
