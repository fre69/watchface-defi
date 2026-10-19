package com.defi.watchface;

import android.app.Activity;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Bundle;

import androidx.wear.input.RemoteInputIntentHelper;

import java.util.Collections;

/**
 * Réponse libre (bouton ✎ de la bulle) : ouvre la saisie du système (dictée,
 * clavier, emoji), puis envoie le texte au téléphone. Sans interface propre.
 */
public class ReplyActivity extends Activity {
    static final String EXTRA_REPLY_ID = "replyId";
    private static final String RESULT_KEY = "reply";
    private static final int REQUEST_INPUT = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null)
            return; // saisie déjà ouverte
        RemoteInput input = new RemoteInput.Builder(RESULT_KEY).setLabel("Réponse").build();
        Intent intent = RemoteInputIntentHelper.createActionRemoteInputIntent();
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, Collections.singletonList(input));
        startActivityForResult(intent, REQUEST_INPUT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Bundle results = data != null ? RemoteInput.getResultsFromIntent(data) : null;
        CharSequence text = results != null ? results.getCharSequence(RESULT_KEY) : null;
        String replyId = getIntent().getStringExtra(EXTRA_REPLY_ID);
        if (replyId != null && text != null && text.toString().trim().length() > 0)
            ReplySender.send(this, replyId, text.toString().trim());
        finish();
    }
}
