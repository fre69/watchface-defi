package com.defi.watchface;

import android.Manifest;
import android.app.Activity;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.wear.input.RemoteInputIntentHelper;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Assist en dialogue, dans une seule page (singleTask) :
 * - 🎤 : reconnaissance vocale dans la page (SpeechRecognizer), texte affiché pendant qu'on
 *   parle, question envoyée dès qu'on se tait, sans écran de confirmation ;
 * - ✎ : saisie du système (dictée ou clavier, modifiable avant envoi) pour corriger ;
 * - ↺ : nouvelle conversation (écran vidé, contexte oublié par le téléphone).
 * Le téléphone (/ha/assist) pose la question au pipeline Assist préféré de Home Assistant
 * et renvoie la réponse (/ha/assist/reply). Il garde la conversation 5 min après le dernier
 * échange ; l'écran garde l'historique aussi longtemps, même fermé puis rouvert. Quand Home
 * Assistant pose une question en retour, l'écoute se relance seule. Relancer Assist (tuile,
 * bouton de la montre) reprend la page ouverte. Déclaré comme assistant (ASSIST, VOICE_ASSIST).
 */
public class AssistActivity extends Activity {

    private static final String TAG = "DefiAssist";
    static final String ASSIST_PATH = "/ha/assist";
    static final String REPLY_PATH = "/ha/assist/reply";
    /** Nouvelle conversation : le téléphone oublie le conversation_id */
    static final String NEW_PATH = "/ha/assist/new";
    private static final int REQUEST_INPUT = 2;
    private static final int REQUEST_MIC = 3;
    private static final String RESULT_KEY = "assist";
    /** Une réponse de l'IA (Claude) peut prendre plusieurs secondes */
    private static final long REPLY_TIMEOUT_MS = 30_000L;
    private static final long IDLE_CLOSE_MS = 60_000L;
    /** Même durée que la conversation gardée par le téléphone (HaLink) */
    private static final long TRANSCRIPT_KEEP_MS = 5 * 60_000L;
    /** Laisse le temps de lire la question de Home Assistant avant de réécouter */
    private static final long FOLLOW_UP_DELAY_MS = 1_500L;
    private static final int C_LISTENING = 0xFFB39DDB;

    /** Échanges de la conversation en cours : { question, réponse, "0" si erreur } */
    private static final List<String[]> transcript = new ArrayList<>();
    private static long lastExchangeAt;
    /** Écran ouvert qui attend une réponse (une seule à la fois) */
    private static AssistActivity current;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScrollView scroll;
    private LinearLayout history;
    private TextView pendingAnswer;
    /** Ligne « 🎤 … » affichée pendant l'écoute, avec le texte entendu */
    private TextView listeningLine;
    private SpeechRecognizer recognizer;
    private boolean listening;
    private boolean waiting;
    private float density;

    private final Runnable timeout = () -> showAnswer(false, false, "Pas de réponse du téléphone");
    private final Runnable idleClose = this::finish;
    private final Runnable relisten = this::listen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        history = new LinearLayout(this);
        history.setOrientation(LinearLayout.VERTICAL);
        history.setGravity(Gravity.CENTER_HORIZONTAL);
        // Écran rond : marges larges pour que le texte ne touche pas le bord ; en bas, la
        // place des boutons, pour que la dernière réponse ne passe pas dessous
        history.setPadding(px(28), px(44), px(28), px(88));
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFillViewport(true);
        scroll.addView(history);

        // Boutons toujours visibles en bas de l'écran : ✎ corriger, 🎤 parler, ↺ recommencer
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.addView(roundButton("✎", 36, 0xFF2A2A35, 16, v -> type()));
        buttons.addView(gap(12));
        buttons.addView(roundButton("🎤", 50, 0xFF3A2A55, 22, v -> micPressed()));
        buttons.addView(gap(12));
        buttons.addView(roundButton("↺", 36, 0xFF2A2A35, 18, v -> newConversation()));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = px(16);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(scroll);
        root.addView(buttons, bp);
        setContentView(root);
        current = this;

        // Conversation récente : on la reprend là où elle en était
        synchronized (transcript) {
            if (System.currentTimeMillis() - lastExchangeAt > TRANSCRIPT_KEEP_MS)
                transcript.clear();
            for (String[] ex : transcript)
                addExchange(ex[0], ex[1], !"0".equals(ex[2]));
        }
        scrollToEnd();
        if (savedInstanceState == null)
            listen();
    }

    /** Assist relancé (tuile, bouton de la montre) alors que la page est ouverte : on enchaîne */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        listen();
    }

    @Override
    protected void onDestroy() {
        if (current == this)
            current = null;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null)
            recognizer.destroy();
        super.onDestroy();
    }

    private int px(int dp) {
        return (int) (dp * density);
    }

    private View gap(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(px(dp), 1));
        return v;
    }

    private TextView label(int sizeSp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    private TextView roundButton(String label, int sizeDp, int color, int textSp, View.OnClickListener l) {
        TextView b = label(textSp, Color.WHITE);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        b.setBackground(bg);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)));
        return b;
    }

    /** Ajoute un échange à l'écran ; rend le champ de la réponse */
    private TextView addExchange(String question, String answer, boolean ok) {
        TextView q = label(13, 0xFFAAAAAA);
        q.setText("« " + question + " »");
        q.setPadding(0, history.getChildCount() > 0 ? px(12) : 0, 0, 0);
        TextView a = label(16, ok ? Color.WHITE : 0xFFFF4466);
        a.setText(answer);
        a.setPadding(0, px(4), 0, 0);
        history.addView(q);
        history.addView(a);
        return a;
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // =========================================================================
    // 🎤 : écoute dans la page, envoi direct
    // =========================================================================

    /** 🎤 pendant l'écoute : on arrête et on envoie ce qui a été entendu */
    private void micPressed() {
        if (listening && recognizer != null)
            recognizer.stopListening();
        else
            listen();
    }

    private void listen() {
        if (waiting || listening)
            return;
        handler.removeCallbacks(idleClose);
        handler.removeCallbacks(relisten);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQUEST_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // Pas de reconnaissance utilisable par l'appli : saisie du système
            type();
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new Listener());
        }
        listeningLine = label(15, C_LISTENING);
        listeningLine.setText("🎤 …");
        listeningLine.setPadding(0, history.getChildCount() > 0 ? px(12) : 0, 0, 0);
        history.addView(listeningLine);
        scrollToEnd();
        listening = true;
        recognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName()));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_MIC)
            return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            listen();
        else
            type(); // micro refusé : la saisie du système reste possible
    }

    private void stopListeningLine() {
        listening = false;
        if (listeningLine != null) {
            history.removeView(listeningLine);
            listeningLine = null;
        }
    }

    private final class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onPartialResults(Bundle partial) {
            String text = first(partial);
            if (listeningLine != null && text != null && !text.isEmpty())
                listeningLine.setText("🎤 " + text);
        }

        @Override
        public void onResults(Bundle results) {
            String text = first(results);
            stopListeningLine();
            if (text != null && !text.trim().isEmpty())
                ask(text.trim());
            else
                idle();
        }

        @Override
        public void onError(int error) {
            Log.d(TAG, "Écoute interrompue : " + error);
            stopListeningLine();
            idle();
        }
    }

    private static String first(Bundle b) {
        ArrayList<String> list = b != null ? b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
        return list != null && !list.isEmpty() ? list.get(0) : null;
    }

    /** Rien entendu : page vide = on ferme ; sinon la conversation reste à l'écran */
    private void idle() {
        if (history.getChildCount() == 0)
            finish();
        else
            handler.postDelayed(idleClose, IDLE_CLOSE_MS);
    }

    // =========================================================================
    // ✎ : saisie du système, pour corriger avant d'envoyer
    // =========================================================================

    private void type() {
        if (waiting)
            return;
        if (listening && recognizer != null) {
            recognizer.cancel();
            stopListeningLine();
        }
        handler.removeCallbacks(idleClose);
        handler.removeCallbacks(relisten);
        RemoteInput input = new RemoteInput.Builder(RESULT_KEY).setLabel("Assist").build();
        Intent intent = RemoteInputIntentHelper.createActionRemoteInputIntent();
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, Collections.singletonList(input));
        startActivityForResult(intent, REQUEST_INPUT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        String text = null;
        if (requestCode == REQUEST_INPUT && data != null) {
            Bundle results = RemoteInput.getResultsFromIntent(data);
            CharSequence cs = results != null ? results.getCharSequence(RESULT_KEY) : null;
            if (cs != null)
                text = cs.toString();
        }
        if (text == null || text.trim().isEmpty()) {
            idle();
            return;
        }
        ask(text.trim());
    }

    // =========================================================================
    // ↺ : nouvelle conversation
    // =========================================================================

    private void newConversation() {
        if (listening && recognizer != null) {
            recognizer.cancel();
            stopListeningLine();
        }
        waiting = false;
        pendingAnswer = null;
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(relisten);
        synchronized (transcript) {
            transcript.clear();
            lastExchangeAt = 0;
        }
        history.removeAllViews();
        sendToPhone(NEW_PATH, new byte[0], null);
        listen();
    }

    // =========================================================================
    // Échange avec le téléphone
    // =========================================================================

    private void ask(String text) {
        pendingAnswer = addExchange(text, "…", true);
        pendingAnswer.setTag(text);
        scrollToEnd();
        waiting = true;
        handler.postDelayed(timeout, REPLY_TIMEOUT_MS);
        sendToPhone(ASSIST_PATH, text.getBytes(StandardCharsets.UTF_8),
                why -> handler.post(() -> showAnswer(false, false, why)));
    }

    private interface Failure {
        void failed(String why);
    }

    private void sendToPhone(String path, byte[] payload, Failure onFail) {
        Context app = getApplicationContext();
        new Thread(() -> {
            try {
                Set<Node> nodes = Tasks.await(Wearable.getCapabilityClient(app)
                        .getCapability(PhoneLink.CAPABILITY, CapabilityClient.FILTER_REACHABLE)).getNodes();
                if (nodes.isEmpty()) {
                    if (onFail != null)
                        onFail.failed("Téléphone injoignable");
                    return;
                }
                for (Node n : nodes)
                    Tasks.await(Wearable.getMessageClient(app).sendMessage(n.getId(), path, payload));
            } catch (Exception e) {
                Log.w(TAG, path + " non envoyé", e);
                if (onFail != null)
                    onFail.failed("Question non envoyée");
            }
        }).start();
    }

    private void showAnswer(boolean ok, boolean more, String text) {
        if (!waiting || pendingAnswer == null)
            return;
        waiting = false;
        handler.removeCallbacks(timeout);
        pendingAnswer.setTextColor(ok ? Color.WHITE : 0xFFFF4466);
        pendingAnswer.setText(text);
        synchronized (transcript) {
            transcript.add(new String[] { String.valueOf(pendingAnswer.getTag()), text, ok ? "1" : "0" });
            lastExchangeAt = System.currentTimeMillis();
        }
        scrollToEnd();
        // Home Assistant pose une question : l'écoute se relance pour y répondre
        if (ok && more)
            handler.postDelayed(relisten, FOLLOW_UP_DELAY_MS);
        else
            handler.postDelayed(idleClose, IDLE_CLOSE_MS);
    }

    /**
     * Réponse du téléphone : réussite « 1 »/« 0 », suite attendue « 1 »/« 0 »,
     * puis le texte (DataLayerListenerService)
     */
    static void onReply(byte[] data) {
        String payload = new String(data, StandardCharsets.UTF_8);
        boolean ok = payload.startsWith("1");
        boolean more = payload.length() > 1 && payload.charAt(1) == '1';
        String text = payload.length() > 2 ? payload.substring(2) : "";
        AssistActivity a = current;
        if (a != null)
            a.handler.post(() -> a.showAnswer(ok, more, text));
    }
}
