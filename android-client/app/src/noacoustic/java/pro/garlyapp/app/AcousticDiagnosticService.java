package pro.garlyapp.app;

import android.content.Context;

/* Il gemello vuoto del servizio acustico, per le build spedite.
 *
 * La funzione acustica gira solo nella build QA (useLocalQaPage). Il servizio
 * vero sta in src/acoustic e porta con se' TensorFlow Lite, una libreria
 * nativa da 17 MB. Nella build pubblica quella funzione e' spenta
 * (acousticProtection = false), quindi qui non serve, e portarla dietro voleva
 * dire spedire l'unica libreria nativa dell'app: quella che fa scattare il
 * blocco di Play sulle pagine di memoria da 16 KB, e 17 MB in piu' addosso.
 *
 * Il resto dell'app avvia e ferma il servizio per nome di classe, quindi non
 * ha bisogno di questa classe a compilare. L'unico punto che la nomina davvero
 * e' GarlyWebActivity, che chiama sweepClipsNow: nella build pubblica non c'e'
 * nessuna registrazione da ripulire, percio' qui non fa niente, ed e' giusto
 * cosi'. Il ramo che entra lo sceglie build.gradle, come per Firebase. */
final class AcousticDiagnosticService {

    private AcousticDiagnosticService() {}

    static void sweepClipsNow(Context context) {
        // Niente da ripulire: in questa build nessun clip viene mai creato.
    }
}
