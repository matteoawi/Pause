package it.matteo.pausa.probe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** A launcher entry makes this temporary test UID visible to the production app's query. */
public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); TextView text = new TextView(this);
        text.setText("Strumento temporaneo di verifica della rete di Pausa."); setContentView(text);
    }
}
