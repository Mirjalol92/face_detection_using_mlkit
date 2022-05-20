package com.example.facerecognizemlkit.utils

import android.media.Image
import com.google.gson.annotations.SerializedName
import java.io.Serializable

class CloneableImage(val image:Image): Cloneable{
    public override fun clone(): CloneableImage {
        return super.clone() as CloneableImage
    }
}