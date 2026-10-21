package com.nas.messageredirect;

import android.content.Context;

/** Validation code stays independent of Android resources; resolve text at the UI boundary. */
final class UserError extends IllegalArgumentException {
    final int resource;
    UserError(int resource) { super("validation:" + resource); this.resource = resource; }
    static String message(Context context, IllegalArgumentException error) {
        return context.getString(error instanceof UserError ? ((UserError) error).resource : R.string.unexpected_error);
    }
}
