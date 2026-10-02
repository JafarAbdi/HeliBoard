# JNI resolves these by their Java_dev_juruc_pixelvoice_NativeEngine_* symbol names.
-keepclasseswithmembernames class dev.juruc.pixelvoice.NativeEngine {
    native <methods>;
}
