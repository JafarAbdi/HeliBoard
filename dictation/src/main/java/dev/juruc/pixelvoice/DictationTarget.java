package dev.juruc.pixelvoice;

import android.text.InputType;

final class DictationTarget {
    private DictationTarget() {}

    static boolean acceptsDictation(int inputType, String privateImeOptions) {
        int typeClass = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        if (typeClass == InputType.TYPE_NULL) {
            return false;
        }
        if (typeClass == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                        || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            return false;
        }
        if (typeClass == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            return false;
        }
        if (privateImeOptions != null) {
            for (String option : privateImeOptions.split(",")) {
                String trimmed = option.trim();
                if (trimmed.equals("nm") || trimmed.endsWith(".noMicrophoneKey")) {
                    return false;
                }
            }
        }
        return true;
    }
}
